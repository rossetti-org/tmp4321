package ksl.examples.general.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  The simple automated-guided-vehicle example from the chapter on entity movement and material
 *  handling: two carts on a one-way loop carrying parts from an entry station to an exit station at
 *  the end of a spur.
 *
 *  The layout is built here rather than imported, because the layout *is* the lesson. Four things
 *  about it are worth reading before the code:
 *
 *  **The main loop runs one way.** That is what makes head-on deadlock impossible rather than
 *  merely unlikely: two carts on this loop can queue behind one another but can never face each
 *  other. A two-way aisle would be shorter to draw and would introduce the one failure mode this
 *  layout is designed not to have.
 *
 *  **The exit station is at the end of a spur.** A spur admits one cart at a time. The second cart
 *  sent there waits at the spur's mouth, out on the loop, rather than following the first in — a
 *  cart that entered behind another would face it with neither able to reverse.
 *
 *  **Each cart has a parking spur of its own.** A stopped cart goes on holding the zones it stands
 *  on, so where a fleet idles decides how much of the guide path is unavailable to everybody else.
 *  This is the single most likely way for a working-looking model to be quietly wrong, and
 *  [runWithoutHomeBases] below demonstrates it: with the carts left where they stop, the first
 *  delivery parks on the exit station, the only way off the exit spur, and every later delivery
 *  stops at the mouth for the rest of the run. Nothing raises. The run simply stops moving.
 *
 *  **The zone sizes differ between links.** The loop is discretized at twelve feet, chosen so that
 *  two six-foot carts cannot close to less than six feet while moving. The home spurs are only six
 *  feet long — exactly one cart, and half a loop zone — so they get a zone size of their own. Zone
 *  size belongs to a link rather than to a network precisely so a layout like this is expressible.
 */
object SimpleAGVExample {

    const val LOOP_ZONE_LENGTH: Double = 12.0
    const val HOME_SPUR_ZONE_LENGTH: Double = 6.0
    const val ENTRY_STATION: String = "EntryStation"
    const val EXIT_STATION: String = "ExitStation"
    const val AGV1_HOME: String = "I6"
    const val AGV2_HOME: String = "I7"

    /**
     *  Builds the guide path. Coordinates place `I4` at the origin with the loop above and to the
     *  right of it; they drive layout and animation only, never routing, which uses the declared
     *  link lengths.
     *
     *  The loop is one-way, so distances are not symmetric. Entry to exit runs the long way round,
     *  `I1` to `I2` to `I3` to `I4` and down the spur: 204 feet. The return from the exit is only
     *  108, because the spur is two-way and `Link4` carries the cart straight back up to `I1`.
     */
    fun createNetwork(networkName: String = "SimpleAgvNetwork"): GuidedPathNetwork =
        GuidedPathNetwork.builder(networkName)
            .intersection("I1", x = 0.0, y = 72.0)
            .intersection("I2", x = 48.0, y = 72.0)
            .intersection("I3", x = 48.0, y = 0.0)
            .intersection("I4", x = 0.0, y = 0.0)
            .intersection("I5", x = 0.0, y = -36.0)
            .intersection("I6", x = 54.0, y = 72.0)
            .intersection("I7", x = 54.0, y = 0.0)
            .link("Link1", "I1", "I2", length = 48.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 0.0)
            .link("Link2", "I2", "I3", length = 72.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 270.0)
            .link("Link3", "I3", "I4", length = 48.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 180.0)
            .link("Link4", "I4", "I1", length = 72.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 90.0)
            .link(
                "Spur", "I4", "I5", length = 36.0, zoneLength = LOOP_ZONE_LENGTH,
                type = LinkType.SPUR, beginDirection = 270.0
            )
            .link(
                "Link5", "I2", "I6", length = 6.0, zoneLength = HOME_SPUR_ZONE_LENGTH,
                type = LinkType.SPUR, beginDirection = 0.0
            )
            .link(
                "Link6", "I3", "I7", length = 6.0, zoneLength = HOME_SPUR_ZONE_LENGTH,
                type = LinkType.SPUR, beginDirection = 0.0
            )
            .station(ENTRY_STATION, "I1")
            .station(EXIT_STATION, "I5")
            .build()

    /**
     *  Parts arrive at the entry station and are carried to the exit station by whichever cart is
     *  free.
     *
     *  @param parent the containing model element
     *  @param sendCartsHome whether an idle cart returns to its own spur or stays where it stopped
     *  @param timeBtwArrivals the mean time between part arrivals, in minutes
     */
    class AgvShop(
        parent: ModelElement,
        sendCartsHome: Boolean = true,
        timeBtwArrivals: Double = 20.0
    ) : ProcessModel(parent, "AgvShop") {

        val network: GuidedPathNetwork = createNetwork()

        init {
            // The parts travel on the guide path, so it is their spatial model too.
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(AGV1_HOME), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart1"
        ).apply { homeBase = AGV1_HOME }

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(AGV2_HOME), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart2"
        ).apply { homeBase = AGV2_HOME }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            ClosestByNetworkDistanceRule(),
            if (sendCartsHome) ReturnToHomeBaseRule() else ParkInPlaceRule(),
            "Carts"
        )

        val timeInSystem = Response(this, "TimeInSystem")
        val completed = Counter(this, "PartsDelivered")

        @Suppress("unused")
        private val generator = EntityGenerator(
            ::Part, ExponentialRV(timeBtwArrivals, streamNum = 1),
            ExponentialRV(timeBtwArrivals, streamNum = 1)
        )

        inner class Part : Entity() {
            @Suppress("unused")
            val delivery = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY_STATION)
                guidedTransport(
                    carts,
                    destination = EXIT_STATION,
                    pickupLocation = ENTRY_STATION,
                    loadingDelay = ConstantRV(0.5),
                    unLoadingDelay = ConstantRV(0.5)
                )
                timeInSystem.value = time - arrived
                completed.increment()
            }
        }
    }

    /**
     *  Runs the shop. Both configurations run identically -- same replications, same horizon, same
     *  warm-up, same arrival stream -- because the only thing being compared is where an idle cart
     *  waits, and any difference in the run settings would swamp that.
     *
     *  @param sendCartsHome whether an idle cart returns to its own spur or stays where it stopped
     */
    fun run(sendCartsHome: Boolean): AgvShop {
        val m = Model(if (sendCartsHome) "SimpleAGVExample" else "SimpleAGVExampleParked")
        val shop = AgvShop(m, sendCartsHome = sendCartsHome)
        m.numberOfReplications = 10
        m.lengthOfReplication = 8_000.0
        m.lengthOfReplicationWarmUp = 1_000.0
        m.simulate()
        return shop
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val designed = run(sendCartsHome = true)
        val parked = run(sendCartsHome = false)

        println()
        println("Simple AGV shop - where an idle cart waits, and what it costs")
        println()
        println("                        carts sent home    carts left in place")
        println(
            "  parts delivered  %20.1f %22.1f".format(
                designed.completed.acrossReplicationStatistic.average,
                parked.completed.acrossReplicationStatistic.average
            )
        )
        println(
            "  time in system   %20.2f %22.2f".format(
                designed.timeInSystem.acrossReplicationStatistic.average,
                parked.timeInSystem.acrossReplicationStatistic.average
            )
        )
        println(
            "  obstructions     %20.1f %22.1f".format(
                designed.system.numObstructionsDetected.acrossReplicationStatistic.average,
                parked.system.numObstructionsDetected.acrossReplicationStatistic.average
            )
        )
        println(
            "  fraction blocked %20.4f %22.4f".format(
                designed.system.numTransportersBlocked.acrossReplicationStatistic.average / 2.0,
                parked.system.numTransportersBlocked.acrossReplicationStatistic.average / 2.0
            )
        )
        println()
        println("  Neither run fails and neither reports an error. The obstruction count is the")
        println("  only thing that distinguishes them, and it is why the condition is counted into")
        println("  the standard report rather than only written to a log: it is a design defect")
        println("  that a run is perfectly capable of hiding.")
    }
}
