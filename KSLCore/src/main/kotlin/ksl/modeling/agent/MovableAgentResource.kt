/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.VehicleMovementIfc
import ksl.simulation.KSLEvent

/**
 *  An [AgentResource] whose position is tracked in a
 *  [ContinuousProjection]. Composes the agent-resource semantics
 *  (seizable, queueable, on-shift / off-shift, statechart, mailbox,
 *  optional `AgentPerformance` stats) with continuous-space
 *  position tracking. Spatial queries on the projection
 *  (`space.within(p, r)`, `space.neighborsOf(...)`) automatically
 *  see this resource at its current position.
 *
 *  The agent-layer analog of `ksl.modeling.spatial.MovableResource`,
 *  but built on agent-layer primitives. Differences from the
 *  spatial-layer version:
 *
 *   - Position lives in a `ContinuousProjection`, not a `SpatialModel`.
 *     Spatial queries on the projection see this resource alongside
 *     any other agents.
 *   - Movement uses the agent-layer [travelTo] primitive (or any
 *     other code that updates the projection's positions). No
 *     built-in `KSLProcess.transportWith(this)` integration — for
 *     that path, use the spatial-layer `MovableResource` directly
 *     (possibly with the Phase 4.1 bridge for shared coordinates).
 *   - Velocity isn't a fixed property of the resource. Each travel
 *     specifies its own velocity, which is more flexible (allows
 *     loaded vs. empty velocities, fast vs. slow modes) at the
 *     cost of one extra parameter per call site.
 *
 *  Typical usage:
 *
 *  ```kotlin
 *  class Warehouse(parent: ModelElement) : AgentModel(parent, "warehouse") {
 *      val world: Context<AgentLike> = Context("world")
 *      val floor: ContinuousProjection<AgentLike> =
 *          ContinuousProjection(world, 0.0..100.0, 0.0..100.0)
 *
 *      val forklift: MovableAgentResource = MovableAgentResource(
 *          this, floor, initPosition = Point2D(50.0, 50.0), name = "forklift-1",
 *      )
 *
 *      inner class TaskRunner : Agent("runner") {
 *          val script: KSLProcess = process(isDefaultProcess = true) {
 *              val allocation = seize(forklift)
 *              travelTo(forklift, floor, Point2D(80.0, 20.0), velocity = 2.5)
 *              delay(2.0)  // load
 *              travelTo(forklift, floor, Point2D(10.0, 90.0), velocity = 2.5)
 *              delay(2.0)  // unload
 *              release(allocation)
 *          }
 *      }
 *  }
 *  ```
 *
 *  Lifecycle: a `MovableAgentResource` is a `ResourceWithQ` (via
 *  `AgentResource`), so it's a `ModelElement` and must be
 *  constructed before `simulate()`. It joins its projection's
 *  context automatically at construction and is placed at
 *  [initPosition].
 *
 *  Constraints on type variance: the projection is typed as
 *  `ContinuousProjection<AgentLike>` so the same projection can
 *  hold both `Agent`s and `MovableAgentResource`s (and any other
 *  `AgentLike` types). Models that need a more specific projection
 *  type can either keep separate projections per type or upcast
 *  this resource to `AgentLike` at the call site.
 *
 *  @param agentModel the enclosing `AgentModel`; required as
 *    [AgentResource] needs an `AgentModel` parent for its mailbox.
 *  @param space the projection that tracks this resource's
 *    position. Must hold `AgentLike` (or compatible) members.
 *  @param initPosition starting position in the projection.
 *  @param name optional name; defaults to `MovableAgentResource_<id>`.
 *  It also implements [VehicleMovementIfc], the movement seam a
 *  fleet is written against, so tours, dispatching and a manifest
 *  can be driven over a continuous projection by exactly the code
 *  that drives them over a guide path. See "The seam" below.
 *
 *  @param capacity initial resource capacity (default 1).
 *  @param queue optional shared request queue.
 *  @param velocity how fast [beginTravelTo] moves it. A property of
 *    the vehicle rather than of each call, because the seam's caller
 *    is a fleet that knows what a journey is *for* and not how fast
 *    this particular vehicle goes. [travelTo] still takes its own
 *    velocity per call and is unaffected.
 *  @param stepSize the interpolation step, in coordinate units. What
 *    a journey is discretised into, and therefore how quickly a
 *    redirection or a halt is observed: at most `stepSize/velocity`
 *    later.
 */
open class MovableAgentResource @JvmOverloads constructor(
    agentModel: AgentModel,
    val space: ContinuousProjection<AgentLike>,
    initPosition: Point2D,
    name: String? = null,
    capacity: Int = Defaults.capacity,
    queue: RequestQ? = null,
    var velocity: Double = Defaults.velocity,
    var stepSize: Double = Travel.Defaults.stepSize,
) : AgentResource(agentModel, name, capacity, queue), VehicleMovementIfc {

    init {
        require(velocity > 0.0) { "velocity must be positive; was $velocity" }
        require(stepSize > 0.0) { "stepSize must be positive; was $stepSize" }
    }

    /**
     *  Mutable global defaults for [MovableAgentResource] construction.
     */
    companion object Defaults {
        /** Default on-shift capacity for new movable agent resources. Must be positive. */
        var capacity: Int by positive(1)

        /** Default travel velocity for the movement seam. Must be positive. */
        var velocity: Double by positive(1.0)
    }

    init {
        space.context.add(this)
        space.placeAt(this, initPosition)
    }

    /**
     *  Current position in [space]. Throws if the resource has
     *  somehow lost its position (should never happen during normal
     *  use, since the resource joins the context at construction
     *  and only leaves on explicit `context.remove`).
     */
    val position: Point2D
        get() = space.positionOf(this)
            ?: error("MovableAgentResource '${this.name}' has no position in projection '${space.name}'")

    /**
     *  Instantly place at the given point, bypassing any motion-time
     *  semantics. For continuous-time movement use [travelTo] from
     *  inside a `process { }` body.
     */
    fun placeAt(point: Point2D) {
        space.placeAt(this, point)
    }

    // ---- the movement seam ---------------------------------------------------------------------
    //
    // `VehicleMovementIfc` is what a fleet needs from whatever moves its vehicles, and the guide
    // path was its first implementer. This is the second, and the point of there being two is that
    // the machinery above -- tours, stops, dispatching, the manifest -- is written once.
    //
    // **The seam could not be satisfied by handing it a TravelHandle, and that is the finding.**
    // `startTravel`/`awaitTravel` put the integration loop inside the *traveller's own process*: it
    // is `awaitTravel` that delays, steps and re-plans. The seam requires the opposite -- a command
    // that does not suspend, handing back the queue its caller must wait in -- because a fleet's
    // control loop has to be able to command a vehicle from somewhere other than the vehicle's
    // process, and because the two ends of a wait must not disagree about where the wake comes
    // from. So the integration here is driven by scheduled events owned by the vehicle, with the
    // same step semantics `awaitTravel` has: plan a direction and a step distance, wait
    // `step/velocity`, advance, re-plan.
    //
    // Two consequences worth stating rather than discovering:
    //
    //  - A redirection is observed at the next step boundary, exactly as `TravelHandle.redirect`
    //    is, so the substrate's own granularity is what the latency is. The guide path defers a
    //    turn to the next zone boundary for the same reason: something between two places cannot
    //    stop and turn.
    //  - The step in flight when a redirection arrives is completed along the direction it was
    //    planned with. `awaitTravel` re-reads the destination after its delay and would teleport a
    //    vehicle redirected during its *final* step; this does not.

    /** The plane this vehicle's positions are expressed in. One per projection. */
    val plane: ksl.modeling.agent.ProjectionSpatialModel
        get() = space.spatialModel

    private val myTravelQ = HoldQueue(this, "${this.name}:TravelQ")

    init {
        myTravelQ.waitTimeStatOption = false
        myTravelQ.defaultReportingOption = false
    }

    /** Where the journey in progress is going, or null when there is none. */
    private var target: Point2D? = null

    /** Who is to be resumed when it ends, however it ends. */
    private var waiter: ProcessModel.Entity? = null

    private var stepEvent: KSLEvent<Nothing>? = null
    private var plannedStep: Double = 0.0
    private var plannedDirection: Point2D = Point2D.ORIGIN
    private var plannedTarget: Point2D? = null
    private var plannedIsFinal: Boolean = false

    private var myDistanceTravelled: Double = 0.0
    private var myOperatingTime: Double = 0.0
    private var myHalted: Boolean = false

    /** Where the vehicle is now, interpolated to this instant by the step that last completed. */
    override val positionNow: LocationIfc
        get() = plane.location(position)

    /**
     *  Straight-line distance across the projection, which on a plane **is** the path the vehicle
     *  would take. Wraps where the projection is a torus, because there the short way round is the
     *  way it would actually go.
     */
    override fun pathDistanceTo(destination: LocationIfc): Double {
        val to = pointOf(destination)
        return space.distance(position, to)
    }

    /** True for any location on this plane and inside the projection's bounds. */
    override fun isReachable(destination: LocationIfc): Boolean {
        if (destination !is ProjectionSpatialModel.ProjectedLocation) return false
        if (destination.spatialModel !== plane) return false
        val p = destination.point
        return space.torus ||
                (p.x in space.xRange && p.y in space.yRange)
    }

    /**
     *  Sends the vehicle to [destination] and hands back the queue to wait in.
     *
     *  A second call while a journey is under way is a **redirection**: the target changes and the
     *  running step chain re-plans from wherever the vehicle is when its current step completes.
     *  The odometer keeps growing across it, because a vehicle that turns round has still covered
     *  the ground it covered.
     *
     *  @return the queue to suspend [waiter] in, or null when the vehicle was already there
     */
    override fun beginTravelTo(
        destination: LocationIfc,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue? {
        require(isReachable(destination)) {
            "MovableAgentResource (${this.name}) cannot reach (${destination.name}): it is not a " +
                    "location on projection (${space.name})."
        }
        val to = pointOf(destination)
        val underway = target != null && !myHalted
        if (!underway && space.distance(position, to) <= ARRIVAL_TOLERANCE) return null
        this.waiter = waiter
        target = to
        myHalted = false
        // Only a journey that is not already stepping needs starting. One that is re-plans at its
        // next boundary, which is what makes a second call a redirection rather than a restart.
        if (stepEvent == null) planNextStep()
        return myTravelQ
    }

    /** True while the vehicle is stopped short of where it was going, with nothing scheduled. */
    override val isHalted: Boolean
        get() = myHalted

    /**
     *  Stops the vehicle where it stands and wakes whoever was waiting for it.
     *
     *  The substrate's way of stopping a vehicle part way -- the counterpart of a guide path's
     *  movement gate refusing at a boundary. Whoever called this owns starting it again, through
     *  [resumeHalted] or a fresh [beginTravelTo]. Harmless when no journey is under way.
     */
    fun halt() {
        if (target == null || myHalted) return
        stepEvent?.cancel = true
        stepEvent = null
        myHalted = true
        endTheWait()
    }

    /** Starts a halted vehicle again from where it stopped. Harmless on one that is not halted. */
    override fun resumeHalted() {
        if (!myHalted) return
        myHalted = false
        if (target != null && stepEvent == null) planNextStep()
    }

    /** How far this vehicle has travelled this replication. Never decreases. */
    override val distanceTravelled: Double
        get() = myDistanceTravelled

    /**
     *  How long it has spent travelling this replication.
     *
     *  Accumulated a step at a time, so it is a step function rather than a continuous one. It does
     *  not include time seized-but-standing: a fleet asking what a vehicle has *done* means the
     *  moving, and what it has been held for is the resource's own busy time.
     */
    override val operatingTime: Double
        get() = myOperatingTime

    private fun pointOf(location: LocationIfc): Point2D =
        (location as? ProjectionSpatialModel.ProjectedLocation)?.point
            ?: error(
                "location (${location.name}) is not a location on projection (${space.name}); " +
                        "make one with space.spatialModel.location(x, y)"
            )

    /**
     *  Plans one step and schedules it: a direction and a distance now, the movement when the time
     *  for it has passed.
     *
     *  Planned before the delay rather than after it so that the vehicle covers the ground the
     *  elapsed time paid for, which is also what makes a redirection cost the step it interrupts
     *  rather than being free.
     */
    private fun planNextStep() {
        val to = target ?: return
        val from = position
        val remaining = space.distance(from, to)
        if (remaining <= ARRIVAL_TOLERANCE) {
            arrive(to)
            return
        }
        val step = minOf(stepSize, remaining)
        plannedStep = step
        plannedTarget = to
        plannedIsFinal = step >= remaining - ARRIVAL_TOLERANCE
        plannedDirection = space.delta(from, to).normalized()
        stepEvent = schedule(this::advance, step / velocity)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun advance(event: KSLEvent<Nothing>) {
        stepEvent = null
        val planned = plannedTarget ?: return
        val from = position
        val next = if (plannedIsFinal) {
            planned
        } else {
            Point2D(
                from.x + plannedDirection.x * plannedStep,
                from.y + plannedDirection.y * plannedStep
            )
        }
        space.moveTo(this, next)
        myDistanceTravelled += plannedStep
        myOperatingTime += plannedStep / velocity
        // Re-planning from here is what observes a redirection: `target` may no longer be the
        // target this step was planned against.
        val to = target
        if (to != null && space.distance(next, to) <= ARRIVAL_TOLERANCE) {
            arrive(to)
        } else {
            planNextStep()
        }
    }

    private fun arrive(at: Point2D) {
        space.moveTo(this, at)
        target = null
        plannedTarget = null
        endTheWait()
    }

    private fun endTheWait() {
        val w = waiter ?: return
        waiter = null
        if (myTravelQ.contains(w)) myTravelQ.removeAndResume(w)
    }

    override fun initialize() {
        super.initialize()
        target = null
        waiter = null
        stepEvent = null
        plannedTarget = null
        myDistanceTravelled = 0.0
        myOperatingTime = 0.0
        myHalted = false
    }
}

/** Below this a vehicle is treated as being there. Coordinate units, not a fraction. */
private const val ARRIVAL_TOLERANCE = 1e-9
