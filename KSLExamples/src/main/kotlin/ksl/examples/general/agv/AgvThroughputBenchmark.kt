package ksl.examples.general.agv

import ksl.examples.general.guidedpath.GuidedPathThroughputBenchmark
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.agv.policies.NearestVehiclePolicy
import ksl.modeling.agv.policies.ParkInPlaceDisposition
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  The reference throughput benchmark for **active** guided vehicles, on the same layout and the
 *  same fleet as the passive one.
 *
 *  Like its passive counterpart it is **not** a test: it measures wall-clock time, so its answer
 *  belongs to the machine it ran on and has no business failing a build on somebody else's. Run it
 *  with `main`, record the figure alongside the hardware, and compare like with like.
 *
 *  ## What it is for
 *
 *  Two questions, and only the second is about speed.
 *
 *  The first is whether the two paradigms are **comparable at all** on the same work. The network,
 *  the zone count, the fleet size, the velocity and the saturation are shared with
 *  [GuidedPathThroughputBenchmark] -- this file imports its layout rather than restating it, so
 *  they cannot drift apart -- and the traversal count should land in the same neighbourhood.
 *  A large gap would mean the two subsystems are not moving the same vehicles over the same aisles,
 *  which would make every other comparison between them suspect.
 *
 *  The second is **what deciding costs**. The passive fleet is dispatched by a rule evaluated
 *  inside the asking entity's own process; here a dispatcher agent wakes, considers the board, and
 *  awards. That is strictly more machinery, and the honest thing to do with it is measure it rather
 *  than assert it is cheap. Events per zone traversal is where it shows up: the passive engine's
 *  floor is one, and whatever this reports above that is the price of having somewhere to put a
 *  dispatching decision.
 *
 *  ## Saturation, expressed the way this paradigm expresses work
 *
 *  The passive benchmark saturates by re-dispatching each vehicle the instant it arrives, which it
 *  can do because a transporter is a thing you command. Here nobody commands a vehicle: work exists
 *  because a load asked for it. So the load side is what saturates -- a standing population of
 *  loads, each of which asks to be carried somewhere, and on arrival immediately asks again. With
 *  more loads than vehicles the board is never empty and no vehicle is ever idle for want of a task,
 *  which is the same condition the passive benchmark creates from the other end.
 *
 *  [ParkInPlaceDisposition] is deliberate and matches the passive configuration: with the board
 *  never empty, a vehicle is re-assigned the moment it declares itself available, so no disposition
 *  ever runs. Choosing a rule that would send vehicles home would measure a repositioning that a
 *  saturated fleet never does.
 *
 *  The invariant harness is off, as it is there, because it walks every zone and leaving it on
 *  would benchmark the harness. Deadlock detection is left **on**, because that is the configuration
 *  a model actually runs in.
 */
object AgvThroughputBenchmark {

    /** Loads in circulation. More than vehicles, so the board is never empty. */
    const val NUM_LOADS: Int = 40

    /**
     *  The same torus the passive benchmark uses, borrowed rather than rebuilt so that the two
     *  measurements are of one layout and stay that way.
     */
    fun createNetwork(networkName: String = "BenchmarkTorus"): GuidedPathNetwork =
        GuidedPathThroughputBenchmark.createNetwork(networkName)

    private class SaturatedFleet(parent: ModelElement) : ProcessModel(parent, "SaturatedFleet") {

        val network = createNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = NearestVehiclePolicy(), name = "Agv")

        // A stream of its own, so the benchmark repeats exactly and two runs on the same machine
        // differ only in wall-clock time.
        private val stream = RNStreamProvider().rnStream(1)

        val vehicles: List<AgvVehicle> = (0 until GuidedPathThroughputBenchmark.NUM_VEHICLES).map { i ->
            // One vehicle at the head of each of the first twenty links, exactly as the passive
            // benchmark places them, so neither fleet begins with an advantage over the other.
            val r = i / GuidedPathThroughputBenchmark.COLUMNS
            val c = i % GuidedPathThroughputBenchmark.COLUMNS
            AgvVehicle(
                agv, TransporterPlacement.OnZone("E${r}_$c.Zone1"),
                ConstantRV(GuidedPathThroughputBenchmark.VELOCITY), 1, EndOfZoneControl(), "V$i"
            ).apply { dispositionPolicy = ParkInPlaceDisposition() }
        }

        private fun somewhere(): String =
            network.intersections[stream.randInt(0, network.intersections.size - 1)].name

        private inner class Load : Entity() {
            val circulating = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(somewhere())
                while (true) {
                    val there = somewhere()
                    if (there != currentLocation.name) {
                        transportByAgv(agv, destination = there, origin = currentLocation.name)
                    } else {
                        // Asking to be carried where it already stands would be refused, and a load
                        // that stopped asking would quietly shrink the population this claims to run.
                        delay(0.0)
                    }
                }
            }
        }

        override fun initialize() {
            repeat(NUM_LOADS) { activate(Load().circulating) }
        }
    }

    /** What one run measured. The same three quantities the passive benchmark reports. */
    data class Result(
        val zoneTraversals: Double,
        val eventsScheduled: Double,
        val tasksCompleted: Double,
        val wallClockSeconds: Double
    ) {
        val traversalsPerWallClockMinute: Double
            get() = zoneTraversals / wallClockSeconds * 60.0

        val eventsPerTraversal: Double
            get() = if (zoneTraversals > 0.0) eventsScheduled / zoneTraversals else Double.NaN
    }

    /**
     *  Runs the reference configuration.
     *
     *  @param replicationLength how long to run, in simulated minutes
     *  @param replications how many replications to run
     */
    fun run(replicationLength: Double = 200_000.0, replications: Int = 1): Result {
        val m = Model("AgvThroughputBenchmark")
        val fleet = SaturatedFleet(m)
        fleet.agv.checkInvariants = false
        m.numberOfReplications = replications
        m.lengthOfReplication = replicationLength
        val started = System.nanoTime()
        m.simulate()
        val elapsed = (System.nanoTime() - started) / 1e9
        return Result(
            zoneTraversals = fleet.agv.numZoneTraversals.value,
            eventsScheduled = fleet.agv.numEventsScheduled.value,
            tasksCompleted = fleet.agv.dispatcher.numTasksCompleted.value,
            wallClockSeconds = elapsed
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val warmUp = run(replicationLength = 20_000.0)
        println(
            "warm-up (JIT): ${"%,.0f".format(warmUp.zoneTraversals)} traversals in " +
                    "${"%.2f".format(warmUp.wallClockSeconds)} s"
        )
        val active = run()
        val passive = GuidedPathThroughputBenchmark.run()
        val described = createNetwork("Describe")

        println()
        println("AGV throughput benchmark - reference configuration, both paradigms")
        println(
            "  network            : ${GuidedPathThroughputBenchmark.ROWS} x " +
                    "${GuidedPathThroughputBenchmark.COLUMNS} torus, " +
                    "${described.intersections.size} intersections, ${described.links.size} links, " +
                    "${described.zones.size} zones"
        )
        println("  vehicles           : ${GuidedPathThroughputBenchmark.NUM_VEHICLES}, saturated")
        println("  loads circulating  : $NUM_LOADS  (active only; the passive fleet saturates itself)")
        println()
        println("  %-22s %18s %18s".format("", "active", "passive"))
        println(
            "  %-22s %18s %18s".format(
                "zone traversals",
                "%,.0f".format(active.zoneTraversals), "%,.0f".format(passive.zoneTraversals)
            )
        )
        println(
            "  %-22s %18s %18s".format(
                "events scheduled",
                "%,.0f".format(active.eventsScheduled), "%,.0f".format(passive.eventsScheduled)
            )
        )
        println(
            "  %-22s %18s %18s".format(
                "events / traversal",
                "%.3f".format(active.eventsPerTraversal), "%.3f".format(passive.eventsPerTraversal)
            )
        )
        println(
            "  %-22s %18s %18s".format(
                "wall clock (s)",
                "%.2f".format(active.wallClockSeconds), "%.2f".format(passive.wallClockSeconds)
            )
        )
        println(
            "  %-22s %18s %18s".format(
                "traversals / minute",
                "%,.0f".format(active.traversalsPerWallClockMinute),
                "%,.0f".format(passive.traversalsPerWallClockMinute)
            )
        )
        println("  %-22s %18s %18s".format("tasks completed", "%,.0f".format(active.tasksCompleted), "--"))
        println()
        println("  JVM                : ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        println("  OS                 : ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        println("  processors         : ${Runtime.getRuntime().availableProcessors()}")
    }
}
