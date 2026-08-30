package ksl.modeling.agv.policies

import ksl.modeling.agv.AgvVehicle
import ksl.modeling.agv.AssignmentProposal
import ksl.modeling.agv.TaskBoard
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.utilities.random.rng.RNStreamIfc

/**
 * The subsystem's principal extension point: who gets sent where.
 *
 * `suspend` is the whole design in one keyword. A rule that answers immediately is a dispatching
 * rule of the ordinary kind; a policy that first waits ten minutes is batching; a policy that calls
 * for proposals and waits for a deadline is an auction. All three are "decide assignments", and
 * they differ only in whether deciding takes simulated time. An interface that could not suspend
 * would admit only the first, which is exactly the limitation this subsystem exists to remove.
 *
 * A policy **decides only** (`A7`). It cannot move a vehicle, claim a zone, post a task, or mutate
 * the board -- the board it is given is read-only and [AssignmentProposal] is inert, so this is
 * enforced by the types rather than by a rule. It may read anything.
 *
 * A policy must also be a function of what it is given, drawing any randomness from a
 * model-controlled stream (`A8`). Consulting wall-clock time or unmanaged global state would make
 * a run unreproducible in a way no test would catch.
 *
 * ## Why `assign` is written as an extension on the process builder
 *
 * `KSLProcessBuilder` is annotated `@RestrictsSuspension`, which means a process body may only
 * invoke suspending functions that are members or extensions **of the builder itself**. A plain
 * `suspend fun assign(context)` on this interface would therefore not compile at the one call site
 * that matters -- the dispatcher's process -- however sound it looked in isolation.
 *
 * Declaring it as a member extension satisfies the restriction and buys something as well: an
 * implementation receives the real process builder as its receiver, so it may `delay` for a
 * batching window, `hold`, or call `contractNet` to run an auction, rather than being confined to
 * whatever a context object thought to expose. The dispatcher calls it as
 * `with(policy) { assign(context) }`.
 */
interface AssignmentPolicyIfc {
    suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal>
}

/**
 * What a policy is given, and the only things it may do.
 *
 * Grows in later phases -- an auction in the negotiated phase, a feasible-action set in the
 * re-tasking phase -- but [AssignmentPolicyIfc.assign]'s signature never changes, so a policy
 * written today keeps compiling.
 */
class DispatchContext internal constructor(
    val board: TaskBoard,
    val available: List<AgvVehicle>,
    val network: GuidedPathNetwork,
    val time: Double,
    internal val dispatcher: ksl.modeling.agv.Dispatcher
) {

    // There is deliberately no `waitFor` here. A policy receives the process builder as its
    // receiver, so it consumes simulated time with `delay` directly -- the same verb it would use
    // anywhere else, with no wrapper to learn and nothing this object has to anticipate.

    /**
     * Distance from where a vehicle is now to a location, **along the guide path**.
     *
     * Never straight-line: see the warning on [NetworkDistanceBid]. Returns
     * [Double.POSITIVE_INFINITY] when the location is unreachable from where the vehicle stands, so
     * a policy comparing distances naturally never picks a vehicle that cannot get there.
     */
    fun distanceTo(vehicle: AgvVehicle, location: String): Double {
        val here = network.location(vehicle.currentLocationName) ?: return Double.POSITIVE_INFINITY
        val there = network.location(location) ?: return Double.POSITIVE_INFINITY
        if (!network.isReachable(here, there)) return Double.POSITIVE_INFINITY
        return network.distance(here, there)
    }
}

/**
 * The first available vehicle takes the first unassigned task.
 *
 * Deliberately the degenerate case: vehicles pulling from a shared queue, expressed as a policy
 * rather than as an architecture. It is what the passive subsystem's pool does, which is why it is
 * the policy the equivalence benchmark uses -- the two subsystems can only be compared on a
 * question where they are supposed to give the same answer.
 *
 * It never suspends, so under this policy the dispatcher is a synchronous rule and the active model
 * should reproduce the passive one.
 */
class PullFromBoardPolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        if (context.available.isEmpty()) return emptyList()
        val proposals = mutableListOf<AssignmentProposal>()
        val free = context.available.toMutableList()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            proposals.add(AssignmentProposal(free.removeAt(0), task))
        }
        return proposals
    }

    override fun toString(): String = "PullFromBoardPolicy"
}

/**
 * Send the vehicle nearest the pickup, measured **along the guide path**.
 *
 * The default from this phase on, and the one a modeller expects when they think "send the closest
 * cart". Distance is never straight-line separation: on a one-way loop a vehicle a few feet past the
 * pickup point has to travel all the way round, so a Euclidean rule would send precisely the wrong
 * one -- quietly, with no symptom other than a fleet that performs worse than it should and no
 * indication of why.
 *
 * Unreachable vehicles are excluded rather than ranked last. [DispatchContext.distanceTo] reports an
 * unreachable location as an infinite distance, which would sort correctly but would also make an
 * unreachable vehicle *win* whenever it is the only candidate -- and it would then be assigned a task
 * it cannot begin.
 */
class NearestVehiclePolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> =
        greedyByDistance(context) { d -> d }

    override fun toString(): String = "NearestVehiclePolicy"
}

/**
 * Send the vehicle *furthest* from the pickup.
 *
 * Deliberately poor, and useful for exactly that: a study needs a bad rule to measure a good one
 * against, and "how much does nearest-vehicle actually buy over the worst sensible alternative" is a
 * question no amount of arguing about the good rule can answer.
 */
class FurthestVehiclePolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> =
        greedyByDistance(context) { d -> -d }

    override fun toString(): String = "FurthestVehiclePolicy"
}

/**
 * Send whichever available vehicle has completed the fewest tasks so far.
 *
 * A workload-balancing rule rather than a travel-minimising one, and the two genuinely conflict: the
 * nearest vehicle is often the one that has just finished something nearby, so nearest-vehicle tends
 * to concentrate work on whichever vehicles are already busy in the active part of the layout. Which
 * is right depends on whether the cost being managed is time or wear.
 *
 * Ties break on declaration order, which for an untouched fleet at the start of a replication means
 * every vehicle is tied and the first is chosen -- so early in a run this behaves like
 * [PullFromBoardPolicy] and only separates once the counts diverge.
 */
class LeastUsedVehiclePolicy : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val free = context.available.toMutableList()
        val proposals = mutableListOf<AssignmentProposal>()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            val best = free.minByOrNull { it.numTasksCompleted.value } ?: break
            free.remove(best)
            proposals.add(AssignmentProposal(best, task))
        }
        return proposals
    }

    override fun toString(): String = "LeastUsedVehiclePolicy"
}

/**
 * Send a uniformly chosen available vehicle.
 *
 * The stream is supplied rather than created so that it is one of the model's own, which is what
 * makes a run reproducible and lets a study put this policy on common random numbers with the rules
 * it is being compared against. A policy that reached for a global generator would be
 * irreproducible in a way no single run would reveal.
 */
class RandomAssignmentPolicy(private val stream: RNStreamIfc) : AssignmentPolicyIfc {

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        val free = context.available.toMutableList()
        val proposals = mutableListOf<AssignmentProposal>()
        for (task in context.board.unassigned) {
            if (free.isEmpty()) break
            val pick = stream.randInt(0, free.size - 1)
            proposals.add(AssignmentProposal(free.removeAt(pick), task))
        }
        return proposals
    }

    override fun toString(): String = "RandomAssignmentPolicy"
}

/**
 * Wait for a window, then assign everything that accumulated during it, together.
 *
 * **This is the policy the interface exists for.** Every rule above answers immediately and could
 * have been a function; this one consumes simulated time, and while it is waiting the board keeps
 * filling. Deciding later over more information is the trade this makes: a load that arrives just
 * after a window opens waits the whole of it, and in exchange the fleet is allocated over a set of
 * tasks rather than one at a time in arrival order. Whether that pays depends on the layout and the
 * load, which is precisely why it is a policy a modeller can measure rather than a behaviour built
 * into a dispatcher.
 *
 * It is also the demonstration that this design needed a dispatcher with a process of its own. Under
 * the passive paradigm there is nowhere to put this: the decision is made inside an entity's own
 * process at the instant it asks, so "wait and see what else arrives" would mean making that entity
 * wait for reasons that have nothing to do with it.
 *
 * The window runs from when the dispatcher wakes, so an idle fleet still pays it. A model that wants
 * batching only under load should compose this behind a rule that checks the board first.
 *
 * @param window how long to accumulate, in simulated time
 * @param inner what to do with the batch once it has accumulated
 */
class BatchedAssignmentPolicy(
    val window: Double,
    val inner: AssignmentPolicyIfc = NearestVehiclePolicy()
) : AssignmentPolicyIfc {

    init {
        require(window > 0.0) { "A batching window must be positive; a zero window is the inner policy alone." }
    }

    override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
        // SUSPENDS. Tasks posted during the window accumulate and are on the board when this
        // returns, which is the entire point of waiting.
        delay(window, suspensionName = "batchingWindow")
        // The context was built before the wait, so its `available` list and the board's contents
        // are as they were then. The board is a live view, so it has caught up on its own; the
        // vehicle list has not, and a vehicle that has since been given work must not be proposed
        // again. Rebuilding from the dispatcher's current state is what keeps this honest.
        val fresh = DispatchContext(
            context.board, context.dispatcher.availableVehicles, context.network,
            context.dispatcher.time, context.dispatcher
        )
        return with(inner) { assign(fresh) }
    }

    override fun toString(): String = "BatchedAssignmentPolicy(window=$window, inner=$inner)"
}

/**
 * Greedy pairing of tasks to vehicles by a score derived from the network distance to the pickup.
 *
 * Shared by the nearest and furthest rules because they differ only in the sign of that score, and
 * because the part worth getting right once is the part neither is about: skipping unreachable
 * vehicles, taking tasks in the order the selection rule chose, and breaking ties on declaration
 * order so that a run is reproducible.
 */
private inline fun greedyByDistance(
    context: DispatchContext,
    score: (Double) -> Double
): List<AssignmentProposal> {
    val free = context.available.toMutableList()
    val proposals = mutableListOf<AssignmentProposal>()
    for (task in context.board.unassigned) {
        if (free.isEmpty()) break
        var best: AgvVehicle? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (v in free) {
            val d = context.distanceTo(v, task.pickupLocation)
            if (d.isInfinite()) continue          // cannot get there at all
            val sc = score(d)
            if (sc < bestScore) { bestScore = sc; best = v }
        }
        val chosen = best ?: continue             // nobody available can reach this pickup
        free.remove(chosen)
        proposals.add(AssignmentProposal(chosen, task, terms = bestScore))
    }
    return proposals
}
