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
package ksl.modeling.guidedpath.internal

import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.IntersectionZone
import ksl.modeling.guidedpath.LinkZone
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.ZoneState
import ksl.simulation.ConditionalAction

/**
 * Raised when the guide path has reached a state that should be impossible.
 *
 * This is always a defect in the subsystem, never a modelling error, so the message says what was
 * violated and shows the state that violated it rather than advising the modeler to change
 * anything.
 */
class ZoneInvariantViolation(message: String) : IllegalStateException(message)

/**
 * Checks, whenever the simulation clock advances, that no transporter shares space with another,
 * that each covers an unbroken run of zones no longer than itself, and that what the zones believe
 * agrees with what the transporters believe.
 *
 * These are the properties the whole subsystem exists to guarantee, and they are exactly the ones
 * that a plausible-looking run can violate without any visible symptom: a model whose transporters
 * quietly pass through one another produces output that looks entirely reasonable and is wrong.
 * Asserting them continuously is what turns "we believe transporters never overlap" into something
 * the test suite demonstrates on every model it runs.
 *
 * The check runs at a **time advance** rather than after every event, which is the right moment
 * rather than merely a convenient one. Several events can execute at a single instant -- one
 * transporter releasing a zone and another claiming it, say -- and between two of them the state is
 * legitimately half-changed. What has to hold is that the state is sound whenever the clock is
 * about to move on, which is what this tests.
 *
 * The work is proportional to the size of the guide path and the fleet, so it is off unless a model
 * asks for it.
 */
internal class ZoneInvariantChecker(
    private val mySystem: GuidedPathSpace
) : ConditionalAction() {

    /**
     * Performs the checks. Always answers false, so that the action below never runs: the executive
     * evaluates a condition once per sweep, which is exactly the hook wanted, and a condition that
     * answered true would be asked to act and then be swept again.
     */
    override fun testCondition(): Boolean {
        check()
        return false
    }

    override fun action() {
        // Never reached. See testCondition.
    }

    /** Runs every check, throwing on the first violation found. */
    fun check() {
        checkZonesAgreeWithTransporters()
        checkTransportersCoverContiguousRuns()
        checkOccupancyIsConserved()
        checkWaitingIsConsistent()
    }

    /**
     * Runs at the end of a replication, whether or not continuous checking was on.
     *
     * Everything [check] asserts is asserted here too, which is the point: a model that did not ask
     * for the continuous walk still gets one look at its own state, at the moment there is most to
     * find and least left to pay. It costs one pass over the guide path per replication.
     *
     * What it adds is the bookkeeping that has no instantaneous symptom -- a clock left running
     * against a transporter that is not blocked accumulates silently and is only visible as a number
     * that is wrong later, somewhere else.
     */
    fun checkClosing() {
        check()
        checkBlockedClocks()
    }

    /**
     * The blocked-time clock runs exactly while a transporter is blocked, and what it has
     * accumulated is a real, non-negative duration.
     *
     * This is the invariant behind a defect that cost a run: a replication ended with a transporter
     * blocked, the reset cleared the start instant while the state was still `BLOCKED`, and the
     * transition out then accumulated `time - NaN`. The NaN travelled into the first transport
     * result of the *next* replication and failed it thousands of simulated minutes from its cause.
     * Asserted here because the end of a replication is where the two can come apart.
     */
    private fun checkBlockedClocks() {
        for (t in mySystem.transporters) {
            val blocked = t.transporterState == TransporterState.BLOCKED
            if (t.isBlockedClockRunning != blocked) {
                violate(
                    if (blocked) {
                        "transporter (${t.name}) is blocked but its blocked-time clock is not " +
                                "running, so this block will not be counted"
                    } else {
                        "transporter (${t.name}) is ${t.transporterState} but its blocked-time " +
                                "clock is still running, so time it spends not blocked is being " +
                                "counted as blocked"
                    }
                )
            }
            val accumulated = t.cumulativeBlockedTime
            if (!accumulated.isFinite() || accumulated < 0.0) {
                violate(
                    "transporter (${t.name}) has accumulated a blocked time of $accumulated, " +
                            "which is not a duration"
                )
            }
        }
    }

    /**
     * A transporter is waiting for exactly one thing, and only while it is stopped.
     *
     * This is the invariant whose failure has no other symptom. A waiting transporter schedules
     * nothing, so it is started again only by whoever releases what it is waiting for -- which
     * means a transporter left in a waiting list it should have left, or missing from the one it
     * should be in, does not recover slowly. It stalls forever, and the run simply stops advancing
     * with nothing to say why.
     */
    private fun checkWaitingIsConsistent() {
        for (t in mySystem.transporters) {
            val zonesWaitedOn = mySystem.network.zones.filter { t in it.waiters }
            val linksWaitedOn = mySystem.network.links.filter { t in it.waiters }
            val places = zonesWaitedOn.size + linksWaitedOn.size
            if (t.transporterState == TransporterState.BLOCKED) {
                if (places != 1) {
                    violate(
                        "transporter (${t.name}) is blocked but is waiting in $places lists: " +
                                "zones ${zonesWaitedOn.joinToString { it.name }}, links " +
                                linksWaitedOn.joinToString { it.name }
                    )
                }
                if (t.awaitedZone == null) {
                    violate("transporter (${t.name}) is blocked but names nothing it is waiting for")
                }
            } else if (places != 0) {
                violate(
                    "transporter (${t.name}) is ${t.transporterState} but is still waiting in " +
                            "$places list(s), so it would be woken for something it no longer wants"
                )
            }
        }
        for (zone in mySystem.network.zones) {
            if (zone.holder != null && zone.holder in zone.waiters) {
                violate("zone (${zone.name}) lists its own holder among those waiting for it")
            }
            if (zone.waiters.size != zone.waiters.distinct().size) {
                violate("zone (${zone.name}) lists a transporter more than once among its waiters")
            }
        }
    }

    /**
     * A zone is held by at most one transporter, and a zone that believes it is covered is covered
     * by a transporter that agrees.
     */
    private fun checkZonesAgreeWithTransporters() {
        for (zone in mySystem.network.zones) {
            val holder = zone.holder
            if (zone.state == ZoneState.FREE) {
                if (holder != null) {
                    violate("zone (${zone.name}) is free but is held by (${holder.name})")
                }
                continue
            }
            if (holder == null) {
                violate("zone (${zone.name}) is ${zone.state} but names no holder")
                continue
            }
            if (zone.state == ZoneState.CLAIMED && holder.claimedZone !== zone) {
                violate(
                    "zone (${zone.name}) is claimed by (${holder.name}), but that transporter is " +
                            "claiming (${holder.claimedZone?.name ?: "nothing"})"
                )
            }
            if (zone.state == ZoneState.OCCUPIED && zone !in holder.occupiedZones) {
                violate(
                    "zone (${zone.name}) is occupied by (${holder.name}), but that transporter " +
                            "does not count it among the zones it covers: " +
                            holder.occupiedZones.joinToString { it.name }
                )
            }
        }
    }

    /**
     * Each transporter covers a run of adjacent zones, in order from its rear to its front, no
     * longer than the transporter itself.
     */
    private fun checkTransportersCoverContiguousRuns() {
        for (t in mySystem.transporters) {
            val zones = t.occupiedZones
            // Held, not covered: a transporter one zone long that has given up the zone behind at
            // the moment travel began is briefly between zones, covering none and holding only the
            // one it is entering. It still denies exactly one zone to everyone else, which is what
            // has to be true.
            if (t.heldZones.isEmpty()) {
                violate("transporter (${t.name}) holds no zones at all")
                continue
            }
            if (zones.size > t.lengthInZones) {
                violate(
                    "transporter (${t.name}) covers ${zones.size} zones but is only " +
                            "${t.lengthInZones} long: ${zones.joinToString { it.name }}"
                )
            }
            for (zone in zones) {
                if (zone.holder !== t) {
                    violate(
                        "transporter (${t.name}) counts zone (${zone.name}) among the zones it " +
                                "covers, but that zone is held by (${zone.holder?.name ?: "no one"})"
                    )
                }
                if (zone.state != ZoneState.OCCUPIED) {
                    violate(
                        "transporter (${t.name}) covers zone (${zone.name}), which is " +
                                "${zone.state} rather than occupied"
                    )
                }
            }
            val held = t.heldZones
            for (i in 0 until held.size - 1) {
                if (!areAdjacent(held[i], held[i + 1])) {
                    violate(
                        "transporter (${t.name}) holds a broken run: (${held[i].name}) is not " +
                                "adjacent to (${held[i + 1].name})"
                    )
                }
            }
            val claimed = t.claimedZone
            if (claimed != null) {
                if (claimed.holder !== t) {
                    violate(
                        "transporter (${t.name}) believes it has claimed (${claimed.name}), which " +
                                "is held by (${claimed.holder?.name ?: "no one"})"
                    )
                }
                if (claimed.state != ZoneState.CLAIMED) {
                    violate(
                        "transporter (${t.name}) has claimed (${claimed.name}), which reports " +
                                "${claimed.state} rather than being claimed"
                    )
                }
            }
        }
    }

    /** The zones believed occupied and the zones transporters believe they cover are the same set. */
    private fun checkOccupancyIsConserved() {
        val byZones = mySystem.network.zones.count { it.state == ZoneState.OCCUPIED }
        val byTransporters = mySystem.transporters.sumOf { it.occupiedZones.size }
        if (byZones != byTransporters) {
            violate(
                "$byZones zones report being occupied, but transporters between them claim to " +
                        "cover $byTransporters"
            )
        }
    }

    /**
     * Whether one zone leads directly to another, in either direction. A transporter's own body
     * spans zones without regard to which way it is facing, so adjacency here is symmetric.
     */
    private fun areAdjacent(first: Zone, second: Zone): Boolean {
        if (first is LinkZone && second is LinkZone && first.link === second.link) {
            return kotlin.math.abs(first.positionOnLink - second.positionOnLink) == 1
        }
        if (first is LinkZone && second is IntersectionZone) {
            return (first.isLastOnLink && first.link.endIntersection === second.intersection) ||
                    (first.isFirstOnLink && first.link.beginIntersection === second.intersection)
        }
        if (first is IntersectionZone && second is LinkZone) {
            return areAdjacent(second, first)
        }
        return false
    }

    private fun violate(what: String): Nothing = throw ZoneInvariantViolation(
        "Guide path invariant violated in system (${mySystem.name}) at time " +
                "${mySystem.time}: $what."
    )
}
