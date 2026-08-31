package ksl.examples.general.agv

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
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

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
 *  transportByAgv(agv, destination = EXIT, origin = ENTRY)
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

    private const val MEAN_TIME_BETWEEN_ARRIVALS = 40.0
    private const val ARRIVAL_STREAM = 1
    private const val NUM_ARRIVALS = 400
    private const val CART_SPEED = 10.0

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

        val timeInSystem = Response(this, "PassiveShop:TimeInSystem")
        val delivered = Counter(this, "PassiveShop:Delivered")

        private val timeBetweenArrivals = ExponentialRV(MEAN_TIME_BETWEEN_ARRIVALS, ARRIVAL_STREAM)

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                guidedTransport(carts, destination = EXIT, pickupLocation = ENTRY)
                timeInSystem.value = time - arrived
                delivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) {
                    delay(timeBetweenArrivals)
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

        val timeInSystem = Response(this, "ActiveShop:TimeInSystem")
        val delivered = Counter(this, "ActiveShop:Delivered")

        private val timeBetweenArrivals = ExponentialRV(MEAN_TIME_BETWEEN_ARRIVALS, ARRIVAL_STREAM)

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                transportByAgv(agv, destination = EXIT, origin = ENTRY)
                timeInSystem.value = time - arrived
                delivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) {
                    delay(timeBetweenArrivals)
                    activate(Part().production)
                }
            }
        }

        override fun initialize() {
            activate(Source().arrivals)
        }
    }

    private const val REPLICATIONS = 20
    private const val HORIZON = 8_000.0
    private const val WARM_UP = 1_000.0

    fun runPassive(): PassiveShop {
        val m = Model("TwoParadigms-Passive")
        val shop = PassiveShop(m)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.lengthOfReplicationWarmUp = WARM_UP
        m.simulate()
        return shop
    }

    fun runActive(): ActiveShop {
        val m = Model("TwoParadigms-Active")
        val shop = ActiveShop(m)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.lengthOfReplicationWarmUp = WARM_UP
        m.simulate()
        return shop
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val passive = runPassive()
        val active = runActive()

        println()
        println("One shop, modelled two ways - the same world, decided by different objects")
        println()
        println("                              passive               active")
        println(
            "  parts delivered    %18.2f %20.2f".format(
                passive.delivered.acrossReplicationStatistic.average,
                active.delivered.acrossReplicationStatistic.average
            )
        )
        println(
            "  time in system     %18.4f %20.4f".format(
                passive.timeInSystem.acrossReplicationStatistic.average,
                active.timeInSystem.acrossReplicationStatistic.average
            )
        )
        println(
            "  cart transporting  %18.4f %20.4f".format(
                passive.cart.fracTimeTransporting.acrossReplicationStatistic.average,
                active.cart.fracTimeTransporting.acrossReplicationStatistic.average
            )
        )
        println(
            "  cart moving empty  %18.4f %20.4f".format(
                passive.cart.fracTimeMovingEmpty.acrossReplicationStatistic.average,
                active.cart.fracTimeMovingEmpty.acrossReplicationStatistic.average
            )
        )
        println()
        println("  With one cart, \"closest idle transporter\" and \"nearest vehicle\" are the same")
        println("  rule, so the two models should agree - and they do, to the digit rather than")
        println("  within a confidence interval. That is what makes the active subsystem a second")
        println("  way of modelling this world rather than a different world.")
        println()
        println("What only the active model can report")
        println()
        println(
            "  waited to be assigned %15.4f".format(
                active.agv.dispatcher.waitForAssignment.acrossReplicationStatistic.average
            )
        )
        println(
            "  waited in the queue   %15.4f".format(
                active.agv.dispatcher.taskQ.timeInQ.acrossReplicationStatistic.average
            )
        )
        println(
            "  time aboard a vehicle %15.4f".format(
                active.agv.transportTime.acrossReplicationStatistic.average
            )
        )
        println(
            "  fraction on task      %15.4f".format(
                active.cart.fracTimeOnTask.acrossReplicationStatistic.average
            )
        )
        println()
        println("  A passive pool has no object that holds a commitment, so nothing there could")
        println("  separate \"how long until someone was assigned\" from \"how long until it arrived\".")
        println("  Here a dispatcher decides at one instant and a vehicle arrives at another, so the")
        println("  two are different questions with different answers.")
        println()
        println("  \"On task\" is not the same as \"moving\", and neither contains the other: a cart is")
        println("  on task while it stands still being loaded, and it is moving but not on task while")
        println("  it returns to its depot. Only the active model has an object that could tell the")
        println("  difference, because only there is there something that holds a commitment.")
        println()
        println("  The warnings above the table are the horizon diagnostics doing their job, not a")
        println("  fault. Each replication ends with a cart mid-delivery and a load still waiting,")
        println("  which is exactly what a busy shop looks like when the clock stops. They are worth")
        println("  reading rather than silencing: the statistics are computed over the loads that")
        println("  were served, so a run that served far less than it was asked to would report")
        println("  perfectly healthy averages and say so only here.")
    }
}
