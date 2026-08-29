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

    /** True when the junction is treated as a point, so that crossing it takes no time. */
    val isDimensionless: Boolean
        get() = length == 0.0
}
