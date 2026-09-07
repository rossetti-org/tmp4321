package ksl.examples.general.fleet

import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.FreePathFleet
import ksl.modeling.fleet.FreePathVehicle
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A dispatcher, tours and multi-load consolidation over a **plain spatial model** -- no guide path,
 *  no zones, nothing that blocks.
 *
 *  This is the example for the cell most people fall into and least often see written down: they
 *  want a fleet that decides for itself, and their vehicles do not contend for space. A fork-lift
 *  yard, a hospital porter pool, a field-service crew: the vehicles queue for *work*, never for
 *  *aisles*.
 *
 *  ## What is substrate and what is not
 *
 *  Two lines in this file name a substrate:
 *
 *  ```
 *  val fleet = FreePathFleet(this, plane, places, ...)
 *  val cart  = FreePathVehicle(fleet, "Depot", ConstantRV(30.0), ...)
 *  ```
 *
 *  Everything else -- the batching window, the consolidating policy, the load capacity, the
 *  `transportByFleet` call in the part's process, every statistic printed below -- is the fleet
 *  layer and is written exactly as it would be over a guide path. Swapping the two lines above for
 *  `AgvSystem`/`AgvVehicle` and a network is the whole of what it takes to run this study on a
 *  guide path instead. That is what the movement seam bought.
 *
 *  ## What a free path assumes, reported rather than remembered
 *
 *  A free-path vehicle travels straight to where it is going at its own speed and never waits for
 *  another vehicle. That is an assumption, not a fact about a yard, and it is the assumption that
 *  makes free-path fleet-sizing optimistic: buy enough carts and the model will keep rewarding you,
 *  because nothing in it can represent the door they have to queue at.
 *
 *  So `FracTimeBlocked` is registered here and reads **exactly zero for the whole run**. It is not
 *  an oversight that it is present and flat; it is the model's central assumption made visible in
 *  the same row a guide-path run would fill in. If blocking matters to your answer, that row is
 *  telling you this substrate cannot produce it, and [ksl.examples.general.agv] is where to go.
 *
 *  ## What the run shows
 *
 *  The same yard is run twice, differing in one number: how many pallets a cart can hold. Whether
 *  consolidation pays is a property of how loaded the fleet is rather than a law, so the run
 *  reports it rather than the comment asserting it.
 */
class FreePathFleetExample(
    parent: ModelElement,
    cartCapacity: Int,
    name: String,
    private val meanTimeBetweenArrivals: Double = 20.0,
    private val batchWindow: Double = 20.0,
    numCarts: Int = 2
) : ProcessModel(parent, name) {

    private val plane = Euclidean2DPlane()

    // A fleet is written in named places; the spatial model supplies the geometry between them.
    private val places = listOf(
        plane.Point(0.0, 0.0, "Depot"),
        plane.Point(300.0, 0.0, "Press"),
        plane.Point(300.0, 200.0, "Paint"),
        plane.Point(0.0, 200.0, "Ship")
    )

    init {
        spatialModel = plane
    }

    /**
     *  A batching window collects the tasks; the consolidating policy is what fills a cart that
     *  still has room. Both are ordinary fleet-layer policies and neither knows what it is running
     *  over. With a capacity of one the consolidating policy has nothing to consolidate and the
     *  inner rule decides everything, which is why the capacity-one run is a fair baseline rather
     *  than a differently-configured model.
     */
    val fleet = FreePathFleet(
        this, plane, places,
        assignmentPolicy = BatchedAssignmentPolicy(window = batchWindow, inner = ConsolidatingPolicy(NearestVehiclePolicy())),
        name = "Yard"
    )

    val carts = List(numCarts) { i ->
        FreePathVehicle(
            fleet, "Depot", ConstantRV(30.0), name = "Cart${i + 1}",
            loadCapacity = cartCapacity, stepSize = 10.0
        ).apply { homeBase = "Depot" }
    }

    val timeInSystem = Response(this, "${this.name}:TimeInSystem")
    val delivered = Counter(this, "${this.name}:Delivered")

    private val timeBetweenArrivals = ExponentialRV(meanTimeBetweenArrivals, streamNum = 1)

    inner class Pallet : Entity() {
        val movement = process(isDefaultProcess = true) {
            val arrived = time
            currentLocation = fleet.space.requireLocation("Press")
            // States what it needs and suspends. It never chooses a cart.
            transportByFleet(fleet, destination = "Ship", origin = "Press")
            timeInSystem.value = time - arrived
            delivered.increment()
        }
    }

    inner class Source : Entity() {
        val arrivals = process(isDefaultProcess = true) {
            repeat(600) {
                delay(timeBetweenArrivals)
                activate(Pallet().movement)
            }
        }
    }

    override fun initialize() {
        activate(Source().arrivals)
    }
}

fun main() {
    // Two capacities against two batching windows. The window is what collects several tasks so
    // that there is anything to consolidate; capacity is what lets a cart take them.
    val cells = listOf(
        Cell("capacity 1, window 25", run(1, "C1W25", window = 25.0)),
        Cell("capacity 4, window 25", run(4, "C4W25", window = 25.0)),
        Cell("capacity 1, window 40", run(1, "C1W40", window = 40.0)),
        Cell("capacity 4, window 40", run(4, "C4W40", window = 40.0))
    )

    println()
    println("A dispatcher over a plane -- no guide path, nothing that blocks")
    println("Two carts, pallets from Press to Ship, 10 replications of 8000 after a 1000 warm-up")
    println()
    println("  %-24s %10s %10s %10s %10s %8s %9s".format(
        "", "delivered", "in system", "assigned", "aboard", "blocked", "loads/move"))
    for (c in cells) {
        println("  %-24s %10.1f %10.2f %10.2f %10.2f %8.4f %9s".format(
            c.label, c.r.delivered, c.r.timeInSystem, c.r.waitForAssignment, c.r.timeAboard,
            c.r.blocked, c.r.loadsPerLoadedMove?.let { "%.3f".format(it) } ?: "--"))
    }

    println()
    println("Read the throughput column first.")
    println()
    println("  At window 25 the two rows deliver the same load -- %.1f against %.1f -- so the".format(
        cells[0].r.delivered, cells[1].r.delivered))
    println("  times beside them are comparable, and carrying up to four cuts time in system by")
    println("  %.0f%%. At window 40 the capacity-1 fleet delivers %.1f against %.1f: it has fallen".format(
        100.0 * (1.0 - cells[1].r.timeInSystem / cells[0].r.timeInSystem),
        cells[2].r.delivered, cells[3].r.delivered))
    println("  behind, so its time in system is a number about the loads it managed rather than")
    println("  about the fleet. Comparing it with anything would be comparing two different")
    println("  questions. **Check throughput parity before believing a time-in-system comparison.**")
    println()
    println("  The window is a cost paid by every load and redeemed only by capacity: widening it")
    println("  from 25 to 40 makes the capacity-1 fleet much worse and the capacity-4 fleet only")
    println("  slightly worse, because only the latter can do anything with what the window")
    println("  collected. Loads per loaded move says how much of the room was actually used.")
    println()
    println("The blocked column is zero everywhere, and will be in every free-path run.")
    println()
    println("  Nothing here waits for another vehicle -- that is what a free path means. The row is")
    println("  registered and flat on purpose: it is this model's central assumption showing up in")
    println("  the output rather than being left to be remembered. A guide path fills that column")
    println("  in, and the difference between the two is what a substrate comparison measures.")
    println("  See ksl.examples.general.agv for the same machinery where the aisles push back.")
}

private class Cell(val label: String, val r: Result)

private class Result(
    val delivered: Double,
    val timeInSystem: Double,
    val waitForAssignment: Double,
    val timeAboard: Double,
    val movingEmpty: Double,
    val blocked: Double,
    val loadsPerLoadedMove: Double?,
    val capacityUsed: Double?
)

private fun run(capacity: Int, label: String, tba: Double = 20.0, window: Double = 20.0, carts: Int = 2): Result {
    val m = Model("FreePathYard_$label")
    val shop = FreePathFleetExample(m, capacity, label, tba, window, carts)
    m.numberOfReplications = 10
    m.lengthOfReplication = 8000.0
    m.lengthOfReplicationWarmUp = 1000.0
    m.simulate()

    fun avg(name: String): Double? =
        m.response(name)?.acrossReplicationStatistic?.average

    return Result(
        delivered = m.counter("$label:Delivered")?.acrossReplicationStatistic?.average ?: Double.NaN,
        timeInSystem = avg("$label:TimeInSystem") ?: Double.NaN,
        waitForAssignment = shop.fleet.dispatcher.waitForAssignment.acrossReplicationStatistic.average,
        timeAboard = shop.fleet.timeAboard.acrossReplicationStatistic.average,
        movingEmpty = shop.carts[0].fracTimeMovingEmpty.acrossReplicationStatistic.average,
        blocked = shop.carts[0].fracTimeBlocked.acrossReplicationStatistic.average,
        // Registered on the body, so read by row name rather than off the vehicle.
        loadsPerLoadedMove = avg("Cart1:Body:LoadsPerLoadedMove"),
        capacityUsed = avg("Cart1:Body:CapacityUtilization")
    )
}

private fun row(label: String, a: Double, b: Double) {
    println("  %-28s %14.4f %17.4f".format(label, a, b))
}

private fun row(label: String, a: Double?, b: Double?) {
    fun f(v: Double?) = if (v == null) "         --" else "%11.4f".format(v)
    println("  %-28s %17s %17s".format(label, f(a), f(b)))
}
