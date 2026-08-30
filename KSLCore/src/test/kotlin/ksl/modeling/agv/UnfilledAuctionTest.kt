package ksl.modeling.agv

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.agv.policies.Bid
import ksl.modeling.agv.policies.BidPolicyIfc
import ksl.modeling.agv.policies.CallForProposals
import ksl.modeling.agv.policies.ContractNetAssignmentPolicy
import ksl.modeling.agv.policies.NetworkDistanceBid
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
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
 *  An auction nobody bids on is counted, not raised, and the task stays on the board.
 *
 *  A fleet that is out of range, out of charge, or under a rule that makes it unwilling has nothing
 *  to offer, and that is ordinary operation of a negotiated system rather than a fault. Raising here
 *  would be badly wrong: it would turn a modelling situation the subsystem exists to represent --
 *  demand the fleet cannot currently serve -- into a crash, and it would do so in the models where
 *  it matters most.
 *
 *  So the task waits and is auctioned again on the next pass, accruing waiting time all the while,
 *  which is exactly what should happen to work nobody will take. The count exists because a rising
 *  unfilled rate is the earliest sign that a bidding rule has been set too strictly, and nothing
 *  else in the output would say so: the fleet looks idle, the loads look slow, and no single
 *  statistic points at the reason.
 */
class UnfilledAuctionTest {

    /** Declines everything until [until], then bids normally. Silence is how a vehicle declines. */
    private class DeclineUntil(private val until: Double) : BidPolicyIfc {
        override fun bid(vehicle: AgvVehicle, cfp: CallForProposals, network: GuidedPathNetwork): Bid? {
            if (cfp.issuedAt < until) return null
            return NetworkDistanceBid().bid(vehicle, cfp, network)
        }
    }

    private class Shop(parent: ModelElement, declineUntil: Double) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, assignmentPolicy = ContractNetAssignmentPolicy(0.0), name = "Agv"
        )

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME; bidPolicy = DeclineUntil(declineUntil) }

        var result: AgvTransportResult? = null

        /** Board size while the fleet was refusing, so "stayed on the board" is observed and not
         *  inferred from the load eventually being delivered. */
        var boardWhileRefusing = -1
        var declinesWhileRefusing = 0

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                result = transportByAgv(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
            }
        }

        override fun initialize() {
            activate(Part().p)
            schedule(::sample, 25.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            boardWhileRefusing = agv.dispatcher.taskQ.size
            declinesWhileRefusing = cart.agent?.callsDeclined ?: 0
        }
    }

    @Test
    @DisplayName("Every vehicle declining is counted, the task waits, and it is served once bidding resumes")
    fun anUnfilledAuctionIsCountedNotRaised() {
        val m = Model("UnfilledAuction")
        val shop = Shop(m, declineUntil = 50.0)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        // Notably: no exception. The whole point is that this runs.
        m.simulate()

        // The task was on the board while the fleet was refusing it, and the vehicle was being asked
        // rather than being passed over.
        assertEquals(1, shop.boardWhileRefusing,
            "the task did not stay on the board while every vehicle was declining")
        assertTrue(shop.declinesWhileRefusing > 0,
            "the vehicle was never asked while it was set to decline, so nothing was tested")

        // Unfilled auctions were counted, and there were several: the dispatcher keeps asking.
        val unfilled = shop.agv.dispatcher.numAuctionsUnfilled.value
        assertTrue(unfilled > 0.0, "an auction that nobody bid on was not counted")
        assertTrue(shop.agv.dispatcher.numAuctionsRun.value > unfilled,
            "every auction went unfilled, so the fleet never resumed bidding and the run proves " +
                    "only that refusal does not crash")

        // And once bidding resumed the load was served, having waited through the refusal.
        val r = requireNotNull(shop.result) { "the load was never delivered" }
        assertTrue(r.waitForAssignment >= 50.0,
            "the load did not wait through the period in which the fleet refused it: $r")
        assertEquals(1.0, shop.agv.dispatcher.numTasksCompleted.value)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCancelled.value,
            "an unfilled auction cancelled the task rather than leaving it on the board")
    }

    @Test
    @DisplayName("Stranded work is reconsidered on a timer, and the interval must be positive")
    fun strandedWorkIsReconsidered() {
        // The retry is what makes the previous test's load ever get served. Without it the
        // dispatcher, having gone dormant after an unfilled auction, has no future event that would
        // reconsider the task: it is woken by a posting or an availability declaration and by
        // nothing else. In a busy model the next arrival covers for it; in a quiet one an idle fleet
        // sits beside a full board indefinitely, which is the invariant this discharges.
        fun waitWith(interval: Double): Double {
            val m = Model("Retry")
            val shop = Shop(m, declineUntil = 50.0)
            shop.agv.dispatcher.retryInterval = interval
            m.numberOfReplications = 1
            m.lengthOfReplication = 900.0
            m.simulate()
            return requireNotNull(shop.result).waitForAssignment
        }

        val brisk = waitWith(5.0)
        val slow = waitWith(40.0)
        assertTrue(brisk >= 50.0 && slow >= 50.0,
            "the load was assigned before the fleet resumed bidding: $brisk, $slow")
        assertTrue(slow > brisk,
            "the retry interval made no difference, so stranded work is not being reconsidered on " +
                    "a timer at all: brisk=$brisk slow=$slow")

        // A zero interval would reconsider stranded work at the instant it was stranded, which is a
        // busy loop rather than a retry.
        for (bad in listOf(0.0, -1.0)) {
            val m = Model("RetryBad")
            val shop = Shop(m, declineUntil = 50.0)
            val e = runCatching { shop.agv.dispatcher.retryInterval = bad }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException, "a retry interval of $bad should be refused")
        }
    }
}
