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
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.Zone

/**
 * A vehicle has stopped and cannot carry on by itself.
 *
 * Sealed, because what a modeller may need to know differs by cause while everything they need to
 * *decide* is the same: where the vehicle is, what it is holding, what it was doing, and who is now
 * stuck behind it. The guide path draws no distinction at all -- a flat battery, a breakdown and an
 * operator stopping the line are one event to it -- so the distinction lives here, where the
 * response is decided.
 *
 * An interruption is handed to the vehicle's [InterruptionPolicyIfc], which runs as a process and
 * may do anything a process may do: wait for a technician, delay for travel and assessment, tow the
 * vehicle out of the way. **The policy has no return value.** When it returns, the framework asks
 * the same question the movement gate asks -- is this vehicle fit to carry on? -- and acts on the
 * answer. A repair that finished leaves a repaired vehicle, so it continues; a charge policy that
 * did nothing leaves a flat vehicle, so it does not. One predicate, and no way for a policy to
 * claim it fixed something it did not.
 *
 * @property vehicle the vehicle that stopped
 * @property at when it stopped
 * @property location where it stopped, as a network location name
 * @property heldZones the zones it is denying to everyone else while it stands there
 * @property wasDoing what it was doing at the moment it stopped
 * @property task the work in hand, or null if it had none
 * @property loadIsAboard whether it is carrying the load it was given
 */
sealed class Interruption(
    val vehicle: AgvVehicle,
    val at: Double,
    val location: String,
    val heldZones: List<Zone>,
    val wasDoing: TransporterState,
    val task: Dispatcher.Task?,
    val loadIsAboard: Boolean
) {

    /**
     * The vehicles standing still right now because this one is in their way.
     *
     * Answered from the guide path rather than guessed: a blocked vehicle records the zone or the
     * link it is waiting for, so being in the way is a fact about what this vehicle holds. Read it
     * again after time passes -- it is a live query, not a snapshot taken when the vehicle stopped.
     */
    val obstructed: List<AgvVehicle>
        get() {
            val bodies = vehicle.system.spaceSystem.transportersHeldUpBy(vehicle.body)
            if (bodies.isEmpty()) return emptyList()
            return vehicle.system.vehicles.filter { v -> bodies.any { it === v.body } }
        }

    /**
     * True when at least one vehicle is currently held up by this one.
     *
     * The question a policy actually asks. A vehicle broken down on a spur nobody uses can be
     * repaired where it stands; the same vehicle across a main aisle cannot, and this is the
     * difference between the two.
     *
     * **Do not ask it at the instant the vehicle stopped.** A queue behind a stopped vehicle takes
     * time to form, so the answer then is almost always no -- including on a layout where that same
     * vehicle goes on to block the fleet for most of the run. Measured on the chapter's shop: over
     * sixteen breakdowns, asking immediately found an obstruction **not once**, while asking forty
     * time units later found several, and leaving the vehicle in the aisle cost the healthy cart
     * 77% of its time blocked.
     *
     * A realistic policy does not have this problem, because it cannot decide anything until
     * somebody has been told, been free, and walked to the vehicle -- and by then the queue is
     * real. A policy that branches on this before any time has passed is asking a question whose
     * answer it has arranged to be no.
     */
    val isObstructing: Boolean
        get() = obstructed.isNotEmpty()

    /**
     * The vehicle has broken down.
     *
     * @property failureNumber how many times this vehicle has failed in this replication, this one
     *   included
     * @property repairTime how long the repair itself takes, drawn from the vehicle's failure model
     *   when the failure was booked. Handed over rather than left on the failure model for a policy
     *   to find, so that a policy which surrounds it with travel and waiting uses the same draw the
     *   default would have used, and a policy which ignores it is making a visible choice.
     */
    class Failed internal constructor(
        vehicle: AgvVehicle,
        at: Double,
        location: String,
        heldZones: List<Zone>,
        wasDoing: TransporterState,
        task: Dispatcher.Task?,
        loadIsAboard: Boolean,
        val failureNumber: Int,
        val repairTime: Double
    ) : Interruption(vehicle, at, location, heldZones, wasDoing, task, loadIsAboard) {

        override fun toString(): String =
            "Failed(${vehicle.name} at $location, failure $failureNumber, repair=$repairTime)"
    }

    /**
     * The vehicle has run out of charge.
     *
     * Nothing a policy can do at the vehicle will change that: charge is a function of the
     * odometers, so the only way back into service is to reach a charger, which means towing. A
     * policy that does nothing leaves the vehicle flat and the framework takes it out of service --
     * which is the right answer and the honest one, because a vehicle that has run flat on a guide
     * path is an obstruction for the rest of the run.
     */
    class OutOfCharge internal constructor(
        vehicle: AgvVehicle,
        at: Double,
        location: String,
        heldZones: List<Zone>,
        wasDoing: TransporterState,
        task: Dispatcher.Task?,
        loadIsAboard: Boolean
    ) : Interruption(vehicle, at, location, heldZones, wasDoing, task, loadIsAboard) {

        override fun toString(): String = "OutOfCharge(${vehicle.name} at $location)"
    }
}

/**
 * What happens when a vehicle stops and cannot carry on by itself.
 *
 * **A process, not a duration**, and that is the whole of the design. The real procedure around a
 * breakdown is: somebody is told, somebody who is free walks to the vehicle, looks at it, decides,
 * perhaps pushes it out of the aisle, repairs it, and puts it back to work. Every step of that is a
 * wait or a branch, and a duration can express none of them.
 *
 * `suspend` is what makes them expressible, exactly as it is for [ksl.modeling.agv.policies.AssignmentPolicyIfc]:
 * a policy that returns immediately is the simple case, and one that first waits for a scarce
 * technician is the general one. They differ only in whether responding takes simulated time.
 *
 * Declared as a member extension on the process builder for the same reason `assign` is:
 * `KSLProcessBuilder` is `@RestrictsSuspension`, so a plain `suspend fun` on this interface would
 * not compile at the call site. Being an extension also means an implementation receives the real
 * process builder and may `seize`, `delay`, `hold` and [tow] -- rather than being confined to
 * whatever a context object thought to offer.
 *
 * The policy runs inside **the vehicle's own agent**, so while it is running the vehicle is doing
 * nothing else, is not available to the dispatcher, and keeps its assignment and its load. A
 * failure interrupts the tour; it does not revoke it.
 *
 * ## What the framework does around it
 *
 * - Books the failure before the call and clears it after, so a policy cannot forget to end a
 *   repair and `FracTimeFailed` spans the whole procedure rather than the repair alone.
 * - Asks whether the vehicle is fit to continue when the policy returns. If it is, the vehicle
 *   re-routes from wherever it now stands and finishes its tour. If it is not, it goes out of
 *   service for the rest of the replication, holding its zones.
 * - Requires the same reproducibility as any other policy: randomness comes from a model-controlled
 *   stream, never a global generator.
 */
fun interface InterruptionPolicyIfc {

    /** @param interruption what happened, where, and what is stuck behind it */
    suspend fun KSLProcessBuilder.handle(interruption: Interruption)
}

/**
 * Repairs a broken vehicle where it stands, and leaves a flat one flat.
 *
 * The default, and it is the whole of what the subsystem did before repair became a procedure: a
 * failure costs the drawn repair time and nothing else -- no technician to wait for, no walk to the
 * vehicle, no decision about whether it is in anybody's way. Every one of those is a delay or a
 * branch a modeller adds by writing their own policy.
 *
 * Doing nothing about a flat battery is not an oversight. Charge is a function of the odometers and
 * no amount of standing at the vehicle changes it, so the only way back into service is to reach a
 * charger. A policy that wants that must tow; this one does not, and the vehicle stays where it
 * stopped for the rest of the replication.
 */
class RepairInPlacePolicy : InterruptionPolicyIfc {

    override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
        when (interruption) {
            is Interruption.Failed -> delay(
                interruption.repairTime,
                suspensionName = "${interruption.vehicle.name}:repair"
            )

            is Interruption.OutOfCharge -> Unit
        }
    }

    override fun toString(): String = "RepairInPlacePolicy"
}
