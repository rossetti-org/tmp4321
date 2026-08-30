package ksl.modeling.agv

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Nothing is silently lost.
 *
 *  Every failure this guards against produces a run that **completes normally**. That is the whole
 *  difficulty: a fleet too small for its demand, a bidding rule too strict, a station nothing can
 *  reach, a horizon that falls mid-delivery -- none of these raise, none hang, and all of them
 *  report perfectly plausible statistics for the work that *was* done. The averages are computed
 *  over the loads that were served, so a fleet serving a third of its demand can show a better mean
 *  waiting time than one serving all of it.
 *
 *  So the subsystem counts what it did not do, and says so by name. Three situations are exercised
 *  here, chosen because they are lost in three different ways and a modeller acting on the wrong
 *  diagnosis changes the wrong thing:
 *
 *  - a task nothing can reach, which was never assigned at all;
 *  - a horizon falling mid-tour, where the work was under way and the run was too short;
 *  - a task cancelled while a vehicle was committed to it.
 */
class LostWorkTest {

    // ── unreachable ────────────────────────────────────────────────────────────────────────

    private class MaroonedShop(parent: ModelElement) : ProcessModel(parent, "Marooned") {
        val network = FeasibleSetTest.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        // Parked on the island: one way in, no way out. It can reach nothing, ever.
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(FeasibleSetTest.ISLAND), ConstantRV(10.0), name = "Stranded"
        )

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(FeasibleSetTest.NORTH_STATION)
                transportByAgv(
                    agv, FeasibleSetTest.SOUTH_STATION, origin = FeasibleSetTest.NORTH_STATION
                )
            }
        }

        override fun initialize() {
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("A task nothing can reach is counted as never assigned, and the run completes")
    fun anUnreachableTaskIsCountedNotHung() {
        val m = Model("LostUnreachable")
        val shop = MaroonedShop(m)
        m.numberOfReplications = 2
        m.lengthOfReplication = 300.0
        // Completes rather than hanging. That it terminates is half the assertion.
        m.simulate()

        assertEquals(1.0, shop.agv.numTasksNeverAssigned.value,
            "a task that no vehicle could reach was not counted as never assigned")
        assertEquals(1, shop.agv.unfinishedTasksAtHorizon)
        assertEquals(1, shop.agv.loadsAwaitingPickupAtHorizon)
        assertEquals(1.0, shop.agv.numEntitiesNeverResumed.value,
            "the load suspended awaiting a vehicle was not counted")
        assertEquals(0.0, shop.agv.numAssignmentsStillOpen.value,
            "nothing was ever assigned, so no assignment should be open")

        // Every replication, not just the first -- the point of counting rather than flagging.
        assertEquals(2.0, shop.agv.numTasksNeverAssigned.acrossReplicationStatistic.count)
        assertEquals(1.0, shop.agv.numTasksNeverAssigned.acrossReplicationStatistic.average, 1e-9,
            "the same task was stranded in every replication and the counter should say so")

        // And the misleading part is real: nothing completed, so the statistics that were collected
        // describe nothing at all -- no waits, no deliveries -- while the run reports normally.
        assertEquals(0.0, shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count,
            "a wait that never ended is not an observation and must not be counted as one")
        assertEquals(0.0, shop.agv.dispatcher.numTasksCompleted.value)
    }

    // ── cut off mid-tour ───────────────────────────────────────────────────────────────────

    private class BusyShop(parent: ModelElement) : ProcessModel(parent, "Busy") {
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
            repeat(6) { i -> activate(Part().p, timeUntilActivation = i * 5.0) }
        }
    }

    @Test
    @DisplayName("A horizon falling mid-tour reports an open assignment and a load aboard")
    fun aRunCutOffMidTourSaysSo() {
        val m = Model("LostMidTour")
        val shop = BusyShop(m)
        m.numberOfReplications = 3
        // The first delivery completes at 40.2 (198 to the pickup, 204 carrying, at speed 10), so a
        // horizon of 40 would cut the run off before anything finished and this would be the
        // unreachable case wearing different clothes. 70 lands with one delivered, one riding and
        // several still waiting -- which is what "cut off mid-tour" is supposed to mean.
        m.lengthOfReplication = 70.0
        m.simulate()

        assertEquals(1.0, shop.agv.numAssignmentsStillOpen.value,
            "the cart was mid-tour at the horizon and no open assignment was reported")
        assertEquals(1, shop.agv.loadsInTransitAtHorizon)
        assertTrue(shop.agv.numEntitiesNeverResumed.value > 1.0,
            "the load aboard and those still waiting should all be counted: " +
                    "${shop.agv.numEntitiesNeverResumed.value}")

        // Tasks the run ended before reaching are counted as never assigned too, and correctly so
        // -- they were not assigned. What distinguishes this from the unreachable case is not that
        // counter but the two beside it: here work was under way and some of it finished, so there
        // is an open assignment and there are real completed waits. There, neither.
        assertTrue(shop.agv.numTasksNeverAssigned.value > 0.0,
            "tasks the horizon cut off should still be counted as never assigned")
        assertTrue(shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count > 0.0,
            "some loads were collected, so some waits are real observations -- which is what tells " +
                    "a run that was too short from a fleet that could never have served the demand")
        assertTrue(shop.agv.dispatcher.numTasksCompleted.value > 0.0,
            "no load was delivered at all, so this is not the mid-tour case it claims to be")
    }

    // ── cancelled while assigned ───────────────────────────────────────────────────────────

    private class CancellingShop(parent: ModelElement) : ProcessModel(parent, "Cancelling") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        var cancelled = false

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByAgv(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            cancelled = false
            activate(Part().p)
            schedule(::cancelIt, 8.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun cancelIt(event: KSLEvent<Nothing>) {
            val task = agv.dispatcher.board.tasks.firstOrNull() ?: return
            agv.dispatcher.cancel(task)
            cancelled = true
        }
    }

    @Test
    @DisplayName("A task cancelled while assigned leaves its load counted, not silently abandoned")
    fun cancellingAnAssignedTaskIsVisible() {
        val m = Model("LostCancelled")
        val shop = CancellingShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        m.simulate()

        assertTrue(shop.cancelled, "the task was not cancelled, so nothing was tested")
        assertEquals(1.0, shop.agv.dispatcher.numTasksCancelled.value)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCompleted.value,
            "a cancelled task must not also be counted as completed")

        // The load is still suspended: cancelling the task does not resume the entity waiting on it,
        // and pretending otherwise would leave a modeller believing a load was delivered. The
        // diagnostic is what makes that visible rather than a puzzle.
        assertEquals(1.0, shop.agv.numEntitiesNeverResumed.value,
            "the load whose task was cancelled was not reported as still suspended")
        assertEquals(1, shop.agv.loadsAwaitingPickupAtHorizon)

        // The cancelled wait is not an observation: the queue reports nothing rather than a zero.
        assertEquals(0.0, shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count,
            "an abandoned wait was recorded as a served one")
    }
}
