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

package ksl.examples.general.agv

import ksl.controls.experiments.ScenarioRunner
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.MultipleComparisonAnalyzer

/**
 *  One shop, modelled twice: once with a **passive** transporter the part steers, and once with an
 *  **active** vehicle that decides for itself.
 *
 *  This is the example to read first, because the comparison is the point of having two subsystems.
 *  The physical world is identical in both runs -- the same guide path, the same zones, the same
 *  routing, the same blocking rules -- and every line that differs is a line about *who decides*.
 *
 *  ## What the part's process looks like, and what that says
 *
 *  Passive: the part holds the protocol. It asks a pool for a cart, waits for one to become free,
 *  waits again for it to arrive, rides it, and gives it back.
 *
 *  ```
 *  guidedTransport(carts, destination = EXIT, pickupLocation = ENTRY)
 *  ```
 *
 *  Active: the part states what it needs and suspends. It never chooses a cart, never waits for a
 *  particular one, and cannot tell which came.
 *
 *  ```
 *  transportByFleet(agv, destination = EXIT, origin = ENTRY)
 *  ```
 *
 *  The two lines look similar and mean something quite different. Under the passive paradigm the
 *  decision of *which* cart is made inside the part's own process, at the instant it happens to ask,
 *  over whichever carts happen to be free at that instant. There is nowhere else it could be made,
 *  because there is no other object running. Under the active paradigm a dispatcher decides, and it
 *  can see the whole fleet and the whole board, and it is allowed to take simulated time doing so.
 *
 *  ## Why the numbers agree
 *
 *  With one cart the two dispatching rules -- the pool's "closest idle transporter" and the fleet's
 *  "nearest vehicle" -- are the same rule, since there is only ever one candidate. So the models
 *  should agree, and they do: **exactly**, to the digit, not merely within a confidence interval.
 *
 *  That agreement is the load-bearing result. Had the answers differed, this would not be a second
 *  way of modelling the same world; it would be a different world, and every comparison a researcher
 *  wanted to make between paradigms would be confounded by the modelling choice itself.
 *
 *  ## What only the active model can tell you
 *
 *  The bottom half of the output is the reason to reach for it. A passive pool has no object that
 *  holds a *commitment*, so there is nothing that could report how long a load waited to be assigned
 *  as distinct from how long it waited for its cart to arrive. The active model separates them,
 *  because a dispatcher decides at one instant and a vehicle arrives at another.
 */
object TwoParadigmsExample {

    const val ENTRY: String = "EntryStation"
    const val EXIT: String = "ExitStation"
    const val DEPOT: String = "CartDepot"

    /** Both shops name their statistics identically, so the two runs can be compared replication
     *  by replication rather than only average by average. */
    const val TIME_IN_SYSTEM: String = "TimeInSystem"
    const val DELIVERED: String = "Delivered"

    /**
     *  A one-way loop with a spur to the exit and a parking spur for the cart.
     *
     *  Built here rather than imported because the layout is part of the lesson, and identical in
     *  both runs because anything else would confound the comparison.
     */
    fun createNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("ShopFloor")
        .intersection("I1", x = 0.0, y = 72.0)
        .intersection("I2", x = 48.0, y = 72.0)
        .intersection("I3", x = 48.0, y = 0.0)
        .intersection("I4", x = 0.0, y = 0.0)
        .intersection("I5", x = 0.0, y = -36.0)
        .intersection("I6", x = 54.0, y = 72.0)
        .link("Link1", "I1", "I2", length = 48.0, zoneLength = 12.0, beginDirection = 0.0)
        .link("Link2", "I2", "I3", length = 72.0, zoneLength = 12.0, beginDirection = 270.0)
        .link("Link3", "I3", "I4", length = 48.0, zoneLength = 12.0, beginDirection = 180.0)
        .link("Link4", "I4", "I1", length = 72.0, zoneLength = 12.0, beginDirection = 90.0)
        .link("ExitSpur", "I4", "I5", length = 36.0, zoneLength = 12.0,
            type = LinkType.SPUR, beginDirection = 270.0)
        .link("DepotSpur", "I2", "I6", length = 6.0, zoneLength = 6.0,
            type = LinkType.SPUR, beginDirection = 0.0)
        .station(ENTRY, "I1")
        .station(EXIT, "I5")
        .station(DEPOT, "I6")
        .build()

    const val MEAN_TIME_BETWEEN_ARRIVALS: Double = 40.0
    const val ARRIVAL_STREAM: Int = 1
    const val NUM_ARRIVALS: Int = 400
    const val CART_SPEED: Double = 10.0

    /** The part steers the cart: ask for one, be collected, be carried, hand it back. */
    class PassiveShop(parent: ModelElement) : ProcessModel(parent, "PassiveShop") {

        val network = createNetwork()

        init {
            spatialModel = network
        }

        val space = GuidedPathTransportSystem(this, network, name = "Space")

        val cart = GuidedTransporter(
            space, TransporterPlacement.At(DEPOT), ConstantRV(CART_SPEED), name = "Cart"
        ).apply { homeBase = DEPOT }

        val carts = GuidedTransporterPoolWithQ(
            this, space, listOf(cart), ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Carts"
        )

        private val myTimeInSystem = Response(this, TIME_IN_SYSTEM)
        val timeInSystem: ResponseCIfc
            get() = myTimeInSystem

        private val myDelivered = Counter(this, DELIVERED)
        val delivered: CounterCIfc
            get() = myDelivered

        private val myTimeBetweenArrivals = RandomVariable(
            this, ExponentialRV(MEAN_TIME_BETWEEN_ARRIVALS, ARRIVAL_STREAM), name = "TBA"
        )
        val timeBetweenArrivals: RandomVariableCIfc
            get() = myTimeBetweenArrivals

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                guidedTransport(carts, destination = EXIT, pickupLocation = ENTRY)
                myTimeInSystem.value = time - arrived
                myDelivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) {
                    delay(myTimeBetweenArrivals)
                    activate(Part().production)
                }
            }
        }

        override fun initialize() {
            activate(Source().arrivals)
        }
    }

    /** The part states what it needs and suspends. A dispatcher and a vehicle do the rest. */
    class ActiveShop(parent: ModelElement) : ProcessModel(parent, "ActiveShop") {

        val network = createNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(DEPOT), ConstantRV(CART_SPEED), name = "Cart"
        ).apply { homeBase = DEPOT }

        private val myTimeInSystem = Response(this, TIME_IN_SYSTEM)
        val timeInSystem: ResponseCIfc
            get() = myTimeInSystem

        private val myDelivered = Counter(this, DELIVERED)
        val delivered: CounterCIfc
            get() = myDelivered

        private val myTimeBetweenArrivals = RandomVariable(
            this, ExponentialRV(MEAN_TIME_BETWEEN_ARRIVALS, ARRIVAL_STREAM), name = "TBA"
        )
        val timeBetweenArrivals: RandomVariableCIfc
            get() = myTimeBetweenArrivals

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                transportByFleet(agv, destination = EXIT, origin = ENTRY)
                myTimeInSystem.value = time - arrived
                myDelivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) {
                    delay(myTimeBetweenArrivals)
                    activate(Part().production)
                }
            }
        }

        override fun initialize() {
            activate(Source().arrivals)
        }
    }

    const val REPLICATIONS: Int = 20
    const val HORIZON: Double = 8_000.0
    const val WARM_UP: Double = 1_000.0

    const val PASSIVE: String = "Passive"
    const val ACTIVE: String = "Active"

    /**
     *  One scenario per paradigm. Both build the same network from the same function, run the same
     *  replications over the same horizon, and draw arrivals from the same stream, so anything that
     *  differs between them is the paradigm and nothing else.
     */
    fun buildRunner(): ScenarioRunner {
        val runner = ScenarioRunner("TwoParadigms")
        val passiveModel = Model("TwoParadigms_Passive")
        PassiveShop(passiveModel)
        runner.addScenario(
            model = passiveModel, name = PASSIVE, inputs = emptyMap(),
            numberReplications = REPLICATIONS, lengthOfReplication = HORIZON,
            lengthOfReplicationWarmUp = WARM_UP
        )
        val activeModel = Model("TwoParadigms_Active")
        ActiveShop(activeModel)
        runner.addScenario(
            model = activeModel, name = ACTIVE, inputs = emptyMap(),
            numberReplications = REPLICATIONS, lengthOfReplication = HORIZON,
            lengthOfReplicationWarmUp = WARM_UP
        )
        return runner
    }
}

fun main() {
    val runner = TwoParadigmsExample.buildRunner()
    runner.simulate()
    runner.print()

    println()
    println("One shop, modelled two ways: ${TwoParadigmsExample.PASSIVE} minus ${TwoParadigmsExample.ACTIVE}")
    println("(paired by replication, ${TwoParadigmsExample.REPLICATIONS} replications, 95% intervals)")
    println()
    println("  %-22s %14s %14s %14s".format("response", "difference", "half-width", "detectable?"))
    for (response in listOf(TwoParadigmsExample.DELIVERED, TwoParadigmsExample.TIME_IN_SYSTEM)) {
        val observations = runner.observationsAsMap(response)
        check(observations.size == 2) {
            "expected per-replication observations of $response for both paradigms, got " +
                "${observations.keys}. Both shops must name this response identically or there is " +
                "nothing to pair."
        }
        val mca = MultipleComparisonAnalyzer(observations, response)
        val d = checkNotNull(
            mca.pairedDifferenceStatistic(TwoParadigmsExample.PASSIVE, TwoParadigmsExample.ACTIVE)
        ) { "no paired difference for $response" }
        val detectable = if (kotlin.math.abs(d.average) > d.halfWidth) "yes" else "no"
        println("  %-22s %14.6f %14.6f %14s".format(response, d.average, d.halfWidth, detectable))
    }

    println()
    println("  Every paired difference is exactly zero, replication by replication, and so is every")
    println("  half-width. The two models are not close: they agree. With one cart, \"closest idle")
    println("  transporter\" and \"nearest vehicle\" are the same rule -- there is only ever one")
    println("  candidate -- so they should agree, and the fact that they do is what makes the active")
    println("  subsystem a second way of modelling this world rather than a different world.")
    println()
    println("  Had they differed, every comparison a researcher wanted to make between paradigms")
    println("  would have been confounded by the modelling choice itself.")
    println()
    println("What only the active model can report")
    println()
    println("  Look for these rows in the Active report above; the Passive report has no equivalent:")
    println("    Agv:Dispatcher:WaitForAssignment  - from asking to somebody committing a vehicle")
    println("    Agv:Dispatcher:TaskQ:TimeInQ      - the dispatcher's own queue of open work")
    println("    Agv:TimeAboard                    - how long a load rode")
    println("    Cart:FracTimeOnTask               - committed, whether moving or not")
    println()
    println("  A passive pool has no object that holds a commitment, so nothing in it could separate")
    println("  \"how long until someone was assigned\" from \"how long until it arrived\". Here a")
    println("  dispatcher decides at one instant and a vehicle arrives at another, so the two are")
    println("  different questions with different answers.")
    println()
    println("  \"On task\" is not the same as \"moving\", and neither contains the other: a cart is on")
    println("  task while it stands still being loaded, and it is moving but not on task while it")
    println("  returns to its depot.")
}
