package ksl.modeling.agv

import ksl.modeling.agv.policies.BidPolicyIfc
import ksl.modeling.agv.policies.DispositionPolicyIfc
import ksl.modeling.agv.policies.NetworkDistanceBid
import ksl.modeling.agv.policies.ReturnToHomeBaseDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * A vehicle: the permanent identity a modeller declares, names, and reads statistics from.
 *
 * What it is *not* is the thing that decides, and it is not the thing that occupies space. Those
 * are two other objects, and both are hidden. The vehicle **composes** a `GuidedTransporter` for
 * its physical presence, because a transporter is a `Resource` and a resource is passive by
 * construction: inheriting one would expose a way to seize this vehicle as though it were a tool,
 * which is exactly the modelling stance this subsystem exists to replace. And it holds a
 * per-replication agent for its behaviour, because a `KSLProcess` runs once and a vehicle must run
 * in every replication.
 *
 * A modeller names only this object.
 */
open class AgvVehicle @JvmOverloads constructor(
    val system: AgvSystem,
    initialPlacement: TransporterPlacement,
    velocity: RVariableIfc,
    lengthInZones: Int = 1,
    zoneControlRule: ZoneControlRuleIfc = EndOfZoneControl(),
    name: String? = null
) : ModelElement(system, name) {

    /**
     * The physical presence: zone occupancy, movement, and the utilization statistics that go with
     * them. Composed rather than inherited, for the reason in the class comment. A modeller never
     * names this.
     */
    internal val body: GuidedTransporter = GuidedTransporter(
        system.spaceSystem, initialPlacement, velocity, lengthInZones, zoneControlRule,
        "${this.name}:Body"
    )

    /**
     * Required by `seize`, which always takes a queue. Nothing ever waits here: a vehicle's body is
     * seized only by that vehicle's own agent, and only once the dispatcher has already chosen it.
     * The allocation records the vehicle's commitment; it is not an allocation protocol.
     */
    internal val bodyQ: RequestQ = RequestQ(this, "${this.name}:BodyQ")

    init {
        system.addVehicle(this)
    }

    /** Where the vehicle waits when it has nothing to do, or null to wait where it stops. */
    var homeBase: String? = null
        set(value) {
            require(model.isNotRunning) { "The home base cannot be changed while the model is running." }
            field = value
            body.homeBase = value
        }

    /** How many loads it may carry at once. One for now; raising it is the multi-load seam. */
    var loadCapacity: Int = 1
        set(value) {
            require(model.isNotRunning) { "The load capacity cannot be changed while the model is running." }
            require(value >= 1) { "A vehicle must be able to carry at least one load." }
            field = value
        }

    /** What it does with itself when the dispatcher has no work. Per vehicle, so a fleet may be
     *  heterogeneous. */
    var dispositionPolicy: DispositionPolicyIfc = ReturnToHomeBaseDisposition()

    /** What it offers when a dispatcher calls for proposals. Consulted from the negotiated phase. */
    var bidPolicy: BidPolicyIfc = NetworkDistanceBid()

    /** The live agent for this replication. Non-null only between `initialize` and the horizon, and
     *  **replaced** rather than reused each replication -- a retained agent would read the previous
     *  replication's mailbox, which nothing in the framework resets for a runtime agent. */
    internal var agent: AgvSystem.VehicleAgent? = null

    /** Where it is now, as a network location name. */
    val currentLocationName: String
        get() = body.currentLocation.name

    /**
     * How fast this vehicle travels, as most recently sampled.
     *
     * For a bidding rule that wants to quote a completion time rather than a distance, since a fast
     * vehicle slightly further off finishes first and a distance-only rule cannot say so. It is the
     * *last sampled* value rather than a distributional mean, which is exact when velocity is
     * constant -- the usual case -- and an estimate otherwise. A bid is a quote, not a guarantee, so
     * an estimate is the right thing here; anything drawing on it for a hard constraint should not.
     */
    val nominalVelocity: Double
        get() = body.currentVelocity

    /** True when it has declared itself available and holds no assignment. Asserted by the vehicle,
     *  never inferred by the dispatcher. */
    val isAvailable: Boolean
        get() = system.dispatcher.isAvailable(this)

    val currentAssignment: Assignment?
        get() = agent?.assignment

    // ---- statistics ---------------------------------------------------------------------------
    // These delegate to the body wherever the passive subsystem already reports the same quantity,
    // so that a model built both ways can be compared row by row. The element *names* still differ
    // by a ":Body" suffix, which the equivalence benchmark maps; see the note in AgvSystem.

    val fracTimeMoving: TWResponseCIfc get() = body.fracTimeMoving
    val fracTimeTransporting: TWResponseCIfc get() = body.fracTimeTransporting
    val fracTimeMovingEmpty: TWResponseCIfc get() = body.fracTimeMovingEmpty
    val fracTimeBlocked: TWResponseCIfc get() = body.fracTimeBlocked
    val numTimesBlocked: CounterCIfc get() = body.numTimesBlocked

    /**
     * The fraction of time the vehicle holds an assignment.
     *
     * Not the body's figure, and the difference is the point: a vehicle standing still while a load
     * is put on it is on task and not moving. Under the passive paradigm there is no object that
     * could report this, because there is nothing that holds a commitment.
     */
    private val myFracTimeOnTask = TWResponse(this, "${this.name}:FracTimeOnTask")
    val fracTimeOnTask: TWResponseCIfc
        get() = myFracTimeOnTask

    private val myNumTasksCompleted = Counter(this, "${this.name}:NumTasksCompleted")
    val numTasksCompleted: CounterCIfc
        get() = myNumTasksCompleted

    internal fun taskStarted() {
        myFracTimeOnTask.value = 1.0
    }

    internal fun taskEnded() {
        myFracTimeOnTask.value = 0.0
        myNumTasksCompleted.increment()
    }

    /**
     * Commands the body toward a location and reports whether a journey is now under way.
     *
     * **Does not suspend**, and that is deliberate: the `hold` that waits for arrival stays visible
     * at the call site in the control loop, where a reader of the loop can see every point at which
     * simulated time passes and the world can change underneath the vehicle.
     *
     * Not `sendTo`, which refuses while the body is allocated. That refusal is right for its own
     * purpose -- a passive transporter belonging to an entity must not wander off -- but it makes
     * `sendTo` unusable for a vehicle that has seized its own body to record commitment.
     *
     * @param waiter the vehicle's own agent, which is what arrival resumes. Never the load: the
     *   load waits in this subsystem's hold queue, and passing it here would produce a model that
     *   works while attributing the riding time to the space layer's queue instead.
     * @return true when a journey began, false when the body was already there
     */
    internal fun beginTravelTo(
        location: String,
        state: TransporterState,
        waiter: ProcessModel.Entity
    ): Boolean = system.spaceSystem.beginJourney(body, location, state, waiter)

    override fun initialize() {
        myFracTimeOnTask.value = 0.0
    }

    override fun toString(): String =
        "AgvVehicle($name, at=$currentLocationName, assignment=${currentAssignment})"
}
