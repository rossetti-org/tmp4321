package ksl.modeling.queue

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 *  The queue's closing check, and the proof that it can fail.
 *
 *  `numInQ` is maintained beside the list rather than derived from it, which a time-weighted
 *  statistic requires -- it has to be told *when* the value changed, and the list can only say what
 *  it holds. The cost of that is two records of one quantity, and the failure it admits has no
 *  symptom of its own: a queue whose count has drifted from its contents goes on behaving perfectly
 *  and reports numbers that are simply wrong.
 *
 *  Which is why the check needs a demonstration that it fires. The subclass below drifts on purpose,
 *  in the one way the real code could: it takes an item out of the list and does not tell the count.
 */
class QueueAccountingTest {

    private class Shop(parent: ModelElement) : ModelElement(parent, "Shop") {

        /** A queue that can be made to lose track of itself, the way a defect would make it. */
        inner class DriftingQueue(parent: ModelElement) :
            Queue<ModelElement.QObject>(parent, "Drifting") {

            /** Removes an item from the contents without decrementing the count. */
            fun loseOne() {
                if (myList.isNotEmpty()) myList.removeAt(0)
            }
        }

        val q = DriftingQueue(this)

        var drift = true

        /** What the queue said and what it held, sampled at the instant the check looks. */
        var countAtHorizon: Double = -1.0
        var sizeAtHorizon: Int = -1

        // Children are visited before their parent, so by the time this runs the queue's own check
        // has already had its say. After the replication the queue is cleared, which is why the
        // sample has to be taken here rather than read off the model afterwards.
        override fun replicationEnded() {
            countAtHorizon = q.numInQ.value
            sizeAtHorizon = q.size
        }

        override fun initialize() {
            q.enqueue(QObject("A"))
            q.enqueue(QObject("B"))
            schedule(::maybeDrift, 5.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun maybeDrift(event: KSLEvent<Nothing>) {
            if (drift) q.loseOne()
        }
    }

    @Test
    @DisplayName("A queue whose count has drifted from its contents is caught, and says by how much")
    fun driftIsCaught() {
        val m = Model("QueueDrift")
        Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0

        val e = assertFailsWith<QueueAccountingViolation> { m.simulate() }
        val msg = e.message ?: ""
        assertEquals(true, msg.contains("reports 2.0 in queue"), "the count should be named: $msg")
        assertEquals(true, msg.contains("holds 1"), "the contents should be named: $msg")
    }

    @Test
    @DisplayName("A queue that agrees with itself passes, and the check can be switched off")
    fun agreementPassesAndTheCheckIsOptional() {
        val honest = Model("QueueHonest")
        val shop = Shop(honest)
        shop.drift = false
        honest.numberOfReplications = 2
        honest.lengthOfReplication = 20.0
        honest.simulate()
        assertEquals(2.0, shop.countAtHorizon)
        assertEquals(2, shop.sizeAtHorizon)

        // The same drift, unaudited: the run completes, and the queue reports two waiting while
        // holding one. That is what the check exists to stop being the default outcome -- and it is
        // what makes the previous test's failure the check talking rather than the model breaking.
        val quiet = Model("QueueQuiet")
        val quietShop = Shop(quiet)
        quietShop.q.auditAtReplicationEnd = false
        quiet.numberOfReplications = 1
        quiet.lengthOfReplication = 20.0
        quiet.simulate()
        assertEquals(2.0, quietShop.countAtHorizon)
        assertEquals(1, quietShop.sizeAtHorizon)
    }
}
