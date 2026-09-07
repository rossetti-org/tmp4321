# Vehicles in KSL — a tutorial through the examples

*Ten worked cases.* Each one states a problem, describes the model, shows what
it produced, and says what that is evidence **for**. Every figure on this page
came from running the example named beside it; none is recalled or estimated.

If you have not met the four transport subsystems, read
[`ksl-transport`](ksl-transport.md) first — it is one page and it is the map
this tutorial walks over.

---

## How to read this tutorial

The examples are ordered so that each one needs only what came before it.

| # | Case | Run it with |
|---|---|---|
| 1 | [A simple AGV shop](#1-a-simple-agv-shop) | `:KSLExamples:simpleAgvExample` |
| 2 | [The same shop, both paradigms](#2-the-same-shop-modelled-both-ways) | `:KSLExamples:twoParadigmsExample` |
| 3 | [Free path against guide path](#3-free-path-against-guide-path) | *(a model class; see below)* |
| 4 | [Six dispatching rules](#4-six-dispatching-rules) | `:KSLExamples:dispatchingRuleComparison` |
| 5 | [Turning a cart round](#5-turning-a-cart-round) | `:KSLExamples:retaskingExample` |
| 6 | [A hospital on two floors](#6-a-hospital-on-two-floors) | `:KSLExamples:multiFloorHospitalExample` |
| 7 | [A two-lane warehouse](#7-a-two-lane-warehouse) | `:KSLExamples:twoLaneWarehouseExample` |
| 8 | [A dispatcher with no aisles](#8-a-dispatcher-with-no-aisles) | `:KSLExamples:freePathFleetExample` |
| 9 | [What the engine costs](#9-what-the-engine-costs) | `:KSLExamples:guidedPathBenchmark` |
| 10 | [What deciding costs](#10-what-deciding-costs) | `:KSLExamples:agvBenchmark` |

**A note on the output.** Several examples print warnings above their tables —
horizon diagnostics, and in two cases deadlock reports logged at ERROR. Those
are the audit doing its job, not a fault. Read the tables last.

---

## 1. A simple AGV shop

`ksl.examples.general.guidedpath.SimpleAGVExample`

### The problem

Two carts carry parts from an entry station to an exit station in a small shop.
This is the smallest layout that is worth building, and the point of it is that
**the layout is the model**.

### The model

A one-way loop with a spur down to the exit station and a parking spur for each
cart. Four properties of that description are load-bearing:

- **The main loop runs one way.** Two carts can queue behind one another but can
  never face each other, so head-on deadlock is impossible rather than merely
  unlikely.
- **The exit station is on a spur.** A spur admits one cart. The second cart
  sent there waits at the mouth, out on the loop, rather than following the
  first in and facing it with neither able to reverse.
- **Each cart has a parking spur of its own.** A stopped cart goes on holding
  the zones it stands on.
- **Zone sizes differ between links.** The loop is discretised at twelve feet so
  two six-foot carts cannot close to less than six; the home spurs are six feet
  and get a zone size of their own. Zone size belongs to a *link* precisely so
  that this is expressible.

### What it shows

The example ships a second run, `runWithoutHomeBases()`, which changes one thing
— the carts are left where they stop — and produces this:

| | carts sent home | carts left in place |
|---|---|---|
| parts delivered | 349.9 | 349.8 |
| time in system | 61.76 | 62.96 |
| **obstructions detected** | **0.0** | **40.5** |
| fraction of fleet blocked | 0.0052 | 0.0742 |

### What to learn

**Neither run fails, and throughput is identical.** This shop is arrival-limited,
so the damage does not reach the headline number. The *only* clear signal is the
obstruction count — which is why that condition is counted into the standard
report rather than merely logged.

> **A destination is a resource.** A transporter that stops goes on holding its
> zones for the rest of the run. Any model in which two vehicles finish in the
> same place will have the first arrival block the second — and the second is not
> delayed, it waits for ever.

This is the single most likely way for a working-looking guide-path model to be
quietly wrong. Watch `numObstructionsDetected`; a positive value means something
in your layout is standing in the way.

---

## 2. The same shop, modelled both ways

`ksl.examples.general.agv.TwoParadigmsExample` — **read this one first if you
read only one.**

### The problem

KSL can model the same vehicles two ways. Under the **passive** paradigm an
entity seizes a cart from a pool; under the **active** paradigm a dispatcher
decides. Are these two models of one world, or two different worlds?

### The model

One shop, built twice. The physical world is identical in both runs — same guide
path, same zones, same routing, same blocking rules — and every line that differs
is a line about *who decides*:

```kotlin
guidedTransport(carts, destination = EXIT, pickupLocation = ENTRY)   // passive
transportByFleet(agv,  destination = EXIT, origin = ENTRY)           // active
```

The two lines look similar and mean something quite different. Under the passive
paradigm the choice of *which* cart is made inside the part's own process, at
the instant it happens to ask, over whatever is free then. There is nowhere else
it could be made, because no other object is running.

### What it shows

With one cart, "closest idle transporter" and "nearest vehicle" are the same
rule — there is only ever one candidate — so the models should agree. They do:

| | passive | active |
|---|---|---|
| parts delivered | 174.90 | 174.90 |
| time in system | 88.2894 | 88.2894 |
| cart transporting | 0.5096 | 0.5096 |
| cart moving empty | 0.3636 | 0.3636 |

**Exactly, to the digit** — not within a confidence interval.

### What to learn

That agreement is the load-bearing result of the whole subsystem. Had the
answers differed, this would not be a second way of modelling one world; it
would be a different world, and every comparison a researcher wanted to make
between paradigms would be confounded by the modelling choice itself.

The example then prints what **only** the active model can report — the wait
split into *waiting to be assigned* and *waiting for arrival*. A passive pool
has no object that holds a commitment, so there is no instant at which a
decision was made to measure from.

---

## 3. Free path against guide path

`ksl.examples.book.chapter8.TestAndRepairShopWithGuidedTransporters`

This is a model class rather than a runnable study: it is the guide-path twin of
`TestAndRepairShopWithMovableResources`, built so the two can be compared.

### The problem

When does it matter that vehicles must follow aisles?

### The model

Chapter 8's test-and-repair shop, with its three transport workers moved off a
distance model onto a guide path. **Everything about the work is identical** —
the same four test plans with the same probabilities, the same processing-time
distributions, the same repair times, the same arrival process, the same five
stations, the same three transporters, the same walking speed. Only the *space*
changes.

The leg lengths are taken from the free-path model's own distances along the
cycle, so the two models agree about how far apart things are and disagree only
about what a worker must do to get between them.

### What it shows

Run on a comparable haul as the fleet grows (the table in
[`ksl-guidedpath` §1](ksl-guidedpath.md#1-what-this-package-is-for)):

| carts | free-path completions | guided | free-path time in system | guided |
|---|---|---|---|---|
| 1 | 64 | 64 | 878.4 | 878.4 |
| 2 | 128 | 128 | 752.4 | 754.6 |
| 4 | 255 | 236 | 498.5 | 538.6 |
| 6 | 380 | **236** | 248.4 | **538.6** |
| 8 | 492 | **236** | 31.0 | **538.6** |

### What to learn

At one and two carts the two models agree — **that is a real range**, and inside
it a free-path model is a fair approximation. Beyond it they part company. The
guide path stops improving at four carts because the exit spur admits one cart
at a time and no size of fleet can put two of them down it. The distance model
has no such notion, so it goes on rewarding every cart added, for ever.

A study that sized this fleet from the free-path answer would buy eight carts,
expect thirty-one minutes, and get five hundred and thirty-eight.

> **The point is not that the free-path number is wrong.** It is that nothing in
> a free-path model is *capable* of being wrong here: there is no statistic it
> could report, however carefully read, that would reveal the aisle it does not
> represent.

---

## 4. Six dispatching rules

`ksl.examples.general.agv.DispatchingRuleComparison`

### The problem

Does the dispatching rule matter, and how would you know?

### The model

One shop, three carts, six rules, common random numbers throughout. Because
deciding is a substitutable object, the study changes the rule and nothing else.

Two pickup points, deliberately: on a single-origin layout every task costs a
given vehicle the same, so rules that rank *tasks* differently cannot be told
apart — the comparison would be unfalsifiable and would quietly report that the
choice does not matter.

### What it shows

```
  rule                        delivered         wait    in system   imbalance
  nearest vehicle                 323.6        11.49        31.51       69.93
  furthest vehicle                323.6        26.54        46.57       46.60
  least used                      323.9        20.53        40.57        1.00
  batched (window 30)             283.8       820.02       860.96        1.13
  contract net (instant)          323.6        11.50        31.53       70.40
  contract net (deadline 5)       323.6        21.33        41.47       50.93
```

### What to learn

**Five of the six deliver within 0.3 loads of one another.** With a fleet this
size the guide path is the constraint, not the decision — so a study that
measured throughput alone would conclude that dispatching does not matter here,
and would be wrong about everything except throughput.

What the rule changes is **who waits and how evenly the fleet is worn**.
Least-used leaves an imbalance of 1.00 against nearest-vehicle's 69.93, at
throughput differing in the second decimal place. Whether that matters depends
on whether the cost being managed is time or wear — a modelling question, not a
library one.

**Batching is the exception, instructively.** It costs 39.8 loads and 820 time
units of waiting against nearest-vehicle's 11. A window pays for itself when a
fleet has slack and the board has choices to weigh; this fleet is saturated, so
the window delays every decision and the delay compounds. The rule is not broken
— it is being asked to do the thing it is worst at, which is what a comparison
is for.

**And one check rather than a coincidence:** the instant auction reproduces
nearest-vehicle almost exactly, 11.50 against 11.49. With distance bidding the
vehicles quote what the rule would have computed, so the negotiation machinery
is shown not to be changing the answer by itself. The deadline row then shows
what it costs once negotiating is charged for.

**`FurthestVehiclePolicy` is deliberately poor** and exists so that "nearest is
better" can be a finding rather than an assertion.

---

## 5. Turning a cart round

`ksl.examples.general.agv.RetaskingInFlightExample`

### The problem

A cart is on its way to a far pickup when a nearer job appears. Should it turn
round — and what stops a fleet that can from doing it constantly?

### The model

A one-way ring of four legs of 100 with the cart parked on a spur. The
arithmetic is made unambiguous: at **t = 2** the cart has travelled 20 and
stands at a junction; its own pickup is 300 ahead, the new one 100. The swap
saves 200.

Three runs: no re-tasking; re-tasking with the near job at t = 2; and
re-tasking with the near job at t = 15, by which point the swap would *cost*
200.

### What it shows

```
  With re-tasking, near job at t=2 (worth 200 units): the cart is turned round.
    near   delivered at    60.0   waited   50.0   reassignments 0
    far    delivered at   102.0   waited   72.0   reassignments 1
    revocations: 1

  With re-tasking, near job at t=15 (would cost 200): the rule declines the swap.
    far    delivered at    62.0   waited   32.0   reassignments 0
    near   delivered at    87.0   waited   77.0   reassignments 0
    revocations: 0
```

### What to learn

**The middle case is the capability; the third is what makes it a rule rather
than a reflex.** A policy that always swapped would produce the middle result
and the wrong third one, and on a busy floor it would churn — revoking and
re-revoking as the board shifts, with carts spending their time changing their
minds. `ReassigningPolicy` therefore requires a positive `improvementThreshold`
and refuses zero at construction.

Two details worth noticing:

- **The cart never reverses.** A redirect takes effect at the next zone
  boundary, because something between two places cannot stop and turn. That is
  the same code the passive subsystem has always used.
- **The put-back load keeps its accumulated wait**, because the task never left
  the queue. A load that has been waiting longest still looks like one, and the
  fact that it was passed over is reported (`numReassignments`) rather than
  absorbed.

And the reason this is an *active*-paradigm example at all: it is not that the
movement machinery cannot redirect a moving transporter — it always could. It is
that under the passive paradigm a transporter belongs to the entity that seized
it, so **the decision has nowhere to live**.

---

## 6. A hospital on two floors

`ksl.examples.general.agv.MultiFloorHospitalExample`

### The problem

How do you model a lift?

### The model

You do not. A guide path routes on **declared link lengths**, never on
coordinates, so nothing in the network knows that two of its intersections are
one above the other. A lift is therefore expressible as what it physically is: a
one-way link of a single zone. The zone rule already says one zone admits one
vehicle, so the shaft excludes everybody else for the duration of a ride without
a line being written to make it do so.

A one-way circuit climbs one shaft and descends the other, so **every delivery
cycle rides each shaft exactly once**. Three studies run on it.

### What it shows

With an 8-unit ride, throughput scales cleanly to four porters — 2.486, 4.971,
7.486, 10.000 per 100 units, essentially 2.5 apiece — then **stops dead at
12.486** for six porters and for eight.

That ceiling is not an artefact of the fleet or the rule: an 8-unit ride passes
at most 12.50 deliveries per 100 units, which is five porters' worth.

What the surplus porters do instead is exact:

| porters | blocked | porters' worth standing still |
|---|---|---|
| 6 | 0.1667 | 6 × 0.1667 = **1** |
| 8 | 0.3750 | 8 × 0.3750 = **3** |

Precisely the surplus over the five the shaft will carry. Cycle time rises to
match, 60.0 at four porters to 80.0 at eight. With a 2-unit ride instead, eight
porters deliver 19.94 per 100 against the 20.00 perfect scaling would give — the
fleet never comes near that shaft's 50 per 100. **Same circuit, same porters,
same rule, same code; a different lift.**

### What to learn

**Buying porters buys queue.** Past a constraint, added vehicles convert into
blocked time at a rate you can predict from the constraint's capacity.

The example also checks its own columns against **Little's law**: orders
outstanding = throughput × cycle time. Eight porters with the slow lift,
0.12486 × 80.00 = 9.99 against 10 orders out; with the fast lift, 0.19943 ×
49.98 = 9.97. It holds because the system is closed — which is also why cycle
time here is a number about the hospital rather than about the length of the
run.

> **What none of this needed:** an elevator class, a floor attribute, a capacity
> semaphore, or a branch anywhere in the dispatcher or the vehicle control loop.

Heights are carried for the picture only — the engine never reads them — so
placing the floors changed not one number.

---

## 7. A two-lane warehouse

`ksl.examples.general.agv.TwoLaneWarehouseExample`

### The problem

A rectangular warehouse of pick aisles and cross-aisles, each wide enough for
traffic both ways. Is that one network or two? What is the second lane worth?

### The model

**A lane is a link.** Two lanes on one span are two links, opposed — and it is
**one network**. Nothing keys on the pair of endpoints, so a second link between
the same junctions is not a duplicate of anything. Two networks would be worse
than redundant: routing, blocking and deadlock detection are per network, so a
vehicle on one could not see a vehicle on the other, and the whole point of a
road layout is that the directions share the junctions.

A vehicle changes direction by taking the return lane, which is ordinary routing
rather than a manoeuvre.

Three pick aisles, two cross-aisles, one dock, a parking spur per cart. **Demand
is set above what the building can serve on purpose**, so the layout rather than
the arrival stream is what limits the answer.

### What it shows

Two-lane, sweeping the fleet:

| carts | delivered | in system | blocked |
|---|---|---|---|
| 2 | 443.7 | 2007.78 | 2.2% |
| 4 | 834.7 | 1132.61 | 8.0% |
| 6 | 1109.4 | 525.30 | 18.6% |
| 8 | 1111.0 | 522.16 | 38.9% |
| 10 | 1111.0 | 523.06 | 51.1% |
| 12 | **DEADLOCK** | — | — |

The same building with single two-way aisles **deadlocks at two carts**.

### What to learn

**Three findings, and the third is the one to take away.**

1. **Throughput ceilings at six carts.** Where the reward stops is the number a
   fleet-sizing study exists to find.
2. **The carts added past it are not idle — they are blocked.** 6 → 10 carts
   moves deliveries 1109.4 → 1111.0 while fleet time blocked goes 18.6% → 51.1%.
3. **The grid gridlocks at twelve**, among six transporters:

```
Cart5 holds [T1]           awaits T1-T0.Zone1
Cart3 holds [T1-T0.Zone1]  awaits T1-T0.Zone2
Cart9 holds [T1-T0.Zone2]  awaits T0
Cart7 holds [T0]           awaits T0-T1.Zone1
Cart6 holds [T0-T1.Zone1]  awaits T0-T1.Zone2
Cart8 holds [T0-T1.Zone2]  awaits T1
```

Both lanes of one span full nose to tail, and the junction at each end held by a
vehicle wanting the lane the others are standing in. **Blocking the box** —
ordinary traffic gridlock.

> **Paired one-way lanes are not deadlock-proof.** They remove the head-on
> meeting *on a link*. They do nothing about a cycle closing through the
> junctions at each end of a span. A second lane raises the fleet a layout can
> carry — one cart to ten, here — but it does not remove the ceiling.

**A run that deadlocks raises**, which is deliberate: the model is valid and the
answer is "this configuration deadlocks", often the finding a study is after.
Both sweeps catch it and record the design point as infeasible. Do not "fix" it
by disabling detection — the run would still deadlock and would simply stop
saying so.

---

## 8. A dispatcher with no aisles

`ksl.examples.general.fleet.FreePathFleetExample`

### The problem

You want a fleet that decides for itself, and your vehicles do **not** contend
for space — a fork-lift yard, a porter pool, a field-service crew. They queue
for *work*, never for *aisles*.

### The model

Two lines name the substrate:

```kotlin
val fleet = FreePathFleet(this, plane, places, ...)
val cart  = FreePathVehicle(fleet, "Depot", ConstantRV(30.0), ...)
```

Everything else — the batching window, the consolidating policy, the load
capacity, the `transportByFleet` call, every statistic — is the fleet layer, and
is written exactly as it would be over a guide path. Exchanging those two lines
for `AgvSystem`/`AgvVehicle` and a network is all it takes to run the same study
on aisles that push back.

Two cart capacities against two batching windows.

### What it shows

| | delivered | in system | blocked | loads/move |
|---|---|---|---|---|
| capacity 1, window 25 | 350.4 | 43.84 | 0.0000 | — |
| capacity 4, window 25 | 350.3 | 35.80 | 0.0000 | 1.332 |
| capacity 1, window 40 | 342.9 | **224.82** | 0.0000 | — |
| capacity 4, window 40 | 350.8 | 43.42 | 0.0000 | 1.581 |

### What to learn

**Read the throughput column first.** At window 25 the two rows deliver the same
load, so the times beside them are comparable and carrying up to four cuts time
in system by 18%. At window 40 the capacity-one fleet delivers 342.9 against
350.8 — it has fallen behind, so its 224.82 is a number about the loads it
*managed* rather than about the fleet.

> **Before comparing times, check that the configurations served the same load.**

**A batching window is a cost paid by every load and redeemed only by capacity.**
Widening it from 25 to 40 makes the capacity-one fleet much worse and the
capacity-four fleet only slightly worse, because only the latter can use what
the window collected.

**And the blocked column is zero, on purpose.** `FracTimeBlocked` is registered
on a free-path vehicle and reads exactly zero for the whole run. It is flat by
design: a free path's central assumption is that a vehicle never waits for
another, and this is that assumption appearing in the same row a guide-path run
fills in — rather than being left to be remembered.

---

## 9. What the engine costs

`ksl.examples.general.guidedpath.GuidedPathThroughputBenchmark`

### The problem

How finely can you afford to discretise a guide path?

### The model

Twenty intersections, forty links, four hundred zones, twenty vehicles under
saturated demand — every vehicle given a fresh destination the instant it
arrives, so none is idle and the engine does nothing but move things. A
four-by-five torus of one-way aisles keeps every intersection reachable while
never letting two vehicles meet head on.

**This is deliberately not a test.** It measures wall-clock time, so its answer
belongs to the machine it ran on and has no business failing a build on somebody
else's laptop. Run it, record the figure alongside the hardware, compare like
with like.

### What to learn

**One zone traversal is one scheduled event**, so traversals per second is very
nearly events per second — and that is the figure that decides whether a fine
discretisation is affordable.

> **Halving the zone size doubles the events for the same motion.**

A modeller choosing zone size for the smoothness of an animation rather than for
the granularity of the control system is spending throughput on the picture, and
this is the exchange rate.

The invariant harness and link statistics are off, as the reference
configuration specifies — both walk every zone, and leaving them on would
measure them rather than the engine. Deadlock detection is left **on**, because
that is the configuration a model actually runs in, and a benchmark of a
configuration nobody uses is not worth having.

---

## 10. What deciding costs

`ksl.examples.general.agv.AgvThroughputBenchmark`

### The problem

A dispatcher is strictly more machinery than a pool's allocation rule. What does
it cost?

### The model

The same layout, zone count, fleet size, velocity and saturation as case 9 —
this file **imports** that benchmark's layout rather than restating it, so the
two cannot drift apart.

### What it shows

```
                                     active            passive
  zone traversals                 4,379,794          4,379,615
  events scheduled                4,412,310          4,412,312
  events / traversal                  1.007              1.007
  wall clock (s)                       5.06               4.77
  traversals / minute            51,964,070         55,041,395
  tasks completed                    55,564                 --
```

### What to learn

Two things, and only the second is about speed.

**The traversal counts agree to 0.004%.** That is the check that the two
subsystems are moving the same vehicles over the same aisles. A large gap would
mean every other comparison between the paradigms was suspect.

**Events per traversal is 1.007 in both.** Deciding costs *nothing* in engine
events, because a dispatching pass is not a zone traversal. The ~6% in wall
clock is the dispatcher's and the vehicle agents' coroutines — which is what an
object that can hold an opinion costs.

Saturation is expressed differently on the two sides, necessarily. The passive
benchmark re-dispatches each vehicle the instant it arrives, which it can do
because a transporter is a thing you command. Here nobody commands a vehicle, so
the *load* side saturates instead.

---

## What the ten have in common

**Four of them exist to prevent a false conclusion**, not to demonstrate a
feature — and that is the habit worth carrying into your own models:

| Example | The false conclusion it prevents |
|---|---|
| 1, no home bases | "Throughput is fine, so the layout is fine" |
| 4, a deliberately poor rule | "Nearest is better" as an assertion |
| 7, demand above capacity | "Throughput flattened, so the aisles bind" |
| 8, the throughput column first | "It's faster" when it served fewer loads |

**Two are explicitly not tests** (9 and 10), because a wall-clock number should
not fail somebody else's build.

**And one rule recurs across three of them** — cases 6, 7 and 8 all meet it:

> **Before comparing times, check that the configurations being compared served
> the same load.** A fleet that gave up on more work will look faster on every
> per-load statistic you have.

---

## See also

- [`ksl-transport`](ksl-transport.md) — the map: four subsystems, two axes.
- [`ksl-guidedpath`](ksl-guidedpath.md) — zones, links, blocking, routing,
  deadlock.
- [`ksl-fleet`](ksl-fleet.md) — the dispatcher, tours, stops, lines, multi-load.
- [`ksl-spatial`](ksl-spatial.md) — the substrate beneath all of it.
