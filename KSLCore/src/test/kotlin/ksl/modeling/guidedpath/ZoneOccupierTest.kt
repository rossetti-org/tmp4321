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
package ksl.modeling.guidedpath

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Something that is not a vehicle takes a zone, and traffic works around it.
 *
 *  Step three of the general-occupancy design: the holder, the request, and the allocation. The
 *  thing being demonstrated is not that a zone can be blocked -- step two did that with a
 *  population -- but that space can be taken **exclusively** by something with no route, no body
 *  and no journey, and that the subsystem treats it exactly as it treats a parked vehicle without
 *  needing to know the difference.
 *
 *  Geometry throughout: twelve-foot zones at twelve feet a minute, so a zone is one minute, and the
 *  junctions are dimensionless and cost nothing.
 */
class ZoneOccupierTest {

    /** A straight aisle of four zones, with a cart that crosses it and an occupier that takes one. */
    private class Aisle(parent: ModelElement) : ModelElement(parent, "Aisle") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val crew = ZoneOccupier(system, "Crew")

        /** The zone the crew takes: the third along, so the cart is under way before it matters. */
        val closed: Zone get() = network.zone("L1.Zone3")!!

        var requestAt: Double = Double.NaN
        var releaseAt: Double = Double.NaN
        var grantedAtRequest: Boolean? = null

        val cartArrivedAt = mutableListOf<Double>()

        init {
            cart.attachArrivalListener { cartArrivedAt.add(time) }
        }

        override fun initialize() {
            grantedAtRequest = null
            cartArrivedAt.clear()
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
            if (requestAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> ->
                    grantedAtRequest = crew.requestZone(closed).isGranted
                }, requestAt)
            }
            if (releaseAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> -> crew.releaseZone() }, releaseAt)
            }
        }
    }

    private fun run(
        requestAt: Double = Double.NaN,
        releaseAt: Double = Double.NaN,
        length: Double = 40.0,
        replications: Int = 1
    ): Aisle {
        val m = Model("ZoneOccupier")
        val a = Aisle(m)
        a.requestAt = requestAt
        a.releaseAt = releaseAt
        a.system.checkInvariants = true
        m.numberOfReplications = replications
        m.lengthOfReplication = length
        m.simulate()
        return a
    }

    // ---- taking space, and traffic working around it -------------------------------------------

    @Test
    fun `a free zone is granted in the instant it is asked for`() {
        // Asked for at 0.5, while the cart is still crossing Zone1, so there is nothing to drain.
        val a = run(requestAt = 0.5)
        assertEquals(true, a.grantedAtRequest, "an empty zone has nothing to drain")
        assertTrue(a.crew.isHoldingSpace)
        assertSame(a.crew, a.closed.holder, "the zone must name the crew as its holder")
        assertEquals(0.0, a.crew.timeToEngage.withinReplicationStatistic.weightedAverage, 1e-12)
    }

    @Test
    fun `a vehicle is stopped by the occupier and released when it gives the zone back`() {
        val a = run(requestAt = 0.5, releaseAt = 10.0)
        assertEquals(1.0, a.cart.numTimesBlocked.value, 0.0, "the cart must have been stopped once")
        // Settles at the end of Zone2 at 2.0, refused Zone3, waits until 10.0, then two zones.
        assertEquals(listOf(12.0), a.cartArrivedAt)
        assertFalse(a.crew.isHoldingSpace, "the crew gave the zone back")
        assertNull(a.closed.holder)
    }

    @Test
    fun `a vehicle held up by an occupier is not a deadlock`() {
        // The reason `awaitedZone` is always null on an occupier. A holder that never queues has no
        // outgoing edge in the wait-for graph, so no cycle can run through it -- the cart behind it
        // is obstructed, which is a different condition with a different remedy, and reporting a
        // circular wait here would be the one mistake the walk exists to avoid.
        val a = run(requestAt = 0.5, releaseAt = 10.0)
        assertEquals(0.0, a.system.numDeadlocksDetected.value, 0.0)
        assertNull(a.crew.awaitedZone)
    }

    // ---- draining, which is the part that had to be built --------------------------------------

    @Test
    fun `a zone a vehicle is crossing drains rather than being taken from it`() {
        // Asked for at 2.5, while the cart is crossing Zone3 itself. Nothing is evicted: the cart
        // finishes its traversal and leaves, and only then does the crew have the zone.
        val a = run(requestAt = 2.5)
        assertEquals(false, a.grantedAtRequest, "the cart was in it, so there was something to drain")
        // The cart crosses Zone4 and reaches B unimpeded -- the closure does not reach backwards.
        assertEquals(listOf(4.0), a.cartArrivedAt, "the cart must not have been held up at all")
        assertEquals(0.0, a.cart.numTimesBlocked.value, 0.0)
        assertTrue(a.crew.isHoldingSpace, "and the crew has it once the cart has gone")
        // At 2.5 the cart has claimed Zone3 and is travelling into it, arriving at 3.0; under
        // end-of-zone control it gives Zone3 up when it arrives in Zone4, at 4.0. So the crew waits
        // 1.5 minutes, which is the drain this construct exists to do rather than evict.
        assertEquals(1.5, a.crew.timeToEngage.withinReplicationStatistic.weightedAverage, 1e-9)
        assertEquals(1.0, a.crew.numEngagements.value, 0.0)
    }

    @Test
    fun `a reservation beats a vehicle that was already waiting for the zone`() {
        // The property that makes a drain terminate. A zone that merely refused everyone would go
        // to whichever vehicle asked next, and on a busy aisle the closure would never happen.
        //
        // Parked sits on Zone3 from the start. Runner blocks behind it at 2.0. The crew asks for
        // Zone3 at 3.0, while Parked still holds it. At 5.0 Parked is sent away down a second link,
        // so it leaves the aisle entirely rather than settling on Runner's destination, and gives
        // up Zone3 on arriving in Zone4 at 6.0.
        val m = Model("DrainPriority")
        val holder = object : ModelElement(m, "Yard") {
            val network: GuidedPathNetwork = GuidedPathNetwork.builder("Yard")
                .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
                .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
                .build()
            val system = GuidedPathTransportSystem(this, network, name = "Sys")
            val runner = GuidedTransporter(
                system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Runner"
            )
            val parked = GuidedTransporter(
                system, TransporterPlacement.OnZone("L1.Zone3"), ConstantRV(12.0), 1, name = "Parked"
            )
            val crew = ZoneOccupier(system, "Crew")
            val closed: Zone get() = network.zone("L1.Zone3")!!
            val runnerArrived = mutableListOf<Double>()

            init {
                runner.attachArrivalListener { runnerArrived.add(time) }
            }

            override fun initialize() {
                runnerArrived.clear()
                schedule({ _: KSLEvent<Nothing> -> runner.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> crew.requestZone(closed) }, 3.0)
                schedule({ _: KSLEvent<Nothing> -> parked.sendTo("C") }, 5.0)
                schedule({ _: KSLEvent<Nothing> -> crew.releaseZone() }, 20.0)
            }
        }
        holder.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        // The arrival time is the proof, and a stronger one than any end state: Parked gives up
        // Zone3 at 6.0, and had the waiting vehicle been given it then, it would have reached B at
        // 8.0. It arrives at 22.0 instead -- it waited until the crew released at 20.0, which is
        // only possible if the promise beat it to the zone.
        assertEquals(listOf(22.0), holder.runnerArrived)
        assertEquals(1.0, holder.crew.numEngagements.value, 0.0)
        // Asked at 3.0, taken at 6.0 when Parked cleared the zone.
        assertEquals(3.0, holder.crew.timeToEngage.withinReplicationStatistic.weightedAverage, 1e-9)
    }

    @Test
    fun `giving up a request before it is granted reopens the zone and wakes the vehicle`() {
        // A closure cancelled before it took effect. The zone is free, empty and wanted, and every
        // vehicle refused while it was closing is still waiting with nothing scheduled -- so
        // reopening has to go through the same handover a release does.
        val m = Model("AbandonedRequest")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val arrived = mutableListOf<Double>()
        a.cart.attachArrivalListener { arrived.add(a.time) }
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                // Asked for at 0.5 with nothing to drain, so granted at once; given up at 7.0.
                schedule({ _: KSLEvent<Nothing> -> a.crew.requestZone(a.closed) }, 0.5)
                schedule({ _: KSLEvent<Nothing> -> a.crew.releaseZone() }, 7.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertEquals(listOf(9.0), arrived, "the cart waited from 2.0 to 7.0 and then crossed two zones")
        assertNull(a.closed.holder)
        assertNull(a.closed.closingFor)
    }

    // ---- the statistics, which are the point ---------------------------------------------------

    @Test
    fun `the space reports how much of the guide path is closed, and why vehicles are stopped`() {
        val a = run(requestAt = 0.5, releaseAt = 10.0, length = 20.0)

        // One zone closed from 0.5 to 10.0 of a twenty-minute run.
        assertEquals(
            9.5 / 20.0, a.system.numZonesClosed.withinReplicationStatistic.weightedAverage, 1e-9,
            "the mean number of zones closed to traffic"
        )
        // The cart was held up by the occupier from 2.0 to 10.0, and by nothing else ever.
        assertEquals(
            8.0 / 20.0,
            a.system.numBlockedByOccupier.withinReplicationStatistic.weightedAverage, 1e-9
        )
        assertEquals(
            0.0, a.system.numBlockedByVehicle.withinReplicationStatistic.weightedAverage, 1e-12
        )
        assertEquals(
            0.0, a.system.numBlockedByPopulation.withinReplicationStatistic.weightedAverage, 1e-12
        )
        // And the decomposition accounts for all of the blocked time, which is the claim that
        // makes it worth collecting: a residue would mean a cause nobody is naming.
        assertEquals(
            a.system.numTransportersBlocked.withinReplicationStatistic.weightedAverage,
            a.system.numBlockedByOccupier.withinReplicationStatistic.weightedAverage,
            1e-9
        )
    }

    @Test
    fun `an occupier's hold does not count as guide path covered by vehicles`() {
        // `zoneUtilization` measures vehicle bodies and `numZonesClosed` measures space denied by
        // something else. Three things can now make a zone unavailable and collapsing them into one
        // number would lose exactly the decomposition this work exists to expose.
        val a = run(requestAt = 0.5, length = 20.0)
        assertEquals(ZoneState.CLAIMED, a.closed.state, "an occupier reserves space; it has no body")
        assertFalse(a.closed.isCovered)
        assertTrue(a.closed.hasHolder)
        assertFalse(a.closed.isAvailable)
    }

    @Test
    fun `the hold is cleared between replications`() {
        // The fourth instance of a defect family this work has met three times before -- a
        // manifest, a position and a zone population, each left behind by a reset. The occupier's
        // belief about what it holds is a separate copy from the zone's, so both have to be cleared
        // and only a run of more than one replication can show it.
        val a = run(requestAt = 0.5, releaseAt = 10.0, replications = 3)
        assertFalse(a.crew.isHoldingSpace)
        val engagements = a.crew.numEngagements.acrossReplicationStatistic
        assertEquals(3.0, engagements.count, 0.0, "one observation per replication")
        assertEquals(1.0, engagements.average, 1e-12, "the crew took the zone once every replication")
        assertEquals(
            0.0, engagements.variance, 1e-12,
            "a deterministic model must engage identically every replication; any spread means a " +
                    "hold was carried over"
        )
    }
}
