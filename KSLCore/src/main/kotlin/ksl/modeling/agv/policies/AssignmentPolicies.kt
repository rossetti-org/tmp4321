package ksl.modeling.agv.policies

import ksl.modeling.agv.AgvVehicle
import ksl.modeling.agv.AssignmentProposal
import ksl.modeling.agv.TaskBoard
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.guidedpath.GuidedPathNetwork

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
    val time: Double
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
