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
 * A reservation over one or more zones, which the zones consult to decide who may still pass.
 *
 * The zone asks rather than decides, and the reason is the hazard a set closure has and a single
 * zone does not. Closing a *set* can trap a vehicle inside it: the vehicle's route needs a zone the
 * closure has reserved, and the zone the vehicle is standing in is one the closure is waiting to
 * drain. Neither can move. It is not a circular wait the detector can see, either -- an occupier
 * has no [ZoneHolderIfc.awaitedZone], so there is no edge to close a cycle with -- so the run would
 * simply stop advancing with nothing to say why.
 *
 * The fix is the one a real closure uses: **stop letting traffic in, and let the traffic already
 * inside get out.** A closing zone admits the holder it was promised to, which is how the grant is
 * taken up, and it admits a vehicle that is already holding some other zone of the same closure,
 * which is how that vehicle leaves. The drain then terminates for any set, because every vehicle
 * inside can always continue -- the zones beyond the region are not reserved, and once a vehicle is
 * fully out it holds nothing in the set and so cannot get back in.
 *
 * A vehicle whose route *ends* inside the region is the one case that still hangs, and it is a
 * modelling error rather than a mechanism defect: it is the same trap as sending a vehicle to a
 * junction another vehicle is parked on. The end-of-replication report names the zone and its
 * holder.
 */
internal interface ZoneClosureIfc {

    /** Who the reserved space is for. */
    val holder: ZoneHolderIfc

    /** Every zone this closure has reserved. */
    val zones: List<Zone>

    /** True when this claimant may take a zone the closure has reserved. */
    fun admits(claimant: ZoneHolderIfc): Boolean
}

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
 * **A set is taken all at once or not at all.** Taking the zones one by one as they drain is what
 * creates the trap described on [ZoneClosureIfc]; waiting until every zone has drained and then
 * claiming them together keeps the occupier holding nothing while it waits, which keeps it a sink
 * in the wait-for graph and makes a deadlock impossible rather than undetectable. The cost is that
 * a closure over a busy region begins later, and that delay is reported rather than hidden --
 * [ZoneOccupier.fracTimeWaitingForSpace] is exactly it.
 *
 * @param occupier who asked
 * @param zones what was asked for, one or more
 * @param requestedAt when it was asked for
 */
class ZoneRequest internal constructor(
    val occupier: ZoneOccupier,
    override val zones: List<Zone>,
    val requestedAt: Double
) : ZoneClosureIfc {

    override val holder: ZoneHolderIfc
        get() = occupier

    /**
     * The holder it is promised to may take a reserved zone, and so may a vehicle that is already
     * inside the region -- see [ZoneClosureIfc] for why the second is what makes a drain terminate.
     */
    override fun admits(claimant: ZoneHolderIfc): Boolean =
        claimant === occupier || zones.any { it.holder === claimant }

    /** The single zone asked for, when exactly one was. */
    val zone: Zone
        get() = zones.single()

    /** True when every zone asked for has drained and could now be taken together. */
    internal val isDrained: Boolean
        get() = zones.all { it.isDrained }

    /** The grant, once the zones have drained and been taken, or null while any is still draining. */
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
        append("ZoneRequest(${occupier.name} -> ${zones.joinToString { it.name }}, ")
        append("asked at $requestedAt")
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
 * @param occupier who holds the space
 * @param zones what is held, one or more, taken together and given back together
 * @param engagedAt when the hold began
 */
class ZoneAllocation internal constructor(
    val occupier: ZoneOccupier,
    val zones: List<Zone>,
    val engagedAt: Double
) {

    /** The single zone held, when exactly one is. */
    val zone: Zone
        get() = zones.single()

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
        "ZoneAllocation(${occupier.name} holds ${zones.joinToString { it.name }} from $engagedAt" +
                (if (isReleased) " until $releasedAt" else ", still held") + ")"
}
