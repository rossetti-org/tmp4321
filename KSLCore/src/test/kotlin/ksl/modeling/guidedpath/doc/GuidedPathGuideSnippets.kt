package ksl.modeling.guidedpath.doc

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.exceptions.DeadlockReport
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.LeastUsedTransporterRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-guidedpath.md`.
 * Each `fun` body is a verbatim snippet (or its body); compiling this file
 * proves every example in the guide references real public APIs.
 *
 * This file is not run as a test — the build only needs to compile it.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object GuidedPathGuideSnippets {

    // -- §3 Quick start: the network -------------------------------------

    fun buildNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("SimpleAgv")
        .intersection("I1", x = 0.0, y = 72.0)
        .intersection("I2", x = 48.0, y = 72.0)
        .intersection("I3", x = 48.0, y = 0.0)
        .intersection("I4", x = 0.0, y = 0.0)
        .intersection("I5", x = 0.0, y = -36.0)
        .intersection("I6", x = 54.0, y = 72.0)
        .intersection("I7", x = 54.0, y = 0.0)
        // The main loop, clockwise and one way.
        .link("Link1", "I1", "I2", length = 48.0, zoneLength = 12.0)
        .link("Link2", "I2", "I3", length = 72.0, zoneLength = 12.0)
        .link("Link3", "I3", "I4", length = 48.0, zoneLength = 12.0)
        .link("Link4", "I4", "I1", length = 72.0, zoneLength = 12.0)
        // The spur down to the exit station.
        .link("Spur", "I4", "I5", length = 36.0, zoneLength = 12.0, type = LinkType.SPUR)
        // A parking spur per cart, each one cart long.
        .link("Link5", "I2", "I6", length = 6.0, zoneLength = 6.0, type = LinkType.SPUR)
        .link("Link6", "I3", "I7", length = 6.0, zoneLength = 6.0, type = LinkType.SPUR)
        .station("EntryStation", "I1")
        .station("ExitStation", "I5")
        .build()

    // -- §3 Quick start: the model ---------------------------------------

    class AgvShop(parent: ModelElement) : ProcessModel(parent, "AgvShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At("I6"), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = "I6" }

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At("I7"), ConstantRV(10.0), name = "Cart2"
        ).apply { homeBase = "I7" }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            idleDispositionRule = ReturnToHomeBaseRule(), name = "Carts"
        )

        inner class Part : Entity() {
            val delivery = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("EntryStation")
                guidedTransport(
                    carts,
                    destination = "ExitStation",
                    pickupLocation = "EntryStation",
                    loadingDelay = ConstantRV(0.5),
                    unLoadingDelay = ConstantRV(0.5)
                )
            }
        }
    }

    // -- §4 How do I…? ----------------------------------------------------

    class DecomposedShop(parent: ModelElement) : ProcessModel(parent, "DecomposedShop") {
        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart1 = GuidedTransporter(system, TransporterPlacement.At("I6"), ConstantRV(10.0), name = "Cart1")
        val cart2 = GuidedTransporter(system, TransporterPlacement.At("I7"), ConstantRV(10.0), name = "Cart2")

        // …decide which cart gets sent?
        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            allocationRule = LeastUsedTransporterRule(),
            idleDispositionRule = ReturnToHomeBaseRule(),
            name = "Carts"
        )

        inner class Part : Entity() {
            // …decompose the transport?
            val decomposed = process("decomposed") {
                val request = requestGuidedTransporter(carts, pickupLocation = "EntryStation")
                delay(1.0)                                        // load by hand, say
                val result = transportBy(request, destination = "ExitStation")
                delay(2.0)                                        // unload
                releaseGuidedTransporter(request, carts)
            }

            // …find out what a journey cost?
            val measured = process("measured") {
                val result = guidedTransport(carts, destination = "ExitStation")
                val lost = result.blockedTime      // time unable to claim the space ahead
                val far = result.routeLength
                val zones = result.zonesTraversed
            }
        }
    }

    // …decide which cart gets sent? — writing your own
    class AlphabeticalDispatchRule : GuidedTransporterAllocationRuleIfc {
        override fun selectTransporter(
            network: GuidedPathNetwork,
            pickup: GuidedPathNetwork.Intersection,
            candidates: List<GuidedTransporter>
        ): GuidedTransporter = candidates.minByOrNull { it.name }!!
    }

    // …change how closely carts may follow one another?
    fun followingClosely(system: GuidedPathTransportSystem) {
        GuidedTransporter(
            system, TransporterPlacement.At("I6"), ConstantRV(10.0),
            lengthInZones = 1, zoneControlRule = StartOfZoneControl(), name = "Cart"
        )
    }

    // …collect congestion statistics per link or per zone?
    class InstrumentedShop(parent: ModelElement) : ModelElement(parent, "InstrumentedShop") {
        val network = buildNetwork()
        val system = GuidedPathTransportSystem(
            this, network, collectLinkStatistics = true, name = "Sys"
        )
    }

    // …handle a deadlock in a parameter sweep?
    fun sweep() {
        for (fleetSize in 1..12) {
            val model = Model("Sweep$fleetSize")
            val shop = buildShop(model, fleetSize)
            model.numberOfReplications = 30
            try {
                model.simulate()
                record(fleetSize, shop.systemTime.acrossReplicationStatistic.average)
            } catch (e: GuidedPathDeadlockException) {
                // A domain outcome, not a defect: this fleet size cannot run on this layout.
                recordInfeasible(fleetSize, e.report)
            }
        }
    }

    // Stand-ins for the study's own code, so the sweep snippet compiles as written.
    private class SweptShop(parent: ModelElement, fleetSize: Int) : ModelElement(parent, "SweptShop") {
        val systemTime = ksl.modeling.variable.Response(this, "SystemTime")
    }

    private fun buildShop(model: Model, fleetSize: Int): SweptShop = SweptShop(model, fleetSize)
    private fun record(fleetSize: Int, average: Double) = Unit
    private fun recordInfeasible(fleetSize: Int, report: DeadlockReport) = Unit
}
