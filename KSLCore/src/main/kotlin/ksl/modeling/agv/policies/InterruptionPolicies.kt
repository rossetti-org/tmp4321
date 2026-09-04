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
package ksl.modeling.agv.policies

import ksl.modeling.agv.Interruption
import ksl.modeling.agv.InterruptionPolicyIfc
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.charge
import ksl.modeling.entity.tow
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Somebody free walks to the stopped vehicle, looks at it, and pushes it out of the aisle if it is
 * in anybody's way.
 *
 * The procedure a site actually runs, written as a process because that is what it is. Every step
 * is a wait or a branch, and the only one the framework contributes is [ksl.modeling.entity.tow].
 *
 * **The order matters and is the point.** The technician is seized *before* the walk, because a
 * person who is busy elsewhere is not walking anywhere yet -- and on a fleet with one technician
 * and several vehicles that wait is most of the answer. The assessment happens *after* the walk,
 * because nobody knows what is wrong until they are standing at it. And whether to move the vehicle
 * is decided from [Interruption.isObstructing], read at that moment: a vehicle broken down on a
 * spur nobody uses can be repaired where it stands, and the same vehicle across a main aisle
 * cannot.
 *
 * **What it does about a flat battery is to tow it to a charger and nothing else.** Charge is a
 * function of the vehicle's odometers, so no amount of standing at it helps; reaching a charger is
 * the only thing that does. If the fleet has no charger this policy leaves the vehicle where it is,
 * which is what the default does too.
 *
 * @param technicians who does the work. A `ResourceWithQ`, so the wait for one is reported as a
 *   queue without this policy having to count anything.
 * @param reportingDelay how long before anybody knows. Zero is a fleet that radios in immediately.
 * @param walkingTime how long it takes to reach the vehicle. A single distribution rather than a
 *   function of where it stopped, which is the right first cut for a site where walking anywhere
 *   takes about as long as walking anywhere else; a model where it does not should subclass.
 * @param assessmentTime how long the look takes.
 * @param refuge where an obstructing vehicle is pushed to. Must be reachable from anywhere a
 *   vehicle can stop, or the tow raises.
 * @param towVelocity how fast a person moves a dead vehicle. Slower than it drives.
 */
open class VisitAndAssessPolicy @JvmOverloads constructor(
    private val technicians: ResourceWithQ,
    private val walkingTime: RVariableIfc,
    private val assessmentTime: RVariableIfc,
    private val refuge: String,
    private val towVelocity: Double,
    private val reportingDelay: RVariableIfc? = null
) : InterruptionPolicyIfc {

    init {
        require(towVelocity > 0.0) { "A tow velocity must be > 0.0, but was $towVelocity." }
    }

    override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
        val vehicle = interruption.vehicle
        reportingDelay?.let { delay(it, suspensionName = "${vehicle.name}:reportingFailure") }
        val technician = seize(technicians, suspensionName = "${vehicle.name}:awaitingTechnician")
        delay(walkingTime, suspensionName = "${vehicle.name}:technicianWalking")
        delay(assessmentTime, suspensionName = "${vehicle.name}:assessing")
        when (interruption) {
            is Interruption.Failed -> {
                // Read now rather than when it stopped: the queue behind it has had the walk and
                // the assessment to build up, and it is the state at the decision that decides.
                if (interruption.isObstructing) {
                    tow(vehicle, refuge, towVelocity)
                }
                delay(interruption.repairTime, suspensionName = "${vehicle.name}:repair")
            }

            is Interruption.OutOfCharge -> {
                val charger = vehicle.system.nearestCharger(vehicle.currentLocationName)
                if (charger != null) {
                    tow(vehicle, charger, towVelocity)
                    charge(vehicle)
                }
            }
        }
        release(technician)
    }

    override fun toString(): String = "VisitAndAssessPolicy(refuge=$refuge, towVelocity=$towVelocity)"
}
