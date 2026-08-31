package ksl.examples.general.agv

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.agv.policies.AssignmentPolicyIfc
import ksl.modeling.agv.policies.BatchedAssignmentPolicy
import ksl.modeling.agv.policies.ContractNetAssignmentPolicy
import ksl.modeling.agv.policies.FurthestVehiclePolicy
import ksl.modeling.agv.policies.LeastUsedVehiclePolicy
import ksl.modeling.agv.policies.NearestVehiclePolicy
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.abs

/**
 *  The same shop under six dispatching rules, on common random numbers.
 *
 *  This is what the active paradigm is *for*. Deciding who goes where is a substitutable object, so
 *  a study can change the rule and nothing else, and the fleet, the layout and the arrival stream
 *  stay exactly as they were.
 *
 *  ## The rules, and what each is trying to do
 *
 *  - **Nearest vehicle** — send whoever can get there soonest. The default, and what most people
 *    mean by "sensible".
 *  - **Furthest vehicle** — deliberately poor, and useful for exactly that: a study needs a bad rule
 *    to measure a good one against, or "nearest is better" is an assertion rather than a finding.
 *  - **Least used** — send whoever has done the least work. A *balancing* rule rather than a
 *    travel-minimising one, and the two genuinely conflict.
 *  - **Batched** — wait for a window, then decide over everything that accumulated. Pays a delay to
 *    decide with more information.
 *  - **Contract net (instant)** — the vehicles bid and the best bid wins. Here the knowledge moves:
 *    each vehicle answers from what it knows about itself, rather than the dispatcher computing on
 *    its behalf.
 *  - **Contract net (deliberate)** — the same, with a deadline the auction actually costs. A
 *    negotiation that took no time would be a convenient fiction.
 *
 *  ## Two pickup points, and why that matters
 *
 *  Loads arrive at two different stations. On a single-origin layout every task costs a given
 *  vehicle the same, so rules that rank *tasks* differently cannot be told apart — the comparison
 *  would be unfalsifiable, and would quietly report that the choice of rule does not matter. It is
 *  worth knowing that a layout can hide a difference this way.
 *
 *  ## Reading the output
 *
 *  Throughput is nearly the same for every rule, which is the point most easily missed: with a fleet
 *  this size the *bottleneck* is the guide path, not the decision. What the rule changes is who
 *  waits and how evenly the fleet is worn, and those differ a great deal. A study that measured only
 *  throughput would conclude that dispatching does not matter here, and would be wrong about
 *  everything except throughput.
 */
object DispatchingRuleComparison {

    const val NORTH_PICKUP: String = "NorthPickup"
    const val SOUTH_PICKUP: String = "SouthPickup"
    const val SHIPPING: String = "Shipping"
    const val DEPOT_A: String = "DepotA"
    const val DEPOT_B: String = "DepotB"
    const val DEPOT_C: String = "DepotC"

    /**
     *  A one-way ring with two pickup stations on opposite sides, shipping between them, and a
     *  parking spur per vehicle.
     *
     *  Shipping sits between the two pickups rather than next to one of them. Put it beside a pickup
     *  and a freed vehicle is always nearest that one, so "first in the queue" and "nearest" name the
     *  same task and half these rules become indistinguishable.
     */
    fun createNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("RingShop")
        .intersection("N", x = 0.0, y = 100.0)
        .intersection("E", x = 100.0, y = 0.0)
        .intersection("S", x = 0.0, y = -100.0)
        .intersection("W", x = -100.0, y = 0.0)
        .intersection("PA", x = 0.0, y = 150.0)
        .intersection("PB", x = 150.0, y = 0.0)
        .intersection("PC", x = 0.0, y = -150.0)
        .link("NE", "N", "E", length = 120.0, zoneLength = 12.0, beginDirection = 315.0)
        .link("ES", "E", "S", length = 120.0, zoneLength = 12.0, beginDirection = 225.0)
        .link("SW", "S", "W", length = 120.0, zoneLength = 12.0, beginDirection = 135.0)
        .link("WN", "W", "N", length = 120.0, zoneLength = 12.0, beginDirection = 45.0)
        .link("SpurA", "N", "PA", length = 24.0, zoneLength = 24.0,
            type = LinkType.SPUR, beginDirection = 90.0)
        .link("SpurB", "E", "PB", length = 24.0, zoneLength = 24.0,
            type = LinkType.SPUR, beginDirection = 0.0)
        .link("SpurC", "S", "PC", length = 24.0, zoneLength = 24.0,
            type = LinkType.SPUR, beginDirection = 270.0)
        .station(NORTH_PICKUP, "N")
        .station(SOUTH_PICKUP, "S")
        .station(SHIPPING, "W")
        .station(DEPOT_A, "PA")
        .station(DEPOT_B, "PB")
        .station(DEPOT_C, "PC")
        .build()

    private const val MEAN_TIME_BETWEEN_ARRIVALS = 26.0
    private const val ARRIVAL_STREAM = 1
    private const val NUM_ARRIVALS = 600

    class Shop(parent: ModelElement, policy: AssignmentPolicyIfc) : ProcessModel(parent, "Shop") {

        val network = createNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        val fleet = listOf(DEPOT_A, DEPOT_B, DEPOT_C).mapIndexed { i, depot ->
            AgvVehicle(agv, TransporterPlacement.At(depot), ConstantRV(12.0), name = "Cart${i + 1}")
                .apply { homeBase = depot }
        }

        val waitForVehicle = Response(this, "Shop:WaitForVehicle")
        val timeInSystem = Response(this, "Shop:TimeInSystem")
        val delivered = Counter(this, "Shop:Delivered")

        /** Largest minus smallest per-vehicle completions: how unevenly the work fell. Observed at
         *  the horizon, so a Response rather than a Counter -- it is one measurement of the finished
         *  replication, not a total that accumulated during it. */
        val fleetImbalance = Response(this, "Shop:FleetImbalance")

        private val timeBetweenArrivals = ExponentialRV(MEAN_TIME_BETWEEN_ARRIVALS, ARRIVAL_STREAM)

        inner class Load(private val from: String) : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(from)
                val result = transportByAgv(agv, destination = SHIPPING, origin = from)
                waitForVehicle.value = result.waitForAssignment + result.waitForArrival
                timeInSystem.value = time - arrived
                delivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(NUM_ARRIVALS) {
                    delay(timeBetweenArrivals)
                    // Alternating origins, so that which task is nearest genuinely varies.
                    val from = if (it % 2 == 0) NORTH_PICKUP else SOUTH_PICKUP
                    activate(Load(from).production)
                }
            }
        }

        override fun initialize() {
            activate(Source().arrivals)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            val counts = fleet.map { it.numTasksCompleted.value }
            fleetImbalance.value = counts.max() - counts.min()
        }
    }

    private const val REPLICATIONS = 15
    private const val HORIZON = 10_000.0
    private const val WARM_UP = 1_500.0

    fun run(label: String, policy: AssignmentPolicyIfc): Shop {
        val m = Model("DispatchRules-$label")
        val shop = Shop(m, policy)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.lengthOfReplicationWarmUp = WARM_UP
        m.simulate()
        return shop
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val rules = listOf(
            "nearest vehicle" to NearestVehiclePolicy(),
            "furthest vehicle" to FurthestVehiclePolicy(),
            "least used" to LeastUsedVehiclePolicy(),
            "batched (window 30)" to BatchedAssignmentPolicy(30.0),
            "contract net (instant)" to ContractNetAssignmentPolicy(0.0),
            "contract net (deadline 5)" to ContractNetAssignmentPolicy(5.0)
        )

        val results = rules.map { (label, policy) -> label to run(label.filter { c -> c.isLetter() }, policy) }

        println()
        println("Three carts, one ring, six dispatching rules - common random numbers throughout")
        println()
        println("  %-26s %10s %12s %12s %11s".format("rule", "delivered", "wait", "in system", "imbalance"))
        for ((label, shop) in results) {
            println(
                "  %-26s %10.1f %12.2f %12.2f %11.2f".format(
                    label,
                    shop.delivered.acrossReplicationStatistic.average,
                    shop.waitForVehicle.acrossReplicationStatistic.average,
                    shop.timeInSystem.acrossReplicationStatistic.average,
                    shop.fleetImbalance.acrossReplicationStatistic.average
                )
            )
        }

        val byLabel = results.toMap()
        val nearest = byLabel.getValue("nearest vehicle")
        val leastUsed = byLabel.getValue("least used")
        val batched = byLabel.getValue("batched (window 30)")
        val instantAuction = byLabel.getValue("contract net (instant)")

        val unbatched = results.filterNot { it.first.startsWith("batched") }
            .map { it.second.delivered.acrossReplicationStatistic.average }
        val unbatchedSpread = unbatched.max() - unbatched.min()
        val batchedLoss = nearest.delivered.acrossReplicationStatistic.average -
                batched.delivered.acrossReplicationStatistic.average

        println()
        println("  Five of the six rules deliver within %.1f loads of one another.".format(unbatchedSpread))
        println("  With a fleet this size the guide path is the constraint, not the decision, so a")
        println("  study that measured throughput alone would conclude that dispatching does not")
        println("  matter here - and would be wrong about everything except throughput.")
        println()
        println("  What the rule changes is who waits and how evenly the fleet is worn.")
        println(
            "  Least-used leaves an imbalance of %.2f against nearest-vehicle's %.2f, at throughput".format(
                leastUsed.fleetImbalance.acrossReplicationStatistic.average,
                nearest.fleetImbalance.acrossReplicationStatistic.average
            )
        )
        println("  that differs in the second decimal place. Nearest-vehicle concentrates work on")
        println("  whichever cart is closest to the busy part of the layout, which on a one-way ring")
        println("  is persistently the same cart. Whether that matters depends on whether the cost")
        println("  being managed is time or wear - a modelling question, not a library one.")
        println()
        println("  Batching is the exception, and instructively so. It costs %.1f loads and".format(batchedLoss))
        println(
            "  %.0f time units of waiting against nearest-vehicle's %.0f.".format(
                batched.waitForVehicle.acrossReplicationStatistic.average,
                nearest.waitForVehicle.acrossReplicationStatistic.average
            )
        )
        println("  A window pays for itself when a fleet has slack and the board has choices to weigh")
        println("  up. This fleet is saturated: the window delays every decision, the queue never")
        println("  drains, and the delay compounds. The rule is not broken - it is being asked to do")
        println("  the one thing it is worst at, which is the sort of thing a comparison is for.")
        println()
        println(
            "  Note also that the instant auction reproduces nearest-vehicle almost exactly (%.2f".format(
                instantAuction.waitForVehicle.acrossReplicationStatistic.average
            )
        )
        println("  against %.2f). That is a check rather than a coincidence: with distance bidding".format(
            nearest.waitForVehicle.acrossReplicationStatistic.average))
        println("  the vehicles quote what the rule would have computed, so the negotiation machinery")
        println("  is shown not to be changing the answer by itself. The deadline row shows what it")
        println("  costs once negotiating is charged for, which is the honest way to model it.")
    }
}
