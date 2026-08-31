package ksl.modeling.guidedpath

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.CyclicalTransporterRule
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The same shop, in KSL and in Arena, compared number by number.
 *
 *  This is the cross-check the guided-path plan called Gate B, and it is the only evidence in the
 *  repository that this subsystem means the same thing by an automated guided vehicle as the tool
 *  the field already uses. Everything else the suite asserts, it asserts against itself.
 *
 *  ## Why it can be exact
 *
 *  The Arena model is deterministic: parts are created every 25 minutes from time zero, the load and
 *  unload delays are 20 minutes apiece, and the carts travel at a constant 10. There is nothing
 *  random in it, so there is no confidence interval to hide inside. Either the two tools produce the
 *  same timeline or they do not, and a discrepancy is a discrepancy rather than sampling noise. That
 *  is a much stronger gate than the plan's original "within one standard error", and it is available
 *  only because the source model happens to have been built without randomness.
 *
 *  ## What had to be matched
 *
 *  The network was already identical -- the KSL layout was built from this model -- so what needed
 *  matching was the experiment: `StartOfZoneControl` for Arena's `Zone Control Rule: Start`,
 *  `ParkInPlaceRule` for `When Freed: Remain`, [CyclicalTransporterRule] for the Request module's
 *  `Selection Rule: CYC`, and constant arrivals and delays in place of the exponential ones the
 *  KSL example ships with. The carts start on the I6 and I7 spurs, which the Arena documentation
 *  report does not record -- it prints `Home Station` but never a unit's initial position -- and
 *  which had to be supplied by the model's author.
 *
 *  ## Arena's timing categories, and how they are reproduced
 *
 *  An Arena entity accrues **Wait** while queued for a transporter, **Transfer** from the instant one
 *  is allocated until it is freed, and **Other** during a Delay declared with `Allocation = Other`,
 *  which is what both 20-minute delays are. So `Total = Wait + Transfer + Other`, and the fixture
 *  satisfies that identity exactly. The process below is written in the decomposed verbs rather than
 *  `guidedTransport` precisely so that the instant of allocation is visible and the same three
 *  quantities can be formed here.
 *
 *  ## Where the comparison stands -- Gate B is NOT passed, and the reason is now known
 *
 *  The two tools split cleanly into a half that agrees exactly and a half that does not, and the
 *  half that does not has a single identified cause.
 *
 *  **The fleet agrees exactly.** Total transporter busy time is 1.94791666666667 carts on average,
 *  matching Arena to 3.6e-15 -- 935 cart-minutes over a 480-minute run, to the last bit. Twenty
 *  parts arrive and twelve complete in both.
 *
 *  **The parts do not.** Mean time in system is 131.9 in Arena and 134.3 here; the wait for a cart
 *  is 59.667 against 61.417 and the transfer 32.233 against 32.883. The differences are internally
 *  consistent -- 2.4 = 1.75 + 0.65, and the WIP difference is exactly 12 x 2.4 / 480 -- so this is
 *  one discrepancy seen through several statistics rather than several faults.
 *
 *  ### The cause: Arena's transporter has a physical length, and this one does not
 *
 *  The Arena transporter is sized with the **LENGTH** option at **6 feet**. A vehicle with a physical
 *  extent that is parked at a dead end has already covered its own length of the spur, so **leaving
 *  a spur costs its length less than the spur's declared distance, while entering costs the whole
 *  of it**. That asymmetry, and only that, accounts for every number above:
 *
 *  - *Steady state.* A cart returns from I5 out of the 36-foot exit spur and round to I1. Arena
 *    pays 30 + 72 = 102 units empty against this subsystem's 36 + 72 = 108, then both pay the full
 *    204 loaded. Arena's cycle transfer is therefore 30.6 and this one's 31.2. **Arena's reported
 *    minimum transfer time is 30.5999999999998.**
 *  - *The opening trip.* Cart1 starts on the 6-foot home spur at I6, which its 6-foot body exactly
 *    fills, so leaving costs nothing: 192 units empty against this subsystem's 198. Its first part
 *    is delivered at 79.6 rather than 80.2. **Arena's reported minimum total time is 79.6.**
 *
 *  Two independent quantities predicted exactly from one mechanism, from data that was not used to
 *  find it. Two rival explanations were tested and eliminated: the exit spur's zone structure
 *  (Arena declares it as one zone of 36 where the KSL layout uses three of 12) changes nothing at
 *  all, and simply shortening the spur by 6 over-corrects by exactly the same 6, because a cart
 *  crosses the spur twice per cycle and only the outbound crossing is shortened.
 *
 *  ### What that means
 *
 *  It is a difference in what the two tools can express, not a defect in either. A
 *  [GuidedTransporter] is `lengthInZones` zones long -- a whole number of them -- and its travel is
 *  measured point to point along declared link lengths, with no notion of a body extending back
 *  from where it stands. There is no configuration of this subsystem that reproduces Arena's
 *  numbers, and distorting the network to compensate would be fitting the answer rather than
 *  measuring it.
 *
 *  So Gate B stands unpassed with its discrepancy explained and quantified, and the decision it
 *  waits on -- whether this subsystem should gain a physical transporter length -- belongs with the
 *  plan. Recording the difference explicitly is what §8.5 asks for where the two tools genuinely
 *  differ.
 *
 *  ### One further accounting difference, already settled
 *
 *  The queue observation counts differ, 12 here against 14 in Arena, because an entity that finds a
 *  cart free never enters the KSL pool's queue while Arena records a queue observation for every
 *  request, including the zero-wait ones -- its reported minimum waiting time is 0. The two queue
 *  averages are therefore over different populations and are not directly comparable. It accounts
 *  for some of the gap on `requestQueueWaitingTime` and none of the gap on the entity statistics,
 *  which are over the same twelve completed parts in both tools.
 */
class SimpleAgvArenaCrossCheckTest {

    /** Arena's experiment, in KSL. Every constant here is read off the Arena model report. */
    private class ArenaShop(parent: ModelElement) : ProcessModel(parent, "ArenaShop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(VELOCITY), 1, StartOfZoneControl(), "Cart1"
        )

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(VELOCITY), 1, StartOfZoneControl(), "Cart2"
        )

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            CyclicalTransporterRule(), ParkInPlaceRule(), "Carts"
        )

        val totalTime = Response(this, "TotalTime")
        val transferTime = Response(this, "TransferTime")
        val waitTime = Response(this, "WaitTime")
        val otherTime = Response(this, "OtherTime")
        val wip = TWResponse(this, "WIP")

        var numberIn: Int = 0
            private set
        var numberOut: Int = 0
            private set

        /** arrival, allocation, cart, freed -- for reading the two timelines against each other. */
        val trace = mutableListOf<String>()

        inner class Part : Entity() {
            val make = process("part") {
                val arrived = time
                numberIn++
                wip.increment()
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                // Returns once a cart has been allocated *and* has arrived, which is what Arena's
                // Request module does.
                val request = requestGuidedTransporter(carts, SimpleAgvNetwork.ENTRY_STATION)
                val allocatedAt = request.timeAllocated
                delay(LOAD_DELAY)
                transportBy(request, SimpleAgvNetwork.EXIT_STATION)
                delay(UNLOAD_DELAY)
                val freedAt = time
                releaseGuidedTransporter(request, carts)
                waitTime.value = allocatedAt - arrived
                transferTime.value = (freedAt - allocatedAt) - (LOAD_DELAY + UNLOAD_DELAY)
                otherTime.value = LOAD_DELAY + UNLOAD_DELAY
                totalTime.value = time - arrived
                trace.add(
                    "    arrived %7.2f  allocated %7.2f  by %-6s freed %7.2f".format(
                        arrived, allocatedAt, request.transporter.name, freedAt))
                numberOut++
                wip.decrement()
            }
        }

        override fun initialize() {
            numberIn = 0
            numberOut = 0
            // Arena: Create, Type Constant, Value 25, First Creation 0.0, Max Arrivals Infinite.
            // Over a 480-minute horizon that is exactly twenty parts, at 0, 25, ..., 475.
            var t = 0.0
            while (t < HORIZON) {
                activate(Part().make, timeUntilActivation = t)
                t += TIME_BETWEEN_ARRIVALS
            }
        }

        companion object {
            const val VELOCITY = 10.0
            const val LOAD_DELAY = 20.0
            const val UNLOAD_DELAY = 20.0
            const val TIME_BETWEEN_ARRIVALS = 25.0
            const val HORIZON = 480.0
        }
    }

    private fun arenaFixture(): Map<String, Double> {
        val text = checkNotNull(javaClass.getResourceAsStream("/arena/SimpleAGVExample.csv")) {
            "the Arena fixture is missing from the test resources"
        }.bufferedReader().readText()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it != "statistic,value" }
            .map { it.split(",") }
            .associate { it[0] to it[1].toDouble() }
    }

    @Test
    @DisplayName("KSL and Arena agree exactly on the fleet; the parts' timings are not yet reconciled")
    fun kslComparedWithArena() {
        val arena = arenaFixture()

        val m = Model("ArenaCrossCheck")
        val shop = ArenaShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = ArenaShop.HORIZON
        m.simulate()

        val ksl = linkedMapOf(
            "entityTotalTime" to shop.totalTime.withinReplicationStatistic.weightedAverage,
            "entityTransferTime" to shop.transferTime.withinReplicationStatistic.weightedAverage,
            "entityWaitTime" to shop.waitTime.withinReplicationStatistic.weightedAverage,
            "entityOtherTime" to shop.otherTime.withinReplicationStatistic.weightedAverage,
            "entityWIP" to shop.wip.withinReplicationStatistic.weightedAverage,
            "requestQueueNumberWaiting" to shop.carts.waitingQ.numInQ.withinReplicationStatistic.weightedAverage,
            "requestQueueWaitingTime" to shop.carts.waitingQ.timeInQ.withinReplicationStatistic.weightedAverage,
            "transporterNumberBusy" to
                    (shop.cart1.numBusyUnits.withinReplicationStatistic.weightedAverage +
                            shop.cart2.numBusyUnits.withinReplicationStatistic.weightedAverage),
            "numberIn" to shop.numberIn.toDouble(),
            "numberOut" to shop.numberOut.toDouble(),
            "entityObservations" to shop.totalTime.withinReplicationStatistic.count,
            "queueObservations" to shop.carts.waitingQ.timeInQ.withinReplicationStatistic.count
        )
        ksl["transporterUtilization"] = ksl.getValue("transporterNumberBusy") / 2.0

        // Printed whether it passes or fails. A gate whose evidence is only visible when it breaks
        // is a gate nobody can check.
        println()
        println("  per-part timeline (completed parts only):")
        shop.trace.forEach { println(it) }
        println("  queue observations: ksl=%.0f".format(
            shop.carts.waitingQ.timeInQ.withinReplicationStatistic.count))
        println()
        println("Gate B: the simple AGV example, KSL against Arena (deterministic, 1 rep of 480)")
        println()
        println("  %-28s %20s %20s %14s".format("quantity", "Arena", "KSL", "difference"))
        for ((name, arenaValue) in arena) {
            val kslValue = ksl[name]
            if (kslValue == null) {
                println("  %-28s %20.10f %20s".format(name, arenaValue, "not measured"))
                continue
            }
            println(
                "  %-28s %20.10f %20.10f %14.2e".format(name, arenaValue, kslValue, kslValue - arenaValue)
            )
        }
        println()

        // ---- what this test asserts, and what it does not -------------------------------------
        //
        // Two groups of quantities, and they behave completely differently. Asserting only the
        // group that agrees would be self-serving, so the other is printed above, described in this
        // class's KDoc, and left unasserted until it is understood. Gate B is NOT passed.

        // The fleet. These agree to floating-point noise, which for a deterministic model is
        // agreement: the carts were allocated for the same total time, to the last bit, and the
        // same number of parts got through.
        for (name in listOf("numberIn", "numberOut", "entityObservations")) {
            assertEquals(arena.getValue(name), ksl.getValue(name), "structural mismatch on $name")
        }
        for (name in listOf("transporterNumberBusy", "transporterUtilization")) {
            assertTrue(
                abs(ksl.getValue(name) - arena.getValue(name)) < 1.0e-9,
                "$name: Arena ${arena.getValue(name)} against KSL ${ksl.getValue(name)}"
            )
        }

        // The parts. Unreconciled -- see the KDoc. Asserted only loosely, to catch a regression that
        // moved the answer somewhere else entirely, and deliberately not to a threshold chosen to
        // fit the difference that is there. Gate B's acceptance is a plan-level decision and belongs
        // with the plan, not with whatever this run happened to produce.
        val worst = arena.entries
            .filter { ksl.containsKey(it.key) }
            .maxByOrNull { abs(ksl.getValue(it.key) - it.value) / maxOf(1.0, abs(it.value)) }
        checkNotNull(worst)
        val relative = abs(ksl.getValue(worst.key) - worst.value) / maxOf(1.0, abs(worst.value))
        println("  Largest relative difference: %.3e, on %s".format(relative, worst.key))
        println()
        assertTrue(relative < 0.5, "the two models are no longer describing the same run: ${worst.key}")
    }
}
