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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.internal.MovementEngine
import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.guidedpath.internal.ZoneInvariantChecker
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simulation.ModelElement

/**
 * The runtime half of a guide path: it owns everything about the network that changes during a run.
 *
 * The network describes the guide path and never changes. This owns which transporter holds which
 * zone, resets all of it at the start of every replication, hosts the transporters, and reports how
 * congested the path is. Splitting the two is not tidiness. A spatial model and a model element are
 * both abstract classes, so one object cannot be both, and the split falls naturally along the line
 * between what is fixed and what is not: nothing about the guide path itself can differ between
 * replications, because the network has nothing to differ.
 *
 * A network belongs to one running system. A second system attaching to the same network would
 * share its zones and quietly corrupt both, so the attempt is refused rather than left to produce
 * an inexplicable run.
 *
 * @param parent the containing model element
 * @param network the guide path to operate, already built
 * @param name a name for the system
 */
open class GuidedPathTransportSystem @JvmOverloads constructor(
    parent: ModelElement,
    val network: GuidedPathNetwork,
    val zoneContentionRule: ZoneContentionRuleIfc = FIFOZoneContentionRule(),
    name: String? = null
) : ModelElement(parent, name) {

    init {
        network.attachTo(this.name)
        spatialModel = network
    }

    private val myTransporters = mutableListOf<GuidedTransporter>()

    /** The transporters on this guide path, in the order they were declared. */
    val transporters: List<GuidedTransporter>
        get() = myTransporters

    internal val engine: MovementEngine = MovementEngine(this)

    private var myInvariantChecker: ZoneInvariantChecker? = null

    /**
     * Whether the space-exclusivity invariants are checked whenever the simulation clock advances.
     *
     * Off by default, because the check walks every zone and every transporter and a model that is
     * correct pays for nothing. Tests turn it on: it is the standing proof that no transporter ever
     * shares space with another, ever covers a broken run of zones, or ever loses track of what it
     * holds.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var checkInvariants: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "Invariant checking cannot be switched while the model is running."
            }
            field = value
        }

    private val myNumMoving = TWResponse(this, name = "${this.name}:NumTransportersMoving")

    /** How many transporters are travelling. */
    val numTransportersMoving: TWResponseCIfc
        get() = myNumMoving

    private val myNumBlocked = TWResponse(this, name = "${this.name}:NumTransportersBlocked")

    /** How many transporters cannot claim the space ahead of them. */
    val numTransportersBlocked: TWResponseCIfc
        get() = myNumBlocked

    private val myNumIdle = TWResponse(this, name = "${this.name}:NumTransportersIdle")

    /** How many transporters are standing still with nothing to do. */
    val numTransportersIdle: TWResponseCIfc
        get() = myNumIdle

    private val myZoneUtilization = TWResponse(this, name = "${this.name}:ZoneUtilization")

    /** The fraction of the guide path's zones that are covered by a transporter. */
    val zoneUtilization: TWResponseCIfc
        get() = myZoneUtilization

    internal fun addTransporter(transporter: GuidedTransporter) {
        require(model.isNotRunning) {
            "A transporter cannot be added while the model is running."
        }
        myTransporters.add(transporter)
    }

    /**
     * Turns a declared placement into the zones a transporter covers, rear first.
     *
     * A transporter longer than one zone extends backwards from where it is placed, because the
     * zone named is where its front is. Backwards means against the direction of travel on the
     * link, so a transporter placed on a link is ready to move forward off it.
     */
    internal fun resolvePlacement(
        transporter: GuidedTransporter,
        placement: TransporterPlacement
    ): List<Zone> = when (placement) {
        is TransporterPlacement.At -> {
            val intersection = network.location(placement.locationName)
                ?: throw GuidedPathNetworkException(
                    "Transporter (${transporter.name}) is placed at (${placement.locationName}), " +
                            "which is neither an intersection nor a station alias of network " +
                            "${network.name}."
                )
            if (transporter.lengthInZones > 1) {
                throw GuidedPathNetworkException.multiZoneTransporterAtIntersection(
                    transporter.name, transporter.lengthInZones, intersection.name
                )
            }
            listOf(intersection.zone)
        }

        is TransporterPlacement.OnZone -> {
            val zone = network.zone(placement.zoneName)
                ?: throw GuidedPathNetworkException(
                    "Transporter (${transporter.name}) is placed on zone (${placement.zoneName}), " +
                            "which network ${network.name} does not have."
                )
            when (zone) {
                is IntersectionZone -> {
                    if (transporter.lengthInZones > 1) {
                        throw GuidedPathNetworkException.multiZoneTransporterAtIntersection(
                            transporter.name, transporter.lengthInZones, zone.intersection.name
                        )
                    }
                    listOf(zone)
                }

                is LinkZone -> {
                    val link = zone.link
                    if (transporter.lengthInZones > link.numZones) {
                        throw GuidedPathNetworkException.transporterTooLongForPlacement(
                            transporter.name, transporter.lengthInZones, link.name, link.numZones
                        )
                    }
                    val front = zone.positionOnLink
                    if (front < transporter.lengthInZones) {
                        throw GuidedPathNetworkException(
                            "Transporter (${transporter.name}) covers ${transporter.lengthInZones} " +
                                    "zones and was placed with its front at (${zone.name}), which is " +
                                    "only position $front along link (${link.name}). Its rear would " +
                                    "hang off the start of the link. Place its front further along."
                        )
                    }
                    (front - transporter.lengthInZones until front).map { link.zones[it] }
                }
            }
        }
    }

    /** Where a transporter is, expressed as a location the rest of the library understands. */
    internal fun locationOf(transporter: GuidedTransporter): LocationIfc {
        val front = transporter.frontZone
        return when (front) {
            is IntersectionZone -> front.intersection
            is LinkZone -> front.link.endIntersection
            null -> network.defaultLocation
        }
    }

    /**
     * Starts a transporter travelling toward a destination.
     *
     * @return true when a movement was started, false when it is already there
     */
    internal fun startMove(
        transporter: GuidedTransporter,
        destinationName: String,
        movingState: TransporterState
    ): Boolean {
        val destination = network.requireLocation(destinationName)
        return engine.startMove(transporter, destination, movingState)
    }

    /** Keeps the fleet-level counts current. Called whenever a transporter changes what it is doing. */
    internal fun refreshFleetCounts() {
        var moving = 0
        var blocked = 0
        var idle = 0
        for (t in myTransporters) {
            when {
                t.transporterState == TransporterState.BLOCKED -> blocked++
                t.isMoving -> moving++
                else -> idle++
            }
        }
        myNumMoving.value = moving.toDouble()
        myNumBlocked.value = blocked.toDouble()
        myNumIdle.value = idle.toDouble()
        var occupied = 0
        for (z in network.zones) if (z.isOccupied) occupied++
        myZoneUtilization.value = occupied.toDouble() / network.zones.size
    }

    // ---- event scheduling ---------------------------------------------------------------------
    //
    // The engine decides what happens and when; the scheduling lives here because a model element
    // is what the executive will accept events from. Each transporter has at most one traversal in
    // flight, so a traversal event needs no bookkeeping beyond the transporter and the zone.

    private inner class TraversalAction : EventActionIfc<Pair<GuidedTransporter, Zone>> {
        override fun action(event: KSLEvent<Pair<GuidedTransporter, Zone>>) {
            val (transporter, zone) = event.message!!
            engine.endZoneTraversal(transporter, zone)
        }
    }

    private inner class ClaimRetryAction : EventActionIfc<GuidedTransporter> {
        override fun action(event: KSLEvent<GuidedTransporter>) {
            engine.retryClaim(event.message!!)
        }
    }

    private inner class RearReleaseAction : EventActionIfc<GuidedTransporter> {
        override fun action(event: KSLEvent<GuidedTransporter>) {
            engine.releaseRearAfterDistance(event.message!!)
        }
    }

    private val myTraversalAction = TraversalAction()
    private val myClaimRetryAction = ClaimRetryAction()
    private val myRearReleaseAction = RearReleaseAction()

    /** Schedules a transporter's arrival in the zone it is travelling into. */
    internal fun scheduleTraversal(transporter: GuidedTransporter, zone: Zone, delay: Double) {
        schedule(
            myTraversalAction, delay, transporter to zone, ProcessModel.MOVE_PRIORITY,
            "${transporter.name}:enter:${zone.name}"
        )
    }

    /**
     * Schedules a waiting transporter's fresh attempt at what it was waiting for.
     *
     * Scheduled rather than called directly, so that its order against everything else happening at
     * that instant is explicit, and so that one release cannot set off an unbounded chain of
     * wake-ups inside a single event. The priority puts the attempt ahead of other transporters'
     * arrivals at the same instant and behind a process resumption already in flight.
     */
    internal fun scheduleClaimRetry(transporter: GuidedTransporter) {
        schedule(
            myClaimRetryAction, 0.0, transporter, ProcessModel.ZONE_CLAIM_PRIORITY,
            "${transporter.name}:retryClaim"
        )
    }

    /**
     * Schedules the release of the zone behind, for a control rule that gives it up part way into
     * the zone ahead rather than at one end or the other.
     */
    internal fun scheduleRearRelease(transporter: GuidedTransporter, delay: Double) {
        schedule(
            myRearReleaseAction, delay, transporter, ProcessModel.MOVE_PRIORITY,
            "${transporter.name}:releaseRear"
        )
    }

    override fun initialize() {
        for (zone in network.zones) {
            zone.resetZone()
        }
        for (link in network.links) {
            link.resetLink()
        }
        for (transporter in myTransporters) {
            transporter.placeAtInitialPosition()
            engine.acquirePlacementHolds(transporter)
        }
        refreshFleetCounts()
    }

    /** The transporters currently unable to proceed, with what each is waiting for. */
    val blockedTransporters: List<GuidedTransporter>
        get() = myTransporters.filter { it.transporterState == TransporterState.BLOCKED }

    /**
     * Reports any transporter still waiting when a replication ends.
     *
     * A waiting transporter schedules nothing, so a guide path that has stopped moving does not
     * announce itself: the clock simply runs on to the end of the replication with nobody going
     * anywhere, and the output looks like a system that merely had no work to do. This is the point
     * at which that becomes visible, and it names what each transporter holds and what it is
     * waiting for, which is what a modeler needs in order to see the cycle or the obstruction.
     */
    override fun replicationEnded() {
        val stuck = blockedTransporters
        if (stuck.isEmpty()) return
        logger.warn {
            buildString {
                append("GuidedPathTransportSystem ($name): ${stuck.size} transporter(s) were still ")
                append("waiting when replication ${model.currentReplicationNumber} ended. ")
                append("The guide path may have stopped moving rather than run out of work.")
                for (t in stuck) {
                    append(System.lineSeparator())
                    append("  (${t.name}) holds [${t.heldZones.joinToString { z -> z.name }}] ")
                    append("and waits for ")
                    append(t.awaitedLink?.let { l -> "link (${l.name})" } ?: "zone (${t.awaitedZone?.name})")
                }
            }
        }
    }

    override fun registerConditionalActions() {
        if (!checkInvariants) return
        val checker = ZoneInvariantChecker(this)
        myInvariantChecker = checker
        executive.register(checker)
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}
    }

    override fun toString(): String = buildString {
        appendLine("GuidedPathTransportSystem : $name")
        appendLine("network = ${network.name}")
        appendLine("transporters:")
        for (t in myTransporters) appendLine("  $t")
    }
}
