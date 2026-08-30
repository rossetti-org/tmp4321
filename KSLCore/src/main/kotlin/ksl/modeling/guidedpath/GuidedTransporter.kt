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
import ksl.modeling.entity.Resource
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.routing.Route
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.SpatialElement
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.toDouble

/**
 * Where a transporter stands at the start of every replication.
 *
 * Placement is declared rather than remembered, and re-applied on every replication, so a run that
 * ends with the fleet scattered -- or deadlocked -- cannot bias the next one. Where a transporter
 * starts matters more on a guide path than in an ordinary resource pool, because a stationary
 * transporter occupies space that others may need.
 */
sealed class TransporterPlacement {

    /** Standing at a junction, addressed by its name or by a station alias. */
    data class At(val locationName: String) : TransporterPlacement()

    /**
     * Standing on a link, covering the named zone and the zones behind it.
     *
     * @param zoneName the zone the transporter's front is in
     */
    data class OnZone(val zoneName: String) : TransporterPlacement()
}

/**
 * What a transporter is doing.
 *
 * The states divide into those that involve an entity and those that do not, and blocking can
 * happen from either side: a transporter returning to its home base with nothing aboard can be
 * blocked just as a loaded one can. That is why blocking remembers what was interrupted rather than
 * inferring it from whether an entity is present.
 */
enum class TransporterState {

    /** Allocated to no one and going nowhere. Still occupying its zones. */
    IDLE,

    /** Travelling without an entity aboard, to collect one. */
    MOVING_EMPTY,

    /** Travelling with an entity aboard. */
    MOVING_LOADED,

    /** An entity is being loaded. */
    LOADING,

    /** An entity is being unloaded. */
    UNLOADING,

    /** Travelling to a home base or staging area, allocated to no one. */
    RETURNING_HOME,

    /** Unable to claim the next zone, and waiting for whoever holds it. */
    BLOCKED
}

/**
 * How often a transporter's velocity is drawn when its velocity is random.
 */
enum class VelocitySampling {

    /**
     * Drawn once when a movement begins and held for the whole route. Matches how a free-path
     * movable resource behaves, which is what makes a free-path model and a guide-path model of the
     * same system comparable: the difference between them is then congestion, not a change in how
     * velocity was sampled.
     */
    PER_MOVE,

    /**
     * Drawn again for every zone. The right model of a drive whose speed genuinely fluctuates over
     * short distances.
     */
    PER_ZONE
}

/**
 * Told when a transporter reaches the end of a route.
 */
fun interface TransporterArrivalListenerIfc {

    /** @param transporter the transporter that has just arrived */
    fun arrived(transporter: GuidedTransporter)
}

/**
 * A vehicle on a guide path: a resource of capacity one that occupies space and must claim the
 * space ahead of it before moving into it.
 *
 * A transporter is a resource in every respect, so it is seized and released like any other, and
 * the allocation machinery, request queues, and utilization statistics apply unchanged. What sets
 * it apart is that it has extent. It covers a contiguous run of zones, and while it stands still it
 * denies that space to everyone else -- which is why an idle transporter left on the guide path is
 * a hazard rather than merely an idle asset.
 *
 * It deliberately does **not** extend the free-path movable resource. That class moves by
 * scheduling a single delay computed from distance and velocity, with no notion of the space in
 * between; inheriting it would expose a way to move that ignores every rule this subsystem exists
 * to enforce. The two share the abstractions that genuinely mean the same thing -- position and
 * velocity -- and nothing else.
 *
 * @param system the runtime this transporter belongs to
 * @param initialPlacement where it stands at the start of every replication
 * @param velocity how fast it travels, sampled per movement by default
 * @param lengthInZones how many zones it covers when fully on the guide path
 * @param zoneControlRule when it gives up the zone behind it
 * @param name a name for it
 */
class GuidedTransporter @JvmOverloads constructor(
    val system: GuidedPathTransportSystem,
    initialPlacement: TransporterPlacement,
    velocity: RVariableIfc,
    val lengthInZones: Int = 1,
    val zoneControlRule: ZoneControlRuleIfc = EndOfZoneControl(),
    name: String? = null
) : Resource(system, name, 1) {

    init {
        require(lengthInZones >= 1) {
            "A transporter must cover at least one zone, but ($name) was given $lengthInZones."
        }
        system.addTransporter(this)
    }

    /** Where this transporter stands at the start of every replication. */
    var initialPlacement: TransporterPlacement = initialPlacement
        set(value) {
            require(model.isNotRunning) {
                "The initial placement cannot be changed while the model is running."
            }
            field = value
        }

    /**
     * Where this transporter waits when it has nothing to do, or null when it waits where it stops.
     *
     * Named rather than resolved, so that a network can be rebuilt from data without the fleet
     * holding stale references into the old one.
     */
    var homeBase: String? = null
        set(value) {
            require(model.isNotRunning) {
                "The home base cannot be changed while the model is running."
            }
            field = value
        }

    /** How often the velocity is drawn when it is random. */
    var velocitySampling: VelocitySampling = VelocitySampling.PER_MOVE
        set(value) {
            require(model.isNotRunning) {
                "The velocity sampling policy cannot be changed while the model is running."
            }
            field = value
        }

    private val myVelocity: RandomVariable =
        RandomVariable(this, velocity, name = "${this.name}:VelocityRV")

    /** The velocity source. */
    val velocityRV: RandomVariableCIfc
        get() = myVelocity

    private val mySpatialElement: SpatialElement =
        SpatialElement(this, system.network.defaultLocation, this.name)

    /** Where the transporter is, for animation and for updating what it carries. */
    var currentLocation: LocationIfc
        get() = mySpatialElement.currentLocation
        internal set(value) {
            mySpatialElement.currentLocation = value
        }

    private val myOccupiedZones = mutableListOf<Zone>()

    /**
     * The zones the transporter covers, from the rear of the vehicle to its front. Contiguous along
     * its direction of travel, and no longer than its length once it is fully on the guide path.
     */
    val occupiedZones: List<Zone>
        get() = myOccupiedZones

    /**
     * The zone the transporter has reserved and is travelling into, or null when it is not moving.
     *
     * A transporter holds this zone as surely as it holds the ones it covers -- no one else may
     * enter it -- but its body is not in it yet. The distinction matters under a control rule that
     * gives up the zone behind at the moment travel begins: a transporter one zone long is then
     * briefly between zones, covering none and holding only this one.
     */
    var claimedZone: Zone? = null
        internal set

    /** Every zone the transporter denies to others: the ones it covers, plus the one it is entering. */
    val heldZones: List<Zone>
        get() = claimedZone?.let { myOccupiedZones + it } ?: myOccupiedZones

    /** The zone at the leading edge, or null before the transporter has been placed. */
    val frontZone: Zone?
        get() = myOccupiedZones.lastOrNull()

    /** The zone at the trailing edge, or null before the transporter has been placed. */
    val rearZone: Zone?
        get() = myOccupiedZones.firstOrNull()

    /**
     * What the transporter is doing. Named apart from the resource state it inherits, which says
     * whether the resource is busy or idle rather than what the vehicle is up to.
     */
    var transporterState: TransporterState = TransporterState.IDLE
        internal set(value) {
            // Blocked time is accumulated here rather than read back off the time-weighted
            // statistic, because that statistic reports an average over the post-warm-up interval
            // and only moves when the state does. A journey needs the elapsed blocked time between
            // two instants, which is a different quantity.
            if (field == TransporterState.BLOCKED && value != TransporterState.BLOCKED) {
                // A replication can end with a transporter still blocked, and the reset that starts
                // the next one clears the start instant while the state is still BLOCKED. Without
                // this guard that transition accumulates `time - NaN`, and the NaN then travels
                // into the first transport result of the new replication and fails the run --
                // several thousand simulated minutes away from the reset that caused it.
                if (!blockedSince.isNaN()) {
                    myCumulativeBlockedTime += time - blockedSince
                }
                blockedSince = Double.NaN
            } else if (field != TransporterState.BLOCKED && value == TransporterState.BLOCKED) {
                blockedSince = time
            }
            field = value
            myFracTimeMoving.value = isMoving.toDouble()
            myFracTimeBlocked.value = (value == TransporterState.BLOCKED).toDouble()
            myFracTimeTransporting.value = (value == TransporterState.MOVING_LOADED).toDouble()
            myFracTimeMovingEmpty.value =
                (value == TransporterState.MOVING_EMPTY || value == TransporterState.RETURNING_HOME).toDouble()
            system.emitTransporterState(this, value)
        }

    /**
     * What the transporter was doing when it blocked, so that it resumes the movement it was making
     * rather than a guess. A transporter returning home and one collecting an entity are both empty
     * and both blockable, so being empty says nothing about which it was.
     */
    internal var stateBeforeBlocking: TransporterState = TransporterState.IDLE

    /** True while the transporter is travelling, whatever the reason. */
    val isMoving: Boolean
        get() = transporterState == TransporterState.MOVING_EMPTY ||
                transporterState == TransporterState.MOVING_LOADED ||
                transporterState == TransporterState.RETURNING_HOME

    /** The route being followed, or null when the transporter is not travelling. */
    var currentRoute: Route? = null
        internal set

    /**
     * Where the transporter has been told to go instead, once it finishes entering the zone it is
     * already travelling into.
     *
     * A vehicle part way into a zone cannot stop and turn round: it is physically between two
     * places. So a redirection given while it is moving takes effect at the next zone boundary
     * rather than immediately, which is also what keeps the reservation it is holding honest --
     * the zone it reserved is the zone it will enter.
     */
    internal var pendingDestination: GuidedPathNetwork.Intersection? = null

    /** What the transporter will be doing once a pending redirection takes effect. */
    internal var pendingMovingState: TransporterState = TransporterState.RETURNING_HOME

    /**
     * The entity suspended until this transporter finishes its current movement, or null.
     *
     * A transporter travels over many events, so an entity riding it -- or waiting for it to come
     * and collect it -- has to be held for the whole journey rather than for a single delay. This
     * is who to wake when the journey ends.
     */
    internal var waitingEntity: ProcessModel.Entity? = null

    /**
     * The spur this transporter has taken over, or null.
     *
     * Held separately from the zones it covers, because a transporter parked at the dead end of a
     * spur covers only the junction there and no part of the spur itself -- yet it has plainly not
     * left, since the only way out is back down the spur. Deciding it had left by looking at link
     * zones alone would let a second transporter in behind it, and the two would then face each
     * other with neither able to move.
     */
    internal var reservedSpur: Link? = null

    /** The zone the transporter is waiting for, or null when it is not waiting. */
    var awaitedZone: Zone? = null
        internal set

    /**
     * The link holding the transporter up, or null when it is waiting for a zone instead, or not
     * waiting at all.
     *
     * A transporter can be stopped by a link whose zone is perfectly free: the link may be running
     * the other way, or be a spur that another transporter is down. Recording which of the two is
     * in the way is what lets it be woken by the right event.
     */
    var awaitedLink: Link? = null
        internal set

    /** The direction it faces on a link, which decides where it may go next. */
    var travellingForward: Boolean = true
        internal set

    /** The velocity in force for the current movement, held when sampling is per movement. */
    internal var currentVelocity: Double = 1.0

    /** The velocity to use for the next zone, drawn according to the sampling policy. */
    internal fun velocityForNextZone(): Double = when (velocitySampling) {
        VelocitySampling.PER_MOVE -> currentVelocity
        VelocitySampling.PER_ZONE -> sampleVelocity()
    }

    internal fun sampleVelocity(): Double {
        val v = myVelocity.value
        check(v > 0.0) {
            "Transporter (${this.name}) drew a velocity of $v. A velocity must be > 0.0, so the " +
                    "velocity random variable must not be able to produce zero or a negative value."
        }
        return v
    }

    private val myArrivalListeners = mutableListOf<TransporterArrivalListenerIfc>()

    /**
     * Asks to be told whenever this transporter reaches the end of a route.
     *
     * Arrival is the moment several unrelated concerns care about at once: an entity being carried
     * must be resumed, an animation must be told, a dispatcher may want to send the transporter on.
     * Announcing it leaves the engine ignorant of all of them.
     */
    fun attachArrivalListener(listener: TransporterArrivalListenerIfc) {
        myArrivalListeners.add(listener)
    }

    /** Stops telling a listener about arrivals. */
    fun detachArrivalListener(listener: TransporterArrivalListenerIfc) {
        myArrivalListeners.remove(listener)
    }

    internal fun notifyArrival() {
        // The system first, so that an entity being carried is released before anything a modeler
        // has attached runs and possibly sends this transporter somewhere else.
        system.transporterArrived(this)
        // Copied, so that a listener may detach itself, or attach another, while being told.
        for (listener in myArrivalListeners.toList()) {
            listener.arrived(this)
        }
    }

    // ---- statistics ---------------------------------------------------------------------------

    private val myFracTimeMoving = TWResponse(this, name = "${this.name}:FracTimeMoving")

    /** The fraction of time spent travelling, for or without an entity. */
    val fracTimeMoving: TWResponseCIfc
        get() = myFracTimeMoving

    private val myFracTimeTransporting = TWResponse(this, name = "${this.name}:FracTimeTransporting")

    /** The fraction of time spent travelling with an entity aboard. */
    val fracTimeTransporting: TWResponseCIfc
        get() = myFracTimeTransporting

    private val myFracTimeMovingEmpty = TWResponse(this, name = "${this.name}:FracTimeMovingEmpty")

    /** The fraction of time spent travelling with nothing aboard. */
    val fracTimeMovingEmpty: TWResponseCIfc
        get() = myFracTimeMovingEmpty

    private val myFracTimeBlocked = TWResponse(this, name = "${this.name}:FracTimeBlocked")

    /**
     * The fraction of time spent unable to claim the space ahead. The statistic a free-path model
     * cannot produce, and the one that says how much a fleet is getting in its own way.
     */
    val fracTimeBlocked: TWResponseCIfc
        get() = myFracTimeBlocked

    private val myNumTimesBlocked = Counter(this, name = "${this.name}:NumTimesBlocked")

    /** How many times the transporter has been unable to claim the space ahead. */
    val numTimesBlocked: CounterCIfc
        get() = myNumTimesBlocked

    internal fun countBlocking() {
        myNumTimesBlocked.increment()
    }

    private var blockedSince: Double = Double.NaN
    private var myCumulativeBlockedTime: Double = 0.0

    /**
     * How long this transporter has spent blocked so far in this replication, including any block
     * still in progress. Differences of this between two instants give the blocked time within a
     * journey.
     */
    internal val cumulativeBlockedTime: Double
        get() = if (blockedSince.isNaN()) myCumulativeBlockedTime
        else myCumulativeBlockedTime + (time - blockedSince)

    // ---- placement and movement ---------------------------------------------------------------

    /**
     * Puts the transporter where its placement says, claiming and occupying the zones it covers.
     * Called for every transporter at the start of every replication.
     */
    internal fun placeAtInitialPosition() {
        myOccupiedZones.clear()
        currentRoute = null
        claimedZone = null
        pendingDestination = null
        awaitedZone = null
        awaitedLink = null
        reservedSpur = null
        waitingEntity = null
        travellingForward = true
        transporterState = TransporterState.IDLE
        stateBeforeBlocking = TransporterState.IDLE
        // After the state, so that a transporter still blocked when the previous replication ended
        // leaves that state through the setter above before its running totals are cleared.
        blockedSince = Double.NaN
        myCumulativeBlockedTime = 0.0
        currentVelocity = sampleVelocity()
        val zones = system.resolvePlacement(this, initialPlacement)
        for (zone in zones) {
            if (!zone.claim(this)) {
                throw GuidedPathNetworkException.placementOverlap(
                    zone.holder?.name ?: "another transporter", this.name, zone.name
                )
            }
            zone.occupy(this)
            myOccupiedZones.add(zone)
        }
        currentLocation = system.locationOf(this)
    }

    /** Adds a zone at the leading edge. Called only by the movement engine. */
    internal fun addFrontZone(zone: Zone) {
        myOccupiedZones.add(zone)
    }

    /** Removes the trailing zone. Called only by the movement engine. */
    internal fun removeRearZone(): Zone? =
        if (myOccupiedZones.isEmpty()) null else myOccupiedZones.removeAt(0)

    /**
     * True when the transporter covers more zones than it is long, so a zone at the rear is surplus
     * and may be given up. This is the test that applies once a zone has been entered.
     */
    internal val hasSurplusZones: Boolean
        get() = myOccupiedZones.size > lengthInZones

    /**
     * True when the transporter is fully on the guide path, so that giving up the zone behind
     * leaves it still covering its own length together with the zone it is entering.
     *
     * This is the test that applies at the moment travel begins, and it is deliberately not the
     * same test as the one above. A transporter still driving onto the network covers fewer zones
     * than it is long and has nothing to spare; one that is fully on has already claimed the zone
     * ahead, so releasing the zone behind keeps the count right even though the claimed zone is not
     * yet covered.
     */
    internal val isFullyOnPath: Boolean
        get() = myOccupiedZones.size >= lengthInZones

    /**
     * Sends an unallocated transporter to a destination, without an entity aboard.
     *
     * This is how a transporter is repositioned: to a home base, to a staging area, or simply out
     * of the way. It returns as soon as the movement has begun; the transporter travels over
     * simulated time and the caller does not wait.
     *
     * @param destinationName an intersection name or station alias
     * @return true when a movement was started, false when the transporter was already there
     * @throws IllegalStateException when the transporter is allocated to an entity
     */
    fun sendTo(destinationName: String): Boolean {
        check(numBusy == 0) {
            "Transporter (${this.name}) is allocated and cannot be sent somewhere on its own."
        }
        return system.startMove(this, destinationName, TransporterState.RETURNING_HOME)
    }

    override fun toString(): String =
        "GuidedTransporter($name, state=$transporterState, at=${frontZone?.name ?: "unplaced"}, " +
                "length=$lengthInZones zones)"
}
