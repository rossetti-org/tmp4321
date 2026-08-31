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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
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
) : ModelElement(parent, name) {

    init {
        require(transporters.isNotEmpty()) { "A transporter pool must contain at least one transporter." }
        for (t in transporters) {
            require(t.system === system) {
                "Transporter (${t.name}) belongs to system (${t.system.name}), not to " +
                        "(${system.name}), so it cannot be pooled here."
            }
        }
    }

    private val myTransporters: List<GuidedTransporter> = transporters.toList()

    /** The fleet, in declaration order, which is the order ties are broken in. */
    val transporters: List<GuidedTransporter>
        get() = myTransporters

    /**
     * Required by the underlying resource seize, which always takes a queue. In practice a
     * transporter is only ever seized once it is known to be free, so nothing waits here; entities
     * that find the fleet busy wait on [waitingQ] instead, where the statistics that matter are
     * collected.
     */
    internal val seizeQ = RequestQ(this, "${this.name}:SeizeQ")

    private val myWaitingQ = HoldQueue(this, "${this.name}:WaitingQ")

    /** Entities waiting for any transporter of this pool to become free. */
    val waitingQ: QueueCIfc<ProcessModel.Entity>
        get() = myWaitingQ

    internal val holdQueue: HoldQueue
        get() = myWaitingQ

    /** The transporters that are allocated to nobody. */
    val idleTransporters: List<GuidedTransporter>
        get() = myTransporters.filter { it.hasAvailableUnits }

    /** True when some transporter of the pool could be sent now. */
    val hasIdleTransporter: Boolean
        get() = myTransporters.any { it.hasAvailableUnits }

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
     * Wakes the entity that has waited longest, if any.
     *
     * Called when a transporter is released. Exactly one is woken: it will choose a transporter for
     * itself, and if the fleet has meanwhile become busy again it simply waits once more.
     */
    internal fun wakeNextWaiter() {
        val next = myWaitingQ.peekNext() ?: return
        myWaitingQ.removeAndResume(next)
    }

    /**
     * Puts a released transporter back to work, or to rest.
     *
     * Anything waiting takes precedence over the idle rule, and not as a matter of policy: sending
     * a transporter off to a home base while an entity waits for one would leave both worse off,
     * and no rule should be able to ask for that. Only when nothing is waiting does where the
     * transporter idles become a question at all.
     *
     * A transporter sent somewhere to wait travels there over simulated time, and the entity that
     * released it does not wait for it to arrive.
     */
    internal fun dispose(transporter: GuidedTransporter) {
        if (myWaitingQ.isNotEmpty) {
            wakeNextWaiter()
            return
        }
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
        "GuidedTransporterPoolWithQ($name, ${myTransporters.size} transporters, " +
                "${idleTransporters.size} idle, ${myWaitingQ.size} waiting)"
}
