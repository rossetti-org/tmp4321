# Using `ksl.modeling.guidedpath`

*Experimental.* Guided path transporters: vehicles that travel a fixed
network of aisles and must claim the space ahead of them before they can
move into it.

Code snippets here are compile-verified against the source on every build
(`KSLCore/src/test/kotlin/ksl/modeling/guidedpath/doc/GuidedPathGuideSnippets.kt`).

---

## 1. What this package is for

Use this package when **the vehicles get in each other's way, and that
matters to the answer.**

KSL already moves things through space. `MovableResource` over a
`DistancesModel` (see [`ksl-spatial`](ksl-spatial.md)) carries an entity
from A to B in `distance / velocity` time. It is the right model for a
fork-lift in an open warehouse or a worker walking a shop floor: they may
queue for each other, but they do not *block* each other, because two
people can occupy the same corridor.

Automated guided vehicles cannot. An aisle holds one vehicle at a time, a
one-way loop cannot be run backwards, and a vehicle parked at the end of a
spur is in the way of anything that needs to go past it. None of that is
representable in a distance model, and the difference is not a rounding
error.

Here is the same haul modelled both ways — same distances, same velocity,
same arrival stream, same policies — as the fleet grows:

| carts | free-path completions | guided completions | free-path time in system | guided |
|---|---|---|---|---|
| 1 | 64 | 64 | 878.4 | 878.4 |
| 2 | 128 | 128 | 752.4 | 754.6 |
| 4 | 255 | 236 | 498.5 | 538.6 |
| 6 | 380 | **236** | 248.4 | **538.6** |
| 8 | 492 | **236** | 31.0 | **538.6** |

At one and two carts the two models agree — that is the range in which a
free-path model is a fair approximation, and it is a real range. Beyond
it they part company. The guide path stops improving at four carts because
the exit spur admits one cart at a time and no size of fleet can put two
of them down it. The distance model has no such notion, so it goes on
rewarding every cart added, for ever.

A study that sized this fleet from the free-path answer would buy eight
carts, expect thirty-one minutes, and get five hundred and thirty-eight.

**The point is not that the free-path number is wrong.** It is that
nothing in a free-path model is *capable* of being wrong here: there is no
statistic it could report, however carefully read, that would reveal the
aisle it does not represent. That is what this package adds.

**When not to use it.** If your vehicles do not contend for space, this
package costs you a network to build and buys nothing. Use
`MovableResource`. If your material moves on a belt rather than on
vehicles, use `Conveyor` — see [§6, cells and zones](#cells-and-zones).

**Not modelled in this version.** Acceleration, deceleration, and
turn penalties. Traversal time is exactly `zoneLength / (velocity *
velocityFactor)`. Link direction in degrees and intersection coordinates
are carried for layout and animation and are *never read by the engine*.
If turn cost matters to your answer, model it as a `velocityFactor` on the
links either side of the turn, or as an intersection whose length
represents the time to negotiate it.

---

## 2. The mental model

**A zone is the atom of contended space.** A network is divided into
zones; a zone holds at most one transporter; a transporter claims the zone
ahead before it moves into it, and gives up the zone behind according to a
rule. Everything else in the package follows from that sentence.

Four types carry the model:

- **`GuidedPathNetwork`** — the fixed geometry: intersections, links, and
  the zones links are divided into. It is immutable, built once, and is
  itself a `SpatialModel`, so entities and transporters can be located on
  it. It knows shortest-path distances between any two intersections.
- **`GuidedPathTransportSystem`** — the `ModelElement` that *operates* a
  network: it owns which transporter holds which zone, resets all of it
  between replications, and reports congestion.
- **`GuidedTransporter`** — a vehicle. It is a capacity-one `Resource`, so
  it is seized and released by the machinery you already know.
- **`GuidedTransporterPoolWithQ`** — a fleet asked for by the group. It is
  an `AbstractResourcePool` with a `RequestQ`, so it is seized exactly as
  a resource pool or a movable resource pool is: a request is enqueued on
  **every** call, whether or not it waits, and removed when a transporter
  is allocated. An entity served immediately therefore records a wait of
  **zero** rather than no observation at all, which is what `seize` has
  always done and what the reported mean has to be over to mean anything.

The network is fixed and the system is what varies, which is why they are
two objects rather than one. (There is also a Kotlin reason: `SpatialModel`
and `ModelElement` are both abstract classes, so one object cannot be both.)

**A transporter propels itself.** There is no fleet-wide movement event.
Each transporter claims the zone ahead, schedules its own arrival, and on
arriving either claims the next or stops. A transporter that cannot
proceed schedules *nothing at all* and costs the executive nothing while
it waits; it is started again by whoever releases what it is waiting for.
This is the fundamental difference from a `Conveyor`, where one engine
advances everything at once.

**Blocking is normal.** A transporter waiting for the zone ahead is not an
error and not a queue discipline failure. It is the phenomenon you came
here to measure.

---

## 3. Quick start

A one-way loop with a spur to the exit station, two carts each with a
parking spur of its own, and parts carried from entry to exit.

```kotlin
val network = GuidedPathNetwork.builder("SimpleAgv")
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
```

Then the model. Note `spatialModel = network`: the parts travel on the
guide path, so it is their spatial model too.

```kotlin
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
```

That is the whole thing. `guidedTransport` asks for a cart, waits for one
to arrive, is carried, and gives the cart back.

**Three things in that model are load-bearing, and leaving any of them out
produces a run that looks fine and is not.** The loop is one-way; the exit
station is on a spur; and every cart has a home base. §6 explains each.

---

## 4. How do I…?

### …decompose the transport?

`guidedTransport` is the composed verb. When something has to happen
between being picked up and being set down, use the three it is built
from:

```kotlin
val request = requestGuidedTransporter(carts, pickupLocation = "EntryStation")
delay(1.0)                                        // load by hand, say
val result = transportBy(request, destination = "ExitStation")
delay(2.0)                                        // unload
releaseGuidedTransporter(request, carts)
```

The request is your claim on that transporter. It goes inert the moment
you release it: using it again raises rather than quietly commanding a
transporter that now belongs to someone else.

### …find out what a journey cost?

`transportBy` and `guidedTransport` both return a `GuidedTransportResult`:

```kotlin
val result = guidedTransport(carts, destination = "ExitStation")
val lost = result.blockedTime      // time unable to claim the space ahead
val far = result.routeLength
val zones = result.zonesTraversed
```

`blockedTime` is the quantity a free-path model cannot produce at all.
The same figures are also accumulated into system-level responses
(`transportBlockedTime`, `routeLengthPerTransport`, and the rest), so you
get the fleet summary without writing an observer.

### …decide which cart gets sent?

Pass an allocation rule to the pool. The default,
`ClosestByNetworkDistanceRule`, minimises empty running.

```kotlin
GuidedTransporterPoolWithQ(
    this, system, listOf(cart1, cart2),
    allocationRule = LeastUsedTransporterRule(),
    idleDispositionRule = ReturnToHomeBaseRule(),
    name = "Carts"
)
```

Distance here is distance **along the guide path**, never separation in
space. On a one-way loop a cart standing a few feet past the pickup may
have to go all the way round, so a straight-line rule would routinely send
the wrong one — quietly, with no symptom but a fleet that performs worse
than it should.

Writing your own is a one-method interface, and it does not need to be in
this package:

```kotlin
class AlphabeticalDispatchRule : GuidedTransporterAllocationRuleIfc {
    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter = candidates.minByOrNull { it.name }!!
}
```

### …decide where idle carts wait?

This is the decision that most often decides whether a model works at all.
See §6. Three rules ship: `ParkInPlaceRule` (the default, and the one to
change), `ReturnToHomeBaseRule`, and `MoveToStagingAreaRule`.

### …give a vehicle a physical length?

Two ways, and they are not interchangeable. `lengthInZones` is the usual
one: a vehicle two zones long covers two, and the network refuses a spur
too short to hold it. `physicalLength` sizes the vehicle in the network's
own length units instead, for a vehicle **shorter than a zone**:

```kotlin
GuidedTransporter(
    system, TransporterPlacement.At("I6"), ConstantRV(10.0),
    zoneControlRule = StartOfZoneControl(), name = "Cart", physicalLength = 6.0
)
```

It cannot be combined with `lengthInZones`, and it must fit inside the
smallest zone in the network — both are checked at construction.

What it buys is one thing only, and it is worth knowing exactly what:
a vehicle parked at a **dead end** has already covered its own length of
the spur, so leaving costs `physicalLength` less than entering did. That
asymmetry is real, it is what the reference tool does, and it is the
difference between agreeing with that tool to thirteen decimal places and
being visibly out. Generalising the credit to every junction was tried and
is wrong — it overshoots badly, and the code records the measurement that
rejected it.

If your vehicles are a whole number of zones long, ignore this parameter.

### …model a system on more than one floor?

Nothing special, and that is the point. A guide path routes on **declared link lengths**, never on
coordinates, so nothing in the network knows or cares that two of its intersections are one above
the other. A lift is therefore expressible as exactly what it physically is — a link of a single
zone:

```kotlin
// Two shafts joining the floors, each one zone long. The rest of the builder is ordinary corridors.
.link("ShaftUp", "Ground3", "First1", length = 40.0, zoneLength = 40.0, beginDirection = 90.0)
.link("ShaftDown", "First3", "Ground1", length = 40.0, zoneLength = 40.0, beginDirection = 270.0)
```

One zone admits one vehicle, so the shaft excludes everybody else for the duration of a ride without
a line being written to make it do so. There is no lift class, no floor concept, and no special case
anywhere in the engine — the exclusion is the zone rule you already have. Give the shaft its own
`velocityFactor` if a lift is slower than the corridors.

Two things to know before trusting a two-floor model. Ask a shaft zone `isHeld`, **not**
`isOccupied`: a link's last zone is never occupied, because arriving at its far end means arriving
at the junction beyond, so `isOccupied` reports an idle single-zone lift and `isHeld` reports the
truth. And give the intersections honest `x`/`y` coordinates anyway — the engine never reads them,
but an animation does, and two floors drawn on top of each other are unreadable. Offsetting one
floor is a layout decision, not a modelling one.

`ksl.examples.general.agv.MultiFloorHospitalExample` is a worked two-floor system; it uses the
active subsystem, but the network idiom above is the whole of what makes the floors work and applies
here unchanged.

### …change how closely carts may follow one another?

The zone control rule decides when a transporter gives up the zone
*behind* it, which is what sets the separation between vehicles:

- `EndOfZoneControl` (default) — release on arriving in the next zone, so
  a follower stays a full zone back.
- `StartOfZoneControl` — release the moment travel begins, so a follower
  may close up immediately.
- `DistanceIntoZoneControl(d)` — release after travelling `d` into the
  next zone: the general case, with the other two as its endpoints.

```kotlin
GuidedTransporter(system, TransporterPlacement.At("I6"), ConstantRV(10.0),
    lengthInZones = 1, zoneControlRule = StartOfZoneControl(), name = "Cart")
```

This is a modelling parameter, not a tuning knob: the control system of a
real installation implements one of these, and which one it implements
changes throughput.

### …collect congestion statistics per link or per zone?

Off by default, because a thousand-zone network would otherwise register a
thousand responses in every report and every output database.

```kotlin
val system = GuidedPathTransportSystem(
    this, network, collectLinkStatistics = true, name = "Sys"
)
```

Both flags can also be set later, up to the moment the model runs, and
either direction takes effect at once — switching one on registers its
responses, switching it off removes them.

Always registered, whatever the network size: `numTransportersMoving`,
`numTransportersBlocked`, `numTransportersIdle`, `zoneUtilization`,
`numDeadlocksDetected`, `numObstructionsDetected`, the six per-transport
responses, and per transporter `fracTimeBlocked` and `numTimesBlocked`.

### …find out who is suspended in the middle of a journey?

Three queues hold them, split by what the wait *is*:

```kotlin
system.awaitingPickupHoldQ   // standing where they are, while a transporter comes for them
system.ridingHoldQ           // aboard one
system.drivingHoldQ          // driving one -- always empty under this paradigm; see below
```

**None of the three reports anything.** A hold queue is how a suspended
entity is found again; it is not a waiting line, and letting it double as
the statistic conflates a mechanism with a measurement. `RidingHoldQ`'s
time in queue would be the mean length of a loaded move and its number in
queue a count of moving carts — read by anybody scanning a report as a
line of entities waiting for something. Both quantities are already
reported properly by `emptyMoveTime` and `loadedMoveTime`, which is what to
use. `Conveyor` makes the same call for the same three-way split.

```kotlin
system.statisticalReportingForHoldQueues(true)   // for debugging a model that stopped moving
```

`drivingHoldQ` is empty here and is not dead weight: it is where the
[active subsystem's](ksl-agv.md) vehicle agents wait for their own body to
finish a leg — including a leg with nothing aboard, which is a wait neither
of the other two describes.

### …animate it?

Nothing to switch on. When an animation sink is active the system emits
`GuidedPathDefined` once per replication and a `GuidedTransporterMoved`
each time a transporter enters a zone. The guide path carries its own
coordinates, so — unlike a conveyor — it needs no authored layout.

### …check the subsystem's own bookkeeping?

Two audits, at two costs:

```kotlin
system.checkInvariants = true          // every clock advance; expensive, for development
system.auditAtReplicationEnd = true    // once per replication; on by default
```

`checkInvariants` re-establishes zone exclusivity whenever the clock
advances: no zone held by two transporters, no transporter holding zones
it does not cover, spur reservations consistent with occupancy. Its
default comes from the `ksl.guidedpath.checkInvariants` system property,
so a whole study can be run under checking without editing a model.

The closing audit runs once as each replication ends and adds the blocked
clocks to that: a blocked clock runs if and only if the transporter is
blocked, and every cumulative blocked time is finite and non-negative. It
is on by default because it costs one pass over the fleet per replication.

Neither is a modelling check. They fail when the *subsystem* has got
something wrong, which is why they raise rather than warn.

### …handle a deadlock in a parameter sweep?

A run that deadlocks raises. That is deliberate: the model is valid and
the answer is "this configuration deadlocks", which is often the finding a
study is after. In a sweep over fleet size, catch it and record the design
point as infeasible rather than letting it end the study:

```kotlin
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
```

Do not "fix" this by disabling detection. The run would still deadlock; it
would simply stop saying so, and the design point would be recorded as
though it had worked.

---

## 5. The key types at a glance

| Type | What it is |
|---|---|
| `GuidedPathNetwork` | The immutable geometry, and a `SpatialModel`. Built by `GuidedPathNetwork.builder(...)`. |
| `GuidedPathNetwork.Intersection` | A junction, and a `LocationIfc`. Station names are aliases for intersections. |
| `Link` | A one-way, two-way, or spur aisle between two intersections, divided into zones. |
| `Zone` | The atom of contended space: `LinkZone` along a link, `IntersectionZone` at a junction. Holds at most one transporter. |
| `GuidedPathTransportSystem` | The `ModelElement` operating a network. Owns zone occupancy, resets between replications, reports congestion. |
| `GuidedTransporter` | A vehicle; a capacity-one `Resource`. |
| `GuidedTransporterPoolWithQ` | A fleet asked for by the group, with the queue of entities waiting for one. |
| `GuidedTransportRequest` | An entity's claim on a transporter. Inert after release. |
| `GuidedTransportResult` | What a journey cost, including `blockedTime`. |
| `awaitingPickupHoldQ` / `ridingHoldQ` / `drivingHoldQ` | Where a waiter on a journey is suspended, split by what the wait is. Mechanism, not measurement: none of them reports. |
| `TransporterPlacement` | Where a transporter starts: `At(location)` or `OnZone(zoneName)`. Re-applied every replication. |
| `GuidedPathDeadlockException` | A circular wait, carrying a `DeadlockReport` naming every participant. |
| `IdleTransporterObstruction` | A transporter blocked behind one that will never move. Warned and counted, not thrown. |

The five replaceable policies: `RouteSelectionRuleIfc` (on the network),
`ZoneContentionRuleIfc` (on the system), `ZoneControlRuleIfc` (per
transporter), `GuidedTransporterAllocationRuleIfc` and
`IdleDispositionRuleIfc` (on the pool).

---

## 6. Gotchas & best practices

### A destination is a resource

**This is the one to read.** A transporter that stops goes on holding the
zones it stands on, for the rest of the run. So any model in which two
transporters finish in the same place will have the first arrival block
the second — and the second is not delayed, it waits *for ever*, because
nothing will ever move the first.

The symptom is a run that completes, reports no error, and quietly
contains a fleet that stopped working halfway through.

This is what parking spurs and staging areas are for, and it is why
`homeBase` and `ReturnToHomeBaseRule` exist. In the simple AGV shop, with
the carts left where they stop:

|  | carts sent home | carts left in place |
|---|---|---|
| parts delivered | 349.9 | 349.8 |
| time in system | 61.76 | 62.96 |
| obstructions detected | 0.0 | 40.5 |
| fraction of fleet blocked | 0.0052 | 0.0742 |

Neither run fails. Throughput is identical, because this shop is
arrival-limited. The *only* clear signal is the obstruction count — which
is why that condition is counted into the standard report rather than
merely logged. Watch `numObstructionsDetected`; a positive value means
something in your layout is standing in the way.

The subsystem tells you two conditions apart, and the distinction matters
because the fixes are opposite:

- **`GuidedPathDeadlockException`** — a circular wait. Cannot resolve
  itself, so it raises and names the participants.
- **`IdleTransporterObstruction`** — a transporter behind one that has
  nothing scheduled. *Can* resolve itself the moment something dispatches
  the idle transporter, so it warns and counts. Set
  `strictObstructionPolicy = true` to promote it to an exception, and
  expect occasional false alarms in exchange for certainty.

### Prefer one-way links

Two-way links are where deadlock comes from. The direction lock stops two
vehicles meeting head-on *on the link* — but it cannot stop the vehicle
waiting at the mouth from standing on the first vehicle's *destination*,
and that closes the cycle just as effectively:

```
Outbound holds [Both.Zone3] and awaits B
Inbound  holds [B]          and awaits Both.Zone3
```

The network warns at `build()` about every two-way link, and about any
spur too short to contain the longest declared vehicle.

### A staging area stages one vehicle

`MoveToStagingAreaRule` names an intersection, and a zone holds one
vehicle. Send three carts to one staging intersection and one parks there
while the other two stop on the approach.

That is sometimes exactly what a layout intends — a queue of idle vehicles
waiting their turn — but it has a consequence worth being concrete about,
because it is counter-intuitive. **A cart stopped on the approach is still
available.** It is in the pool, it can be allocated, and on a one-way link
it cannot leave until whatever is in the staging zone moves. Worse, a
transporter part-way along a link reports its location as that link's
*far end*, so a cart queued for the staging area is ranked by
`ClosestByNetworkDistanceRule` exactly as the cart already sitting there
is — and ties go to whichever was declared first. A dispatching rule can
therefore commit a job to a cart that cannot start on it.

Measure `fracTimeBlocked` across the fleet before believing a
staging-area design. Where it matters, give each cart a spur of its own
and use `ReturnToHomeBaseRule` instead.

### How far this has been checked

Four models built in a commercial guided-path tool have been reproduced
here and compared statistic by statistic, with the fixtures and the
comparisons kept as tests
(`KSLCore/src/test/kotlin/ksl/modeling/guidedpath/*CrossCheckTest.kt`).
Agreement is judged by `z = |difference| / sqrt(h1² + h2²)`, so **z ≤ 1 is
agreement at 95%** for two independent estimates.

| Model | Fleet | Result |
|---|---|---|
| Simple AGV shop, deterministic | 2 | All ten quantities agree; largest difference 4e-13 |
| Test and repair, two-way aisles | 1 | Shop agrees, worst z = 0.89; aisle out by a third of a percent |
| Test and repair, one-way aisles | 2 | Shop agrees, worst z = 0.84; aisle agrees, z = 0.11 |
| Painting flow line, staging area | 3 | Shop agrees, worst z = 0.59; the fleet does not — see below |

Two of these found something. The deterministic model agreed only after
`physicalLength` was implemented, because the reference tool's vehicle was
sized by length and gets its own length back when it reverses out of a
dead end. The painting flow line does **not** agree on the fleet, and the
cause is structural rather than numerical: that tool cannot move an
unallocated vehicle, so its trip to the staging area is an ordinary
transport made by a duplicated entity — the vehicle is *busy* and cannot
be diverted, where here it is neither. Committed vehicle time agrees to
2.3%; how that time is classified does not.

The point of saying so here is that these are the terms on which the
subsystem has been checked. The shop around a guide path reproduces to
within sampling error in every case tried. The guide path itself
reproduces where the two tools mean the same thing by a vehicle's time,
and the one place they do not is written down rather than tuned away.

### Cells and zones

`Conveyor.Cell` and `Zone` look alike and are not the same idea.

| | `Conveyor.Cell` | `Zone` |
|---|---|---|
| What moves | One engine advances the whole belt | Each transporter propels itself |
| Blocking | Propagates backwards down the line | Local: the blocked vehicle simply schedules nothing |
| Cost of waiting | The belt keeps being advanced | Zero — a waiting transporter has no events |
| Routing | The belt's fixed path | Shortest path over a network |
| What occupies it | An item, which does not choose | A vehicle, which chooses where to go next |

If your material has no say in where it goes and moves in lockstep, it is
a conveyor. If each vehicle decides its own route and stops independently,
it is a guide path.

### One network per model

A `GuidedPathTransportSystem` takes ownership of its network's zone state.
Two systems on one network would share that state and corrupt each other,
so the second attachment is refused with an exception naming the system
that got there first. Build a network per model; they are cheap.

### Choose zone size from control granularity, not from animation

One zone traversal is one scheduled event. Halving the zone size doubles
the event count for the same motion. Choose zone size from the granularity
at which the real control system reserves space — not from how smooth the
animation looks.

The reference benchmark (`./gradlew :KSLExamples:guidedPathBenchmark`) is
a twenty-intersection, forty-link network of 420 zones carrying twenty
vehicles under saturated demand:

```
zone traversals    : 4,379,615
events / traversal : 1.007
wall clock         : 4.60 s
throughput         : 57,156,149 zone traversals per wall-clock minute
JVM                : OpenJDK 64-Bit Server VM 21.0.10, Linux amd64, 4 processors
```

Events per traversal is the number to watch. One is the floor — a
transporter with a clear path ahead schedules a single event per zone.
`DistanceIntoZoneControl` adds a second by design and lands near two.
Anything much above that is transporters being woken and refused, which is
a performance defect that leaves every answer correct.

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| Run completes, most of the fleet motionless, no error | An idle transporter in the way. Check `numObstructionsDetected` and give the fleet home bases. |
| `GuidedPathDeadlockException` | A circular wait. The report names both vehicles and both zones. Nearly always a two-way link — make it one-way, or route around it. |
| `GuidedPathRoutingException: No path exists` | A one-way link traversed against its direction. Check link direction, not distance. |
| "would both occupy zone" at startup | Two transporters placed on the same zone. Placements are re-applied every replication, so this fails immediately rather than drifting. |
| Fleet performs worse as you add vehicles | Expected, and the reason this package exists. Look at `numTransportersBlocked` and the per-link utilization. |
| Model refuses to build with a second system | One network per model — see above. |

---

## 7. See also

- [`ksl-agv`](ksl-agv.md) — the **active** paradigm over this same
  physical world: vehicles that decide for themselves, a dispatcher with a
  process of its own, and the batching, negotiation and re-tasking that a
  pool's allocation rule cannot express. Everything in this guide is true
  there.
- [`ksl-spatial`](ksl-spatial.md) — `MovableResource` and `DistancesModel`:
  the free-path family this package sits beside. Start there if your
  vehicles do not contend for space.
- [`ksl-entity`](ksl-entity.md) — the process view, `Resource`, `seize` /
  `release`, and `Conveyor`. The guided-path verbs are members of
  `KSLProcessBuilder` alongside `transportWith` and `convey`.
- [`ksl-simulation`](ksl-simulation.md) — `ModelElement` lifecycle, which
  is where placement reset between replications happens.
- `KSLExamples`: `ksl.examples.general.guidedpath.SimpleAGVExample` and
  `GuidedPathThroughputBenchmark`;
  `ksl.examples.book.chapter8.TestAndRepairShopWithGuidedTransporters`,
  which is the chapter-eight shop with its transport moved onto an aisle.
