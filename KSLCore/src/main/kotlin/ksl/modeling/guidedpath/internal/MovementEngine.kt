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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.IntersectionZone
import ksl.modeling.guidedpath.LinkZone
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.VelocitySampling
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.rules.ZoneReleaseTiming

/**
 * Moves transporters, one zone at a time.
 *
 * **Every zone state change in the subsystem happens here.** Nothing else claims, occupies, or
 * releases a zone. That is the property the whole design rests on: exclusivity can be established
 * by reading one file rather than by auditing every caller, and it is why the zone mutators are not
 * visible outside the package.
 *
 * Each transporter propels itself. It claims the zone ahead, schedules its own arrival, and on
 * arriving either claims the next or stops. There is no fleet-wide movement event and no
 * propagation of a stoppage backwards down a queue of vehicles: a transporter that cannot proceed
 * simply schedules nothing, which costs the executive nothing at all while it waits. This is the
 * fundamental difference from a conveyor, where one engine advances everything at once and a
 * blockage travels backwards along the line.
 */
internal class MovementEngine(
    private val mySystem: GuidedPathTransportSystem
) {

    private val myNetwork: GuidedPathNetwork
        get() = mySystem.network

    /**
     * Plans a route and sets a transporter travelling.
     *
     * @return true when a movement began, false when the transporter was already at the destination
     */
    fun startMove(
        transporter: GuidedTransporter,
        destination: GuidedPathNetwork.Intersection,
        movingState: TransporterState
    ): Boolean {
        val front = transporter.frontZone
            ?: throw IllegalStateException(
                "Transporter (${transporter.name}) has not been placed and cannot be moved."
            )
        if (transporter.claimedZone != null) {
            // Already travelling into a zone. A vehicle between two places cannot stop and turn
            // round, so the redirection waits for the boundary. The reservation it holds is
            // therefore always a reservation it will use, which is what stops a superseded
            // movement leaving a zone held by a transporter that never arrives.
            transporter.pendingDestination = destination
            transporter.pendingMovingState = movingState
            return true
        }
        if (front === destination.zone) return false
        transporter.currentVelocity = transporter.sampleVelocity()
        transporter.currentRoute =
            myNetwork.routeFrom(front, destination, transporter.travellingForward)
        transporter.transporterState = movingState
        mySystem.refreshFleetCounts()
        advance(transporter)
        return true
    }

    /**
     * Takes one step: claim the zone ahead and schedule the arrival, or stop.
     *
     * On every path out of this function the transporter is either scheduled to arrive somewhere or
     * has stopped for a stated reason. It is never left with nothing scheduled and no explanation,
     * which would be a transporter lost in the middle of the network.
     */
    fun advance(transporter: GuidedTransporter) {
        val route = transporter.currentRoute
            ?: throw IllegalStateException(
                "Transporter (${transporter.name}) was advanced without a route."
            )
        val next = route.nextZone
        if (next == null) {
            arrive(transporter)
            return
        }
        if (!next.claim(transporter)) {
            // Contention arrives with the next phase, which adds the waiting list and the wake-up
            // that goes with it. Until then a refused claim can only mean a model that a single
            // transporter cannot satisfy, and failing loudly beats stalling silently.
            check(false) {
                "Transporter (${transporter.name}) could not claim zone (${next.name}), which is " +
                        "held by (${next.holder?.name}). Waiting for a held zone is not yet " +
                        "implemented."
            }
            return
        }
        transporter.claimedZone = next
        val velocity = transporter.velocityForNextZone()
        if (transporter.velocitySampling == VelocitySampling.PER_ZONE) {
            transporter.currentVelocity = velocity
        }
        val traversalTime = next.traversalTime(velocity)
        when (val timing = transporter.zoneControlRule.releaseTiming(transporter, next)) {
            is ZoneReleaseTiming.AtStart -> releaseRearAtStart(transporter)

            is ZoneReleaseTiming.AtEnd -> Unit

            is ZoneReleaseTiming.AfterDistance -> {
                if (next.length <= 0.0) {
                    // A junction treated as a point has no distance to travel into, so the release
                    // can only be immediate. Refusing instead would make this rule unusable on any
                    // network at all, since every route crosses junctions.
                    releaseRearAtStart(transporter)
                } else {
                    // Zone sizes vary from link to link, so a rule with one fixed distance will
                    // meet zones shorter than it. Releasing at the far end of such a zone is the
                    // only reading that stays monotone in the distance asked for; refusing would
                    // make the rule unusable wherever a network mixes zone sizes, which is common.
                    val effective = minOf(timing.distance, next.length)
                    val delay = effective / (velocity * next.velocityFactor)
                    mySystem.scheduleRearRelease(transporter, delay)
                }
            }
        }
        mySystem.scheduleTraversal(transporter, next, traversalTime)
    }

    /** Completes a traversal: the transporter now covers the zone it was travelling into. */
    fun endZoneTraversal(transporter: GuidedTransporter, zone: Zone) {
        zone.occupy(transporter)
        transporter.claimedZone = null
        transporter.addFrontZone(zone)
        transporter.travellingForward = directionAfterEntering(transporter, zone)
        val route = transporter.currentRoute
            ?: throw IllegalStateException(
                "Transporter (${transporter.name}) completed a traversal without a route."
            )
        route.advance()
        if (transporter.zoneControlRule.releaseTiming(transporter, zone) is ZoneReleaseTiming.AtEnd) {
            releaseRearIfSurplus(transporter)
        }
        val redirect = transporter.pendingDestination
        if (redirect != null) {
            transporter.pendingDestination = null
            if (zone === redirect.zone) {
                // The redirection asked for somewhere the transporter has just reached.
                transporter.currentRoute = null
                arrive(transporter)
                return
            }
            transporter.currentVelocity = transporter.sampleVelocity()
            transporter.currentRoute =
                myNetwork.routeFrom(zone, redirect, transporter.travellingForward)
            transporter.transporterState = transporter.pendingMovingState
        }
        mySystem.refreshFleetCounts()
        advance(transporter)
    }

    /**
     * Gives up zones behind that the transporter has outgrown: the test that applies once a zone
     * has been entered, and the one that settles a transporter to its own length when it stops.
     */
    fun releaseRearIfSurplus(transporter: GuidedTransporter) {
        while (transporter.hasSurplusZones) {
            val rear = transporter.removeRearZone() ?: return
            rear.release(transporter)
        }
    }

    /**
     * Gives up the zone behind at the moment travel begins, freeing it for a follower straight
     * away, but only once the transporter is fully on the guide path. One still driving on covers
     * fewer zones than it is long and has nothing to spare, which is the case that a release
     * applied unconditionally would get wrong.
     */
    fun releaseRearAtStart(transporter: GuidedTransporter) {
        if (!transporter.isFullyOnPath) return
        val rear = transporter.removeRearZone() ?: return
        rear.release(transporter)
    }

    /**
     * The delayed form: give up the zone behind after travelling a stated distance into the zone
     * ahead. Guarded on the claim still being outstanding, so that a release scheduled for the very
     * end of a traversal cannot arrive after the transporter has settled and take a zone its body
     * is standing in.
     */
    fun releaseRearAfterDistance(transporter: GuidedTransporter) {
        if (transporter.claimedZone == null) return
        releaseRearAtStart(transporter)
    }

    private fun arrive(transporter: GuidedTransporter) {
        // Settle to exactly the transporter's own length. Nothing else releases here: a control
        // rule that gives up the zone behind at the start of a traversal has no traversal left to
        // start, so without this the transporter would stand on more of the guide path than it
        // covers and deny that space to everyone else for the rest of the run.
        releaseRearIfSurplus(transporter)
        transporter.currentRoute = null
        transporter.transporterState = TransporterState.IDLE
        transporter.currentLocation = mySystem.locationOf(transporter)
        mySystem.refreshFleetCounts()
        transporter.notifyArrival()
        ProcessModel.logger.trace {
            "GUIDED PATH (${mySystem.name}): transporter (${transporter.name}) arrived at " +
                    "(${transporter.frontZone?.name})"
        }
    }

    /**
     * Which way the transporter now faces, taken from the zone it came from rather than from where
     * the entered zone sits on its link. A link of a single zone has its first and last zone in the
     * same place, so position cannot tell the two directions apart.
     */
    private fun directionAfterEntering(transporter: GuidedTransporter, entered: Zone): Boolean {
        if (entered !is LinkZone) return transporter.travellingForward
        val previous = transporter.occupiedZones.let {
            if (it.size >= 2) it[it.size - 2] else null
        } ?: return transporter.travellingForward
        if (previous is LinkZone && previous.link === entered.link) {
            return entered.positionOnLink > previous.positionOnLink
        }
        if (previous is IntersectionZone) {
            return entered.link.beginIntersection === previous.intersection
        }
        return transporter.travellingForward
    }
}
