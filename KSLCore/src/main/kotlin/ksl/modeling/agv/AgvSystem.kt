package ksl.modeling.agv

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agv.policies.AssignmentPolicyIfc
import ksl.modeling.agv.policies.CallForProposals
import ksl.modeling.agv.policies.DispatchContext
import ksl.modeling.agv.policies.Disposition
import ksl.modeling.agv.policies.NearestVehiclePolicy
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.rules.FIFOZoneContentionRule
import ksl.modeling.guidedpath.rules.ZoneContentionRuleIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 * A fleet of self-directing vehicles on a guide path, and the dispatcher that tasks them.
 *
 * The same physical world as the passive subsystem -- the same network, zones, routing, blocking
 * and deadlock detection -- with the decision-making moved out of the entity's process and into
 * objects that have processes of their own. An entity asks for transport and suspends; it never
 * chooses a vehicle, never waits for a particular one, and cannot tell which one came.
 *
 * `AgentModel`, and therefore `ProcessModel`, because the vehicles and the dispatcher are agents
 * with processes and mailboxes, and only an `AgentModel` can host them. A modeller's own
 * `ProcessModel` holds this as a child element, and its entities suspend in this system's queues.
 */
open class AgvSystem @JvmOverloads constructor(
    parent: ModelElement,
    val network: GuidedPathNetwork,
    zoneContentionRule: ZoneContentionRuleIfc = FIFOZoneContentionRule(),
    assignmentPolicy: AssignmentPolicyIfc = NearestVehiclePolicy(),
    name: String? = null
) : AgentModel(parent, name) {

    /** The space layer's runtime. It owns zone occupancy; this subsystem owns nothing physical. */
    internal val spaceSystem: GuidedPathTransportSystem =
        GuidedPathTransportSystem(this, network, zoneContentionRule, name = "${this.name}:Space")

    val dispatcher: Dispatcher = Dispatcher(this, assignmentPolicy, name = "${this.name}:Dispatcher")

    private val myVehicles = mutableListOf<AgvVehicle>()

    /** In declaration order, which is the order ties are broken in. */
    val vehicles: List<AgvVehicle>
        get() = myVehicles

    internal fun addVehicle(vehicle: AgvVehicle) {
        require(model.isNotRunning) { "A vehicle cannot be added while the model is running." }
        myVehicles.add(vehicle)
    }

    // ---- the four hold queues ------------------------------------------------------------------
    //
    // Suspension plumbing, not the model's waiting line -- that is the dispatcher's task queue.
    // All four are internal so only this subsystem can suspend anything in them, and all four
    // report nothing, which is what `Conveyor` does for the same reason: a hold queue is how a
    // suspended entity is found again, and letting it double as the statistic conflates a mechanism
    // with a measurement.

    internal val awaitingPickupHoldQ = HoldQueue(this, "${this.name}:AwaitingPickupHoldQ")
    internal val inTransitHoldQ = HoldQueue(this, "${this.name}:InTransitHoldQ")
    internal val availabilityQ = HoldQueue(this, "${this.name}:AvailabilityQ")
    internal val dispatcherIdleQ = HoldQueue(this, "${this.name}:DispatcherIdleQ")

    init {
        statisticalReportingForHoldQueues(false)
    }

    /**
     * Switches reporting for the hold queues. Off by default.
     *
     * The rows this adds are diagnostic. They are not a second opinion on the numbers the task
     * queue and the two responses report, and reading them as though they were will mislead: the
     * in-transit queue in particular looks like a waiting line and is not one, since nothing is
     * contended while riding.
     *
     * This also covers the **space layer's** movement hold queue, which the passive subsystem
     * reports by default and which this one must not. Under the passive paradigm that queue holds
     * loads being carried; here it holds *vehicle agents*, so its number in queue is the number of
     * vehicles under way. Left on, it would put a row on the report that looks like a line of loads
     * waiting and is in fact a count of moving carts -- the most misleading row this subsystem
     * could produce, and the one a reader is least likely to question. It is switched here rather
     * than in the space layer because this is a property of that one instance, which this system
     * owns; a passive model's own movement queue keeps reporting exactly as before.
     *
     * @param option true means the hold queues appear on the summary report
     */
    fun statisticalReportingForHoldQueues(option: Boolean) {
        val queues = listOf(
            awaitingPickupHoldQ, inTransitHoldQ, availabilityQ, dispatcherIdleQ,
            spaceSystem.movementHoldQueue
        )
        for (q in queues) {
            q.waitTimeStatOption = option
            q.defaultReportingOption = option
        }
    }

    // ---- statistics ----------------------------------------------------------------------------
    // The number of tasks waiting is deliberately absent: it is the dispatcher's task queue's
    // number in queue, and declaring it here would be a second source of truth for one quantity.

    private val myNumVehiclesIdle = TWResponse(
        this, "${this.name}:NumVehiclesIdle", allowedDomain = ksl.utilities.Interval(0.0, Double.MAX_VALUE)
    )
    val numVehiclesIdle: TWResponseCIfc get() = myNumVehiclesIdle

    private val myNumVehiclesOnTask = TWResponse(
        this, "${this.name}:NumVehiclesOnTask", allowedDomain = ksl.utilities.Interval(0.0, Double.MAX_VALUE)
    )
    val numVehiclesOnTask: TWResponseCIfc get() = myNumVehiclesOnTask

    /**
     * How long a load spent aboard, observed once at delivery.
     *
     * A `Response`, so warm-up handles it. Not derivable from the task queue, which measures the
     * disjoint interval that ends where this one begins.
     */
    private val myTransportTime = Response(this, "${this.name}:TransportTime")
    val transportTime: ResponseCIfc get() = myTransportTime

    internal fun recordTransportTime(value: Double) {
        myTransportTime.value = value
    }

    /**
     * Hands an assignment to a vehicle's agent, wherever that agent currently is.
     *
     * Two cases, and the second is what keeps this paradigm's answers equal to the passive one's.
     *
     * A vehicle dormant in the availability queue is simply resumed. A vehicle part-way through a
     * *disposition* move -- going home, repositioning -- is suspended in the space layer's movement
     * queue instead, and is **redirected in flight**: the body is sent to the new pickup and the
     * agent is resumed when it arrives there rather than where it was heading. A vehicle on its way
     * to a parking spur is doing work nobody needs while a load waits, and the passive subsystem
     * has always been able to turn one round, because its cart is unallocated while returning and
     * the next entity simply seizes it. Withdrawing the vehicle for the duration of its disposition
     * looked tidier and cost roughly ten per cent of the mean time in system -- which Gate A caught.
     *
     * A vehicle part-way through a *revoked* task reaches this the same way and for the same reason:
     * `revoke` re-declares it before abandoning its tour, so it is available again while still
     * somewhere on the guide path, and turning it round is exactly what re-tasking means. A vehicle
     * holding a live assignment is never in this position -- it is withdrawn for the whole of a tour.
     */
    internal fun deliverAssignment(agent: VehicleAgent, assignment: Assignment) {
        agent.assignment = assignment
        if (availabilityQ.contains(agent)) {
            availabilityQ.removeAndResume(agent)
            return
        }
        // Under way on a disposition. Turn it round.
        val pickup = assignment.task.pickupLocation
        val moving = agent.vehicle.beginTravelTo(pickup, TransporterState.MOVING_EMPTY, agent)
        if (!moving) {
            // Already standing where it is now needed. Its journey is over, so nothing will arrive
            // to resume it; without this the agent would wait in the movement queue for an arrival
            // that has already happened.
            spaceSystem.movementHoldQueue.removeAndResume(agent)
        }
    }

    private fun refreshFleetCounts() {
        val onTask = myVehicles.count { it.currentAssignment != null }
        myNumVehiclesOnTask.value = onTask.toDouble()
        myNumVehiclesIdle.value = (myVehicles.size - onTask).toDouble()
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /**
     * Creates a fresh agent for every vehicle and for the dispatcher, and activates them.
     *
     * Fresh, not reused: a `KSLProcess` is a coroutine that runs once, so an agent cannot be
     * restarted. And because these are created while the model is running, they are deliberately
     * *not* in `AgentModel.agents` -- which is why the fleet is enumerated from [vehicles] and why
     * the handle is assigned unconditionally rather than reused. A retained agent would carry the
     * previous replication's mailbox, which nothing resets for a runtime agent.
     */
    override fun initialize() {
        // The dispatcher first, so its process reaches its first hold before vehicles declare.
        // The dispatcher's pending-wake flag makes this an optimization rather than a requirement.
        val d = DispatcherAgent()
        dispatcher.agent = d
        activate(d.dispatch)
        for (v in myVehicles) {
            val a = VehicleAgent(v)
            v.agent = a
            activate(a.control)
        }
        refreshFleetCounts()
    }

    /**
     * Reports whatever was still in flight when the horizon fell, and wakes nothing.
     *
     * This runs for every element before `afterReplication` runs for any, so what it sees is the
     * live state. Resuming anything here would schedule a resume on a calendar that is about to be
     * discarded, and run model logic after the run has ended.
     */
    override fun replicationEnded() {
        super.replicationEnded()
        myUnfinishedTasks = dispatcher.board.tasks.size
        myLoadsAwaitingPickup = awaitingPickupHoldQ.size
        myLoadsInTransit = inTransitHoldQ.size
    }

    private var myUnfinishedTasks: Int = 0
    private var myLoadsAwaitingPickup: Int = 0
    private var myLoadsInTransit: Int = 0

    /** How many tasks were still outstanding when the last replication ended. */
    val unfinishedTasksAtHorizon: Int get() = myUnfinishedTasks

    /** How many loads were still waiting to be collected when the last replication ended. */
    val loadsAwaitingPickupAtHorizon: Int get() = myLoadsAwaitingPickup

    /** How many loads were still aboard a vehicle when the last replication ended. */
    val loadsInTransitAtHorizon: Int get() = myLoadsInTransit

    /**
     * Drops this replication's agent references.
     *
     * **Calls `super`**, and that is the whole of the teardown that matters: `ProcessModel`'s
     * implementation is what terminates every suspended participant, including a vehicle agent
     * dormant inside an unbounded loop. Overriding without calling it would leave those coroutines
     * suspended across the replication boundary, holding allocations, in queues that were then
     * cleared out from under them.
     */
    override fun afterReplication() {
        super.afterReplication()
        for (v in myVehicles) v.agent = null
    }

    // ---- the active participants ---------------------------------------------------------------
    // Inner classes because `AgentModel.Agent` is an inner class, so an agent can only be declared
    // inside its own agent model.

    /**
     * A vehicle's control loop: the object that makes this subsystem what it is.
     *
     * Under the passive paradigm there is nothing this could be a rewrite of. A passive transporter
     * has no loop, because it has no behaviour -- it is moved by whatever seized it. Here the
     * vehicle runs, decides when to declare itself available, drives its own body, and returns into
     * a loop that belongs to it.
     */
    internal inner class VehicleAgent(val vehicle: AgvVehicle) : Agent("${vehicle.name}:Agent") {

        internal var assignment: Assignment? = null

        /** How many calls for proposals this vehicle answered, and how many it declined. Kept on the
         *  agent rather than the vehicle because they are per-replication facts about a negotiation,
         *  and because a test that could not see them would have to infer declining from silence. */
        internal var bidsSubmitted: Int = 0
            private set
        internal var callsDeclined: Int = 0
            private set

        init {
            // Answering a call for proposals is done by a mailbox arrival handler rather than by the
            // control loop, and the reason is structural. A vehicle spends almost all of its time
            // suspended -- dormant, travelling, loading -- so a loop that had to be *at* a receive
            // point to hear a call would only ever bid when it happened to be idle, which is exactly
            // the vehicle a dispatcher least needs to ask about. An arrival handler answers wherever
            // the vehicle is.
            //
            // It also has to be non-suspending, and that is not a limitation to work around: a bid
            // is delivered synchronously inside the initiator's broadcast, which is what lets an
            // auction with a zero deadline collect every bid rather than none. `BidPolicyIfc.bid`
            // is a plain function, so the type system enforces this rather than a comment.
            mailbox.onArrival { message -> respond(message) }
        }

        /**
         * Gives up the current assignment, wherever the vehicle has got to with it.
         *
         * Called only from `Dispatcher.revoke`, which has already checked that the load is not
         * aboard. The vehicle keeps its body allocation and its place in the movement queue: it is
         * still a vehicle that is somewhere and may be moving, and the loop it will return into is
         * the same one. What it loses is the reason it was going there.
         *
         * The tour is dropped rather than advanced, because a revoked tour's remaining stops belong
         * to a task this vehicle no longer holds. Nothing is redirected here: the vehicle's own loop
         * discovers the change when its current leg ends, and the dispatcher's next pass -- which
         * `revoke` has already arranged by re-declaring availability -- either turns it round in
         * flight through `deliverAssignment` or leaves it to finish where it was going and then ask
         * for work. Redirecting from here as well would race with that.
         */
        internal fun abandonAssignment() {
            assignment = null
            tour = null
        }

        private fun respond(message: AgentMessage) {
            when (message) {
                is AgentMessage.Request<*> -> {
                    val cfp = message.payload as? CallForProposals ?: return
                    // Handled: take it out of the mailbox so calls do not accumulate over a run.
                    mailbox.consume(message)
                    val initiator = message.from as? Agent ?: return
                    val bid = vehicle.bidPolicy.bid(vehicle, cfp, network)
                    if (bid == null) {
                        // Declining is ordinary operation, not a failure. Saying nothing is how a
                        // vehicle declines; there is deliberately no "I decline" message, because a
                        // dispatcher that received one would have to distinguish it from a bid.
                        callsDeclined++
                        return
                    }
                    bidsSubmitted++
                    initiator.mailbox.deliver(
                        AgentMessage.Propose(this, bid, message.conversationId!!)
                    )
                }
                // The outcome of a negotiation reaches this vehicle as an assignment through the
                // ordinary dispatching path, so these carry no information it needs. They are
                // consumed rather than ignored: an unread message is a slow leak within a
                // replication, and a mailbox that fills is a bug that only shows up in long runs.
                is AgentMessage.Accept -> mailbox.consume(message)
                is AgentMessage.Reject -> mailbox.consume(message)
                else -> Unit
            }
        }

        internal var tour: Tour? = null
            private set

        /** True once a disposition has been considered since the last task, so that a vehicle with
         *  nothing to do settles instead of reconsidering forever at the same instant. */
        private var disposed: Boolean = false

        // Every suspension in this loop is written at the call site. There are no private
        // suspending helpers, so a reader can see every point at which simulated time passes and
        // the world can change underneath the vehicle; the non-suspending bookkeeping is on
        // AgvVehicle and Dispatcher as ordinary methods.
        //
        // That restraint takes effort, because the language pushes the other way. KSLProcessBuilder
        // is @RestrictsSuspension, so an extension on the builder is the *only* way to factor a
        // suspending helper out of a process body -- which makes reaching for one the path of least
        // resistance precisely where it does the most damage to readability.
        val control: KSLProcess = process("${vehicle.name}:control") {
            while (true) {                                  // terminated by afterReplication
                if (assignment == null) {
                    dispatcher.declareAvailable(vehicle)
                    // SUSPENDS. The dispatcher resumes us either with work or, having none for us,
                    // without -- which is the only way to reach the disposition branch below.
                    hold(availabilityQ, suspensionName = "${vehicle.name}:awaitingWork")
                }
                val a = assignment
                if (a == null) {
                    // Work beats disposition, structurally: we are only here because the dispatcher
                    // has already had its pass and declined.
                    if (disposed) {
                        // Nothing to do and nowhere to go. Dormant until an assignment arrives.
                        hold(availabilityQ, suspensionName = "${vehicle.name}:dormant")
                    } else {
                        disposed = true
                        // Deliberately NOT withdrawn. A vehicle moving for its own reasons stays
                        // assignable and is turned round in flight if work arrives; see
                        // deliverAssignment. Withdrawing here costs real time in system.
                        when (val d = vehicle.dispositionPolicy.disposition(vehicle)) {
                            is Disposition.ParkInPlace -> Unit
                            is Disposition.ReturnToHomeBase -> {
                                val home = vehicle.homeBase
                                if (home != null && vehicle.beginTravelTo(
                                        home, TransporterState.RETURNING_HOME, this@VehicleAgent)
                                ) {
                                    hold(spaceSystem.movementHoldQueue,     // SUSPENDS
                                        suspensionName = "${vehicle.name}:returningHome")
                                }
                            }
                            is Disposition.MoveTo -> {
                                if (vehicle.beginTravelTo(
                                        d.locationName, TransporterState.RETURNING_HOME, this@VehicleAgent)
                                ) {
                                    hold(spaceSystem.movementHoldQueue,     // SUSPENDS
                                        suspensionName = "${vehicle.name}:repositioning")
                                }
                            }
                        }
                    }
                    continue
                }

                disposed = false
                dispatcher.withdraw(vehicle)   // committed now; not assignable until the tour ends
                vehicle.taskStarted()
                refreshFleetCounts()
                val allocation = seize(vehicle.body, 1, queue = vehicle.bodyQ)
                val t = tourFor(a).also { tour = it }
                val blockedAtStart = vehicle.body.cumulativeBlockedTime
                while (!t.isComplete) {
                    val stop = t.nextStop!!
                    // beginTravelTo commands the body and returns whether a journey is under way.
                    // `this@VehicleAgent` is the waiter: the AGENT sits in the space layer's
                    // movement queue, never the load.
                    val moving = vehicle.beginTravelTo(stop.location, movingStateFor(stop), this@VehicleAgent)
                    // Taken while the journey is still on the books. The route is cleared on
                    // arrival, so asking for it after the hold returns nothing and the leg appears
                    // to have covered no ground at all -- a silent zero rather than an error.
                    val route = if (moving) vehicle.body.currentRoute else null
                    if (moving) {
                        hold(spaceSystem.movementHoldQueue,                 // SUSPENDS
                            suspensionName = "${vehicle.name}:travellingTo:${stop.location}")
                    }
                    // The assignment can be revoked while we travel, and if it was, this stop
                    // belongs to a task we no longer hold. `t` is a local, so without this check the
                    // loop would go on to collect a load that has been given to someone else -- and
                    // the model would keep running, with two vehicles believing they had it.
                    if (assignment !== a) break
                    when (val act = stop.action) {
                        is StopAction.PickUp -> {
                            act.task.blockedAtPickup = vehicle.body.cumulativeBlockedTime - blockedAtStart
                            if (act.task.loadingDelay != ConstantRV.ZERO) {
                                delay(act.task.loadingDelay,                // SUSPENDS
                                    suspensionName = "${vehicle.name}:loading")
                            }
                            act.task.carriedBy = vehicle
                            // Dequeuing the TASK is what ends its recorded wait, and doing it here
                            // rather than at assignment is what makes the queue's time in queue the
                            // load's wait for transport.
                            dispatcher.tookPossession(a)
                            awaitingPickupHoldQ.removeAndResume(act.task.load)
                        }
                        is StopAction.SetDown -> {
                            act.task.loadedRouteLength = route?.totalLength ?: 0.0
                            if (act.task.unLoadingDelay != ConstantRV.ZERO) {
                                delay(act.task.unLoadingDelay,              // SUSPENDS
                                    suspensionName = "${vehicle.name}:unloading")
                            }
                            act.task.blockedWhileLoaded =
                                vehicle.body.cumulativeBlockedTime - blockedAtStart - act.task.blockedAtPickup
                            act.task.load.currentLocation = network.requireLocation(act.task.destination)
                            act.task.transitionTo(TaskState.COMPLETED)
                            inTransitHoldQ.removeAndResume(act.task.load)   // the verb returns
                        }
                        StopAction.Reposition -> Unit
                    }
                    t.advance()
                }
                release(allocation)
                if (assignment === a) {
                    // Finished it. A tour abandoned part-way through is not a completion, and
                    // counting it as one would let a fleet report more deliveries than there were
                    // loads.
                    dispatcher.completed(a)
                    assignment = null
                    vehicle.taskCompleted()
                }
                tour = null
                vehicle.taskEnded()
                refreshFleetCounts()
            }
        }

        /** The route metadata the setdown stop reads is captured before the route is cleared; this
         *  turns one task into the stops that discharge it. A two-stop tour today, and the loop
         *  above does not know that. */
        private fun tourFor(a: Assignment): Tour = when (val t = a.task) {
            is Dispatcher.TransportTask -> Tour(
                listOf(
                    TourStop(t.origin, StopAction.PickUp(t)),
                    TourStop(t.destination, StopAction.SetDown(t))
                )
            )
            is Dispatcher.ServiceTask -> Tour(listOf(TourStop(t.destination, StopAction.Reposition)))
            else -> throw IllegalStateException("Unknown task type ${t::class.simpleName}")
        }

        private fun movingStateFor(stop: TourStop): TransporterState = when (stop.action) {
            is StopAction.SetDown -> TransporterState.MOVING_LOADED
            else -> TransporterState.MOVING_EMPTY
        }
    }

    /** The dispatcher's process. Dormant until something happens that could change a decision. */
    internal inner class DispatcherAgent : Agent("${dispatcher.name}:Agent") {

        val dispatch: KSLProcess = process("${dispatcher.name}:dispatch") {
            while (true) {                                  // terminated by afterReplication
                if (!dispatcher.consumeWake()) {
                    // SUSPENDS. Woken by a posting, an availability declaration, or a policy timer.
                    hold(dispatcherIdleQ, suspensionName = "${dispatcher.name}:idle")
                }
                val context = DispatchContext(
                    dispatcher.board, dispatcher.availableVehicles, network, time, dispatcher
                )
                // The policy may consume simulated time -- that is what makes batching and auctions
                // expressible -- so this call is a suspension point even though Phase 1's policy
                // returns immediately. `with` supplies this process builder as the policy's
                // receiver, which KSLProcessBuilder's @RestrictsSuspension requires and which is
                // also what lets a policy delay, hold or run an auction.
                val proposals = with(dispatcher.assignmentPolicy) { assign(context) }
                dispatcher.applyProposals(proposals)
            }
        }
    }

    override fun toString(): String =
        "AgvSystem($name, ${myVehicles.size} vehicles, network=${network.name})"
}
