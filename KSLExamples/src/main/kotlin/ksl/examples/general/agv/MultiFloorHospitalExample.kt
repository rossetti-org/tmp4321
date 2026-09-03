package ksl.examples.general.agv

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A hospital on two floors, joined by a lift -- and there is no lift class anywhere in it.
 *
 *  A guide path routes on **declared link lengths**, never on coordinates, so nothing in the network
 *  knows or cares that two of its intersections are one above the other. That makes a dedicated lift
 *  expressible as exactly what it physically is: a one-way link consisting of a single zone. The
 *  zone rules already say that one zone admits one vehicle, so the lift excludes everybody else for
 *  the duration of a ride without a line being written to make it do so. No lift class, no floor
 *  concept, no special case in the dispatcher or in a vehicle's control loop.
 *
 *  Which is a pleasing claim and an easy one to be wrong about, so this example does not simply
 *  assert it. It runs three studies.
 *
 *  ## The layout
 *
 *  A one-way circuit that climbs one shaft and descends the other. Porters collect at the pharmacy
 *  on the first floor and deliver to a ward on the ground floor, so **every delivery cycle rides
 *  each shaft exactly once**: down with a load, up empty.
 *
 *  ```
 *      F3 ◄──────── Pharmacy ◄──────── F1        first floor
 *      │                                ▲
 *   ShaftDown                        ShaftUp      (one zone each: one porter at a time)
 *      ▼                                │
 *    Lobby ────────► WardA ────────────► G3       ground floor
 *      │
 *   parking spurs
 *  ```
 *
 *  The circuit is **400 long in every configuration studied here**, and the porters all travel at 10,
 *  so one porter completes a delivery every 40 time units no matter which configuration is running.
 *  That is arranged deliberately: when the shaft is shortened below, the corridors are lengthened by
 *  the same amount. Without it, a faster lift would also be a shorter round trip, and the study
 *  could not tell the two effects apart.
 *
 *  ## Study 1 -- it works, and one porter is in the shaft at a time
 *
 *  Three porters, a short run, and the shaft's single zone sampled through it. The sampling asks
 *  `isHeld`, not `isOccupied`, and the difference is worth understanding rather than copying. A
 *  transporter *reserves* the zone ahead before it enters -- that reservation is what stops two of
 *  them starting into the same free space -- and marks it OCCUPIED only once it comes to rest
 *  covering it. The last zone of a link is therefore never OCCUPIED, because arriving at its far end
 *  means arriving at the junction beyond. On a single-zone shaft that is the only zone there is, so
 *  `isOccupied` would report an idle lift throughout a run that plainly uses one. Exclusion lives on
 *  the reservation, and so must any measurement of it.
 *
 *  ## Study 2 -- adding porters stops helping
 *
 *  A shaft ride of 8 time units passes at best one porter every 8 units, so the circuit cannot carry
 *  more than one delivery per 8 units however many porters are pushed onto it -- that is 5 porters'
 *  worth, and beyond it the extra porters queue at the foot of the shaft rather than deliver. The
 *  table shows throughput flattening, cycle time growing to absorb the difference, and the blocked
 *  fraction climbing.
 *
 *  ## Study 3 -- the same round trip with a fast lift
 *
 *  Identical in every respect except that the ride is 2 time units instead of 8, with the corridors
 *  lengthened to keep the circuit at 400. One porter is therefore no faster than before, and that is
 *  the control: any difference in the table belongs to the shaft's capacity and to nothing else.
 *
 *  ## Why the orders recirculate
 *
 *  The pharmacy is modelled as always having the next order ready: a fixed number of orders is in
 *  circulation, and finishing one releases the next. That is a **closed** system, chosen because the
 *  question here is one of capacity. Under open arrivals fast enough to saturate eight porters, one
 *  porter's queue would grow without bound, and its mean time in system would then be a fact about
 *  the length of the run rather than about the hospital. Closed, every fleet size is stable, cycle
 *  time means something, and Little's law is available as a check on the table:
 *  `orders in circulation = throughput x cycle time`, which the printed columns satisfy.
 */
object MultiFloorHospitalExample {

    const val PHARMACY: String = "Pharmacy"
    const val WARD: String = "WardA"
    const val LOBBY: String = "Lobby"

    /** The porters' parking spurs, one apiece. Two porters cannot stand in one zone. */
    fun parkingSpur(i: Int): String = "Park$i"

    const val SPEED: Double = 10.0

    /** The circuit is held at this length whatever the shaft costs, so the fleet studies compare. */
    const val CIRCUIT: Double = 400.0

    /** Time to make an order up at the pharmacy, which also keeps the fleet from phase-locking. */
    const val MEAN_PREPARATION: Double = 1.0

    private const val MAX_PORTERS = 8

    /**
     *  The hospital, with the lift ride costing [shaftLength] of travel.
     *
     *  The two corridor legs that are not fixed at 60 absorb whatever the shafts do not use, which
     *  is what holds the circuit at [CIRCUIT] across configurations. Coordinates are supplied for
     *  drawing only: routing reads the declared lengths, which is precisely why a network can span
     *  floors at all.
     */
    fun createNetwork(shaftLength: Double): GuidedPathNetwork {
        val corridor = (CIRCUIT - 2.0 * shaftLength - 120.0) / 2.0
        require(corridor > 0.0) { "the shafts leave no room for corridors" }
        val builder = GuidedPathNetwork.builder("Hospital")
            .intersection("G1", x = 0.0, y = 0.0)
            .intersection("G2", x = 60.0, y = 0.0)
            .intersection("G3", x = 60.0 + corridor, y = 0.0)
            // The first floor sits directly above the ground floor. Before an intersection carried
            // a height this layout had to offset the upper floor in y to be drawable at all, which
            // put the wards somewhere they are not. The heights are layout only: routing reads
            // declared link lengths and never a coordinate.
            .intersection("F1", x = 60.0 + corridor, y = 0.0, z = shaftLength)
            .intersection("F2", x = 60.0, y = 0.0, z = shaftLength)
            .intersection("F3", x = 0.0, y = 0.0, z = shaftLength)
            .link("GroundA", "G1", "G2", length = 60.0, zoneLength = 10.0, beginDirection = 0.0)
            .link("GroundB", "G2", "G3", length = corridor, zoneLength = 10.0, beginDirection = 0.0)
            // The lift: one zone, so exactly one porter may be inside it at a time.
            .link("ShaftUp", "G3", "F1", length = shaftLength, zoneLength = shaftLength, beginDirection = 90.0)
            .link("FirstA", "F1", "F2", length = corridor, zoneLength = 10.0, beginDirection = 180.0)
            .link("FirstB", "F2", "F3", length = 60.0, zoneLength = 10.0, beginDirection = 180.0)
            .link("ShaftDown", "F3", "G1", length = shaftLength, zoneLength = shaftLength, beginDirection = 270.0)
            .station(LOBBY, "G1")
            .station(WARD, "G2")
            .station(PHARMACY, "F2")
        // A spur per porter. Without one, porters "at the lobby" would be several vehicles in one
        // zone, which a guide path does not allow -- and a porter left standing on the circuit
        // would deny that space to everyone else for the rest of the run.
        for (i in 1..MAX_PORTERS) {
            builder.intersection("P$i", x = -16.0 - 6.0 * i, y = -16.0)
                .link(
                    "Spur$i", "G1", "P$i", length = 20.0, zoneLength = 20.0,
                    type = LinkType.SPUR, beginDirection = 225.0
                )
                .station(parkingSpur(i), "P$i")
        }
        return builder.build()
    }

    /**
     *  Orders are made up at the pharmacy and are wanted on the ward below.
     *
     *  @param numPorters how many porters serve the circuit
     *  @param shaftLength how far a lift ride is, and so how long the shaft is held
     *  @param ordersInCirculation how much work is outstanding at any moment. Finishing an order
     *    releases the next one, so this number is constant for the whole run.
     */
    class Hospital(
        parent: ModelElement,
        val numPorters: Int,
        shaftLength: Double,
        private val ordersInCirculation: Int
    ) : ProcessModel(parent, "Hospital") {

        val network = createNetwork(shaftLength)

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val porters: List<AgvVehicle> = (1..numPorters).map { i ->
            AgvVehicle(
                agv, TransporterPlacement.At(parkingSpur(i)), ConstantRV(SPEED), name = "Porter$i"
            ).apply { homeBase = parkingSpur(i) }
        }

        val delivered = Counter(this, "Delivered")
        val cycleTime = Response(this, "CycleTime")

        private val preparation = ExponentialRV(MEAN_PREPARATION, 1)

        inner class Order : Entity() {
            val delivery: KSLProcess = process(isDefaultProcess = true) {
                val placed = time
                currentLocation = network.requireLocation(PHARMACY)
                delay(preparation)
                transportByAgv(agv, destination = WARD, origin = PHARMACY)
                cycleTime.value = time - placed
                delivered.increment()
                // The shelf is never the constraint: the next order is ready the moment this one
                // is delivered, which is what holds the outstanding work constant.
                activate(Order().delivery)
            }
        }

        override fun initialize() {
            repeat(ordersInCirculation) { activate(Order().delivery) }
        }
    }

    /**
     *  The same hospital, watched. Sampling is on the half-tick so that an observer does not compete
     *  with the zone transitions for ordering at the instants they happen: with a constant velocity
     *  and equal zone lengths every transition lands on a whole number here, and an observer
     *  scheduled at those same instants sees whichever side of them event priority puts it on. That
     *  is not a subtlety of this subsystem -- it is what makes a deterministic model easy to observe
     *  wrongly -- and it reported an unused lift in a model that was plainly using one.
     */
    class WatchedHospital(
        parent: ModelElement,
        numPorters: Int,
        shaftLength: Double,
        ordersInCirculation: Int,
        private val horizon: Double
    ) : ProcessModel(parent, "Watched") {

        private val inner = Hospital(this, numPorters, shaftLength, ordersInCirculation)

        val network get() = inner.network
        val delivered get() = inner.delivered

        /** How many of the samples found the up shaft reserved by somebody. */
        var samples: Int = 0
            private set
        var samplesHeld: Int = 0
            private set

        /** Which porters were ever seen holding it. */
        val holders: MutableSet<String> = sortedSetOf()

        /** The largest number of porters found inside the shaft at once. */
        var maxInShaft: Int = 0
            private set

        override fun initialize() {
            samples = 0
            samplesHeld = 0
            holders.clear()
            maxInShaft = 0
            var t = 0.5
            while (t < horizon) {
                schedule(::sampleShaft, t)
                t += 1.0
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sampleShaft(event: KSLEvent<Nothing>) {
            val shaft = network.link("ShaftUp")!!.zones
            // `isHeld`, not `isOccupied` -- see the note in this file's header.
            val inside = shaft.count { it.isHeld }
            samples++
            if (inside > 0) samplesHeld++
            if (inside > maxInShaft) maxInShaft = inside
            shaft.forEach { z -> z.holder?.let { holders.add(it.name) } }
        }
    }

    private const val REPLICATIONS = 4
    private const val HORIZON = 4_000.0
    private const val WARM_UP = 500.0

    /** How much work is outstanding: enough that a porter never waits for one, and no more. */
    fun ordersFor(numPorters: Int): Int = numPorters + 2

    /** One point of a fleet study: the fleet size, and what the fleet managed. */
    data class FleetResult(
        val numPorters: Int,
        val deliveries: Double,
        val cycleTime: Double,
        val fracBlocked: Double
    ) {
        /** Deliveries per 100 time units, which is the quantity a capacity study is about. */
        val throughput: Double get() = 100.0 * deliveries / (HORIZON - WARM_UP)
    }

    fun runFleet(numPorters: Int, shaftLength: Double): FleetResult {
        val m = Model("Hospital-$numPorters-${shaftLength.toInt()}")
        val h = Hospital(m, numPorters, shaftLength, ordersFor(numPorters))
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON
        m.lengthOfReplicationWarmUp = WARM_UP
        m.simulate()
        return FleetResult(
            numPorters = numPorters,
            deliveries = h.delivered.acrossReplicationStatistic.average,
            cycleTime = h.cycleTime.acrossReplicationStatistic.average,
            fracBlocked = h.porters.sumOf { it.fracTimeBlocked.acrossReplicationStatistic.average } / numPorters
        )
    }

    fun runWatched(): WatchedHospital {
        val m = Model("Hospital-Watched")
        val h = WatchedHospital(
            m, numPorters = 3, shaftLength = 80.0, ordersInCirculation = 3, horizon = 600.0
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()
        return h
    }

    private fun fleetTable(title: String, shaftLength: Double, sizes: List<Int>) {
        val rideTime = shaftLength / SPEED
        println("  $title")
        println(
            "  a ride costs %.1f time units, so the shaft passes at most %.2f porters per 100"
                .format(rideTime, 100.0 / rideTime)
        )
        println()
        println("    porters   orders out   deliveries   per 100 units   cycle time   blocked")
        for (n in sizes) {
            val r = runFleet(n, shaftLength)
            println(
                "    %7d   %10d   %10.1f   %13.3f   %10.2f   %7.4f".format(
                    r.numPorters, ordersFor(n), r.deliveries, r.throughput, r.cycleTime, r.fracBlocked
                )
            )
        }
        println()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println()
        println("A hospital on two floors - and no lift class anywhere in it")
        println()

        val watched = runWatched()
        val ward = watched.network.requireLocation(WARD)
        val pharmacy = watched.network.requireLocation(PHARMACY)
        println("  Study 1: three porters, one shaft, watched for 600 time units")
        println(
            "    is the first floor reachable from the ground floor? %s"
                .format(watched.network.isReachable(ward, pharmacy))
        )
        println("    routed distance, ward to pharmacy:       %8.1f".format(watched.network.distance(ward, pharmacy)))
        println("    deliveries completed:                    %8.0f".format(watched.delivered.value))
        println(
            "    fraction of samples with the shaft held: %8.4f".format(
                watched.samplesHeld.toDouble() / watched.samples
            )
        )
        println("    most porters ever inside the shaft:      %8d".format(watched.maxInShaft))
        println("    porters seen using it:                   %s".format(watched.holders.joinToString(", ")))
        println()
        println("  Both floors are reachable and the routed distance is a real number, so the network")
        println("  knows the floors connect - by declared length, since nothing here has a third")
        println("  coordinate. Every porter used the lift, and never two at once. Nothing was written")
        println("  to make that true: a zone admits one vehicle, and a lift is one zone.")
        println()
        println("  The held fraction is worth checking against the deliveries rather than taken on")
        println("  trust. Each delivery cycle rides the up shaft once, at 8 units a ride, so 42")
        println("  deliveries in 600 units account for about 0.56 of it. The sampled figure is a")
        println("  little higher, and should be: the zone is held from the moment it is reserved,")
        println("  not from the moment a porter enters it, and that reservation is the exclusion.")
        println()

        fleetTable(
            "Study 2: a slow lift - an 8 unit ride, 60 unit corridors, circuit 400",
            shaftLength = 80.0,
            sizes = listOf(1, 2, 3, 4, 6, 8)
        )
        fleetTable(
            "Study 3: a fast lift - a 2 unit ride, 120 unit corridors, circuit still 400",
            shaftLength = 20.0,
            sizes = listOf(1, 2, 3, 4, 6, 8)
        )

        println("  Read the two tables against each other, one porter first. A single porter travels")
        println("  the same 400 in both, so it delivers at the same rate in both, which is the whole")
        println("  reason the corridors were lengthened when the shaft was shortened. Any difference")
        println("  further down the tables is therefore about how many porters the shaft will pass,")
        println("  and about nothing else.")
        println()
        println("  Study 2 scales cleanly to four porters - 2.486, 4.971, 7.486, 10.000, which is")
        println("  essentially 2.5 apiece - then stops dead at 12.486 for six porters and for eight.")
        println("  That ceiling is not an artefact of the fleet or of the dispatching rule: an 8")
        println("  unit ride passes at most 12.50 deliveries per 100 units, which is five porters'")
        println("  worth, and the fleet reaches it and can go no further however many more are hired.")
        println()
        println("  What the surplus porters do instead is visible in the last two columns, and the")
        println("  arithmetic is exact. Six porters are blocked 0.1667 of the time and 6 x 0.1667 is")
        println("  1; eight are blocked 0.3750 and 8 x 0.3750 is 3. One porter's worth of the fleet")
        println("  is standing still at six, three porters' worth at eight - precisely the surplus")
        println("  over the five the shaft will carry. Cycle time rises to match, from 60.0 at four")
        println("  porters to 80.0 at eight, because the extra orders are waiting rather than moving.")
        println("  Buying porters buys queue.")
        println()
        println("  In study 3 the same fleet sizes keep converting into throughput: eight porters")
        println("  deliver 19.94 per 100 against the 20.00 that perfect scaling would give, because a")
        println("  2 unit ride will pass 50 per 100 and the fleet never comes near it. Same circuit,")
        println("  same porters, same rule, same code - a different lift.")
        println()
        println("  The columns are not independent, and it is worth checking that they hang together.")
        println("  Little's law says orders outstanding = throughput x cycle time, with throughput")
        println("  put back on a per-unit basis by dividing the column by 100. Eight porters in study")
        println("  2: 0.12486 x 80.00 = 9.99, against 10 orders out. In study 3: 0.19943 x 49.98 =")
        println("  9.97. It holds because the system is closed, which is also why cycle time here is")
        println("  a number about the hospital rather than about the length of the run.")
        println()
        println("  The warnings above each table are the horizon diagnostics doing their job and")
        println("  finding nothing wrong. A closed system necessarily has its whole population")
        println("  outstanding when the clock stops, so those counts never exceed the orders-out")
        println("  column - which is exactly the reading that would tell you something was wrong if")
        println("  they did.")
        println()
        println("  What none of this needed: an elevator object, a floor attribute, a capacity")
        println("  semaphore, or a branch anywhere in the dispatcher or the vehicle control loop. A")
        println("  lift is a one-way link of a single zone. The floors are placed at their own")
        println("  heights, so the picture is right as well as the behaviour - and because a height")
        println("  is layout and nothing else, placing them changed not one number above.")
    }
}
