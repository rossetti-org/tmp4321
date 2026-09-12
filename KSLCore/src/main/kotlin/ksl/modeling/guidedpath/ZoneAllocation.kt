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
 * A request for guide-path space: what was asked for, before it has been given.
 *
 * The middle term of a trio that mirrors the resource layer exactly, which is the analogy this
 * whole construct is built on. A [Zone] is the persistent thing with its own occupancy, as a
 * `Resource` is; a `ZoneRequest` is what somebody asked for and is waiting on, as an
 * `Entity.Request` is; and a [ZoneAllocation] is the transient record of the grant, as an
 * `Allocation` is.
 *
 * A request exists because a grant is not instant. Between asking and holding, the zone **drains**:
 * whatever vehicle is crossing it finishes crossing, whatever is present in it leaves, and only
 * then can the zone be given. Nothing is evicted. So there is a state here that the resource layer
 * has no equivalent of -- *asked for, promised, not yet held* -- and this is the object that has it.
 *
 * Requests are made by asking a [ZoneOccupier], never by construction: the space has to stay in
 * charge of its own exclusivity, which is the invariant the whole subsystem rests on.
 *
 * @param occupier who asked
 * @param zone what was asked for
 * @param requestedAt when it was asked for
 */
class ZoneRequest internal constructor(
    val occupier: ZoneOccupier,
    val zone: Zone,
    val requestedAt: Double
) {

    /** The grant, once the zone has drained and been taken, or null while it is still draining. */
    var allocation: ZoneAllocation? = null
        internal set

    /** True once the zone has been taken. */
    val isGranted: Boolean
        get() = allocation != null

    /** True while the zone is still draining, or once the request was given up unsatisfied. */
    var isAbandoned: Boolean = false
        internal set

    /** True while the request is neither granted nor given up: asked for and still draining. */
    val isWaiting: Boolean
        get() = !isGranted && !isAbandoned

    override fun toString(): String = buildString {
        append("ZoneRequest(${occupier.name} -> ${zone.name}, asked at $requestedAt")
        append(
            when {
                isGranted -> ", granted at ${allocation!!.engagedAt}"
                isAbandoned -> ", abandoned"
                else -> ", draining"
            }
        )
        append(")")
    }
}

/**
 * The record of guide-path space actually held: which holder, which zone, from when until when.
 *
 * Transient, as an `Allocation` is, and **created by the space rather than by the holder**. That is
 * not a stylistic preference: exclusivity can only be guaranteed if nothing outside the space can
 * mint a claim on it, which is why the constructor is internal and why a holder asks rather than
 * takes.
 *
 * What it is for is the statistics. A zone cannot usefully keep them -- a network has thousands of
 * zones and almost none will ever be held by anything but a vehicle -- so the numbers belong to the
 * few holders, and this is the object that records the interval each hold covers.
 *
 * @param occupier who holds the zone
 * @param zone what is held
 * @param engagedAt when the hold began
 */
class ZoneAllocation internal constructor(
    val occupier: ZoneOccupier,
    val zone: Zone,
    val engagedAt: Double
) {

    /** When the hold ended, or NaN while it is still held. */
    var releasedAt: Double = Double.NaN
        internal set

    /** True once the zone has been given back. */
    val isReleased: Boolean
        get() = !releasedAt.isNaN()

    /**
     * How long the zone was held, or has been held so far.
     *
     * @param now the current simulated time, for a hold still in progress
     */
    fun timeHeld(now: Double): Double =
        if (isReleased) releasedAt - engagedAt else now - engagedAt

    override fun toString(): String =
        "ZoneAllocation(${occupier.name} holds ${zone.name} from $engagedAt" +
                (if (isReleased) " until $releasedAt" else ", still held") + ")"
}
