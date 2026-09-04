package ksl.modeling.agv

import ksl.modeling.agv.exceptions.AgvDispatchException
import ksl.modeling.agv.exceptions.AgvProtocolException
import ksl.modeling.agv.policies.AssignmentPolicyIfc
import ksl.modeling.agv.policies.NearestVehiclePolicy
import ksl.modeling.agv.policies.TaskSelectionRuleIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/**
 * Decides which vehicle goes where, and owns the line of work waiting to be done.
 *
 * The existence of this object is the design. Under the passive paradigm the equivalent decision is
 * made inside a pool's allocation rule, at the moment an entity happens to ask, over whichever
 * vehicles happen to be free -- which makes batching, look-ahead and auctions inexpressible, not
 * because they are hard but because there is nowhere to put them. Here the decision has a home, a
 * process of its own, and the ability to consume simulated time while deciding.
 *
 * It owns the [TaskQ] rather than delegating it to the system or accepting one from the modeller,
 * because that queue is not a holding pen the subsystem happens to need. It is this object's
 * pending-work list: the collection its policy ranks, a batching policy batches, and an auction
 * announces from. A queue supplied from outside would force the dispatcher to reach into a foreign
 * object to do its one job.
 */
open class Dispatcher @JvmOverloads constructor(
    val system: AgvSystem,
    assignmentPolicy: AssignmentPolicyIfc = NearestVehiclePolicy(),
    discipline: Queue.Discipline = Queue.Discipline.FIFO,
    name: String? = null
) : ModelElement(system, name ?: "Dispatcher") {

    /** Substitutable while the model is not running. */
    var assignmentPolicy: AssignmentPolicyIfc = assignmentPolicy
        set(value) {
            require(model.isNotRunning) { "The assignment policy cannot be changed while the model is running." }
            field = value
        }

    private val myTaskQ: TaskQ = TaskQ(this, "${this.name}:TaskQ", discipline)

    /**
     * The waiting line, and the only queue this subsystem reports.
     *
     * Its time in queue is the load's wait for transport: posting to pickup. Its number in queue is
     * the outstanding work.
     */
    val taskQ: QueueCIfc<Task>
        get() = myTaskQ

    /** Orders what a policy sees. Null means the queue discipline alone decides. */
    var taskSelectionRule: TaskSelectionRuleIfc?
        get() = myTaskQ.taskSelectionRule
        set(value) {
            myTaskQ.taskSelectionRule = value
        }

    /** The read-only view handed to policies. Holds no tasks of its own. */
    val board: TaskBoard = TaskBoard(myTaskQ)

    // ---- the available set ---------------------------------------------------------------------
    // A vehicle is available because it said so, never because the dispatcher worked it out (A6).
    // `newlyDeclared` is drained on every pass: a vehicle that has declared and not been given work
    // is resumed once, with no assignment, which is its cue to consider a disposition. It then goes
    // dormant and stays in `available` until a task arrives for it.

    private val myAvailable = mutableListOf<AgvVehicle>()
    private val myNewlyDeclared = mutableListOf<AgvVehicle>()

    val availableVehicles: List<AgvVehicle>
        get() = myAvailable.toList()

    internal fun isAvailable(vehicle: AgvVehicle): Boolean = myAvailable.contains(vehicle)

    /**
     * The live assignment for a task, or null when nobody is committed to it.
     *
     * A vehicle holds its assignment, so this is a search over the fleet rather than a lookup. That
     * is deliberate: an index would be a second place the pairing is recorded, and two records of
     * one fact is exactly the arrangement that lets a revocation update one and not the other. The
     * fleet is small enough that the search costs nothing worth the risk.
     */
    fun assignmentFor(task: Task): Assignment? =
        system.vehicles.firstNotNullOfOrNull { v ->
            v.currentAssignment?.takeIf { it.task === task }
        }

    internal var agent: AgvSystem.DispatcherAgent? = null

    /** Set when the dispatcher is woken while it is not dormant, so the wake is not lost. */
    private var wakePending: Boolean = false


    // ---- tasks -----------------------------------------------------------------------------
    // Task types are inner classes because QObject is an inner class of ModelElement, exactly as
    // Conveyor.ConveyorRequest is an inner class of Conveyor. A useful consequence: a task cannot
    // exist without a dispatcher to record its wait, so there is no window in which a caller holds
    // an unposted task.

    /**
     * Something a vehicle may be asked to do.
     *
     * A `QObject`, so the task itself waits in the [TaskQ] and carries the waiting statistics. The
     * load does not wait in a reported queue; its task does. That is also what gives an assignment
     * policy a first-class object to rank and a bidding policy something to bid on.
     *
     * Not `sealed`: Kotlin refuses `sealed inner`, and `inner` is forced by `QObject`. The
     * hierarchy is closed at two members by construction, and the exhaustiveness that matters lives
     * on [ServiceKind] instead.
     */
    abstract inner class Task internal constructor(aName: String? = null) : QObject(aName) {

        /** Where the vehicle must end up. */
        abstract val destination: String

        /** Where the vehicle must go first. The destination itself for a task with nothing to
         *  collect, which is what makes a service task a one-stop tour. */
        abstract val pickupLocation: String

        /** The entity suspended on this task, if any. Null for a task a vehicle raised for itself. */
        abstract val waitingEntity: ProcessModel.Entity?

        var state: TaskState = TaskState.POSTED
            internal set

        /** When a vehicle committed. NaN until then. */
        var assignedAt: Double = Double.NaN
            internal set

        val isTerminal: Boolean
            get() = state == TaskState.COMPLETED || state == TaskState.CANCELLED

        /** The dispatcher that created it, so a task always knows where its wait is recorded. */
        val dispatcher: Dispatcher
            get() = this@Dispatcher

        internal fun transitionTo(next: TaskState) {
            val legal = when (state) {
                TaskState.POSTED -> next == TaskState.ASSIGNED || next == TaskState.CANCELLED
                TaskState.ASSIGNED ->
                    next == TaskState.IN_PROGRESS || next == TaskState.POSTED || next == TaskState.CANCELLED
                TaskState.IN_PROGRESS -> next == TaskState.COMPLETED
                TaskState.COMPLETED, TaskState.CANCELLED -> false
            }
            if (!legal) {
                throw AgvProtocolException(
                    "Task (${this.name}) cannot go from $state to $next."
                )
            }
            state = next
            if (next == TaskState.CANCELLED) {
                // Counted where the task is cancelled rather than where someone asked for it,
                // because two different callers can ask: the dispatcher's own `cancel`, and
                // `TaskQ.removeAndTerminate`, which is the *only* way to withdraw a transport
                // request. Counting at the call site meant every load withdrawn by termination left
                // the accounts one short -- posted, and then neither completed, nor cancelled, nor
                // waiting anywhere. A count of how many tasks ended cancelled must not depend on
                // which route they took to get there.
                myNumTasksCancelled.increment()
            }
        }

        /** `QObject` supplies `id`, `name`, `priority` (a var, so a ranked discipline needs no
         *  extra field), `timeEnteredQueue`, `timeExitedQueue` and `timeInQueue`. A `postedAt`
         *  field is therefore deliberately absent: it *is* `timeEnteredQueue`, and duplicating it
         *  would create a second source of truth for the wait. */
    }

    /** Carries a load for a requesting entity. */
    inner class TransportTask internal constructor(
        val load: ProcessModel.Entity,
        val origin: String,
        override val destination: String,
        val loadingDelay: GetValueIfc,
        val unLoadingDelay: GetValueIfc
    ) : Task("${load.name}:Transport") {

        override val pickupLocation: String
            get() = origin

        override val waitingEntity: ProcessModel.Entity
            get() = load

        /** Counts revocations, so the load's result can report them. */
        var numReassignments: Int = 0
            internal set

        /**
         * Set by the carrying vehicle so the load's result, and the guide path's per-carry
         * statistics, can report what the journey cost.
         *
         * The two move times are the **travel legs only**: the leg that ended at this task's pickup
         * and the leg that ended at its set-down, each taken before the loading or unloading delay
         * that follows it. That is what the passive subsystem means by the same two names, and the
         * point of recording them here is that the two paradigms then report the same quantity.
         * `waitForArrival` and `timeAboard` on the result are the wider intervals that include
         * those delays, and neither is derivable from the other.
         */
        internal var carriedBy: AgvVehicle? = null
        internal var blockedAtPickup: Double = 0.0
        internal var loadedRouteLength: Double = 0.0
        internal var blockedWhileLoaded: Double = 0.0
        internal var approachTime: Double = 0.0
        internal var rideTime: Double = 0.0
        internal var loadedZonesTraversed: Int = 0
        internal var failedBeforePickup: Double = 0.0
        internal var failedWhileLoaded: Double = 0.0

        /**
         * How much of the journey the vehicle spent unable to claim the space ahead.
         *
         * Computed here rather than at each of the two places that want it, so that the load's
         * result and the guide path's statistic cannot come to disagree about what blocked means.
         */
        internal val blockedTime: Double
            get() = blockedAtPickup + blockedWhileLoaded

        /**
         * How much of the journey the vehicle spent out of service.
         *
         * The same shape as [blockedTime] and for the same reason. It is a *part of* the approach
         * and ride times rather than something outside them: those are protocol intervals and the
         * load was waiting, or aboard, throughout. This is what lets a study separate a fleet that
         * is slow from one that is unreliable, which the two intervals alone cannot distinguish.
         */
        internal val failedTime: Double
            get() = failedBeforePickup + failedWhileLoaded
    }

    /** Something a vehicle does for itself. Nothing is suspended on it. */
    inner class ServiceTask internal constructor(
        override val destination: String,
        val kind: ServiceKind
    ) : Task("Service:$destination") {

        override val pickupLocation: String
            get() = destination

        override val waitingEntity: ProcessModel.Entity?
            get() = null
    }

    // ---- statistics -----------------------------------------------------------------------
    // Counters, not a per-decision trace: the general form of "record what was decided and why"
    // belongs to the sequential-decision-making subsystem's trajectory sink, and duplicating a
    // weaker version of it here would have to be thrown away later.

    private val myNumTasksPosted = Counter(this, "${this.name}:NumTasksPosted")
    val numTasksPosted: CounterCIfc get() = myNumTasksPosted

    private val myNumTasksCompleted = Counter(this, "${this.name}:NumTasksCompleted")
    val numTasksCompleted: CounterCIfc get() = myNumTasksCompleted

    private val myNumTasksCancelled = Counter(this, "${this.name}:NumTasksCancelled")
    val numTasksCancelled: CounterCIfc get() = myNumTasksCancelled

    private val myNumAssignmentsMade = Counter(this, "${this.name}:NumAssignmentsMade")
    val numAssignmentsMade: CounterCIfc get() = myNumAssignmentsMade

    private val myNumAssignmentsRevoked = Counter(this, "${this.name}:NumAssignmentsRevoked")
    val numAssignmentsRevoked: CounterCIfc get() = myNumAssignmentsRevoked

    private val myNumAuctionsRun = Counter(this, "${this.name}:NumAuctionsRun")
    val numAuctionsRun: CounterCIfc get() = myNumAuctionsRun

    /**
     * Auctions in which every vehicle declined.
     *
     * Counted rather than raised. A fleet that is out of range, out of charge or simply all busy has
     * nothing to offer, and that is ordinary operation of a negotiated system rather than a fault --
     * the task stays on the board and is auctioned again on the next pass. It is worth counting
     * because a rising unfilled rate is the earliest sign that a bidding rule has been set too
     * strictly, and nothing else in the output would say so.
     */
    private val myNumAuctionsUnfilled = Counter(this, "${this.name}:NumAuctionsUnfilled")
    val numAuctionsUnfilled: CounterCIfc get() = myNumAuctionsUnfilled

    internal fun auctionRun() = myNumAuctionsRun.increment()
    internal fun auctionUnfilled() = myNumAuctionsUnfilled.increment()

    /**
     * How long a task waited before a vehicle committed to it.
     *
     * Disjoint from the queue's time in queue, which runs past this to pickup, and from the
     * system's transport time, which begins after it. None of the three is derivable from another,
     * which is why all three are measured rather than two being computed.
     */
    private val myWaitForAssignment = Response(this, "${this.name}:WaitForAssignment")
    val waitForAssignment: ResponseCIfc get() = myWaitForAssignment

    // ---- posting --------------------------------------------------------------------------

    internal fun postTransport(
        load: ProcessModel.Entity,
        origin: String,
        destination: String,
        loadingDelay: GetValueIfc,
        unLoadingDelay: GetValueIfc,
        priority: Int
    ): TransportTask {
        system.network.requireLocation(origin)
        system.network.requireLocation(destination)
        val task = TransportTask(load, origin, destination, loadingDelay, unLoadingDelay)
        task.priority = priority
        myTaskQ.enqueue(task)
        myNumTasksPosted.increment()
        wake()
        return task
    }

    internal fun postService(destination: String, kind: ServiceKind, priority: Int): ServiceTask {
        system.network.requireLocation(destination)
        val task = ServiceTask(destination, kind)
        task.priority = priority
        myTaskQ.enqueue(task)
        myNumTasksPosted.increment()
        wake()
        return task
    }

    /**
     * Abandons a task a vehicle raised for itself.
     *
     * **A [TransportTask] cannot be cancelled**, and the refusal is deliberate rather than an
     * omission. A load that asked for transport is suspended waiting for it, and there is no safe
     * thing to do with that load: leaving it suspended strands it for the rest of the replication;
     * terminating its process kills work that may have had nothing to do with the transport; and
     * resuming it with an outcome only helps if the modeller handles that outcome, which Kotlin
     * cannot oblige them to do -- a discarded return value is not a compile error, and the resulting
     * model carries on as though the load had been delivered.
     *
     * `MovableResource` declines to offer cancellation for the same reason, so a modeller learns one
     * rule rather than two. If a load must give up entirely, [TaskQ.removeAndTerminate] ends its
     * process outright -- blunt, but honest about being blunt, and it leaves nothing suspended.
     *
     * A [ServiceTask] is different in the way that matters: a vehicle raised it for itself, so
     * nothing is waiting on it and cancelling one strands nobody. "You were going to park, but work
     * has arrived" is a real thing to want, and it is safe.
     *
     * A vehicle already committed to the task is released first, so it does not go on to a task that
     * no longer exists.
     *
     * @throws AgvProtocolException when the task is a transport request.
     */
    fun cancel(task: Task) {
        require(task.dispatcher === this) { "Task (${task.name}) does not belong to ${this.name}." }
        if (task is TransportTask) {
            throw AgvProtocolException(
                "Task (${task.name}) is a transport request and cannot be cancelled: entity " +
                        "(${task.load.name}) is suspended waiting for it, and there is no outcome " +
                        "this subsystem can give that entity which a model is obliged to handle. " +
                        "MovableResource declines cancellation for the same reason. To make the " +
                        "load give up entirely, use TaskQ.removeAndTerminate, which ends its " +
                        "process and leaves nothing suspended."
            )
        }
        releaseAnyVehicleFrom(task)
        myTaskQ.remove(task, false)
        task.transitionTo(TaskState.CANCELLED)
    }

    /**
     * Releases any vehicle committed to the task, so the task can be abandoned without the vehicle
     * going on to collect a load that is no longer there.
     *
     * Called by [cancel] and by [TaskQ.removeAndTerminate]. Both abandon a task, and both would
     * otherwise leave a vehicle en route to a pickup whose task has gone -- surfacing much later, and
     * far from the cause, as an illegal state transition thrown from the control loop.
     *
     * @throws AgvAssignmentException when the load is already aboard. There is nowhere to set it
     *   down, so the delivery must finish; abandoning the task at that point would leave a vehicle
     *   carrying something that no longer exists.
     */
    internal fun releaseAnyVehicleFrom(task: Task) {
        assignmentFor(task)?.let { live ->
            live.requireRevocable()
            releaseFrom(live)
        }
    }

    /**
     * Detaches a vehicle from an assignment and makes it assignable again, without deciding what
     * becomes of the task.
     *
     * Shared by [revoke], which returns the task to the board, and [cancel], which does not. Keeping
     * the vehicle-side steps in one place is what stops the two operations drifting apart: they
     * differ in what happens to the *task*, and should not differ in what happens to the vehicle.
     */
    private fun releaseFrom(assignment: Assignment) {
        assignment.state = AssignmentState.REVOKED
        // Counted here rather than in `revoke`, for the same reason cancellations are counted in
        // `Task.transitionTo`: this is the one place an assignment becomes revoked, and two callers
        // reach it -- a policy re-tasking a vehicle, and a task being abandoned under one. Counting
        // only the first meant an assignment taken back because its task was withdrawn was made and
        // then never accounted for anywhere.
        myNumAssignmentsRevoked.increment()
        // Available again in the same breath, so a pass that releases and reassigns can do both
        // without an intervening wake.
        declareAvailable(assignment.vehicle)
        assignment.vehicle.agent?.abandonAssignment()
    }

    /**
     * Takes a task back from a vehicle that has not yet collected its load, and gives the vehicle
     * something else to do.
     *
     * This is the capability the passive paradigm has no place for. There, a transporter belongs to
     * the entity that seized it for the whole journey, so a cart three-quarters of the way to a far
     * pickup cannot be turned round for a nearer one that has just appeared — not because the
     * movement machinery could not do it, but because there is no object whose business it would be
     * to decide. Here there is.
     *
     * The task returns to `POSTED` and is **not re-enqueued**: it never left the queue, so its
     * accumulated wait survives. Re-enqueuing would reset the wait and make a load that has been
     * waiting longest look as though it had just arrived — corrupting both the statistic and any
     * age-based selection rule, in the one case where the load has most cause to complain.
     *
     * The vehicle is not told to stop. It is redirected, and the space layer decides when: a vehicle
     * mid-traversal defers to the next zone boundary, because something between two places cannot
     * turn round; a blocked vehicle gives up its wait first, so it is not left on a waiter list for
     * a journey it is no longer making.
     *
     * @throws AgvAssignmentException when the load is already aboard, naming both participants.
     */
    fun revoke(assignment: Assignment) {
        assignment.requireRevocable()
        require(assignment.task.dispatcher === this) {
            "Assignment of task (${assignment.task.name}) does not belong to ${this.name}."
        }
        assignment.task.transitionTo(TaskState.POSTED)
        assignment.task.assignedAt = Double.NaN
        (assignment.task as? TransportTask)?.let { it.numReassignments++ }
        releaseFrom(assignment)
    }

    // ---- the vehicle protocol ----------------------------------------------------------------

    /**
     * A vehicle declares that it will take work.
     *
     * Non-suspending, and it does not assign: it records availability and wakes the dispatcher,
     * whose next pass decides. The vehicle then goes dormant, and is resumed either with an
     * assignment or, if there is nothing for it, without one -- which is its cue to consider a
     * disposition. That ordering is what makes "work beats disposition" structural: the branch that
     * consults a disposition policy is unreachable until the dispatcher has had its pass and
     * declined.
     */
    internal fun declareAvailable(vehicle: AgvVehicle) {
        if (!myAvailable.contains(vehicle)) myAvailable.add(vehicle)
        if (!myNewlyDeclared.contains(vehicle)) myNewlyDeclared.add(vehicle)
        wake()
    }

    /** A vehicle takes itself out of consideration -- it is about to move for its own reasons. */
    internal fun withdraw(vehicle: AgvVehicle) {
        myAvailable.remove(vehicle)
        myNewlyDeclared.remove(vehicle)
    }

    /**
     * A vehicle has the load. Dequeues the task, ending its recorded wait.
     *
     * This call, and not the assignment, is what defines the queue's time in queue as the wait for
     * transport. Dequeuing at assignment instead would silently redefine the subsystem's headline
     * statistic to mean something narrower.
     */
    internal fun tookPossession(assignment: Assignment) {
        val task = assignment.task
        myTaskQ.remove(task)
        task.transitionTo(TaskState.IN_PROGRESS)
        assignment.state = AssignmentState.IN_PROGRESS
    }

    internal fun completed(assignment: Assignment) {
        assignment.state = AssignmentState.COMPLETED
        if (assignment.task.state != TaskState.COMPLETED) {
            assignment.task.transitionTo(TaskState.COMPLETED)
        }
        myNumTasksCompleted.increment()
    }

    // ---- the loop ------------------------------------------------------------------------------

    /**
     * Wakes the dispatcher's process, or records that it should not go back to sleep.
     *
     * The flag matters at the start of a replication, when vehicles may declare availability before
     * the dispatcher's process has reached its first `hold`. Without it that first wake is lost and
     * a fleet sits idle with work on the board until something else happens to wake it -- which in
     * a lightly-loaded model may be never.
     */
    private fun wake() {
        val a = agent
        if (a != null && system.dispatcherIdleQ.contains(a)) {
            system.dispatcherIdleQ.removeAndResume(a)
        } else {
            wakePending = true
        }
    }

    /**
     * Asks the dispatcher to look at the board again.
     *
     * The dispatcher is woken by the two things that can change a decision from *inside* this
     * subsystem: a task being posted, and a vehicle declaring itself available. Between them those
     * cover every internal change, because a vehicle that moves — finishing a task, finishing a
     * disposition — re-declares when it stops, so anything that alters what a vehicle can reach
     * already causes a fresh pass.
     *
     * What they do not cover is a decision that changes for reasons **outside** the subsystem. A
     * policy or a bidding rule is written by the modeller and may depend on anything in their model
     * — a machine coming back up, a buffer draining, a flag an operator sets — and the dispatcher
     * has no way to know when any of it changes. It must not go looking, either: a dispatcher that
     * woke on a timer to re-ask a question whose answer had not changed would be polling, which in a
     * discrete-event model is both wasteful and a sign that a state change has gone unmodelled.
     *
     * So the model says so, by calling this at the event where the change actually happens — which
     * is an event the model already has.
     *
     * Safe to call at any time, including while the dispatcher is mid-pass — the wake is remembered
     * rather than lost. Calling it when nothing has changed costs one dispatching pass that assigns
     * nothing.
     */
    fun reconsider() {
        wake()
    }

    /** True when the loop should skip its `hold` because a wake arrived while it was awake. */
    internal fun consumeWake(): Boolean {
        val pending = wakePending
        wakePending = false
        return pending
    }

    /**
     * One dispatching pass: consult the policy, act on what it proposes, and then release every
     * vehicle that declared and got nothing, so it can consider a disposition.
     *
     * Split out from the process body so that the process body stays a loop and a `hold`, with
     * every suspension visible in it. This function does not suspend, which is why the policy call
     * itself stays in the loop.
     */
    internal fun applyProposals(proposals: List<AssignmentProposal>) {
        for (p in proposals) {
            if (!myAvailable.contains(p.vehicle)) {
                throw AgvDispatchException(
                    "Policy ($assignmentPolicy) proposed vehicle (${p.vehicle.name}) for task " +
                            "(${p.task.name}), but that vehicle has not declared itself available. " +
                            "A policy may only propose vehicles from the available set it was given (A6)."
                )
            }
            if (p.task.state != TaskState.POSTED) {
                throw AgvDispatchException(
                    "Policy ($assignmentPolicy) proposed task (${p.task.name}) for vehicle " +
                            "(${p.vehicle.name}), but that task is ${p.task.state} and already has a " +
                            "vehicle committed to it (A1)."
                )
            }
            val a = Assignment(p.vehicle, p.task, time, assignmentPolicy.toString(), p.terms)
            // The task STAYS in the queue: it is dequeued at pickup, not here.
            p.task.transitionTo(TaskState.ASSIGNED)
            p.task.assignedAt = time
            myWaitForAssignment.value = time - p.task.timeEnteredQueue
            myNumAssignmentsMade.increment()
            system.emitAssignment(a)
            withdraw(p.vehicle)
            resume(p.vehicle, a)
        }
        // Everyone who declared and got nothing hears so, exactly once.
        val leftover = myNewlyDeclared.toList()
        myNewlyDeclared.clear()
        for (v in leftover) resume(v, null)
    }

    private fun resume(vehicle: AgvVehicle, assignment: Assignment?) {
        val a = vehicle.agent ?: throw AgvProtocolException(
            "Vehicle (${vehicle.name}) has no agent for this replication."
        )
        if (assignment != null) {
            // May be dormant, or may be part-way through a disposition move, in which case it is
            // turned round in flight rather than being allowed to finish going home first.
            system.deliverAssignment(a, assignment)
            return
        }
        // "Nothing for you" is only meaningful to a vehicle that is waiting to hear it. One that is
        // already moving for its own reasons has nothing to be told.
        if (system.availabilityQ.contains(a)) {
            a.assignment = null
            system.availabilityQ.removeAndResume(a)
        }
    }

    /** Between replications the board and the available set are emptied. The queue itself is
     *  cleared by `Queue.afterReplication`; this drops the references that outlive it. */
    override fun afterReplication() {
        super.afterReplication()
        myAvailable.clear()
        myNewlyDeclared.clear()
        wakePending = false
        agent = null
    }

    override fun toString(): String = "Dispatcher($name, policy=$assignmentPolicy, board=$board)"
}
