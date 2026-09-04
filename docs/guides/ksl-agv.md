# Using `ksl.modeling.agv`

*Experimental.* Active guided vehicles: a fleet that decides for itself,
tasked by a dispatcher that has a process of its own and is allowed to
take simulated time deciding.

Code snippets here are compile-verified against the source on every build
(`KSLCore/src/test/kotlin/ksl/modeling/agv/doc/AgvGuideSnippets.kt`).

---

## 1. What this package is for

Use this package when **who decides, and when, is part of the answer.**

This is the second of two subsystems over the same physical world. The
first, [`ksl-guidedpath`](ksl-guidedpath.md), gives you vehicles on a
network of zones that block each other; there, an entity holds the
protocol — it asks a pool for a cart, waits, rides, hands it back. The
network, the zones, the routing, the blocking and the deadlock detection
in this package are *literally the same code*. What changes is where the
decision lives.

Compare the one line in an entity's process:

```kotlin
guidedTransport(carts, destination = EXIT, pickupLocation = ENTRY)   // passive
transportByAgv(agv,    destination = EXIT, origin = ENTRY)           // active
```

They look alike and mean something quite different. Under the passive
paradigm the choice of *which* cart is made inside the entity's own
process, at the instant it happens to ask, over whichever carts happen to
be free at that instant. There is nowhere else it could be made, because
no other object is running. Under the active paradigm a **dispatcher**
decides: it can see the whole fleet and the whole board, and it may
consume simulated time doing so.

That last clause is the package. Three things become expressible that a
pool's allocation rule cannot express at all — not because they are hard,
but because there is nowhere to put them:

- **Batching.** Wait ten minutes, then allocate over everything that
  accumulated. Under the passive paradigm this would mean making the
  asking entity wait for reasons that have nothing to do with it.
- **Negotiation.** Broadcast a call for proposals, let each vehicle answer
  from what *it* knows about itself, award the best bid — and charge the
  model for the deadline. A passive resource has nothing with which to
  hold an opinion.
- **Re-tasking in flight.** Take a task back from a vehicle three-quarters
  of the way to a far pickup when a nearer one appears. The movement
  machinery turns a vehicle round; the dispatcher is the object whose
  business it is to decide when that should happen.

**When not to use it.** If your rule is "send the nearest free cart" and
you are content for it to be evaluated the moment an entity asks, the
passive subsystem is simpler, has fewer moving parts, and gives the same
answer. With one vehicle the two agree *exactly*, to the digit — which is
the result that makes them two models of one world rather than two worlds
(`ksl.examples.general.agv.TwoParadigmsExample`).

**Not modelled in this version.** Multi-load vehicles.
A vehicle carries one load at a time, and
`AgvVehicle.loadCapacity` above one is refused at construction rather
than accepted and ignored — model the consolidation upstream, or use a
larger fleet.
Everything the physical layer does not model
([`ksl-guidedpath` §1](ksl-guidedpath.md#1-what-this-package-is-for):
acceleration, turn penalties) is equally absent here.

---

## 2. The mental model

**Three objects, and the modeller names two of them.**

- **`AgvSystem`** — the fleet and its dispatcher. It is an `AgentModel`
  (and so a `ProcessModel`), because the vehicles and the dispatcher are
  agents with processes and mailboxes. Your own `ProcessModel` holds one
  as a child element, and your entities suspend in its queues.
- **`AgvVehicle`** — the permanent identity you declare, name, and read
  statistics from. It is *not* the thing that decides and *not* the thing
  that occupies space. It **composes** a `GuidedTransporter` for its
  physical presence, and holds a per-replication agent for its behaviour.
- **`Dispatcher`** — decides who goes where, and owns the line of work
  waiting to be done.

A vehicle composes its transporter rather than inheriting one, and the
reason is a modelling stance rather than a taste in inheritance: a
`GuidedTransporter` is a `Resource`, and a resource is passive by
construction. Inheriting one would expose a way to `seize` this vehicle as
though it were a tool, which is exactly what this subsystem exists to
replace.

**A task is what queues, not a load.** When an entity asks for transport,
a `Task` is created and posted to the dispatcher's `TaskQ`. The task is a
`QObject`, so it carries the waiting statistics; the entity itself
suspends in a hold queue that reports nothing. This is deliberate and is
the same separation `Conveyor` makes: a hold queue is how a suspended
entity is found again, and letting it double as the statistic conflates a
mechanism with a measurement. It also gives an assignment policy a
first-class object to rank and a bidding policy something to bid on.

**A vehicle is available because it said so.** The dispatcher never infers
availability; a vehicle declares it. A policy that names a vehicle the
dispatcher did not offer it raises `AgvDispatchException` rather than
being quietly skipped.

**A policy decides only.** It cannot move a vehicle, claim a zone, post a
task, or change the board. The board it is handed is read-only and an
`AssignmentProposal` is inert, so this is enforced by the types rather
than by a rule you must remember. It may read anything.

**Once a load is aboard, the delivery finishes.** Re-tasking is supported
right up to the instant of pickup and not past it. Revoking an assignment
whose load is aboard raises `AgvAssignmentException`.

---

## 3. Quick start

The same one-way loop the passive guide uses: a spur down to the exit, a
parking spur for the cart, and parts carried from entry to exit.

```kotlin
val network = GuidedPathNetwork.builder("ShopFloor")
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
```

Then the model. Note `spatialModel = network`: the parts travel on the
guide path, so it is their spatial model too.

```kotlin
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
```

There is no `GuidedPathTransportSystem` in that model, and no pool. The
`AgvSystem` builds and owns the space layer itself -- a
`GuidedPathSpace`, which is the zones, the movement engine and the
congestion statistics without either paradigm's protocol. Both
subsystems run on one, and the statistics `AgvSystem` delegates are that
layer's.

---

## 4. How do I…?

### …ask for transport without waiting for it?

`transportByAgv` is the composed verb. When the process must act between
asking and being carried — so the vehicle can be on its way while an
operation finishes — use the two it is built from:

```kotlin
val task = requestAgvTransport(agv, destination = EXIT, origin = ENTRY)
delay(5.0)                       // finish the operation, release the machine
val result = awaitAgvTransport(task)
```

The returned task **must** be awaited. Abandoning it leaves a vehicle to
collect an entity that never suspends.

### …find out what a transport cost?

Both verbs return an `AgvTransportResult`:

```kotlin
val waited = result.waitForAssignment
val fetched = result.waitForArrival
val rode = result.timeAboard
val who = result.vehicleName
val turnedRound = result.numReassignments
```

`waitForAssignment` and `waitForArrival` are the pair the passive
subsystem cannot report at all: nothing there holds a *commitment*, so
there is no instant at which a decision was made to measure from. Here a
dispatcher decides at one instant and a vehicle arrives at another, and
the two sum to exactly the task's time in the dispatcher's queue.

For the fleet rather than one load, the same five figures the passive
subsystem publishes are on `AgvSystem`, delegated to the guide path
underneath because that is the layer both paradigms run on:

```kotlin
val approach = agv.approachTime.withinReplicationStatistic.weightedAverage
val ride = agv.rideTime.withinReplicationStatistic.weightedAverage
val stuck = agv.transportBlockedTime.withinReplicationStatistic.weightedAverage
val zones = agv.zonesTraversedPerTransport.withinReplicationStatistic.weightedAverage
val far = agv.routeLengthPerTransport.withinReplicationStatistic.weightedAverage
```

Note where the boundaries fall, because they are not the same as the
result's. `approachTime` runs from the instant a vehicle was committed
to the load until it reaches it, and `rideTime` from there until it is
set down -- each stopping short of the loading or unloading delay that
follows. `waitForArrival` and `timeAboard` on the result are the wider
intervals that include those delays, which is why neither pair is
derivable from the other. Measured this way the two paradigms report the
same numbers for the same shop, which `PerCarryStatisticsTest` holds
them to.

These two are **protocol intervals, not vehicle states**, and the
distinction will matter as soon as a vehicle can carry more than one
load. An approach includes time the vehicle spent blocked, and time
disengaging from a repositioning move, neither of which is moving empty.
For the state question, ask the vehicle:
`Cart:Body:FracTimeMovingEmpty` is the fraction of its time moving with
no load, and `FracTimeTransporting` the fraction moving with one. Those
are the figures that stay true whatever a vehicle's capacity.

One consequence worth knowing: after a re-tasking, `approachTime` runs
from the **last** assignment, not the first. The abandoned approach is
not empty travel on this load's behalf, and `numReassignments` on the
result is what says it happened.

### …compare a passive model with an active one?

Put both in one model and the reports sit side by side, but three
differences in how rows are named will meet you.


| Passive row | Active row | Why |
|---|---|---|
| `Sys:NumZoneTraversals` | `Agv:Space:NumZoneTraversals` | One segment, applied to all thirteen shared rows |
| `Cart1:FracTimeBlocked` | `Cart1:Body:FracTimeBlocked` | One segment, applied to all nine physical rows |
| `Sys:TransportTime` | *(no counterpart)* | Different intervals — see below |

**The `:Space:` segment** appears because a passive transport system
*is* a guide path space, so its space rows sit at its own level, while
an active system *has* one, so they sit under it. The mapping is one
segment applied mechanically, which `StatisticNamingTest` asserts over
the whole set rather than over a hand-kept list.

**The `:Body` segment** is the same idea one level down: an `AgvVehicle`
composes a `GuidedTransporter` that carries the physical statistics, and
model element names are unique, so the body cannot share the vehicle's
name. `StatisticParityTest` asserts that mapping.

**`TransportTime` has no active counterpart, and this is the one to be
careful about.** The passive row runs from the entity's request to it
being set down — the whole story, including the wait for a cart. The
active subsystem decomposes that wait deliberately, so what corresponds
to it is a sum, not a row:


```kotlin
// Sys:TransportTime  ==  WaitForAssignment + (waiting for arrival) + TimeAboard
val whole = agv.dispatcher.waitForAssignment.withinReplicationStatistic.weightedAverage +
        agv.timeAboard.withinReplicationStatistic.weightedAverage
// ... or read it per load, where the result gives you the total directly:
val total = result.totalTime
```

The active row that measures aboard-to-set-down is called `TimeAboard`
rather than `TransportTime` for exactly this reason. The two subsystems'
own row names are kept disjoint, so a study that lines rows up by name
never compares two different intervals.

One last pair worth reading carefully: an active model reports both
`Agv:Space:NumTransportersIdle` and `Agv:NumVehiclesIdle`, and they
answer different questions. The first counts vehicles **standing
still**; the second counts vehicles **carrying no task**. A vehicle
repositioning to its home base satisfies the second and not the first.
Each row uses the word of the layer that owns it — transporter for the
shared space, vehicle for this subsystem — because renaming either would
make the shared layer speak one consumer's dialect.
### …change the dispatching rule?

```kotlin
val agv = AgvSystem(parent, network, assignmentPolicy = LeastUsedVehiclePolicy())
// Or later, while the model is not running:
agv.dispatcher.assignmentPolicy = NearestVehiclePolicy()
// What order the policy sees the waiting tasks in:
agv.dispatcher.taskSelectionRule = ByPriorityTaskSelection()
```

Nine ship, and six of them answer immediately. `NearestVehiclePolicy` is
the default and is what most people mean by "send the closest cart" — measured **along the guide path**, never
straight-line, because on a one-way loop a vehicle a few feet past the
pickup has to go all the way round. `FurthestVehiclePolicy` is
deliberately poor and exists so that "nearest is better" can be a finding
rather than an assertion. `LeastUsedVehiclePolicy` balances wear instead
of travel, and the two genuinely conflict. `PullFromBoardPolicy` is the
degenerate case — vehicles taking the next job off a shared queue —
expressed as a policy rather than as an architecture, which is what makes
it the policy the equivalence benchmark uses. `RandomAssignmentPolicy`
takes a model-owned stream. `ScoringAssignmentPolicy` is below. The other
three — batching, negotiation, and re-tasking — consume simulated time or
take work back, and have recipes of their own below.

The selection rule orders what the policy *sees*: `FifoTaskSelection`,
`ByPriorityTaskSelection`, `ByAgeTaskSelection`. It is a separate seam
from the policy because "which task next" and "which vehicle for it" are
separate questions.

### …wait and decide over a batch?

```kotlin
AgvSystem(
    parent, network,
    assignmentPolicy = BatchedAssignmentPolicy(window = 10.0, inner = NearestVehiclePolicy()),
    name = "Agv"
)
```

This is the policy the interface exists for. Every rule above answers
immediately and could have been a function; this one consumes simulated
time, and while it waits the board keeps filling. A load arriving just
after a window opens waits the whole of it, and in exchange the fleet is
allocated over a *set* of tasks rather than one at a time in arrival
order. Whether that pays depends on the layout and the load — which is
exactly why it is something to measure rather than a behaviour built into
the dispatcher.

The window runs from when the dispatcher wakes, so an idle fleet still
pays it.

### …let the vehicles bid?

```kotlin
AgvSystem(
    parent, network,
    assignmentPolicy = ContractNetAssignmentPolicy(deadline = 0.5),
    name = "Agv"
)
// What each vehicle offers is its own business, and may differ across the fleet.
for (vehicle in fleet) {
    vehicle.bidPolicy = DeclineWhenBusyBid(CompletionTimeBid())
}
```

A real Contract-Net negotiation, not a distance rule in an auction's
clothes: the dispatcher broadcasts, each vehicle answers from its own
`BidPolicyIfc`, and two fleets with the same layout and different bidding
rules reach different awards. Declining is *silence* rather than a
message. **The deadline consumes simulated time**, which is the point —
negotiation is not free, and a model that charges for it puts the cost in
the loads' waiting time instead of in an assumption.

A deadline of zero is well defined and is not a trap. `bid` is not a
suspending function, so every vehicle has answered inside the broadcast
itself; a bidding rule that consumed simulated time could not be written.

```kotlin
class LeastLoadedBid : BidPolicyIfc {
    override fun bid(
        vehicle: AgvVehicle,
        cfp: CallForProposals,
        network: GuidedPathNetwork
    ): Bid? = Bid(vehicle, vehicle.numTasksCompleted.value, note = "tasks done so far")
}
```

Ties are broken by **vehicle name**, not by declaration order. Everywhere
else in this subsystem declaration order is the tiebreaker; in an auction
it would be wrong, because bidders are symmetric except for what they
offer.

### …take a task back from a vehicle?

```kotlin
AgvSystem(
    parent, network,
    // A swap must save more than 50 units of guide path before it is worth making.
    assignmentPolicy = ReassigningPolicy(improvementThreshold = 50.0),
    name = "Agv"
)
```

The threshold is what makes this usable rather than pathological. Without
one, any improvement at all justifies a swap and a loaded fleet churns:
revoke, redirect, revoke again as the board shifts under it. Set it in the
same units as your link lengths.

Two different swaps are tested, and a fleet of one has only the second:
somebody else could collect this load sooner, or *this* vehicle could
collect a different load sooner.

**The inner policy must rank pairings, not tasks.** The default is a
scoring policy over the feasible set, and that is not arbitrary.
`NearestVehiclePolicy` walks the tasks and picks a vehicle for each, so
with one vehicle and two tasks it hands the first task in the queue
whatever is free — including the vehicle just taken off it. Wrapping a
re-tasking policy around a rule like that revokes and immediately
re-awards the same pairing, and accomplishes nothing but a rising
revocation count. The failure is silent: the model runs, the loads are
delivered, and only `numAssignmentsRevoked` says anything is wrong.

### …write my own policy?

One method, and it may suspend:

```kotlin
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
```

`assign` is written as an extension **on the process builder**, which is
forced: `KSLProcessBuilder` is `@RestrictsSuspension`, so a plain
`suspend fun assign(context)` would not compile at the one call site that
matters. It buys something too — your implementation receives the real
process builder, so it may `delay` for a window, `hold`, or run an
auction, rather than being confined to whatever a context object thought
to expose.

Draw any randomness from a model-owned stream. A policy that consulted
wall-clock time or unmanaged global state would make a run irreproducible
in a way no test would catch.

`context.feasible` is the vehicle-to-task pairings available at this
instant, as an object to enumerate and search rather than a predicate to
apply after guessing — the shape a cost-function or value-function policy
has, and the shape a decision epoch has:

```kotlin
ScoringAssignmentPolicy { proposal, feasible ->
    val travel = feasible.cost(proposal.vehicle, proposal.task)
    // Lower is better, so a task declaring a lower priority number is worth going further for.
    travel + 100.0 * proposal.task.priority
}
```

Feasibility here is **reachability and nothing more**. Enough charge, the
right attachment, a shift that has begun — those are judgements about
desirability that vary by model, and they belong in a bidding rule or a
scoring function. Reachability is a fact about the network.

### …decide where an idle vehicle goes?

Per vehicle, so a fleet may be heterogeneous:

```kotlin
fleet[0].dispositionPolicy = ReturnToHomeBaseDisposition()
fleet[1].dispositionPolicy = MoveToStagingDisposition("StagingSpur2")
```

```kotlin
class GoHomeWhenTiredDisposition(private val after: Double) : DispositionPolicyIfc {
    override fun disposition(vehicle: AgvVehicle): Disposition =
        if (vehicle.numTasksCompleted.value >= after) Disposition.ReturnToHomeBase
        else Disposition.ParkInPlace
}
```

A disposition policy is consulted **only after** the dispatcher has been
given the chance to assign and has declined, so no disposition can cause a
vehicle to idle while work waits. That is structural — the branch is
unreachable until the dispatcher has passed — rather than a rule an
implementer could break.

See §6 before choosing `ParkInPlaceDisposition`.

### …model batteries and charging?

Give the vehicle a `Battery`, tell the system where the chargers are, and
set the two policies that keep it charged.

```kotlin
val agv = AgvSystem(
    this, network, name = "Agv",
    assignmentPolicy = ChargeReservePolicy(NearestVehiclePolicy())
)
agv.addCharger("ChargeSpur")

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
```

Then read `cart.stateOfCharge`, `cart.fractionCharged`, and on the report
`Cart:FracTimeCharging`, `Cart:NumChargingSessions`,
`Cart:MinStateOfCharge` and `Cart:NumTimesStranded`. Those four rows exist
only for a vehicle that has a battery — a row measuring something the model
does not have is a question its reader has to answer every time.

**Two drain rates, because a real vehicle has two.** Traction energy scales
with distance and stops when the vehicle stops. Hotel load — controller,
radio, lights, heating — scales with time and does not. `chargePerTime`
defaults to zero, so a model that ignores idle draw is exactly a model with
one rate.

**Charge is derived, not stepped.** Nothing schedules an event for it: the
level is a closed-form function of the vehicle's two odometers,
`distanceTravelled` and elapsed time, computed whenever you ask. Adding a
battery to a model does not change how many events its run takes, which
`BatteryTest` asserts by running the same model both ways.

**Exhaustion is noticed at the next zone boundary.** A vehicle cannot stop
part way into a zone — it is physically between two places and has already
claimed the space ahead — so a flat vehicle completes the entry it is
committed to and halts on the zone it holds. From then on it stands there,
and every route through those zones is closed.

**Both policies, or neither works.** They do different jobs and the fleet
needs both:

| Policy | What it does | Why it is not enough alone |
|---|---|---|
| `ChargeReservePolicy` | Refuses an assignment the vehicle could not finish and still reach a charger | Stops the vehicle stranding, but never sends it to charge — so it stops working |
| `ChargeWhenLowDisposition` | Sends an idle low vehicle to a charger | Dispositions are consulted only when the dispatcher has no work, and a busy fleet always has work |

A saturated fleet with only the disposition never charges at all, and runs
flat exactly as though it had no charging policy. The reserve is what makes
a low vehicle decline the next load, and declining is what makes it idle
enough for its disposition to be asked.

### …make vehicles break down?

Give the vehicle a `FailureModel`. A failure is due once the chosen
quantity has advanced by a draw since the last repair, and the quantity is
the choice you are making:

```kotlin
val cart = AgvVehicle(
    agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "Cart",
    failureModel = FailureModel.clockBased(
        timeBetweenFailures = ExponentialRV(400.0, streamNum = 7),
        repairTime = LognormalRV(20.0, 25.0, streamNum = 8),
        basis = FailureBasis.OPERATING_TIME
    )
)
```

| Factory | Basis | Failures go with |
|---|---|---|
| `FailureModel.clockBased(..., OPERATING_TIME)` | Time the vehicle was not idle | Hours in service |
| `FailureModel.clockBased(..., CALENDAR_TIME)` | Elapsed simulated time | Age |
| `FailureModel.usageBased(...)` | Tasks completed | Duty cycles |
| `FailureModel.distanceBased(...)` | Distance travelled | Mileage |

`Cart:NumFailures`, `Cart:FracTimeFailed` and `Cart:RepairTime` land on
the report, for a vehicle that has a failure model and only for one.

**A failure interrupts the tour; it does not revoke the assignment.** The
vehicle keeps its load, is repaired where it stands, and resumes the tour
from the stop it had reached. Handing the task back would put a load on the
board while a vehicle was still physically holding it, and two vehicles
would then believe they had it.

**A failure is noticed at the next check point, not at the instant it comes
due.** None of the four bases has events of its own, so a failure accrues
silently and fires at whichever comes first: the next zone boundary, or the
end of the current tour. A vehicle parked with nothing to do therefore does
not fail while parked — it fails at the first boundary of its next journey,
carrying whatever came due while it stood there. For a busy fleet this is a
rounding difference. For a mostly idle one it is not, and `CALENDAR_TIME`
on such a fleet reads as *failures that had become due by the time the
vehicle next worked*.

**Calendar and operating time are not a refinement of one another.** On a
fleet that is idle most of the run they give different counts, which is why
both are offered rather than one being chosen for you.

The gap is smaller than the idle fraction alone would suggest, and it is
worth knowing why: **failures do not queue up**. When one fires, the next
threshold is drawn from the basis value at that instant, so several
failures that came due while the vehicle stood still collapse into one.
A calendar-basis fleet therefore fails roughly as often as it is *checked*,
not as often as the clock says it should — which reads as "the vehicle was
found broken when it was next needed". One cart, arrivals averaging 600
apart over a horizon of 6000, a failure every 120: 26 failures on calendar
time against 21 on operating time, with the cart working 2568 of the 6000.

A vehicle under repair on the guide path is an obstruction for as long as
the repair lasts — see §6 — and one still under repair when the horizon
falls shows on `Agv:NumVehiclesFailedAtHorizon`, which is what says the
open assignment and the suspended entity beside it belong to a breakdown
rather than to a run that was too short.

### …abandon an outstanding request?

```kotlin
agv.dispatcher.cancel(task)
```

For a model that wants transport requests given up rather than left
hanging. It is not needed for teardown: `ProcessModel.afterReplication`
terminates every suspended entity without help from this subsystem.

### …check the subsystem's own bookkeeping?

```kotlin
agv.checkInvariants = true          // every clock advance; expensive, for development
agv.auditAtReplicationEnd = true    // once per replication; on by default
```

`checkInvariants` covers the guide path underneath as well, and reads the
same `ksl.guidedpath.checkInvariants` system property as the passive
subsystem, so switching checking on for a run switches it on for both
paradigms rather than for one of them. The closing audit reconciles
assignments against tasks, queued tasks against vehicles, suspended loads
against live tasks, and both conservation counts. A failure raises
`AgvInvariantViolation`, which — unlike the other three exceptions — names
something the *subsystem* got wrong rather than something a model did.

### …see what the horizon left undone?

```kotlin
val stranded = agv.numTasksNeverAssigned.acrossReplicationStatistic.average
val hanging = agv.numEntitiesNeverResumed.acrossReplicationStatistic.average
val open = agv.numAssignmentsStillOpen.acrossReplicationStatistic.average
```

These are `Response`s rather than `Counter`s, and the distinction is
semantic. A counter holds a running total that means something only while
a replication runs. These are a *single observation*, taken at the last
instant, of a quantity that does not exist until then: how much work was
left undone. They are written unconditionally, zero included — recording
only the bad replications would make the across-replication average a mean
over those, which is a number that looks like a fleet's performance and is
not.

---

## 5. The key types at a glance

| Type | What it is |
|---|---|
| `AgvSystem` | The fleet and its dispatcher. An `AgentModel`; builds and owns the guide path's runtime. |
| `AgvVehicle` | The vehicle a modeller names. Composes a `GuidedTransporter`; holds a per-replication agent. |
| `Dispatcher` | Decides who goes where; owns the `TaskQ`, which is the only queue this subsystem reports. |
| `Dispatcher.Task` | Something a vehicle may be asked to do. A `QObject`, so the *task* carries the wait. |
| `Dispatcher.TransportTask` | A load to collect and deliver. What `requestAgvTransport` returns. |
| `Dispatcher.ServiceTask` | Something a vehicle does for itself, by `ServiceKind` — currently `Reposition`. |
| `Battery` | A vehicle's energy store: capacity, two drain rates, and a charging rate. Immutable. |
| `FailureModel` | When a vehicle fails and how long a repair takes, against one of four bases. |
| `TaskBoard` | The read-only view of the queue handed to policies: `unassigned`, `assigned`, `oldest`. |
| `Assignment` | A vehicle's commitment to a task. `isRevocable` until the load is aboard. |
| `AssignmentProposal` | What a policy returns. Inert: proposing is not doing. |
| `FeasibleAssignments` | The pairings available now, enumerable and searchable. Feasible means reachable. |
| `AgvTransportResult` | What a transport cost, with the wait split into assignment and arrival. |
| `AgvDispatchException` | A policy named a vehicle that had not declared itself available. |
| `AgvAssignmentException` | An assignment was revoked after pickup, or used after completion. |
| `AgvProtocolException` | A task completed twice, or a suspended entity resumed twice. |
| `AgvInvariantViolation` | The closing audit found the subsystem's own account of itself does not add up. |

The four replaceable policies: `AssignmentPolicyIfc` (on the dispatcher),
`TaskSelectionRuleIfc` (on its queue), `BidPolicyIfc` and
`DispositionPolicyIfc` (per vehicle). The physical layer's five —
routing, zone contention, zone control — are the passive subsystem's and
are documented there.

---

## 6. Gotchas & best practices

### Everything in the passive guide's §6 still applies

The space is the same space. A destination is still a resource, an idle
vehicle left on the guide path still blocks everything behind it, two-way
links are still where deadlock comes from, and zone size is still chosen
from control granularity rather than from how smooth the animation looks.
Read [`ksl-guidedpath` §6](ksl-guidedpath.md#6-gotchas--best-practices)
and treat it as part of this one. `GuidedPathDeadlockException` and the
obstruction count reach you unchanged.

The one thing that changes is that a vehicle now has a `DispositionPolicyIfc`
rather than the pool having an `IdleDispositionRuleIfc`, and it is per
vehicle rather than per fleet.

### What the dispatcher costs

More machinery than a pool's allocation rule, so the honest thing is to measure it rather than
assert it is cheap. `./gradlew :KSLExamples:agvBenchmark` runs the reference configuration — a
twenty-intersection, forty-link torus of 420 zones carrying twenty vehicles under saturated demand —
**both ways on one layout**, imported from the passive benchmark rather than restated so the two
cannot drift apart:

```
                                     active            passive
  zone traversals                 4,379,794          4,379,615
  events scheduled                4,412,310          4,412,312
  events / traversal                  1.007              1.007
  wall clock (s)                       5.06               4.77
  traversals / minute            51,964,070         55,041,395
  tasks completed                    55,564                 --

  JVM: OpenJDK 21.0.10, Linux amd64, 4 processors
```

Two things to read from it. The traversal counts agree to **0.004%**, which is the check that the
two subsystems are moving the same vehicles over the same aisles — if they diverged here, every
other comparison between the paradigms would be suspect. And **events per traversal is 1.007 in
both**: deciding costs nothing in engine events, because a dispatching pass is not a zone traversal.
The ~6% in wall clock is the dispatcher's and the vehicle agents' coroutines, which is what an
object that can hold an opinion costs.

Saturation is expressed differently on the two sides, necessarily. The passive benchmark re-dispatches
each vehicle the instant it arrives, which it can do because a transporter is a thing you command.
Here nobody commands a vehicle, so the load side saturates instead: forty loads that ask again on
arrival, against twenty vehicles, so the board is never empty.

### A staging area stages one vehicle

`MoveToStagingDisposition` names an intersection, and a zone holds one
vehicle. Send three vehicles to one staging intersection and one parks
there while the other two stop on the approach — which is usually not what
was wanted, and is exactly the configuration that quietly strangles a
model. Stage on a spur per vehicle, or accept that this is a rule about
one parking space.

The consequence is worth being concrete about, because it is
counter-intuitive: a vehicle stopped on the approach to a full staging
area is *still available*, and on a one-way link it cannot leave until
whatever is in the staging zone moves. A dispatcher may therefore commit a
task to a vehicle that cannot start on it. Measure `fracTimeBlocked` across
the fleet before believing a staging-area design.

### A vehicle under repair is an obstruction too

Everything the previous heading says about a flat vehicle applies to a
broken-down one for the length of its repair: it halts on the zones it
holds and closes every route through them. The difference is that a repair
ends, so this is a delay rather than a permanent hole — but a repair
distribution with a long tail on a one-way loop will produce blocking that
looks nothing like the mean.

`Cart2:NumTimesBlocked` on the *healthy* vehicles is where that shows up,
not on the one that failed.

### A flat vehicle is a closed aisle, not an idle asset

A vehicle that runs out of charge does not simply stop working. It halts on
the zones it holds and keeps holding them for the rest of the replication,
so every route through those zones is closed and the run's congestion
statistics describe a smaller network than the one you modelled. Nothing
raises: throughput falls and the report looks like a fleet that was merely
too small.

`Agv:NumVehiclesStranded` and `Cart:NumTimesStranded` are what say it
happened, and both are written for every replication, zero included. Any
value above zero means the run's other statistics were measured on a
layout that was missing some of its aisles.

The guard is `ChargeReservePolicy`, and it must reserve for **time as well
as distance**. Reaching a charger costs both, so a reserve computed from
distance alone under-reserves exactly when the trip is slow — which on a
guide path means exactly when it is congested. A reserve that was correct
for a model with no idle draw becomes incorrect the moment you add one,
and it fails in the direction that strands vehicles.

The reserve does not conjure capacity. It trades stranded vehicles for
unserved demand, which shows up as `Agv:NumTasksNeverAssigned`. That is the
honest outcome, and it is the one to act on.

### The re-tasking threshold is not optional

`ReassigningPolicy` requires a positive `improvementThreshold` and refuses
zero at construction. Watch `numAssignmentsRevoked`: a count that rises
with the run rather than settling is churn, and the usual cause is an
inner policy that ranks tasks instead of pairings (see §4).

### A bid cannot suspend, and that is load-bearing

`BidPolicyIfc.bid` is not a suspending function, which is what makes an
auction deadline of zero mean "everyone has bid" rather than "nobody had
time to". If you find yourself wanting to consume time inside a bid, the
thing you want is a longer deadline on the policy, not a suspending bid.

### The dispatcher's queue is the waiting line; the hold queues are not

Four hold queues carry suspensions — awaiting pickup, in transit,
availability, dispatcher idle — and all four report nothing by default.
`statisticalReportingForHoldQueues(true)` switches them on for debugging,
and reaches down to the [space layer's three](ksl-guidedpath.md#find-out-who-is-suspended-in-the-middle-of-a-journey)
as well, so a model being debugged shows all seven. Turn them off again:
they put rows on the report that look like waiting lines, and two of them
— riding, and the space layer's driving queue — are not. The queue to read
is `dispatcher.taskQ`.

Note which of the space layer's three the vehicles use. A vehicle agent
waits in `drivingHoldQ` for its own body, never in `ridingHoldQ`: under
this paradigm the load waits in *this* subsystem's `inTransitHoldQ` while
the vehicle drives. Passing the load rather than the agent as the waiter
would produce a model that runs and attributes the riding to the wrong
layer.

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| `AgvDispatchException` | A policy returned a vehicle not in `context.available`. Policies may only propose from what they were given. |
| `AgvAssignmentException` | A revocation after the load was aboard. Check `Assignment.isRevocable` before revoking. |
| `AgvInvariantViolation` | Not a modelling error. The subsystem's own bookkeeping disagrees with itself; the message says which record disagrees with which. |
| `numAssignmentsRevoked` climbs without bound | Re-tasking churn. Raise the threshold, or use an inner policy that ranks pairings. |
| `numTasksNeverAssigned` positive | The horizon ended with work outstanding. Expected in a terminating run; a warning sign in a steady-state one. |
| Loads wait, vehicles idle | A disposition sending vehicles somewhere they cannot get back from, or a policy excluding them as unreachable. Check `numVehiclesIdle` against `taskQ.numInQ`. |
| Run completes, most of the fleet motionless | The passive guide's §6. Check `numObstructionsDetected`. |
| Two runs with the same seed differ | A policy reading unmanaged state, or drawing randomness from a stream the model does not own. |

---

## 7. See also

- [`ksl-guidedpath`](ksl-guidedpath.md) — **read this first.** The
  physical layer this package runs on: networks, zones, links, blocking,
  routing, zone control, and deadlock. Everything there is true here.
- [`ksl-agent`](ksl-agent.md) — the agent framework the vehicles and the
  dispatcher are built on: mailboxes, `contractNet`, runtime agents.
- [`ksl-entity`](ksl-entity.md) — the process view, and where
  `transportByAgv` sits among `KSLProcessBuilder`'s other verbs.
- [`ksl-spatial`](ksl-spatial.md) — `MovableResource` and
  `DistancesModel`, for vehicles that do not contend for space at all.
- `KSLExamples`, under `ksl.examples.general.agv`:
  - `TwoParadigmsExample` — the same shop modelled both ways, agreeing
    exactly on one vehicle. **Read this one first.**
  - `DispatchingRuleComparison` — six rules on common random numbers.
  - `RetaskingInFlightExample` — a cart turned round, with the arithmetic
    made unambiguous.
  - `MultiFloorHospitalExample` — two floors joined by lifts, with no lift
    class anywhere in it.
