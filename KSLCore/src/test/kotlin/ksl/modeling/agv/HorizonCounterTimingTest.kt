package ksl.modeling.agv

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A count made at the horizon reaches the across-replication statistics.
 *
 *  This pins an ordering dependency that is invisible in the source and silent when broken.
 *
 *  `Counter.replicationEnded` is where a counter records its value for the across-replication
 *  statistics, and `ModelElement.replicationEndedActions` visits children before self. So a count
 *  incremented in `AgvSystem.replicationEnded` lands *after* every counter has already snapshotted:
 *  the within-replication value is correct, the across-replication average is zero, and nothing
 *  anywhere reports a problem. Worse, the failure is invisible on a single-replication run -- the
 *  only kind anybody writes while developing -- and appears only over many, which is exactly the run
 *  where a horizon diagnostic matters.
 *
 *  `AgvSystem` therefore runs its diagnostics from a child element declared **before** the counters,
 *  so it is visited before them. If that field is moved, or the framework's traversal order changes,
 *  this test fails rather than a statistic quietly becoming zero.
 */
class HorizonCounterTimingTest {

    /** A fleet that cannot possibly keep up, so every replication strands the same work. */
    private class Overloaded(parent: ModelElement) : ProcessModel(parent, "Overloaded") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByAgv(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            repeat(8) { i -> activate(Part().p, timeUntilActivation = i * 3.0) }
        }
    }

    @Test
    @DisplayName("Horizon diagnostics appear in the across-replication statistics, not only within one")
    fun horizonCountsSurviveIntoAcrossReplicationStatistics() {
        val m = Model("HorizonTiming")
        val shop = Overloaded(m)
        m.numberOfReplications = 4
        m.lengthOfReplication = 40.0
        m.simulate()

        for (counter in listOf(
            shop.agv.numTasksNeverAssigned,
            shop.agv.numEntitiesNeverResumed,
            shop.agv.numAssignmentsStillOpen
        )) {
            val within = counter.value
            val across = counter.acrossReplicationStatistic
            assertTrue(within > 0.0, "${counter.name} recorded nothing in the last replication")
            assertEquals(4.0, across.count,
                "${counter.name} contributed no observation for each replication")
            assertTrue(across.average > 0.0,
                "${counter.name} has a within-replication value of $within but an across-replication " +
                        "average of ${across.average}. That is the signature of a count made after " +
                        "the counter had already recorded itself: correct on one replication, zero " +
                        "over many, and silent either way.")
            assertEquals(within, across.average, 1e-9,
                "${counter.name}: every replication strands the same work, so the across-replication " +
                        "average should equal the per-replication count")
        }
    }
}
