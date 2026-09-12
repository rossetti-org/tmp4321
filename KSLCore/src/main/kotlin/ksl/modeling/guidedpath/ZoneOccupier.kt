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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * Told when an occupier's hold on guide-path space begins.
 *
 * Attachable rather than only overridable, because the alternative is that finding out when a
 * closure took effect requires declaring a class. A grant may be the instant it was asked for or
 * much later, so a model that has anything to do once it has the space -- a cleaning time to
 * schedule, a picker to start picking -- needs to be told, and needing a subclass for that is the
 * wrong default. The transporter's arrival listener is the same shape for the same reason.
 */
fun interface ZoneEngagementListenerIfc {

    /**
     * @param occupier the occupier whose hold has just begun
     * @param allocation the hold, which names the zone and when it started
     */
    fun engaged(occupier: ZoneOccupier, allocation: ZoneAllocation)
}

/**
 * Something that takes guide-path space without being a vehicle.
 *
 * A spill. An aisle closed for a safety walk. A picker at a rack face. A lift car out of service. A
 * dropped pallet, a cleaning window, staging overflow at shift change. Each of these denies a zone
 * to traffic in exactly the way a parked vehicle does, and none of them is a vehicle — which was
 * the whole finding behind this construct: `Zone.holder` being typed to a transporter was the only
 * thing standing between the subsystem and that entire family of problems.
 *
 * **Without it, obstruction time is fitted into the wrong parameter.** A model with no spills, no
 * picking interference and no closures must still match observed throughput, so that time goes into
 * inflated task times or a depressed velocity. The model then fits the aggregate and is wrong about
 * the mechanism — and it will give bad advice about any change that alters the obstruction rate,
 * which is precisely the change a study is commissioned to evaluate.
 *
 * ## What an occupier is, mechanically
 *
 * It **never waits for space it cannot have**, and that is its defining property rather than an
 * incidental one. It asks for a zone; the zone drains; it takes it. While it waits it holds nothing,
 * so it has no outgoing edge in the wait-for graph and cannot lie on a circular wait — which is why
 * [awaitedZone] is always null and why deadlock detection treats it as a terminal node. Whatever is
 * stuck behind an occupier is *obstructed*, not deadlocked, and those want different remedies.
 *
 * It is also why an occupier is not a `GuidedTransporter` with the movement left out. A transporter
 * has a route and gives up zones one at a time as it moves; an occupier has an allocation and gives
 * up its zone as a whole. That asymmetry is real and is stated here rather than smoothed over.
 *
 * ## Using one
 *
 * The commonest case is a closure of known duration, and it has its own verb because the arithmetic
 * is a trap otherwise -- the clock must start when the hold *begins*, not when it was asked for, or
 * a two-minute drain silently eats two minutes out of a twenty-minute closure:
 *
 * ```
 * val spill = ZoneOccupier(space, "Spill")
 * spill.holdZoneFor(network.zone("Aisle3.Zone2")!!, cleanupTime.value)
 * ```
 *
 * When the duration is not known in advance -- it depends on what is found, or on a crew arriving --
 * ask for the zone and give it back when done, and be told when the hold began:
 *
 * ```
 * spill.attachEngagementListener { occupier, allocation ->
 *     // the space is ours from `allocation.engagedAt`; decide what happens next
 * }
 * spill.requestZone(network.zone("Aisle3.Zone2")!!)   // the zone begins draining at once
 * // …later…
 * spill.releaseZone()
 * ```
 *
 * `requestZone` returns immediately whether or not the zone was free: the zone is closed to new
 * traffic from that instant, and the hold begins when whatever was already there has left.
 * [ZoneEngagementListenerIfc] and the [onEngaged] override are the two ways to be told which.
 *
 * **One zone at a time**, and that is a deliberate limit of this step rather than an oversight: a
 * second request while one is outstanding is refused. Sets of zones, and the question of whether
 * they must be taken atomically, are a separate piece of work with a rule of their own.
 *
 * ## Statistics
 *
 * Here rather than on the zones, and deliberately: there are thousands of zones and a handful of
 * occupiers, so a response per zone would cost every model dearly to report nothing. An occupier
 * collects the time it spent holding space, the time it spent waiting for space to drain, and how
 * many times each happened — which is what makes the decomposition of vehicle blocked time a
 * testable claim rather than an assumption.
 *
 * @param space the guide path whose zones this occupier takes
 * @param name the occupier's name, unique in the model
 */
open class ZoneOccupier(
    val space: GuidedPathSpace,
    name: String? = null
) : ModelElement(space, name), ZoneHolderIfc {

    /**
     * Always null: an occupier never waits for space it does not have.
     *
     * The one thing [ZoneHolderIfc] asks of a holder beyond its name, because it is the outgoing
     * edge of the wait-for graph. An occupier waiting for a zone to drain is not waiting *on* the
     * zone in this sense — it holds nothing while it waits, so no cycle can run through it, and the
     * deadlock walk stops here.
     */
    final override val awaitedZone: Zone?
        get() = null

    private var myRequest: ZoneRequest? = null
    private var myAllocation: ZoneAllocation? = null

    /** What this occupier has asked for and not yet been given, or null. */
    val request: ZoneRequest?
        get() = myRequest

    /** The space this occupier currently holds, or null when it holds none. */
    val allocation: ZoneAllocation?
        get() = myAllocation

    /** True while a zone is draining for this occupier. */
    val isWaitingForSpace: Boolean
        get() = myRequest?.isWaiting == true

    /** True while this occupier holds a zone. */
    val isHoldingSpace: Boolean
        get() = myAllocation != null

    // ---- statistics: on the holder, because there are few holders and many zones ---------------

    private val myFracTimeHolding = TWResponse(this, name = "${this.name}:FracTimeHoldingSpace")

    /** The fraction of time this occupier held guide-path space. */
    val fracTimeHoldingSpace: TWResponseCIfc
        get() = myFracTimeHolding

    private val myFracTimeWaiting = TWResponse(this, name = "${this.name}:FracTimeWaitingForSpace")

    /**
     * The fraction of time this occupier spent waiting for space to drain.
     *
     * The cost of draining rather than evicting, measured. A closure that is wanted *now* and
     * arrives late because an aisle was busy is a real effect on whatever the occupier represents,
     * and it is invisible unless it is counted.
     */
    val fracTimeWaitingForSpace: TWResponseCIfc
        get() = myFracTimeWaiting

    private val myTimeToEngage = Response(this, name = "${this.name}:TimeToEngage")

    /** How long each request waited for its zone to drain. Zero when the zone was already free. */
    val timeToEngage: ResponseCIfc
        get() = myTimeToEngage

    private val myNumEngagements = Counter(this, name = "${this.name}:NumEngagements")

    /** How many times this occupier took a zone. */
    val numEngagements: CounterCIfc
        get() = myNumEngagements

    // ---- the two things a modeller does --------------------------------------------------------

    /**
     * Asks for a zone, which closes to new traffic at once and is held as soon as it has drained.
     *
     * Returns without waiting, whether or not the zone was free. What the zone does from this
     * instant is refuse every new claim and every new admission; what was already in it finishes
     * and leaves in its own time. [onEngaged] fires when the hold actually begins, which is this
     * same instant when the zone was already empty.
     *
     * @param zone the zone to take, which must be on this occupier's guide path
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun requestZone(zone: Zone): ZoneRequest = ask(zone, Double.NaN)

    /**
     * Takes a zone for a stated duration, and gives it back without being asked again.
     *
     * The commonest case, and the one with the trap in it. The duration is measured **from the
     * instant the hold begins**, not from the request -- so a closure of twenty minutes on an aisle
     * that takes two minutes to drain occupies the zone for twenty minutes and is outstanding for
     * twenty-two. Measuring from the request instead would silently shorten every closure by
     * however long the drain happened to take, which depends on traffic and so varies between
     * replications: a defect that shows up as a closure duration that is not the one the modeller
     * asked for, and nowhere as an error.
     *
     * @param zone the zone to take, which must be on this occupier's guide path
     * @param duration how long to hold it once the hold begins, strictly positive
     * @return the request, whose [ZoneRequest.isGranted] says whether the hold began at once
     */
    fun holdZoneFor(zone: Zone, duration: Double): ZoneRequest {
        require(duration > 0.0) {
            "Occupier ($name) was asked to hold zone (${zone.name}) for $duration, which is not a " +
                    "duration. To take a zone until told otherwise, use requestZone."
        }
        return ask(zone, duration)
    }

    /**
     * The one way a request is made, so that the refusal below runs before anything is recorded.
     *
     * Both verbs come through here for a reason that is easy to get wrong: an immediate grant
     * happens *inside* the call, so how long the hold is to last has to be known before the request
     * is made rather than after it returns. Setting it in the two verbs separately left a refused
     * call with a duration still recorded, which the next plain [requestZone] would then have
     * inherited and released itself out of.
     */
    private fun ask(zone: Zone, duration: Double): ZoneRequest {
        check(myRequest == null && myAllocation == null) {
            "Occupier ($name) already ${if (isHoldingSpace) "holds" else "has asked for"} space. " +
                    "One zone at a time: give it back before asking for another."
        }
        myHoldDuration = duration
        return space.requestZoneFor(this, zone)
    }

    /**
     * Gives the zone back, and wakes whoever was waiting for it.
     *
     * Harmless on an occupier that holds nothing. On one that has asked for a zone still draining,
     * this gives up the request instead, which is what a closure cancelled before it took effect
     * does.
     */
    fun releaseZone() {
        myHoldDuration = Double.NaN
        space.releaseZoneFrom(this)
    }

    /**
     * Called when the hold begins, which may be the same instant the request was made or much
     * later. Does nothing by default.
     *
     * The hook for a subclass. [attachEngagementListener] is the same notification for a model that
     * would rather not declare one, and both are told: this first, then the listeners.
     */
    protected open fun onEngaged(allocation: ZoneAllocation) {}

    private val myEngagementListeners = mutableListOf<ZoneEngagementListenerIfc>()

    /** Starts telling a listener when this occupier's holds begin. */
    fun attachEngagementListener(listener: ZoneEngagementListenerIfc) {
        myEngagementListeners.add(listener)
    }

    /** Stops telling a listener about holds. */
    fun detachEngagementListener(listener: ZoneEngagementListenerIfc) {
        myEngagementListeners.remove(listener)
    }

    // ---- internals, driven by the space --------------------------------------------------------

    internal fun recordRequest(request: ZoneRequest) {
        myRequest = request
        myFracTimeWaiting.value = 1.0
    }

    internal fun recordEngagement(allocation: ZoneAllocation) {
        val asked = myRequest?.requestedAt ?: allocation.engagedAt
        myRequest = null
        myAllocation = allocation
        myFracTimeWaiting.value = 0.0
        myFracTimeHolding.value = 1.0
        myTimeToEngage.value = allocation.engagedAt - asked
        myNumEngagements.increment()
        // The statistics are settled before anybody is told, because a listener may give the zone
        // straight back -- which is legitimate, and would otherwise be recorded against a hold that
        // had not yet been counted as having started.
        val duration = myHoldDuration
        if (duration.isFinite()) {
            myHoldDuration = Double.NaN
            schedule(myReleaseAction, duration, allocation, name = "$name:releaseZone")
        }
        onEngaged(allocation)
        // Copied, so that a listener may detach itself, or attach another, while being told.
        for (listener in myEngagementListeners.toList()) {
            listener.engaged(this, allocation)
        }
    }

    /** How long the current hold is to last, or NaN when it lasts until told otherwise. */
    private var myHoldDuration: Double = Double.NaN

    private val myReleaseAction = EventActionIfc<ZoneAllocation> { event ->
        // Tied to the allocation it was scheduled for, not merely to this occupier. A hold may
        // already have been given back by hand or by a listener, and a *second* hold may since have
        // begun -- in which case a release guarded only on "still holding something" would end the
        // wrong one, early, and silently.
        if (myAllocation === event.message) {
            space.releaseZoneFrom(this)
        }
    }

    internal fun recordRelease() {
        myRequest = null
        myAllocation = null
        myFracTimeWaiting.value = 0.0
        myFracTimeHolding.value = 0.0
    }

    /**
     * Clears the hold at the start of every replication.
     *
     * The third instance of a defect family this subsystem has already met three times — a
     * manifest, a position, and a zone population, each left behind by a reset. The zones clear
     * themselves in [GuidedPathSpace.initialize]; what has to be cleared here is this occupier's
     * belief about them, which is a separate copy and would otherwise describe the previous
     * replication for the whole of the next one.
     */
    override fun initialize() {
        myRequest = null
        myAllocation = null
        myHoldDuration = Double.NaN
    }

    override fun toString(): String = buildString {
        append("ZoneOccupier($name, ")
        append(
            myAllocation?.let { "holding ${it.zone.name} since ${it.engagedAt}" }
                ?: myRequest?.let { "waiting for ${it.zone.name} since ${it.requestedAt}" }
                ?: "holding nothing"
        )
        append(")")
    }
}
