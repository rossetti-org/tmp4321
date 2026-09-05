/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.modeling.agv

/**
 * Where a vehicle is going and what it does when it gets there.
 *
 * A single-load transport is a two-stop tour, and the control loop does not know how many stops it
 * has. That is the whole point: multi-load work adds stops rather than adding a code path, so the
 * loop written here is the loop that will still be running when a vehicle carries four things.
 */
class Tour internal constructor(
    stops: List<TourStop>,
    /**
     * True when this round comes back to where it began.
     *
     * Read by a boarding action, which may take somebody bound for a stop this round has already
     * been past: on a loop the vehicle will reach it again, and a rider keeps its seat across the
     * cycle boundary for exactly that reason.
     */
    val cyclic: Boolean = false
) {

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
        val doomed = myStops.subList(cursor, myStops.size).filter { it.action.task === task }
        if (doomed.isEmpty()) return 0
        require(doomed.size == myStops.count { it.action.task === task }) {
            "Task (${task.name}) cannot be taken out of this tour: part of it has already been " +
                    "reached, and a stop already reached cannot be changed."
        }
        myStops.removeAll { s -> doomed.any { it === s } }
        return doomed.size
    }

    override fun toString(): String = "Tour(${myStops.size} stops, $cursor completed)"
}

/** One leg of a tour: somewhere to be, and something to do there. */
class TourStop(val location: String, val action: StopActionIfc) {
    override fun toString(): String = "TourStop($location, $action)"
}
