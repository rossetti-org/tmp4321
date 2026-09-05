package ksl.modeling.agv

/**
 * Where a vehicle is going and what it does when it gets there.
 *
 * A single-load transport is a two-stop tour, and the control loop does not know how many stops it
 * has. That is the whole point: multi-load work adds stops rather than adding a code path, so the
 * loop written here is the loop that will still be running when a vehicle carries four things.
 */
class Tour internal constructor(stops: List<TourStop>) {

    init {
        require(stops.isNotEmpty()) { "A tour must have at least one stop." }
    }

    private val myStops: MutableList<TourStop> = stops.toMutableList()

    /**
     * The stops, in the order they will be reached.
     *
     * Mutable behind the cursor and frozen in front of it: a stop already reached is history, and
     * rewriting history would let a vehicle be told to collect a load it has already set down.
     * [remove] and [insert] are the only ways it changes, and both refuse to touch the past.
     */
    val stops: List<TourStop>
        get() = myStops

    private var cursor: Int = 0

    /** The stop the vehicle is travelling to, or null when the tour is done. */
    val nextStop: TourStop?
        get() = myStops.getOrNull(cursor)

    val isComplete: Boolean
        get() = cursor >= myStops.size

    val stopsCompleted: Int
        get() = cursor

    /** The stops still to be reached, in order. What a tour policy is given to reorder. */
    val remainingStops: List<TourStop>
        get() = myStops.subList(cursor, myStops.size).toList()

    internal fun advance() {
        check(!isComplete) { "The tour is already complete and cannot be advanced." }
        cursor++
    }

    /**
     * Puts a stop into the tour at [position], counted from the next stop.
     *
     * Position 0 makes it the stop the vehicle goes to next, which is what a redirection means; the
     * caller is then responsible for issuing the leg, because a tour describes where a vehicle is
     * going and never commands it.
     *
     * @param position where among the remaining stops, 0 being next and [remainingStops].size being
     *   last
     */
    internal fun insert(stop: TourStop, position: Int) {
        require(position in 0..(myStops.size - cursor)) {
            "Cannot insert at position $position: the tour has ${myStops.size - cursor} stops left " +
                    "to make, and a stop already reached cannot be changed."
        }
        myStops.add(cursor + position, stop)
    }

    /**
     * Takes out every stop still to be reached that belongs to [task], and reports how many went.
     *
     * The unit of removal is the *task* rather than the stop, because the two stops of a transport
     * are not independent: taking out a pickup and leaving its set-down would leave a vehicle
     * routed to put down something it never collected. A task whose pickup has already happened
     * cannot be removed at all -- which is the same statement `A4` makes about revocation, arrived
     * at from the other side.
     */
    internal fun remove(task: Dispatcher.Task): Int {
        val doomed = myStops.subList(cursor, myStops.size).filter { it.action.taskOrNull() === task }
        if (doomed.isEmpty()) return 0
        require(doomed.size == myStops.count { it.action.taskOrNull() === task }) {
            "Task (${task.name}) cannot be taken out of this tour: part of it has already been " +
                    "reached, and a stop already reached cannot be changed."
        }
        myStops.removeAll { s -> doomed.any { it === s } }
        return doomed.size
    }

    override fun toString(): String = "Tour(${myStops.size} stops, $cursor completed)"
}

/** The task a stop acts on, or null for a stop that acts on nobody's behalf. */
internal fun StopAction.taskOrNull(): Dispatcher.Task? = when (this) {
    is StopAction.PickUp -> task
    is StopAction.SetDown -> task
    StopAction.Reposition -> null
}

/** One leg of a tour: somewhere to be, and something to do there. */
class TourStop(val location: String, val action: StopAction) {
    override fun toString(): String = "TourStop($location, $action)"
}

/** What a vehicle does when it arrives. */
sealed class StopAction {

    /** Take possession of a load. */
    data class PickUp(val task: Dispatcher.TransportTask) : StopAction()

    /** Put a load down. */
    data class SetDown(val task: Dispatcher.TransportTask) : StopAction()

    /** Be somewhere, and nothing more. */
    data object Reposition : StopAction()

    // Charge arrives with the battery seam; Repair with the failure seam.
}
