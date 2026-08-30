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
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.exceptions.GuidedPathObstructionException
import ksl.modeling.guidedpath.internal.DeadlockDetector
import ksl.modeling.guidedpath.internal.MovementEngine
import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.guidedpath.internal.ZoneInvariantChecker
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.queue.QueueCIfc
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
    collectLinkStatistics: Boolean = false,
    collectZoneStatistics: Boolean = false,
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

    /**
     * Whether the wait-for graph is walked when a transporter blocks.
     *
     * On by default. A guide path that deadlocks and says nothing is the failure mode the whole
     * subsystem exists to improve on, so the cost is accepted: the walk happens only when a
     * transporter blocks, which in a well-designed network is rare, and it is proportional to the
     * fleet rather than to the number of events.
     *
     * Turning it off buys throughput and gives up `G5`. A run that then deadlocks stops advancing
     * and finishes normally, with nothing but the end-of-replication warning to say so.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var deadlockDetectionEnabled: Boolean = true
        set(value) {
            require(model.isNotRunning) {
                "Deadlock detection cannot be switched while the model is running."
            }
            field = value
        }

    /**
     * Whether an idle transporter obstructing another ends the replication instead of being warned
     * about and counted.
     *
     * Off by default, and the asymmetry with deadlock is deliberate. A cycle cannot resolve itself,
     * so it is always an error. An obstruction can: dispatching the idle transporter clears it, and
     * the condition is judged from a single instant, so raising by default would fail models that
     * are perfectly sound. Set this when a study needs the obstruction treated as a design failure
     * rather than as a warning, and expect occasional false alarms in exchange for certainty.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var strictObstructionPolicy: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "The obstruction policy cannot be changed while the model is running."
            }
            field = value
        }

    private val myDetector: DeadlockDetector = DeadlockDetector(this)

    // ---- what the guide path costs the executive -----------------------------------------------
    //
    // A zone traversal is one event, so discretizing a layout finely to make an animation look
    // smooth buys that smoothness in events, and a modeler who picks zone size for the picture
    // rather than for the control granularity can make a model far slower without meaning to. The
    // guide says so; these two make it measurable, and their ratio is what catches the failure that
    // matters most -- a regression into repeated wake-ups, where a transporter is woken, refused,
    // and rescheduled over and over. That shows up as events per traversal climbing while the model
    // still gives the right answers.

    private val myNumZoneTraversals = Counter(this, name = "${this.name}:NumZoneTraversals")

    /** How many zones were entered, across the whole fleet. */
    val numZoneTraversals: CounterCIfc
        get() = myNumZoneTraversals

    private val myNumEventsScheduled = Counter(this, name = "${this.name}:NumEventsScheduled")

    /** How many events the guide path put on the calendar: traversals, rear releases, and retries. */
    val numEventsScheduled: CounterCIfc
        get() = myNumEventsScheduled

    private val myEventsPerTraversal = Response(this, name = "${this.name}:EventsPerZoneTraversal")

    /**
     * Events scheduled per zone entered, computed when the replication ends.
     *
     * One is the floor: a transporter that never waits for anything schedules a single traversal
     * for each zone it enters. Distance-based zone control adds a second event per traversal by
     * design and lands near two. Anything much above that is transporters being woken and refused,
     * which is a performance defect rather than a modelling choice.
     */
    val eventsPerZoneTraversal: ResponseCIfc
        get() = myEventsPerTraversal

    private val myNumDeadlocks = Counter(this, name = "${this.name}:NumDeadlocksDetected")

    /**
     * How many circular waits were found. At most one per replication, since finding one ends it.
     *
     * Counted anyway, and the reason is the parameter sweep: a study that catches the exception
     * around each replication and records the design point as infeasible needs something in the
     * output that says which points those were. Reading it off the counter beats keeping a tally
     * beside the run.
     */
    val numDeadlocksDetected: CounterCIfc
        get() = myNumDeadlocks

    private val myNumObstructions = Counter(this, name = "${this.name}:NumObstructionsDetected")

    /**
     * How many times a transporter was found blocked behind an idle one that will not move.
     *
     * Counted rather than only logged so that the condition appears in the standard report, where
     * an analyst will see it. A model that produces a positive count here has almost certainly
     * stopped moving somewhere, and the run that produced it should not be believed until the
     * count is explained.
     */
    val numObstructionsDetected: CounterCIfc
        get() = myNumObstructions

    /**
     * Examines a transporter that has just become blocked, and is the only place either condition
     * is looked for.
     *
     * A cycle can only come into existence when somebody enters the blocked state, so checking
     * there is both necessary and sufficient; checking on a timer or at every event would cost in
     * proportion to the event count and find nothing extra.
     *
     * @throws GuidedPathDeadlockException when the transporter lies on a circular wait
     * @throws GuidedPathObstructionException when it is behind an idle transporter and the strict
     *   policy is set
     */
    internal fun transporterBlocked(transporter: GuidedTransporter) {
        if (!deadlockDetectionEnabled) return
        val cycle = myDetector.findCycle(transporter)
        if (cycle != null) {
            myNumDeadlocks.increment()
            logger.error { cycle.toString() }
            throw GuidedPathDeadlockException(cycle)
        }
        val obstruction = myDetector.findObstruction(transporter) ?: return
        myNumObstructions.increment()
        if (strictObstructionPolicy) {
            logger.error { obstruction.toString() }
            throw GuidedPathObstructionException(obstruction)
        }
        logger.warn { obstruction.toString() }
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

    // ---- opt-in detail statistics -------------------------------------------------------------
    //
    // A guide path of a thousand zones would register a thousand time-weighted responses if
    // occupancy were collected automatically, and every report and every output-database table
    // would carry them whether or not anyone asked. So the detail is off unless requested, while
    // the system-level aggregates above -- which are O(1) in network size and answer the first
    // question anybody asks about congestion -- are always there.
    //
    // Both flags are settable up to the moment the model runs, and either direction takes effect
    // immediately: switching one on registers its responses, switching it off removes them. That
    // works because the model only refuses to add or remove a model element while it is *running*,
    // so everything either flag does is legal right up to the first replication. Turning the detail
    // off therefore genuinely shrinks the report rather than merely stopping the numbers being
    // updated, which matters -- a response left registered but never written would appear in every
    // report and every database table with nothing in it, which is worse than either honest answer.

    private var myLinkOccupancy: Map<Link, TWResponse> = emptyMap()
    private var myLinkUtilization: Map<Link, Response> = emptyMap()
    private var myIntersectionOccupancy: Map<GuidedPathNetwork.Intersection, TWResponse> = emptyMap()
    private var myZoneOccupancy: Map<Zone, TWResponse> = emptyMap()

    /**
     * Whether occupancy is collected for each link and each intersection.
     *
     * Settable until the model runs. Setting it registers or removes the responses there and then,
     * so the flag and the report always agree.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectLinkStatistics: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "Link statistics cannot be switched while the model is running."
            }
            if (value == field) return
            if (value) {
                myLinkOccupancy = network.links.associateWith {
                    TWResponse(this, name = "${this.name}:${it.name}:NumZonesOccupied")
                }
                myLinkUtilization = network.links.associateWith {
                    Response(this, name = "${this.name}:${it.name}:Utilization")
                }
                // Named for the tier rather than just for the place. An intersection *is* a zone,
                // so with both flags on it would otherwise be registered twice under one name and
                // the model would refuse to build -- which is exactly what happened the first time
                // the two tiers were switched on together.
                myIntersectionOccupancy = network.intersections.associateWith {
                    TWResponse(this, name = "${this.name}:${it.name}:IntersectionOccupied")
                }
            } else {
                discard(myLinkOccupancy.values)
                discard(myLinkUtilization.values)
                discard(myIntersectionOccupancy.values)
                myLinkOccupancy = emptyMap()
                myLinkUtilization = emptyMap()
                myIntersectionOccupancy = emptyMap()
            }
            field = value
        }

    /**
     * Whether occupancy is collected for every individual zone.
     *
     * The finest tier and the most expensive: one response per zone. Settable until the model runs,
     * in either direction, as [collectLinkStatistics] is.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var collectZoneStatistics: Boolean = false
        set(value) {
            require(model.isNotRunning) {
                "Zone statistics cannot be switched while the model is running."
            }
            if (value == field) return
            if (value) {
                myZoneOccupancy = network.zones.associateWith {
                    TWResponse(this, name = "${this.name}:${it.name}:ZoneOccupied")
                }
            } else {
                discard(myZoneOccupancy.values)
                myZoneOccupancy = emptyMap()
            }
            field = value
        }

    /**
     * Takes responses back out of the model, so that switching a tier off shrinks the report
     * instead of leaving empty columns in it. The name each held becomes free again, which is what
     * lets a tier be switched off and on again.
     */
    private fun discard(responses: Collection<ModelElement>) {
        for (response in responses) {
            model.removeFromModel(response)
        }
    }

    init {
        // Applied through the setters, so that constructing with a flag and setting it afterwards
        // go down exactly the same path and cannot drift apart.
        this.collectLinkStatistics = collectLinkStatistics
        this.collectZoneStatistics = collectZoneStatistics
    }

    /** Zones of each link covered by a transporter, when link statistics were asked for. */
    val linkOccupancy: Map<Link, TWResponseCIfc>
        get() = myLinkOccupancy

    /**
     * The fraction of each link's zones covered over the replication, computed when the replication
     * ends in the same way a conveyor computes its cell utilization.
     */
    val linkUtilization: Map<Link, ResponseCIfc>
        get() = myLinkUtilization

    /** Whether each intersection was covered, when link statistics were asked for. */
    val intersectionOccupancy: Map<GuidedPathNetwork.Intersection, TWResponseCIfc>
        get() = myIntersectionOccupancy

    /** Whether each individual zone was covered, when zone statistics were asked for. */
    val zoneOccupancy: Map<Zone, TWResponseCIfc>
        get() = myZoneOccupancy

    // ---- what each completed transport cost ----------------------------------------------------
    //
    // These are returned to the process in a GuidedTransportResult as well as accumulated here. The
    // duplication is deliberate: a modeler who wants per-entity outcomes gets them without
    // attaching an observer, and one who wants the fleet-level summary gets it without writing any
    // collection code at all.

    private val myTransportTime = Response(this, name = "${this.name}:TransportTime")

    /** How long a whole transport took, from the request to the entity being set down. */
    val transportTime: ResponseCIfc
        get() = myTransportTime

    private val myEmptyMoveTime = Response(this, name = "${this.name}:EmptyMoveTime")

    /** How long transporters spent travelling to collect an entity. */
    val emptyMoveTime: ResponseCIfc
        get() = myEmptyMoveTime

    private val myLoadedMoveTime = Response(this, name = "${this.name}:LoadedMoveTime")

    /** How long transporters spent carrying one. */
    val loadedMoveTime: ResponseCIfc
        get() = myLoadedMoveTime

    private val myTransportBlockedTime = Response(this, name = "${this.name}:TransportBlockedTime")

    /**
     * How much of a transport was spent unable to claim the space ahead. The quantity a free-path
     * model cannot produce at all, which is why it is reported per transport and not only as a
     * fraction of each transporter's time.
     */
    val transportBlockedTime: ResponseCIfc
        get() = myTransportBlockedTime

    private val myZonesTraversed = Response(this, name = "${this.name}:ZonesTraversedPerTransport")

    /** How many zones a loaded transporter crossed. */
    val zonesTraversedPerTransport: ResponseCIfc
        get() = myZonesTraversed

    private val myRouteLength = Response(this, name = "${this.name}:RouteLengthPerTransport")

    /** How far a loaded transporter travelled. */
    val routeLengthPerTransport: ResponseCIfc
        get() = myRouteLength

    /** Records a completed transport. Called by the process verb that finishes one. */
    internal fun collectTransportResult(result: GuidedTransportResult) {
        myTransportTime.value = result.totalTime
        myEmptyMoveTime.value = result.emptyMoveTime
        myLoadedMoveTime.value = result.loadedMoveTime
        myTransportBlockedTime.value = result.blockedTime
        myZonesTraversed.value = result.zonesTraversed.toDouble()
        myRouteLength.value = result.routeLength
    }

    // ---- animation -----------------------------------------------------------------------------

    private val myAnimationEmitter = GuidedPathAnimationEmitter(this)

    /** Emits a transporter's state change, doing nothing when no animation sink is active. */
    internal fun emitTransporterState(transporter: GuidedTransporter, state: TransporterState) {
        myAnimationEmitter.emitTransporterState(transporter, state)
    }

    /** Emits a transporter's arrival in a zone, doing nothing when no animation sink is active. */
    internal fun emitTransporterMoved(transporter: GuidedTransporter, zone: Zone) {
        myAnimationEmitter.emitTransporterMoved(transporter, zone)
    }

    /** Records that a transporter entered a zone while travelling. */
    internal fun countZoneTraversal() {
        myNumZoneTraversals.increment()
    }

    private val myMovementHoldQ = HoldQueue(this, "${this.name}:MovementHoldQ")

    /**
     * Entities suspended while a transporter carries them, or fetches them.
     *
     * A journey spans many events, so an entity cannot simply be delayed for it: it is held here
     * for the whole journey and woken when the transporter announces that it has arrived.
     */
    val movementHoldQ: QueueCIfc<ProcessModel.Entity>
        get() = myMovementHoldQ

    /** The queue itself, for the process verbs that suspend an entity in it. */
    internal val movementHoldQueue: HoldQueue
        get() = myMovementHoldQ

    internal fun addTransporter(transporter: GuidedTransporter) {
        require(model.isNotRunning) {
            "A transporter cannot be added while the model is running."
        }
        myTransporters.add(transporter)
    }

    /**
     * Wakes whoever was waiting on a transporter's journey.
     *
     * Called by the transporter itself when it arrives, rather than through a listener registered
     * from here. Registering one during `addTransporter` would mean reaching back into a
     * transporter that is still running its own constructor, whose properties are not all in place
     * yet -- an initialisation order that happens to work only while the declarations stay in the
     * order they are in today.
     */
    internal fun transporterArrived(transporter: GuidedTransporter) {
        val waiting = transporter.waitingEntity ?: return
        transporter.waitingEntity = null
        myMovementHoldQ.removeAndResume(waiting)
    }

    /**
     * Sets a transporter travelling and returns whether it actually has to go anywhere.
     *
     * @return true when a journey began, false when the transporter was already there
     */
    internal fun beginJourney(
        transporter: GuidedTransporter,
        destinationName: String,
        movingState: TransporterState,
        entity: ProcessModel.Entity
    ): Boolean {
        val moving = startMove(transporter, destinationName, movingState)
        if (moving) {
            transporter.waitingEntity = entity
        }
        return moving
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
        // One walk over the zones serves the aggregate and, when they were asked for, the details.
        // The walk happens either way, so collecting the detail costs the map lookups and nothing
        // more -- and when the flags are off the maps are empty and there are no lookups at all.
        var occupied = 0
        val perLink = if (collectLinkStatistics) HashMap<Link, Int>(network.links.size) else null
        for (z in network.zones) {
            val isOccupied = z.isOccupied
            if (isOccupied) occupied++
            myZoneOccupancy[z]?.value = if (isOccupied) 1.0 else 0.0
            when (z) {
                is LinkZone -> if (perLink != null && isOccupied) {
                    perLink[z.link] = (perLink[z.link] ?: 0) + 1
                }

                is IntersectionZone ->
                    myIntersectionOccupancy[z.intersection]?.value = if (isOccupied) 1.0 else 0.0
            }
        }
        if (perLink != null) {
            for ((link, response) in myLinkOccupancy) {
                response.value = (perLink[link] ?: 0).toDouble()
            }
        }
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
        myNumEventsScheduled.increment()
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
        myNumEventsScheduled.increment()
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
        myNumEventsScheduled.increment()
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
        // Emitted every replication rather than once per run, so that a viewer joining at any
        // replication boundary has the structure before anything moves on it.
        myAnimationEmitter.emitGuidedPathDefined()
        for (transporter in myTransporters) {
            transporter.frontZone?.let { myAnimationEmitter.emitTransporterMoved(transporter, it) }
            myAnimationEmitter.emitTransporterState(transporter, transporter.transporterState)
        }
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
        val traversals = myNumZoneTraversals.value
        if (traversals > 0.0) {
            myEventsPerTraversal.value = myNumEventsScheduled.value / traversals
        }
        // The same derivation a conveyor uses for cell utilization: the time-weighted average
        // number of zones covered, over how many zones the link has.
        for ((link, occupancy) in myLinkOccupancy) {
            myLinkUtilization[link]?.value =
                occupancy.withinReplicationStatistic.weightedAverage / link.numZones
        }
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
