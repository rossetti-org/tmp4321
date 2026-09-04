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

import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc

/**
 * Whether a zone is free, spoken for, or covered.
 *
 * Three states rather than a pair of booleans, because the invariants this subsystem must hold are
 * statements about states and are far easier to assert when the state is a single value. The
 * distinction between being claimed and being occupied is the one that matters: a claimed zone is
 * already unavailable to everyone else, but is not yet covered by a transporter's body, so it
 * counts against availability and not against occupancy.
 */
enum class ZoneState {

    /** No transporter holds the zone. */
    FREE,

    /**
     * A transporter has reserved the zone and is travelling into it, but does not yet cover it.
     * Reserving before entering is what prevents two transporters from both starting into the same
     * free zone and arriving together.
     */
    CLAIMED,

    /** A transporter's body covers the zone. */
    OCCUPIED
}

/**
 * The atom of contended space on a guide path: the unit that is claimed, occupied, and released.
 *
 * A zone is the single most important concept in the subsystem. Every claim, release, block, and
 * wake-up is expressed in zones; a transporter's position is the contiguous run of zones it
 * occupies; and every congestion statistic is a statistic about zones. Links and intersections are
 * both made of zones, which is what lets a route be a flat sequence rather than an alternating
 * structure the movement engine would have to special-case.
 *
 * Zones are created by the network and are geometrically immutable. Their occupancy is
 * per-replication state, mutated only by the movement engine, which is why every mutator is
 * internal to this package: exclusivity can only be guaranteed if nothing outside can claim,
 * occupy, or release a zone.
 *
 * The type is sealed so that the engine handles every kind of zone exhaustively, and so that adding
 * a third kind later has to be a deliberate, reviewed change rather than a silent fall-through.
 */
sealed class Zone {

    /** Index of this zone within the network's zone list. Unique and stable. */
    abstract val id: Int

    /** Unique within the network, and the name used in messages, traces, and statistics. */
    abstract val name: String

    /**
     * The distance a transporter travels to cross this zone, in the modeler's units. Strictly
     * positive for a link zone. Zero is permitted for an intersection zone, which represents a
     * junction treated as a point: crossing it takes no time, but it is still held exclusively.
     */
    abstract val length: Double

    /** Multiplies transporter velocity while crossing this zone. Strictly positive. */
    abstract val velocityFactor: Double

    /** Whether the zone is free, spoken for, or covered. Reset at the start of every replication. */
    var state: ZoneState = ZoneState.FREE
        internal set

    /** The transporter holding the zone, or null when it is free. */
    var holder: GuidedTransporter? = null
        internal set

    /** True when no transporter holds the zone. */
    val isFree: Boolean
        get() = state == ZoneState.FREE

    /** True when some transporter has claimed or is covering the zone. */
    val isHeld: Boolean
        get() = state != ZoneState.FREE

    /** True when a transporter's body covers the zone, as opposed to merely having reserved it. */
    val isOccupied: Boolean
        get() = state == ZoneState.OCCUPIED

    /**
     * Reserves the zone for a transporter about to travel into it.
     *
     * Reserving before entering is what stops two transporters both starting into the same free
     * zone and arriving together. The claim fails, without side effect, when someone else already
     * holds the zone.
     *
     * @return true when the zone was free and is now claimed
     */
    internal fun claim(transporter: GuidedTransporter): Boolean {
        check(holder !== transporter) {
            "Zone ($name) is already held by transporter (${transporter.name}), which cannot claim " +
                    "it a second time."
        }
        if (state != ZoneState.FREE) return false
        state = ZoneState.CLAIMED
        holder = transporter
        return true
    }

    /** Records that the claiming transporter's body now covers the zone. */
    internal fun occupy(transporter: GuidedTransporter) {
        check(state == ZoneState.CLAIMED && holder === transporter) {
            "Zone ($name) cannot be occupied by transporter (${transporter.name}): it is $state " +
                    "held by ${holder?.name ?: "no one"}. A zone must be claimed before it is entered."
        }
        state = ZoneState.OCCUPIED
    }

    private val myWaiters = mutableListOf<GuidedTransporter>()

    /**
     * The transporters waiting for this zone, in the order they began waiting.
     *
     * Ordered, and deliberately so. Which of several waiting transporters gets a zone changes the
     * whole course of a run, so the choice has to be made by a stated rule over a stated order. A
     * set would leave it to whatever order the collection happened to iterate in, which can differ
     * between platforms and would cost the model its reproducibility.
     */
    val waiters: List<GuidedTransporter>
        get() = myWaiters

    /** How many transporters are waiting for this zone. */
    val numWaiting: Int
        get() = myWaiters.size

    /**
     * Records that a transporter is waiting for this zone.
     *
     * Waiting is not something a zone infers from a failed claim: placing transporters at the start
     * of a replication also claims zones, and a clash there is a specification error rather than a
     * queue to join. The engine says which it is.
     */
    internal fun addWaiter(transporter: GuidedTransporter) {
        check(transporter !in myWaiters) {
            "Transporter (${transporter.name}) is already waiting for zone ($name)."
        }
        check(holder !== transporter) {
            "Transporter (${transporter.name}) holds zone ($name) and cannot wait for it."
        }
        myWaiters.add(transporter)
    }

    /** Stops a transporter waiting for this zone, whether or not it was. */
    internal fun removeWaiter(transporter: GuidedTransporter) {
        myWaiters.remove(transporter)
    }

    /**
     * Gives up a zone the transporter occupies, and hands it to at most one waiter.
     *
     * The waiter is chosen by the supplied rule rather than by anything the zone decides for
     * itself, and it is handed the zone by being *woken*, not by being given the claim: the zone
     * genuinely becomes free in between. That matters because it is the only arrangement in which
     * the state is sound at every moment the clock could be observed -- a direct hand-off would
     * leave a window where the zone belonged to two transporters at once, and the invariants could
     * then only be checked at some moments rather than all of them.
     *
     * @param rule chooses among the waiting transporters
     * @return the transporter to wake, or null when none was waiting
     */
    internal fun release(
        transporter: GuidedTransporter,
        rule: ZoneContentionRuleIfc? = null
    ): GuidedTransporter? {
        check(holder === transporter) {
            "Zone ($name) cannot be released by transporter (${transporter.name}): it is held by " +
                    "${holder?.name ?: "no one"}."
        }
        state = ZoneState.FREE
        holder = null
        if (myWaiters.isEmpty() || rule == null) return null
        val chosen = rule.selectWaiter(this, myWaiters)
            ?: return null
        check(chosen in myWaiters) {
            "Zone contention rule ($rule) chose transporter (${chosen.name}), which is not waiting " +
                    "for zone ($name). A rule must choose from the transporters it is given."
        }
        myWaiters.remove(chosen)
        return chosen
    }

    /**
     * Gives up a zone that was claimed but never entered, which happens when a movement is
     * superseded before the transporter reaches the zone it was heading into. Without this the
     * abandoned claim would hold the zone against everyone for the rest of the replication.
     */
    internal fun abandonClaim(transporter: GuidedTransporter) {
        check(state == ZoneState.CLAIMED && holder === transporter) {
            "Zone ($name) has no claim by transporter (${transporter.name}) to abandon: it is " +
                    "$state held by ${holder?.name ?: "no one"}."
        }
        state = ZoneState.FREE
        holder = null
    }

    /** Returns the zone to its start-of-replication condition. */
    internal fun resetZone() {
        state = ZoneState.FREE
        holder = null
        myWaiters.clear()
    }

    /**
     * The time for a transporter travelling at the given velocity to cross this zone. Exactly zero
     * for a dimensionless intersection, which schedules a zero-delay event rather than an
     * instantaneous state change, so that the executive keeps the ordering explicit.
     *
     * @param velocity the transporter's velocity, strictly positive
     */
    fun traversalTime(velocity: Double): Double {
        require(velocity > 0.0) {
            "Zone ($name): a transporter's velocity must be > 0.0 to cross a zone, but was $velocity."
        }
        return length / (velocity * velocityFactor)
    }

    /**
     * Whether traffic with business elsewhere has to pass through this zone.
     *
     * A fact about the layout and not about who happens to be standing here, so it is answerable at
     * any instant -- including the instant a transporter stops, when nobody has yet had time to
     * queue up behind it.
     *
     * A spur is the exception, and it is the exception the guide path already recognises: a dead end
     * is entered and left the same way, so nothing passes *through* it, which is what makes a spur
     * the standard device for keeping a stopped transporter out of everyone's way. Its zones are
     * refuges, and so is the junction it ends at, because that junction leads nowhere else.
     */
    abstract val isOnAThroughRoute: Boolean

    final override fun toString(): String = name
}

/**
 * One of the equal zones that divide a link.
 *
 * Its length and velocity factor come from the owning link, so that the geometry of a link is
 * stated once and cannot drift between its zones.
 *
 * @param id index within the network's zone list
 * @param link the owning link
 * @param positionOnLink the one-based position from the link's beginning intersection
 */
class LinkZone internal constructor(
    override val id: Int,
    val link: Link,
    val positionOnLink: Int
) : Zone() {

    override val name: String = "${link.name}.Zone$positionOnLink"

    /** True unless the owning link is a spur, which nothing passes through. */
    override val isOnAThroughRoute: Boolean
        get() = link.type != LinkType.SPUR

    override val length: Double
        get() = link.zoneLength

    override val velocityFactor: Double
        get() = link.velocityFactor

    /** True when this is the zone a forward traversal of the link enters first. */
    val isFirstOnLink: Boolean
        get() = positionOnLink == 1

    /** True when this is the last zone before a forward traversal reaches the ending intersection. */
    val isLastOnLink: Boolean
        get() = positionOnLink == link.numZones
}

/**
 * The space of a junction, held by one transporter at a time.
 *
 * An intersection is space as well as a place. Treating it as a zone is what makes spur semantics
 * expressible at all: a transporter sent to the end of a dead end has to keep hold of the mouth in
 * order to get back out, and "the mouth" is this zone.
 *
 * Its length defaults to zero, the usual modelling assumption that a junction is a point. A zero
 * length does not make the zone free to share; it remains exclusive.
 *
 * @param id index within the network's zone list
 * @param intersection the junction whose space this is
 * @param length the distance to cross the junction, zero or more
 * @param velocityFactor multiplies transporter velocity while crossing, strictly positive
 */
class IntersectionZone internal constructor(
    override val id: Int,
    val intersection: GuidedPathNetwork.Intersection,
    override val length: Double,
    override val velocityFactor: Double
) : Zone() {

    override val name: String = intersection.name

    /**
     * True unless this is a dead end.
     *
     * A junction with one incident link leads nowhere but back the way a transporter came, so the
     * only traffic that reaches it is traffic that wanted it. Two or more and it is a junction in
     * the ordinary sense: somebody's route runs through it.
     */
    override val isOnAThroughRoute: Boolean
        get() = intersection.incidentLinks.size > 1

    /** True when the junction is treated as a point, so that crossing it takes no time. */
    val isDimensionless: Boolean
        get() = length == 0.0
}
