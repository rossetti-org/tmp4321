/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.AbstractResourcePool
import ksl.modeling.entity.Allocation
import ksl.modeling.entity.RequestQ
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.IdleDisposition
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement

/**
 * A set of interchangeable transporters that entities ask for by the group rather than by name.
 *
 * An entity that wants carrying does not care which transporter comes, only that one does. The pool
 * holds the fleet, decides which idle transporter to send, and holds the entities that arrive when
 * none is free.
 *
 * Waiting happens in one place, on the pool, rather than on the individual transporters. That is
 * deliberate: an entity queued against a particular transporter would go on waiting for that one
 * even after a nearer one came free, which is both slower and harder to explain. Whoever has waited
 * longest is woken when any transporter is released, and chooses afresh.
 *
 * @param parent the containing model element
 * @param system the runtime whose guide path these transporters run on
 * @param transporters the fleet, which must all belong to that system
 * @param allocationRule which idle transporter to send
 * @param idleDispositionRule what a released transporter does when nothing is waiting
 * @param name a name for the pool
 */
open class GuidedTransporterPoolWithQ @JvmOverloads constructor(
    parent: ModelElement,
    val system: GuidedPathTransportSystem,
    transporters: List<GuidedTransporter>,
    val allocationRule: GuidedTransporterAllocationRuleIfc = ClosestByNetworkDistanceRule(),
    val idleDispositionRule: IdleDispositionRuleIfc = ParkInPlaceRule(),
    name: String? = null
) : AbstractResourcePool<GuidedTransporter>(parent, name) {

    init {
        require(transporters.isNotEmpty()) { "A transporter pool must contain at least one transporter." }
        for (t in transporters) {
            require(t.system === system) {
                "Transporter (${t.name}) belongs to system (${t.system.name}), not to " +
                        "(${system.name}), so it cannot be pooled here."
            }
            addResource(t)
        }
    }

    /** The fleet, in declaration order, which is the order ties are broken in. */
    val transporters: List<GuidedTransporter>
        get() = myResources

    /**
     * Where entities wait for a transporter of this pool.
     *
     * A [RequestQ], and the same one the allocation is made through, because that is what makes
     * this pool behave like every other resource pool in the library. A request is enqueued **on
     * every call**, whether or not a transporter is free, and removed when one is allocated: so an
     * entity served immediately records a wait of zero rather than no observation at all, which is
     * what `seize` has always done and what the reported mean has to be over to mean anything.
     *
     * It is also what stops the queue being jumped. A released transporter marks the next eligible
     * request `resumePending`, which reserves the pool's availability for it; an entity arriving in
     * the same instant enqueues behind it and finds itself not next, rather than taking the
     * transporter out from under it.
     */
    internal val myWaitingQ: RequestQ = RequestQ(this, "${this.name}:Q")

    /** Entities waiting for any transporter of this pool to become free. */
    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    /** The transporters that are allocated to nobody. */
    val idleTransporters: List<GuidedTransporter>
        get() = myResources.filter { it.hasAvailableUnits }

    /** True when some transporter of the pool could be sent now. */
    val hasIdleTransporter: Boolean
        get() = myResources.any { it.hasAvailableUnits }

    /**
     * Clears any state the allocation rule carries, so a replication cannot inherit the end of the
     * one before it. Most rules do nothing here; a rule that takes turns does.
     */
    override fun initialize() {
        super.initialize()
        allocationRule.reset()
    }

    /**
     * Chooses an idle transporter to send to a pickup, or null when none is free.
     *
     * @param pickup where the transporter is wanted
     */
    internal fun selectFor(pickup: GuidedPathNetwork.Intersection): GuidedTransporter? {
        val candidates = idleTransporters
        if (candidates.isEmpty()) return null
        val chosen = allocationRule.selectTransporter(system.network, pickup, candidates)
        check(chosen in candidates) {
            "Allocation rule ($allocationRule) chose transporter (${chosen.name}), which is not " +
                    "among the idle transporters of pool (${this.name}). A rule must choose from " +
                    "the candidates it is given."
        }
        return chosen
    }

    /**
     * Allocates a transporter to an entity, choosing which one only now.
     *
     * The choice is deliberately made here rather than when the entity queued. An entity that has
     * been waiting should get the transporter that is best for it *at the moment one is free*, not
     * the one that happened to be nearest when it joined the queue -- and by then the fleet has
     * moved. This is the same division of labour that [ksl.modeling.spatial.MovableResourcePool]
     * uses, for the same reason.
     *
     * @param entity who the transporter is for
     * @param pickup where it is wanted, which is what the rule ranks by
     * @param allocationName names the allocation
     */
    internal fun allocateFor(
        entity: ProcessModel.Entity,
        pickup: GuidedPathNetwork.Intersection,
        allocationName: String? = null
    ): Allocation {
        require(hasIdleTransporter) { "Pool ($name) has no idle transporter to allocate." }
        val chosen = selectFor(pickup)!!
        val allocation = chosen.allocate(entity, 1, myWaitingQ, allocationName)
        // The requests waiting in this queue are the pool's, not the member's: a request names the
        // pool, because no transporter has been chosen when it queues. Recording the pool here is
        // what lets the release process the queue on the pool's behalf.
        allocation.originatingPool = this
        return allocation
    }

    /**
     * What a transporter does once it has been given back and nobody is waiting for it.
     *
     * Called after the release, which has already offered the transporter to whoever was waiting.
     * Work beats disposition: a fleet that sent a transporter home while an entity was queued for
     * it would be paying for the journey twice, so a non-empty queue means there is nothing to
     * decide here.
     */
    internal fun disposeIfUnwanted(transporter: GuidedTransporter) {
        if (myWaitingQ.isNotEmpty) return
        when (val disposition = idleDispositionRule.disposition(transporter)) {
            is IdleDisposition.ParkInPlace -> Unit

            is IdleDisposition.ReturnToHomeBase -> {
                val home = transporter.homeBase
                if (home != null) transporter.sendTo(home)
            }

            is IdleDisposition.MoveTo -> transporter.sendTo(disposition.locationName)
        }
    }

    override fun toString(): String =
        "GuidedTransporterPoolWithQ($name, ${myResources.size} transporters, " +
                "${idleTransporters.size} idle, ${myWaitingQ.size} waiting)"
}
