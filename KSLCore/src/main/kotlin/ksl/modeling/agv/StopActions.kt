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

import ksl.modeling.entity.KSLProcessBuilder

/**
 * What a vehicle does when it gets there.
 *
 * A tour names stops, and a stop is a place paired with one of these. The interface is open and the
 * method is suspending, which together are the whole extension point of this subsystem: an action
 * may wait for a resource, delay for a dwell, ask the dispatcher a question and wait for the
 * answer, or do nothing at all, and the control loop that runs it does not change to accommodate
 * any of that. The loop's job is to get the vehicle to the location; what happens on arrival is
 * this.
 *
 * **The vehicle executes actions; it never invents them.** An action is written by whoever builds
 * the model and put into a tour by the dispatcher's tour policy. That division is what keeps a
 * fleet's behaviour in one place instead of two.
 *
 * A modeller writing one gets the subsystem's per-load bookkeeping by calling the verbs on
 * [StopContextIfc] rather than by touching the load: [StopContextIfc.takeAboard] and
 * [StopContextIfc.setDown] record the intervals, keep the manifest honest, tell the dispatcher and
 * wake the load. An action that moves a load without them will run, and will be missing from every
 * statistic the subsystem reports.
 */
interface StopActionIfc {

    /**
     * The task this action acts on behalf of, or null for an action that acts on nobody's.
     *
     * Read by the tour machinery to decide what belongs to whom: which stops a revocation takes
     * out, which stops are still this vehicle's to make, and which set-down answers which pickup.
     * An action with no task is nobody's to revoke and is always still ours.
     */
    val task: Dispatcher.Task?
        get() = null

    /**
     * How many loads this stop is *planned* to add to the vehicle: +1 for a collection, -1 for a
     * delivery, 0 for a stop that carries nothing either way.
     *
     * A planning figure rather than a promise, and the distinction matters for an action whose
     * effect depends on what it finds when it arrives -- a stop that boards everyone waiting boards
     * a number nobody knows in advance. Such an action declares 0 and is planned as though it
     * changed nothing, which is the only honest thing a planner can be told; the capacity is then
     * enforced where it is actually known, by the manifest, at the moment of boarding.
     */
    val loadChange: Int
        get() = 0

    /** Carried out once the vehicle has arrived at [StopContextIfc.stop]. */
    suspend fun KSLProcessBuilder.perform(context: StopContextIfc)
}

/**
 * What a vehicle knows, and what it can do, at the stop it has just reached.
 *
 * Handed to [StopActionIfc.perform] so that an action written outside this library can do the two
 * things that must be done in one particular way -- take a load aboard and put one down -- without
 * being given the subsystem's internals to do it with.
 */
interface StopContextIfc {

    /** The vehicle that arrived. */
    val vehicle: AgvVehicle

    /** The stop it arrived at. */
    val stop: TourStop

    /** The round this stop belongs to, and how far through it the vehicle is. */
    val tour: Tour

    /**
     * Takes [task]'s load aboard: waits out the loading delay, puts it on the manifest, marks the
     * instant its ride begins, tells the dispatcher the vehicle has possession, and releases the
     * load from its wait.
     *
     * Suspends for as long as loading takes. Every per-load interval this subsystem reports about
     * collection is recorded here, which is why an action that wants to be measured collects this
     * way rather than by touching the load itself.
     */
    suspend fun KSLProcessBuilder.takeAboard(task: Dispatcher.TransportTask)

    /**
     * Puts [task]'s load down: measures the ride, waits out the unloading delay, takes it off the
     * manifest, completes the task and the commitment behind it, and returns the load to its own
     * process.
     *
     * Suspends for as long as unloading takes.
     */
    suspend fun KSLProcessBuilder.setDown(task: Dispatcher.TransportTask)
}

/**
 * Take possession of a load.
 *
 * The collecting half of a transport. Its set-down is a separate stop, and the two are put into a
 * tour together and taken out of one together, because a vehicle routed to deliver something it
 * never collected is not a tour at all.
 */
data class PickUp(override val task: Dispatcher.TransportTask) : StopActionIfc {

    override val loadChange: Int
        get() = 1

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        with(context) { takeAboard(this@PickUp.task) }
    }
}

/** Put a load down, which is what discharges the commitment to carry it. */
data class SetDown(override val task: Dispatcher.TransportTask) : StopActionIfc {

    override val loadChange: Int
        get() = -1

    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
        with(context) { setDown(this@SetDown.task) }
    }
}

/**
 * Be somewhere, and nothing more.
 *
 * What an errand amounts to: the arrival *is* the work. It is not a no-op with a location attached,
 * because getting there was the point.
 */
data object Reposition : StopActionIfc {
    override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) = Unit
}

// Charge arrives with the battery seam; Repair with the failure seam.
