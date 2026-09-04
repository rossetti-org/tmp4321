package ksl.modeling.agv

import ksl.modeling.agv.policies.BidPolicyIfc
import ksl.modeling.agv.policies.DispositionPolicyIfc
import ksl.modeling.agv.policies.NetworkDistanceBid
import ksl.modeling.agv.policies.ReturnToHomeBaseDisposition
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.MovementWait
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.VelocitySampling
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
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
 *
 * @property battery the vehicle's energy store, or null for a vehicle whose charge is not modelled.
 * A vehicle with no battery reports no charging statistics, because a row that measures something
 * the model does not have is a question its reader has to answer for themselves every time.
 * @property loadCapacity how many loads the vehicle can carry at once. One load at a time is what
 * this subsystem implements, so any other value is refused at construction; the refusal names the
 * two ways to get the effect instead -- a larger fleet, or consolidating loads into one entity
 * before the transport is requested.
 */
open class AgvVehicle @JvmOverloads constructor(
    val system: AgvSystem,
    initialPlacement: TransporterPlacement,
    velocity: RVariableIfc,
    lengthInZones: Int = 1,
    zoneControlRule: ZoneControlRuleIfc = EndOfZoneControl(),
    name: String? = null,
    physicalLength: Double? = null,
    val loadCapacity: Int = 1,
    val battery: Battery? = null
) : ModelElement(system, name) {

    /**
     * The physical presence: zone occupancy, movement, and the utilization statistics that go with
     * them. Composed rather than inherited, for the reason in the class comment. A modeller never
     * names this.
     */
    internal val body: GuidedTransporter = GuidedTransporter(
        system.spaceSystem, initialPlacement, velocity, lengthInZones, zoneControlRule,
        "${this.name}:Body", physicalLength
    )

    /**
     * Required by `seize`, which always takes a queue. Nothing ever waits here: a vehicle's body is
     * seized only by that vehicle's own agent, and only once the dispatcher has already chosen it.
     * The allocation records the vehicle's commitment; it is not an allocation protocol.
     */
    internal val bodyQ: RequestQ = RequestQ(this, "${this.name}:BodyQ")

    init {
        require(loadCapacity >= 1) {
            "Vehicle ($name) was given a load capacity of $loadCapacity. A vehicle must be able to " +
                    "carry at least one load."
        }
        // Refused at construction rather than accepted and then ignored: a capacity the subsystem
        // cannot honour is a promise it does not keep, and a modeller who asks for three and
        // silently gets one has no way to tell.
        require(loadCapacity == 1) {
            "Vehicle ($name) was given a load capacity of $loadCapacity, but this subsystem carries " +
                    "one load at a time. Multi-load vehicles are a designed extension that is not " +
                    "yet implemented -- a vehicle holds a single assignment and its tour is built " +
                    "from one task -- so a capacity above one would be accepted and then ignored. " +
                    "Use a larger fleet, or model the consolidation upstream by combining loads into " +
                    "one entity before the transport is requested."
        }
        system.addVehicle(this)
    }

    /**
     * How much of the guide path the vehicle covers, in the network's own length units, when it is
     * sized by length rather than by whole zones. Null means it is sized in zones.
     *
     * Passed straight through to the body. It is here for the same reason the passive transporter
     * has it -- a vehicle that fits inside a zone gets its own length back when it reverses out of a
     * dead end -- and it is here at all so that the two paradigms can model the same vehicle. A
     * feature only one of them had would make any comparison between them a comparison of the
     * feature.
     */
    val physicalLength: Double?
        get() = body.physicalLength

    /**
     * How often the velocity is drawn when it is random: once per movement, or once per zone.
     *
     * Delegated to the body, which owns the movement. `PER_MOVE` by default, matching the passive
     * transporter and `MovableResource`.
     */
    var velocitySampling: VelocitySampling
        get() = body.velocitySampling
        set(value) {
            body.velocitySampling = value
        }

    /** Where the vehicle waits when it has nothing to do, or null to wait where it stops. */
    var homeBase: String? = null
        set(value) {
            require(model.isNotRunning) { "The home base cannot be changed while the model is running." }
            field = value
            body.homeBase = value
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

    /**
     * Where the vehicle is, as a network location name, **updated while it travels**.
     *
     * Derived from the zone the vehicle currently holds rather than from its spatial position, and
     * the difference matters more than it looks. The body's `currentLocation` is a spatial
     * coordinate that is written when the vehicle is *placed* and when it *arrives*: throughout a
     * journey it still reports where the vehicle set off from. Anything asking a moving vehicle
     * where it is -- a bidding rule, a re-tasking rule, a feasible-set cost -- would be answered
     * with its last stopping place, and would keep choosing as though it had never left.
     *
     * A link zone reports the intersection it leads to, so a vehicle part-way along a leg is treated
     * as being at the end of that leg. That is the right approximation for a one-way guide path,
     * where a vehicle cannot turn round: the junction ahead is the first point from which it has any
     * choice, so it is the honest place to measure a future journey from. It is the same convention
     * the passive subsystem's allocation rules use.
     */
    val currentLocationName: String
        get() {
            val front = body.frontZone ?: return body.currentLocation.name
            return system.network.intersectionOf(front).name
        }

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

    /** The vehicle is no longer on task, however that came about -- delivered or abandoned. */
    internal fun taskEnded() {
        myFracTimeOnTask.value = 0.0
    }

    /** The vehicle delivered. Separate from [taskEnded] because a tour abandoned part-way through
     *  ends without completing, and counting it would let a fleet report more deliveries than there
     *  were loads. */
    internal fun taskCompleted() {
        myNumTasksCompleted.increment()
    }


    // ---- charge ---------------------------------------------------------------------------------

    /**
     * How far this vehicle has travelled in this replication, in the network's own length units.
     *
     * An odometer on the body, where the movement happens, so both paradigms have it. Includes the
     * part of a zone traversal still under way.
     */
    val distanceTravelled: Double
        get() = body.distanceTravelled

    /**
     * How long this vehicle has been anything other than idle in this replication.
     *
     * Moving, blocked, loading and unloading count; standing with nothing to do does not. Distinct
     * from elapsed time, and the distinction is what lets a service or wear model be written
     * against hours in operation rather than hours on the wall.
     */
    val operatingTime: Double
        get() = body.operatingTime

    /** Charge added at chargers, and the instant the depletion clock started, both per replication. */
    private var chargeAdded: Double = 0.0
    private var chargeClockStartedAt: Double = 0.0
    private var lowestCharge: Double = Double.MAX_VALUE

    /**
     * The charge account, which may run negative.
     *
     * Kept unclamped because clamping loses the arithmetic. A vehicle that stands flat for an hour
     * has drawn an hour of hotel load it did not have; topping it up to full at a charger must
     * cancel that draw, and it can only do so if the overdraft was recorded. [stateOfCharge] is
     * what a modeller reads.
     */
    private val rawCharge: Double
        get() {
            val b = battery ?: return Double.NaN
            return b.initialCharge + chargeAdded -
                    body.distanceTravelled * b.chargePerDistance -
                    (time - chargeClockStartedAt) * b.chargePerTime
        }

    /**
     * How much charge is left, or NaN for a vehicle with no battery.
     *
     * Derived from the odometers whenever it is asked for rather than stepped by events, so reading
     * it costs nothing and never lags. Never below zero: a flat battery is flat.
     */
    val stateOfCharge: Double
        get() = battery?.let { rawCharge.coerceIn(0.0, it.capacity) } ?: Double.NaN

    /** [stateOfCharge] as a fraction of capacity, or NaN for a vehicle with no battery. */
    val fractionCharged: Double
        get() = battery?.let { stateOfCharge / it.capacity } ?: Double.NaN

    /** True when the vehicle has no charge left. Always false for a vehicle with no battery. */
    val isFlat: Boolean
        get() = battery != null && rawCharge <= 0.0

    /**
     * True while the vehicle is stopped mid-route because it ran out of charge.
     *
     * It holds its zones and obstructs everything behind it for the rest of the replication, which
     * is what running flat on a guide path actually costs. See [ksl.modeling.agv.policies.ChargeReservePolicy] for the guard
     * that stops it happening.
     */
    val isStranded: Boolean
        get() = body.isHalted

    private val myFracTimeCharging: TWResponse? =
        if (battery == null) null else TWResponse(this, "${this.name}:FracTimeCharging")

    /** The fraction of time spent on a charger. Registered only for a vehicle that has a battery. */
    val fracTimeCharging: TWResponseCIfc?
        get() = myFracTimeCharging

    private val myNumChargingSessions: Counter? =
        if (battery == null) null else Counter(this, "${this.name}:NumChargingSessions")

    /** How many times the vehicle charged. Registered only for a vehicle that has a battery. */
    val numChargingSessions: CounterCIfc?
        get() = myNumChargingSessions

    private val myNumTimesStranded: Counter? =
        if (battery == null) null else Counter(this, "${this.name}:NumTimesStranded")

    /**
     * How many times the vehicle stopped mid-route for want of charge.
     *
     * It can be more than one per replication only if something restarts a stranded vehicle;
     * nothing in this subsystem does, so in practice it is zero or one, and one is a modelling
     * failure rather than an outcome to average.
     */
    val numTimesStranded: CounterCIfc?
        get() = myNumTimesStranded

    private val myMinStateOfCharge: Response? =
        if (battery == null) null else Response(this, "${this.name}:MinStateOfCharge")

    /**
     * The lowest charge the vehicle was seen to hold during the replication, one observation per
     * replication.
     *
     * Sampled where charge is checked -- at zone boundaries and around charging -- rather than
     * continuously, because a quantity derived from odometers has no events of its own to sample
     * at. Between two boundaries it falls monotonically, so the boundary readings bracket the true
     * minimum to within one zone.
     */
    val minStateOfCharge: ResponseCIfc?
        get() = myMinStateOfCharge

    /** The gate the space layer asks at every zone boundary. */
    private fun mayContinuePastBoundary(): Boolean {
        observeCharge()
        if (!isFlat) return true
        myNumTimesStranded?.increment()
        logger.warn {
            "AgvVehicle ($name) ran out of charge at ($currentLocationName) during replication " +
                    "${model.currentReplicationNumber} and stopped where it stands. It holds its " +
                    "zones for the rest of the replication and obstructs anything routed through " +
                    "them. A ChargeReservePolicy is what prevents this."
        }
        return false
    }

    private fun observeCharge() {
        if (battery == null) return
        val soc = stateOfCharge
        if (soc < lowestCharge) lowestCharge = soc
    }

    /** Puts the vehicle on a charger and reports how long it must stay to fill the battery. */
    internal fun beginCharging(): Double {
        val b = battery ?: return 0.0
        observeCharge()
        myFracTimeCharging?.value = 1.0
        // Net of the hotel load, which keeps drawing while the vehicle is on the charger. The
        // battery refuses a charging rate that does not outpace it, so this is positive.
        return ((b.capacity - rawCharge) / (b.chargingRate - b.chargePerTime)).coerceAtLeast(0.0)
    }

    /** Takes the vehicle off the charger, full. */
    internal fun endCharging() {
        val b = battery ?: return
        chargeAdded += b.capacity - rawCharge
        myFracTimeCharging?.value = 0.0
        myNumChargingSessions?.increment()
        observeCharge()
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
     * @return the movement queue to suspend the waiter in, or null when the body was already there
     */
    internal fun beginTravelTo(
        location: String,
        state: TransporterState,
        waiter: ProcessModel.Entity
    ): HoldQueue? = system.spaceSystem.beginJourney(
        body, location, state, waiter, MovementWait.DRIVING
    )

    override fun initialize() {
        myFracTimeOnTask.value = 0.0
        chargeAdded = 0.0
        chargeClockStartedAt = time
        lowestCharge = Double.MAX_VALUE
        if (battery != null) {
            myFracTimeCharging?.value = 0.0
            body.attachMovementGate { _, _ -> mayContinuePastBoundary() }
        }
    }

    /**
     * Records the replication's lowest charge.
     *
     * Here rather than in `afterReplication` because this runs while the replication is still live,
     * and a `Response` summarizes in `afterReplication` whatever was observed during the run. A
     * replication in which the vehicle never moved observed nothing, and its initial charge is the
     * honest reading for it.
     */
    override fun replicationEnded() {
        super.replicationEnded()
        if (battery == null) return
        observeCharge()
        myMinStateOfCharge?.value = if (lowestCharge == Double.MAX_VALUE) stateOfCharge else lowestCharge
    }

    override fun toString(): String =
        "AgvVehicle($name, at=$currentLocationName, assignment=${currentAssignment})"
}
