package ksl.modeling.agv

/**
 * Where a vehicle is going and what it does when it gets there.
 *
 * A single-load transport is a two-stop tour, and the control loop does not know how many stops it
 * has. That is the whole point: multi-load work adds stops rather than adding a code path, so the
 * loop written here is the loop that will still be running when a vehicle carries four things.
 */
class Tour internal constructor(val stops: List<TourStop>) {

    init {
        require(stops.isNotEmpty()) { "A tour must have at least one stop." }
    }

    private var cursor: Int = 0

    /** The stop the vehicle is travelling to, or null when the tour is done. */
    val nextStop: TourStop?
        get() = stops.getOrNull(cursor)

    val isComplete: Boolean
        get() = cursor >= stops.size

    val stopsCompleted: Int
        get() = cursor

    internal fun advance() {
        check(!isComplete) { "The tour is already complete and cannot be advanced." }
        cursor++
    }

    override fun toString(): String = "Tour(${stops.size} stops, $cursor completed)"
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
