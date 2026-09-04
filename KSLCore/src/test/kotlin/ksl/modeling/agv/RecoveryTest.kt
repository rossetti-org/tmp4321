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
package ksl.modeling.agv

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.agv.policies.ChargeReservePolicy
import ksl.modeling.agv.policies.NearestVehiclePolicy
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.charge
import ksl.modeling.entity.tow
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  What happens around a breakdown: somebody is told, somebody comes, and the vehicle is pushed out
 *  of the way if it is in anybody's.
 *
 *  Repair used to be a duration. It is now a **procedure** -- an [InterruptionPolicyIfc] that runs
 *  as a process inside the vehicle's own agent and may wait, branch, and move the vehicle. The
 *  single most important thing about that change is what it did *not* do, and the whole of
 *  `FailureTest` and `BatteryTest` passing unchanged is the assertion: the default policy delays for
 *  the drawn repair time and nothing else, which is exactly what the subsystem did before.
 *
 *  ## The one framework change everything here rests on
 *
 *  A tour leg is now **re-issued until the vehicle actually arrives**. The old loop assumed that
 *  when its movement hold returned, the vehicle was at the stop -- true only while a repaired
 *  vehicle resumed the route it never gave up. The moment a vehicle can be pushed somewhere while
 *  it is broken, that route is dead and the assumption is false; the loop would pick up or set down
 *  in the wrong place.
 *
 *  A tour survives being moved because **a tour names stops, not routes**. That was `ADR-4`'s claim
 *  about the control loop and this is the case that tests it: a vehicle pushed to a spur half way
 *  to a pickup re-routes from the spur and collects the load, with its cursor untouched and its
 *  assignment never revoked.
 *
 *  It also forces the per-carry distance and zone count off the **odometers** rather than off the
 *  route, because a leg that took two journeys has no single route to ask. The odometer difference
 *  is right under interruption and under a mid-leg redirection alike, which the route figure was
 *  not -- and the first test here pins it against arithmetic on an uninterrupted run, which is the
 *  case where the two must agree exactly.
 */
class RecoveryTest {

    private companion object {
        const val VELOCITY = 3.0
        const val TOW_VELOCITY = 1.0

        /**
         *  Entry to exit the long way round: `I1`-`I2` 48, `I2`-`I3` 72, `I3`-`I4` 48, spur 36.
         *  Every carry on this layout covers exactly this, so it is an identity and not an average.
         */
        const val CARRY_DISTANCE = 48.0 + 72.0 + 48.0 + 36.0

        fun mean(r: ResponseCIfc) = r.withinReplicationStatistic.weightedAverage
        fun total(c: CounterCIfc) = c.value
    }

    /** Samples the fleet on a fixed grid, which is how a state with no events of its own is seen. */
    private class Sampler(parent: ModelElement, val every: Double, val take: () -> Unit) :
        ModelElement(parent, "Sampler") {

        private val tick = object : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                take()
                schedule(this, every)
            }
        }

        override fun initialize() {
            schedule(tick, every)
        }
    }

    /**
     *  Pushes the vehicle to a refuge whenever it is in somebody's way, and counts the pushes.
     *
     *  Deliberately simpler than the shipped `VisitAndAssessPolicy`: no technician, no walk, no
     *  assessment, so that a test comparing it against no towing at all is comparing towing and
     *  nothing else.
     */
    private class TowIfObstructing(
        private val refuge: String,
        private val alwaysTow: Boolean = false
    ) : InterruptionPolicyIfc {

        var tows = 0
            private set
        var obstructingWhenItStopped = 0
            private set

        fun reset() {
            tows = 0
            obstructingWhenItStopped = 0
        }

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            if (interruption.isObstructing) obstructingWhenItStopped++
            if (alwaysTow || interruption.isObstructing) {
                tows++
                tow(interruption.vehicle, refuge, TOW_VELOCITY)
            }
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
        }
    }

    /**
     *  The chapter's shop. `I6` is the first cart's own home spur and is used as the refuge, since a
     *  spur off the loop is exactly where a broken vehicle would be pushed to.
     */
    private class Shop(
        parent: ModelElement,
        failure: FailureModel?,
        policy: InterruptionPolicyIfc?,
        meanInterarrival: Double,
        secondCart: Boolean = false,
        battery: Battery? = null,
        charger: String? = null,
        reserve: Boolean = false
    ) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, name = "Agv",
            assignmentPolicy = if (reserve) ChargeReservePolicy(NearestVehiclePolicy())
            else NearestVehiclePolicy()
        )

        init {
            charger?.let { agv.addCharger(it) }
        }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY),
            name = "Cart", failureModel = failure, battery = battery
        ).apply {
            homeBase = SimpleAgvNetwork.AGV1_HOME
            if (policy != null) interruptionPolicy = policy
        }

        val cart2: AgvVehicle? = if (!secondCart) null else AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME), ConstantRV(VELOCITY), name = "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        var completions = 0

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByAgv(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                completions++
            }
        }

        private val tba = ExponentialRV(meanInterarrival, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba)

        override fun initialize() {
            completions = 0
        }
    }

    private fun run(name: String, horizon: Double, replications: Int = 1, build: (Model) -> Shop): Shop {
        val m = Model(name)
        val s = build(m)
        m.numberOfReplications = replications
        m.lengthOfReplication = horizon
        m.simulate()
        return s
    }

    // ---- 1: the odometer is the route, when nothing interrupts ----------------------------------

    @Test
    @DisplayName("loaded distance off the odometer is the route's own length, to the digit")
    fun theOdometerAgreesWithTheRouteOnAnUninterruptedRun() {
        val s = run("Odometer", horizon = 2000.0) { m ->
            Shop(m, failure = null, policy = null, meanInterarrival = 90.0)
        }

        assertTrue(s.completions > 10, "only ${s.completions} loads were delivered")
        // Every carry on this layout runs the same way round, so this is an identity. It is what
        // says the change from `route.totalLength` to a difference of odometers moved nothing in
        // the ordinary case -- which is the half of the change that has to be invisible.
        assertEquals(
            CARRY_DISTANCE, mean(s.agv.routeLengthPerTransport), 1.0e-9,
            "a carry covered ${mean(s.agv.routeLengthPerTransport)} rather than the $CARRY_DISTANCE " +
                    "feet from the entry station to the exit station the long way round"
        )
        assertTrue(
            mean(s.agv.zonesTraversedPerTransport) > 0.0,
            "no zones were counted for a carry that covered $CARRY_DISTANCE feet"
        )
    }

    // ---- 2: the payoff -- towing a broken vehicle out of the aisle -------------------------------

    @Test
    @DisplayName("pushing a broken vehicle onto a spur unblocks the fleet behind it")
    fun towingOutOfTheAisleUnblocksTheFleet() {
        fun trial(policy: InterruptionPolicyIfc?): Shop = run("Tow${policy != null}", horizon = 4000.0) { m ->
            Shop(
                m, FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(200.0)),
                policy, meanInterarrival = 40.0, secondCart = true
            )
        }

        val stuck = trial(null)
        val policy = TowIfObstructing(SimpleAgvNetwork.AGV1_HOME, alwaysTow = true)
        val towed = trial(policy)

        assertTrue(total(stuck.cart.numFailures!!) > 0.0, "the first cart never failed")
        assertTrue(total(towed.cart.numFailures!!) > 0.0, "the first cart never failed")
        assertTrue(policy.tows > 5, "the broken cart was pushed only ${policy.tows} times")

        // The second cart has no failure model of its own, so anything that stopped it came from
        // outside itself: on a one-way loop, that is the first cart standing in the way.
        val blockedWhenStuck = mean(stuck.cart2!!.fracTimeBlocked)
        val blockedWhenTowed = mean(towed.cart2!!.fracTimeBlocked)
        assertTrue(
            blockedWhenStuck > 0.0,
            "the control case did not block at all, so there was nothing for towing to improve"
        )
        assertTrue(
            blockedWhenTowed < blockedWhenStuck,
            "pushing the broken cart onto a spur left the healthy one blocked for " +
                    "$blockedWhenTowed of its time against $blockedWhenStuck when it was left in " +
                    "the aisle. The payoff of the whole feature is that this number goes down"
        )
        // And the fleet does more work for it, which is the reason anybody would bother.
        assertTrue(
            towed.completions > stuck.completions,
            "the fleet delivered ${towed.completions} loads with towing against ${stuck.completions} " +
                    "without, so clearing the aisle bought nothing"
        )
    }

    // ---- 2b: when to ask whether it is in the way -------------------------------------------------

    /** Waits, then asks whether anybody is stuck behind the vehicle. */
    private class LooksAfter(private val pause: Double) : InterruptionPolicyIfc {
        var sawObstruction = 0
            private set
        var calls = 0
            private set

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            calls++
            if (pause > 0.0) {
                delay(pause, suspensionName = "${interruption.vehicle.name}:beforeLooking")
            }
            if (interruption.isObstructing) sawObstruction++
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
        }
    }

    @Test
    @DisplayName("a queue behind a stopped vehicle takes time to form, so asking at once says no")
    fun obstructionIsSeenOnlyAfterTimePasses() {
        fun trial(pause: Double): LooksAfter {
            val policy = LooksAfter(pause)
            run("Obstruction$pause", horizon = 4000.0) { m ->
                Shop(
                    m, FailureModel.distanceBased(ConstantRV(150.0), ConstantRV(200.0)),
                    policy, meanInterarrival = 40.0, secondCart = true
                )
            }
            return policy
        }

        val atOnce = trial(0.0)
        val afterAWhile = trial(40.0)

        assertTrue(atOnce.calls > 5 && afterAWhile.calls > 5, "too few breakdowns to compare")
        // The trap, pinned. `isObstructing` is a live query, and at the instant a vehicle stops
        // nobody has had time to arrive behind it: the answer is no, on a layout where the same
        // vehicle goes on to block the fleet for three quarters of the run. A policy that branches
        // on it must let time pass first -- which a realistic one does, because somebody has to be
        // told, be free, and walk there before anybody decides anything.
        assertEquals(
            0, atOnce.sawObstruction,
            "asked at the instant it stopped, the vehicle was found to be obstructing " +
                    "${atOnce.sawObstruction} times out of ${atOnce.calls}. If this is no longer " +
                    "zero the guidance on Interruption.isObstructing needs revisiting"
        )
        assertTrue(
            afterAWhile.sawObstruction > 0,
            "after waiting, the vehicle was never once found to be in anybody's way, over " +
                    "${afterAWhile.calls} breakdowns on a layout where it blocks the fleet for most " +
                    "of the run"
        )
    }

    // ---- 3: a moved vehicle still finishes its tour ----------------------------------------------

    @Test
    @DisplayName("a vehicle pushed to a spur mid-tour re-routes and finishes the tour")
    fun aTowedVehicleFinishesItsTour() {
        val policy = TowIfObstructing(SimpleAgvNetwork.AGV2_HOME, alwaysTow = true)
        val caught = mutableMapOf<String, Dispatcher.Task>()
        var lastInHand: Dispatcher.Task? = null

        val s = run("TowedTour", horizon = 8000.0) { m ->
            val shop = Shop(
                m, FailureModel.distanceBased(ConstantRV(250.0), ConstantRV(10.0)),
                policy, meanInterarrival = 90.0
            )
            Sampler(shop, 0.25) {
                lastInHand = shop.cart.currentAssignment?.task ?: lastInHand
                if (!shop.cart.isFailed) return@Sampler
                shop.cart.currentAssignment?.task?.let { caught[it.name] = it }
            }
            shop
        }

        assertTrue(policy.tows > 10, "the vehicle was pushed only ${policy.tows} times")
        assertTrue(caught.isNotEmpty(), "no sample caught the vehicle broken down holding a task")
        // Every task in hand when the vehicle was pushed off the aisle was finished from wherever it
        // was pushed to -- with the single exception the horizon always allows.
        val open = caught.values.filter { it.state != TaskState.COMPLETED }
        assertTrue(
            open.all { it === lastInHand },
            "tasks the vehicle was holding when it was towed did not reach their destination: " +
                    open.joinToString { "${it.name}=${it.state}" }
        )
        assertEquals(
            0.0, total(s.agv.dispatcher.numAssignmentsRevoked), 0.0,
            "moving a vehicle revoked its assignment. It keeps its load: the tour names stops, not " +
                    "routes, so being somewhere else is not a reason to give the work back"
        )
        assertTrue(s.completions > 10, "only ${s.completions} loads were delivered")
    }

    // ---- 4: a scarce technician ------------------------------------------------------------------

    /** Waits for one of a shared pool before doing anything, which is the whole point of it. */
    private class NeedsATechnician(
        private val technicians: ResourceWithQ,
        private val walk: Double
    ) : InterruptionPolicyIfc {

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            val tech = seize(technicians, suspensionName = "${interruption.vehicle.name}:awaitingTechnician")
            delay(walk, suspensionName = "${interruption.vehicle.name}:walking")
            if (interruption is Interruption.Failed) {
                delay(interruption.repairTime, suspensionName = "${interruption.vehicle.name}:repair")
            }
            release(tech)
        }
    }

    private class TwoCartShop(parent: ModelElement, technicianCount: Int) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val technicians = ResourceWithQ(this, name = "Technicians", capacity = technicianCount)
        private val failure = FailureModel.distanceBased(ConstantRV(120.0), ConstantRV(30.0))
        private val policy = NeedsATechnician(technicians, walk = 5.0)

        val carts = listOf(
            SimpleAgvNetwork.AGV1_HOME to "Cart1",
            SimpleAgvNetwork.AGV2_HOME to "Cart2"
        ).map { (home, name) ->
            AgvVehicle(
                agv, TransporterPlacement.At(home), ConstantRV(VELOCITY), name = name,
                failureModel = failure
            ).apply {
                homeBase = home
                interruptionPolicy = policy
            }
        }

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByAgv(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        private val tba = ExponentialRV(30.0, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba)
    }

    @Test
    @DisplayName("one technician serialises two breakdowns, and the wait is on the report")
    fun aScarceTechnicianIsAQueue() {
        fun run(count: Int): TwoCartShop {
            val m = Model("Technicians$count")
            val shop = TwoCartShop(m, count)
            m.numberOfReplications = 1
            m.lengthOfReplication = 4000.0
            m.simulate()
            return shop
        }

        val plenty = run(2)
        val scarce = run(1)

        for (s in listOf(plenty, scarce)) {
            assertTrue(
                s.carts.all { total(it.numFailures!!) > 0.0 },
                "a cart never failed, so this comparison is not exercising anything"
            )
        }
        // With one technician the vehicles queue for it, and the queue is an ordinary resource
        // queue -- which is the argument for making repair a process rather than a duration. The
        // subsystem counts nothing here; `ResourceWithQ` does.
        assertTrue(
            scarce.technicians.waitingQ.timeInQ.withinReplicationStatistic.count > 0.0,
            "with a single technician, no vehicle ever waited for it"
        )
        // The whole out-of-service interval, which is what the row is named for: with one technician
        // it includes the wait, and with two it does not.
        val outWhenScarce = mean(scarce.carts[0].timeOutOfService!!)
        val outWhenPlenty = mean(plenty.carts[0].timeOutOfService!!)
        assertTrue(
            outWhenScarce > outWhenPlenty,
            "a vehicle was out of service for $outWhenScarce with one technician and $outWhenPlenty " +
                    "with two. TimeOutOfService is the whole procedure, so the wait for a scarce " +
                    "technician has to show up in it"
        )
        assertTrue(
            outWhenPlenty >= 35.0,
            "with a technician always free the vehicle should be out for the walk plus the repair, " +
                    "which is 35, but it was out for $outWhenPlenty"
        )
    }

    // ---- 5: what C2 could not express at all -----------------------------------------------------

    @Test
    @DisplayName("a flat vehicle can be pushed to a charger and put back to work")
    fun aFlatVehicleCanBeRecovered() {
        // Deliberately no reserve policy, so the vehicle does run flat. Under the default
        // interruption policy that is the end of its replication: it stands where it stopped,
        // holding its zones. Here it is pushed to the charger instead.
        val battery = Battery(capacity = 260.0, chargePerDistance = 0.5, chargingRate = 50.0)

        val abandoned = run("Abandoned", horizon = 3000.0) { m ->
            Shop(m, failure = null, policy = null, meanInterarrival = 40.0,
                battery = battery, charger = SimpleAgvNetwork.AGV2_HOME)
        }
        val recovered = run("Recovered", horizon = 3000.0) { m ->
            Shop(
                m, failure = null,
                policy = TowToChargerPolicy(), meanInterarrival = 40.0,
                battery = battery, charger = SimpleAgvNetwork.AGV2_HOME
            )
        }

        assertTrue(
            mean(abandoned.agv.numVehiclesStranded) > 0.0,
            "the control case did not strand its vehicle, so there was nothing to recover"
        )
        assertEquals(
            0.0, mean(recovered.agv.numVehiclesStranded), 0.0,
            "a vehicle pushed to a charger and recharged was still reported as stranded at the horizon"
        )
        assertTrue(
            total(recovered.cart.numChargingSessions!!) > 1.0,
            "the recovered vehicle charged only ${total(recovered.cart.numChargingSessions!!)} times"
        )
        assertTrue(
            recovered.completions > 3 * abandoned.completions,
            "the recovered fleet delivered ${recovered.completions} loads against " +
                    "${abandoned.completions} for one abandoned where it stopped. Recovering a flat " +
                    "vehicle is supposed to be worth several times the run"
        )
    }

    /** Pushes a flat vehicle to the nearest charger and fills it. Ignores everything else. */
    private class TowToChargerPolicy : InterruptionPolicyIfc {
        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            if (interruption !is Interruption.OutOfCharge) return
            val vehicle = interruption.vehicle
            val charger = vehicle.system.nearestCharger(vehicle.currentLocationName) ?: return
            tow(vehicle, charger, TOW_VELOCITY)
            charge(vehicle)
        }
    }

    // ---- 6: the replication boundary --------------------------------------------------------------

    @Test
    @DisplayName("nothing about a recovery survives a replication boundary")
    fun threeReplicationsAreIdentical() {
        val policy = TowIfObstructing(SimpleAgvNetwork.AGV1_HOME, alwaysTow = true)
        val s = run("Reset", horizon = 2000.0, replications = 3) { m ->
            val shop = Shop(
                m, FailureModel.usageBased(ConstantRV(2.0), ConstantRV(5.0)), policy,
                meanInterarrival = 90.0
            )
            shop
        }

        val across = s.cart.numFailures!!.acrossReplicationStatistic
        assertEquals(3.0, across.count, 0.0, "three replications did not produce three observations")
        assertTrue(across.average > 0.0, "no replication produced a failure")
        // Common random numbers make the three replications different experiments, so this is not
        // an equality. What it rules out is state carried over a boundary -- a tow left half done,
        // an odometer not cleared, a vehicle still holding the out-of-service queue -- which would
        // show as one replication unlike the others rather than as ordinary spread.
        assertTrue(
            across.min > 0.0,
            "a replication produced no failures at all while the others did, which is the shape a " +
                    "leaked state has: ${across.min} to ${across.max}"
        )
        assertEquals(
            0.0, mean(s.agv.numVehiclesFailedAtHorizon) % 1.0, 1.0e-12,
            "the number of vehicles under repair at the horizon was not a whole number"
        )
    }
}
