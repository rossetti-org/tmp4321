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

        /** Set by the carrying vehicle so the load's result can report what the journey cost. */
        internal var carriedBy: AgvVehicle? = null
        internal var blockedAtPickup: Double = 0.0
        internal var loadedRouteLength: Double = 0.0
        internal var blockedWhileLoaded: Double = 0.0
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
     * Abandons a task, dequeuing it with no waiting statistics collected: a wait that was given up
     * rather than served is not an observation of service.
     */
    fun cancel(task: Task) {
        require(task.dispatcher === this) { "Task (${task.name}) does not belong to ${this.name}." }
        myTaskQ.remove(task, false)
        task.transitionTo(TaskState.CANCELLED)
        myNumTasksCancelled.increment()
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
     * What they do not cover is a decision that changes for reasons outside the subsystem: a shift
     * beginning, a policy becoming permissive, an operator releasing a hold, a resource this fleet
     * depends on coming back. The dispatcher cannot observe any of that and must not go looking —
     * a dispatcher that woke on a timer to re-ask a question whose answer had not changed would be
     * polling, which in a discrete-event model is both wasteful and a sign that a state change has
     * gone unmodelled.
     *
     * So the model says so, by calling this. A shift change is an event the model already schedules;
     * this is the one line that tells the fleet about it.
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
