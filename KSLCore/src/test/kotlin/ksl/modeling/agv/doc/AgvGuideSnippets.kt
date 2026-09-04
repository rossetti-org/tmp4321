package ksl.modeling.agv.doc

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.agv.Battery
import ksl.modeling.agv.FailureBasis
import ksl.modeling.agv.FailureModel
import ksl.modeling.agv.Interruption
import ksl.modeling.agv.InterruptionPolicyIfc
import ksl.modeling.agv.AssignmentProposal
import ksl.modeling.agv.Dispatcher
import ksl.modeling.agv.exceptions.AgvInvariantViolation
import ksl.modeling.agv.policies.AssignmentPolicyIfc
import ksl.modeling.agv.policies.BatchedAssignmentPolicy
import ksl.modeling.agv.policies.Bid
import ksl.modeling.agv.policies.BidPolicyIfc
import ksl.modeling.agv.policies.ByPriorityTaskSelection
import ksl.modeling.agv.policies.ChargeReservePolicy
import ksl.modeling.agv.policies.ChargeWhenLowDisposition
import ksl.modeling.agv.policies.CallForProposals
import ksl.modeling.agv.policies.CompletionTimeBid
import ksl.modeling.agv.policies.ContractNetAssignmentPolicy
import ksl.modeling.agv.policies.DeclineWhenBusyBid
import ksl.modeling.agv.policies.DispatchContext
import ksl.modeling.agv.policies.Disposition
import ksl.modeling.agv.policies.DispositionPolicyIfc
import ksl.modeling.agv.policies.LeastUsedVehiclePolicy
import ksl.modeling.agv.policies.MoveToStagingDisposition
import ksl.modeling.agv.policies.NearestVehiclePolicy
import ksl.modeling.agv.policies.ReassigningPolicy
import ksl.modeling.agv.policies.ReturnToHomeBaseDisposition
import ksl.modeling.agv.policies.ScoringAssignmentPolicy
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.tow
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-agv.md`.
 * Each `fun` body is a verbatim snippet (or its body); compiling this file
 * proves every example in the guide references real public APIs.
 *
 * This file is not run as a test — the build only needs to compile it.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object AgvGuideSnippets {

    const val ENTRY: String = "EntryStation"
    const val EXIT: String = "ExitStation"
    const val DEPOT: String = "CartDepot"

    // -- §3 Quick start: the network -------------------------------------

    fun buildNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("ShopFloor")
        .intersection("I1", x = 0.0, y = 72.0)
        .intersection("I2", x = 48.0, y = 72.0)
        .intersection("I3", x = 48.0, y = 0.0)
        .intersection("I4", x = 0.0, y = 0.0)
        .intersection("I5", x = 0.0, y = -36.0)
        .intersection("I6", x = 54.0, y = 72.0)
        // A one-way loop, so two vehicles cannot meet head-on.
        .link("Link1", "I1", "I2", length = 48.0, zoneLength = 12.0)
        .link("Link2", "I2", "I3", length = 72.0, zoneLength = 12.0)
        .link("Link3", "I3", "I4", length = 48.0, zoneLength = 12.0)
        .link("Link4", "I4", "I1", length = 72.0, zoneLength = 12.0)
        .link("ExitSpur", "I4", "I5", length = 36.0, zoneLength = 12.0, type = LinkType.SPUR)
        // A parking spur per vehicle, so an idle one is out of the traffic.
        .link("DepotSpur", "I2", "I6", length = 6.0, zoneLength = 6.0, type = LinkType.SPUR)
        .station(ENTRY, "I1")
        .station(EXIT, "I5")
        .station(DEPOT, "I6")
        .build()

    // -- §3 Quick start: the model ---------------------------------------

    class AgvShop(parent: ModelElement) : ProcessModel(parent, "AgvShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        // The fleet and its dispatcher. A child of this model; its entities suspend in its queues.
        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = DEPOT }

        val timeInSystem = Response(this, "TimeInSystem")
        val delivered = Counter(this, "Delivered")

        private val timeBetweenArrivals = ExponentialRV(40.0, 1)

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                // States what it needs and suspends. It never chooses a vehicle.
                transportByAgv(agv, destination = EXIT, origin = ENTRY)
                timeInSystem.value = time - arrived
                delivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(400) {
                    delay(timeBetweenArrivals)
                    activate(Part().production)
                }
            }
        }

        override fun initialize() {
            activate(Source().arrivals)
        }
    }

    fun runIt() {
        val m = Model("AgvShop")
        val shop = AgvShop(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 8_000.0
        m.lengthOfReplicationWarmUp = 1_000.0
        m.simulate()
        m.print()
    }

    // -- §4 …ask for transport without waiting for it --------------------

    class Decoupled(parent: ModelElement) : ProcessModel(parent, "Decoupled") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At(DEPOT), ConstantRV(10.0), name = "Cart")

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(ENTRY)
                // Post the request now, so the vehicle is on its way while the work finishes.
                val task = requestAgvTransport(agv, destination = EXIT, origin = ENTRY)
                delay(5.0)                       // finish the operation, release the machine
                val result = awaitAgvTransport(task)
                val waited = result.waitForAssignment
                val fetched = result.waitForArrival
                val rode = result.timeAboard
                val who = result.vehicleName
                val turnedRound = result.numReassignments
            }
        }
    }

    // -- §4 …change the dispatching rule ---------------------------------

    fun chooseTheRule(parent: ModelElement, network: GuidedPathNetwork) {
        val agv = AgvSystem(parent, network, assignmentPolicy = LeastUsedVehiclePolicy())
        // Or later, while the model is not running:
        agv.dispatcher.assignmentPolicy = NearestVehiclePolicy()
        // What order the policy sees the waiting tasks in:
        agv.dispatcher.taskSelectionRule = ByPriorityTaskSelection()
    }

    // -- §4 …wait and decide over a batch --------------------------------

    fun batchIt(parent: ModelElement, network: GuidedPathNetwork) {
        AgvSystem(
            parent, network,
            assignmentPolicy = BatchedAssignmentPolicy(window = 10.0, inner = NearestVehiclePolicy()),
            name = "Agv"
        )
    }

    // -- §4 …let the vehicles bid ----------------------------------------

    fun auctionIt(parent: ModelElement, network: GuidedPathNetwork, fleet: List<AgvVehicle>) {
        AgvSystem(
            parent, network,
            assignmentPolicy = ContractNetAssignmentPolicy(deadline = 0.5),
            name = "Agv"
        )
        // What each vehicle offers is its own business, and may differ across the fleet.
        for (vehicle in fleet) {
            vehicle.bidPolicy = DeclineWhenBusyBid(CompletionTimeBid())
        }
    }

    /** A bidding rule cannot suspend: a bid is a quote, and quoting must not consume time. */
    class LeastLoadedBid : BidPolicyIfc {
        override fun bid(
            vehicle: AgvVehicle,
            cfp: CallForProposals,
            network: GuidedPathNetwork
        ): Bid? = Bid(vehicle, vehicle.numTasksCompleted.value, note = "tasks done so far")
    }

    // -- §4 …take a task back --------------------------------------------

    fun retask(parent: ModelElement, network: GuidedPathNetwork) {
        AgvSystem(
            parent, network,
            // A swap must save more than 50 units of guide path before it is worth making.
            assignmentPolicy = ReassigningPolicy(improvementThreshold = 50.0),
            name = "Agv"
        )
    }

    // -- §4 …write your own policy ---------------------------------------

    /** Send whichever available vehicle is nearest, but never turn one round for less than a leg. */
    class AlphabeticalPolicy : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(
            context: DispatchContext
        ): List<AssignmentProposal> {
            val free = context.available.sortedBy { it.name }.toMutableList()
            val proposals = mutableListOf<AssignmentProposal>()
            for (task in context.board.unassigned) {
                if (free.isEmpty()) break
                proposals.add(AssignmentProposal(free.removeAt(0), task))
            }
            return proposals
        }
    }

    /** The same shape as a cost-function rule: enumerate the actions, score each, take the best. */
    fun scoreEveryPairing(): AssignmentPolicyIfc = ScoringAssignmentPolicy { proposal, feasible ->
        val travel = feasible.cost(proposal.vehicle, proposal.task)
        // Lower is better, so a task declaring a lower priority number is worth going further for.
        travel + 100.0 * proposal.task.priority
    }

    // -- §4 …decide where an idle vehicle goes ---------------------------

    fun idleVehicles(fleet: List<AgvVehicle>) {
        fleet[0].dispositionPolicy = ReturnToHomeBaseDisposition()
        fleet[1].dispositionPolicy = MoveToStagingDisposition("StagingSpur2")
    }

    /** Per vehicle, so a fleet can be heterogeneous, and free to look at the vehicle. */
    class GoHomeWhenTiredDisposition(private val after: Double) : DispositionPolicyIfc {
        override fun disposition(vehicle: AgvVehicle): Disposition =
            if (vehicle.numTasksCompleted.value >= after) Disposition.ReturnToHomeBase
            else Disposition.ParkInPlace
    }

    // -- §4 …abandon an outstanding request ------------------------------

    fun abandon(agv: AgvSystem, task: Dispatcher.Task) {
        agv.dispatcher.cancel(task)
    }

    // -- §4 …check the subsystem's own bookkeeping -----------------------

    fun audit(agv: AgvSystem) {
        agv.checkInvariants = true          // every clock advance; expensive, for development
        agv.auditAtReplicationEnd = true    // once per replication; on by default
    }

    // -- §6 A sweep that must survive a deadlock -------------------------

    fun sweep(record: (Int, Double) -> Unit, recordInfeasible: (Int, Any) -> Unit) {
        for (fleetSize in 1..12) {
            val model = Model("Sweep$fleetSize")
            val shop = AgvShop(model)
            model.numberOfReplications = 30
            try {
                model.simulate()
                record(fleetSize, shop.timeInSystem.acrossReplicationStatistic.average)
            } catch (e: GuidedPathDeadlockException) {
                // A domain outcome, not a defect: this fleet size cannot run on this layout.
                recordInfeasible(fleetSize, e.report)
            } catch (e: AgvInvariantViolation) {
                // Not a domain outcome. The subsystem's account of itself did not add up.
                throw e
            }
        }
    }


    // -- §4 Batteries and charging ---------------------------------------

    class ChargedShop(parent: ModelElement) : ProcessModel(parent, "ChargedShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, name = "Agv",
            assignmentPolicy = ChargeReservePolicy(NearestVehiclePolicy())
        )

        init {
            agv.addCharger("I6")
        }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "Cart",
            battery = Battery(
                capacity = 1000.0,
                chargePerDistance = 0.5,   // traction: drawn per foot travelled
                chargePerTime = 0.02,      // hotel load: drawn always, parked included
                chargingRate = 100.0
            )
        ).apply {
            dispositionPolicy = ChargeWhenLowDisposition(threshold = 0.6)
        }
    }

    // -- §4 Breakdowns ---------------------------------------------------

    fun aVehicleThatBreaksDown(agv: AgvSystem): AgvVehicle = AgvVehicle(
        agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "FailingCart",
        failureModel = FailureModel.clockBased(
            timeBetweenFailures = ExponentialRV(400.0, streamNum = 7),
            repairTime = LognormalRV(20.0, 25.0, streamNum = 8),
            basis = FailureBasis.OPERATING_TIME
        )
    )

    // -- §4 What a site actually does when a vehicle breaks down ---------

    class VisitAndAssess(
        private val technicians: ResourceWithQ,
        private val refuge: String,
        private val reportingDelay: RVariableIfc,
        private val walkingTime: RVariableIfc,
        private val assessmentTime: RVariableIfc
    ) : InterruptionPolicyIfc {

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            val vehicle = interruption.vehicle
            delay(reportingDelay)                        // nobody noticed for a while
            val tech = seize(technicians)                // somebody has to be free
            delay(walkingTime)                           // and walk to it
            delay(assessmentTime)                        // and look at it
            if (interruption.isObstructing) {            // decided at the vehicle, not before
                tow(vehicle, refuge, atVelocity = 1.0)   // pushed out of the aisle
            }
            if (interruption is Interruption.Failed) delay(interruption.repairTime)
            release(tech)
        }
    }

    fun installTheRecoveryPolicy(cart: AgvVehicle, technicians: ResourceWithQ) {
        cart.interruptionPolicy = VisitAndAssess(
            technicians, refuge = "MaintenanceSpur",
            reportingDelay = ConstantRV(2.0),
            walkingTime = ExponentialRV(8.0, streamNum = 9),
            assessmentTime = ConstantRV(5.0)
        )
    }


    // -- §6 What the horizon left undone ---------------------------------

    fun readTheHorizon(agv: AgvSystem) {
        val stranded = agv.numTasksNeverAssigned.acrossReplicationStatistic.average
        val hanging = agv.numEntitiesNeverResumed.acrossReplicationStatistic.average
        val open = agv.numAssignmentsStillOpen.acrossReplicationStatistic.average
    }
}
