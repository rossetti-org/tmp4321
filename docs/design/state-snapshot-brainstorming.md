# Model State Snapshot and Warm Start — Design Brainstorming

**Status.** Discussion record, not a design. Nothing here is decided. This document captures a
research and feasibility conversation held against the problem description *"Model State Snapshot
and Warm Start"* (referenced below as **PD**), and is written so the eventual design document can
start from it rather than re-derive it.

**What changed during the discussion.** Several of PD's premises were checked against the KSL source
and turned out to be more favourable than stated, and one structural assumption — that snapshot and
warm start should be solved by one integrated mechanism — was challenged and largely rejected. Both
are recorded in §2 and §3.

**Section numbering.** References of the form §3.3, Q7, C1 refer to the *problem description*, not to
this document. This document's own sections are numbered 1–11.

---

## 1. Where the discussion landed

Eight conclusions, stated up front. Each is developed below.

1. **Snapshot and warm start should be decoupled.** They have different consumers, different
   artifacts, different completeness requirements, and — decisively — different feasibility
   timelines. PD's claim that "C and F are natural partners" is weaker than it reads and risks
   blocking the buildable feature on the blocked one. (§3)

2. **The event-scheduling view and the process view need different mechanisms.** Clone the
   event-scheduling view (objects); describe-and-restart the process view (data plus a declared
   resume discipline). Choosing per part-of-model rather than forcing one mechanism over both is the
   sharpest available scoping decision, and it is a better answer to Q3 than the three options PD
   offers. (§5)

3. **A `protected open fun snapshot()` on `ModelElement`, defaulting to refusal, is feasible and
   idiomatic.** It is structurally identical to KSL's existing lifecycle-hook pattern. Three
   amendments are needed — an identity-map context, model-level calendar reconstruction, and a
   pre-flight capability audit rather than an exception at snapshot time. (§4)

4. **Most of the process-view descriptor already exists in KSL today.** Named suspension points,
   suspension type, current process, remaining delay, and resource allocations are all tracked at
   runtime. PD's §3.3 substantially understates the starting position. What is genuinely missing is
   *resumption*, not *capture*. (§5)

5. **PD's §3.3 is overstated as written.** Kotlin coroutines are CPS-transformed, so a suspended
   coroutine's locals live in heap object fields, not on a native stack. The barrier is the absence
   of a stable API, not physical impossibility. Quasar's serializable fibers are the counterexample a
   reviewer will raise. (§6)

6. **KSL already possesses the expensive precondition for replay-based approaches.** Deterministic
   replay from seed is what reverse debuggers and optimistic PDES systems spend their entire
   engineering budget achieving. This makes *periodic checkpoint plus coast-forward* far more
   available to KSL than PD's §4 suggests, and adds two options missing from that survey. (§7)

7. **Full automation is not achievable and the design should stop pursuing it.** The library's job is
   to **bound, locate, and check** the hand-written portion — not to eliminate it. Compile-time
   exhaustiveness is the strongest available tool for this. (§8)

8. **Serialization serves warm start indirectly, via harvest-edit-prime, and that may be the best
   available answer to C2.** It also delivers C4, C8, and distributed rollouts. Attempting it
   properly with modern Kotlin tooling *forces* a declared per-element projection — arriving at
   design space C from the opposite direction. (§9)

---

## 2. Findings verified against the KSL source

Checked during the discussion. File and line references are to `KSLCore/src/main/kotlin/ksl` at the
time of writing and should be re-verified before being carried into a design document.

| # | Finding | Location | Why it matters |
|---|---|---|---|
| F1 | `ModelElement` uses a `protected open fun X()` + `internal fun XActions()` tree-walk pattern for every lifecycle stage | `simulation/ModelElement.kt:1514–1878` | The proposed `snapshot()` hook is the *same pattern*. Additive, idiomatic, low-risk |
| F2 | `EventAction<T>` is a `protected abstract inner class`; `EventActionIfc<T>` is a `fun interface` | `simulation/ModelElement.kt:1151, 1163` | A freshly constructed element builds its own action objects. No rebinding needed — PD §3.2's framing is misleading on this point |
| F3 | `KSLEvent` exposes `name`, `time`, `priority`, `message`, `timeRemaining`, and the scheduling `modelElement` | `simulation/KSLEvent.kt` | A pending event is fully *describable* without touching the action reference. Enables calendar rebuild by name |
| F4 | `ModelElement` has an `internal constructor`; subclasses have their own constructor signatures | `simulation/ModelElement.kt` | No generic mechanism can construct elements. Only the element knows how to make another of itself — an argument *for* the per-element hook |
| F5 | Every suspending function in `KSLProcessBuilder` takes an optional `suspensionName: String?` | `modeling/entity/KSLProcess.kt:104–112` and all `suspend fun` declarations in `ProcessModel.kt` | Suspension points are already *named* in existing models. The vocabulary for a resume discipline is already in the code |
| F6 | `Entity` tracks `currentSuspendName`, `currentSuspendType`, `currentProcess`/`currentProcessName`, `isSuspended` | `modeling/entity/ProcessModel.kt:570–599` | "Where is this entity" is already known at runtime |
| F7 | `SuspendType` is a 15-value enum (`DELAY`, `SEIZE`, `HOLD`, `WAIT_FOR_SIGNAL`, `WAIT_FOR_ITEMS`, `BATCHING`, `RIDE`, …) | `modeling/entity/KSLProcess.kt:86–102` | The suspension taxonomy exists and is finite — a closed set to design against |
| F8 | `Entity` holds `myDelayEvent: KSLEvent<Nothing>?` and `resourceAllocations: MutableMap<Resource, MutableList<Allocation>>` | `modeling/entity/ProcessModel.kt:588, 651` | Remaining delay and held units are directly readable |
| F9 | A `ResumeIntent` record already carries `scheduledForProcessId`, `scheduledSuspendType`, `scheduledSuspendName`, `scheduledDelayEventId`, `scheduledDelayEventTime` | `modeling/entity/ProcessModel.kt:63, 891` | KSL already assembles something very close to a suspension descriptor, for interrupt handling |
| F10 | `@KSLControl` + the `Controls` layer: annotation-driven, reflection-processed, typed with bounds, JSON-serializable, with `defaultExcludedControlNames()` for opt-out | `controls/KSLControl.kt:26`, `controls/Controls.kt`, `simulation/ModelElement.kt:258` | A working precedent for a declared, annotation-driven, round-trippable property surface. `@KSLState` would reuse the idiom |
| F11 | `ModelElement.specifyCatalog(catalog: ElementCatalogScope)` + `ModelCatalogBuilder` | `simulation/ModelElement.kt:1562, 1569`; `simulation/ModelCatalogBuilder.kt` | A per-element *declarative description* hook already exists. Extend or parallel — a design question, but not foreign |
| F12 | KSLCore already depends on kotlinx.serialization; 44 files carry `@Serializable`, including `ModelControlsExport` | `KSLCore/build.gradle.kts:25, 50` | Precedent for projecting model information to JSON. Also means the tree-serializer limitation (§9) is a known quantity |
| F13 | `KSLCodeSearch` contains a Kotlin compiler PSI parser in a build-time-only `gen` source set, deliberately excluded from the shipped jar | `KSLCodeSearch/src/gen/kotlin/ksl/code/search/gen/KotlinDeclarationParser.kt` | The infrastructure for source analysis exists. Currently extracts declarations only; walking bodies is an extension, not a new capability |

**Net effect.** F5–F9 in particular mean the process view starts from a much better position than PD
§3.3 describes. F1, F4, F10, F11 mean the proposed mechanism has three separate precedents in the
codebase rather than being novel.

---

## 3. The central reframe: three problems, not one

PD treats snapshot and warm start as one capability with two directions of use, and argues (§4) that
this symmetry is the strongest case for design space C. The discussion challenged this.

The two directions need different **completeness**, not merely different mechanisms:

- A **clone** must reproduce everything, including things no analyst would ever know or write down —
  accumulated statistics, stream positions, internal queue bookkeeping. Completeness is total and
  mechanical.
- A **warm start** reproduces only what can be observed about a real system. It is *inherently*
  partial; the rest is filled by defaults and modeling judgment. Completeness is a modeling question.

Forcing one representation to serve both yields either an authoring format bloated with fields no
human can supply, or a lossy clone. That is a real cost for a mostly diagrammatic elegance benefit.

### 3.1 The decoupled problems

| | **P1 — Branching** | **P2 — Warm start** | **P3 — Process view** |
|---|---|---|---|
| Consumers | C1, C5, C7, C8 | C2, C3, C4 | cuts across both |
| Artifact | live object graph | serializable data | data + resume discipline |
| Crosses JVM? | never | yes | yes |
| Authorable? | no | that is the point | partly |
| Cost profile | per rollout — must be fast | per study — may be slow | n/a |
| Blocked on | correspondence, aliasing, calendar rebuild | priming APIs KSL lacks | resumption (§5) |
| Feasibility | incremental, buildable now | needs new APIs | needs a declared discipline |

The decisive row is the last two. **P1 is buildable incrementally against classes under maintainer
control, with default-refusal carrying the tail.** P2 is blocked on priming APIs that KSL genuinely
lacks, because its built-in constructs were not designed to be placed into an arbitrary mid-run
configuration. Coupling them makes the buildable feature wait on the blocked one.

### 3.2 What to keep from the integrated framing

One thing, and it is cheap: **a shared naming and addressing scheme.** Element paths,
`KSLEvent.name`, `suspensionName`, entity ids. Adopting it now costs nothing and lets a later
warm-start format describe the same things the clone knows about, without the two mechanisms sharing
a representation. This is the useful residue of PD's C/F partnership claim — *shared vocabulary, not
a shared artifact.*

---

## 4. Proposed architecture sketch: one traversal, two visitors

A `Model`-level operation walks the model element hierarchy and the executive. What differs between
the two features is only the leaf operation:

- a **copy visitor** calls each element's `snapshot(ctx, into)` and produces a live object → P1
- an **emit visitor** calls each element's `describe()` and produces serializable data → P2

Shared: the traversal, the identity/id scheme, the calendar reconstruction, the pre-flight capability
audit, and the process-view descriptor. Each element implements two small methods instead of one.

This is a candidate answer to **Q1** that avoids coupling the artifacts while avoiding duplication of
the hard part.

### 4.1 The per-element hook

The proposal — a `protected open fun` on `ModelElement` defaulting to a not-implemented exception,
implemented element by element — is sound. It directly answers **Q10**: doing nothing yields an
explicit refusal rather than a silently incomplete snapshot. Three amendments emerged:

**A1 — the hook needs an identity-map context.** If elements A and B both reference queue Q, then
independent per-element cloning produces two copies of Q and silently changes the model's topology.
The signature must thread a context — `snapshot(ctx): ModelElement`, with cross-references resolved
via `ctx.copyOf(original)`. Standard deep-copy context; must be in the signature from the start.

**A2 — the calendar is not element-owned, and needs correspondence.** Per-element cloning yields an
element tree but no calendar, and a large share of the state lives there (S2). The calendar holds a
reference to a *specific* action instance; a clone has new instances but nothing maps old to new.
Two routes, and the choice should be made early because it determines whether the map is needed:

- *copy the calendar* and remap each event's action — requires an old-action → new-action map that
  each element must supply;
- *rebuild the calendar* by asking each clone to re-schedule its pending events **by name and time
  remaining** — uses `KSLEvent.name` and the existing `schedule(…, name)` parameter (F3).

The second looks cheaper and reuses machinery that exists. It also degrades better, since a
description-based calendar is what P2 needs anyway.

**A3 — refusal should be discoverable before the run.** A default-throwing hook fires inside an event
action, potentially hours into a run. Pair it with a capability flag (`open val canSnapshot: Boolean`
or a richer descriptor) and a model-level audit that walks the tree *before* `simulate()`, reporting
every element that would refuse, by name and path. Same information, delivered when it is actionable.

### 4.2 Element coverage is larger than it looks

The `modeling/entity` package alone contains `Conveyor`, `BlockingQueue`, `BatchQueue`,
`ResourcePool`, `Signal`, `HoldQueue`, `CapacitySchedule`, `TaskProcessingSystem`. `Conveyor` is
expected to be the worst. The strength of default-refusal is that coverage can be prioritized by
actual model usage while the tail refuses honestly.

**Sequencing note.** Implement *restore* before *snapshot* on the first element. Capture is reading;
restore is a constructor for an arbitrary mid-run configuration, which these classes were never
designed to permit — it is expected to dominate the effort. Pick `Queue` or `SResource` and do the
round trip end to end. If restoring a queue with correct queue-time statistics and a resource with
correct allocations is tractable, the plan is tractable; if not, that is learned for the cost of one
class rather than thirty.

---

## 5. The process view

### 5.1 Three tiers

**Tier A — where the entity is and what it holds. Solvable now.** Process name, suspension type,
suspension name, remaining delay, queue membership, allocations. All present today (F5–F9). This is
the bulk of what a warm start needs and requires no compiler-level work. It is PD §3.3's "middle
option" descriptor, mostly already implemented for other purposes.

**Tier B — locals in the process body. The irreducible gap.** A `val` declared before a suspending
call and read after it lives inside the generated continuation and is unreachable. The mitigation is
a **modeling discipline**, not a mechanism:

> State that must survive a snapshot lives as a property on the `Entity`, not as a local in the
> process body.

This is teachable, expressible in the modeler's own vocabulary, and — importantly — **checkable**:
walk process-builder bodies with the PSI parser (F13), flag locals declared before a suspending call
and read after it. That is a live-variable analysis across suspension points, and it turns "you might
have missed something" into a specific list of lines.

**Tier C — resumption. Where the hand-coding lands.** A complete descriptor still does not re-enter a
coroutine mid-body. Three candidate routes, in increasing ambition:

1. **Declared resume-point dispatch.** The author writes the process as a `when (entity.resumePoint)`
   over labeled segments. The suspension names KSL already carries (F5) are the natural labels, so
   authors who named their suspension points have most of the annotation done. With a generated enum
   and an exhaustive `when`, the compiler enforces that every resume point is handled.
2. **Library re-establishment for the purely mediated suspension types.** For `HOLD`,
   `WAIT_FOR_SIGNAL`, and queued `SEIZE`, the entity's suspension is entirely a library condition —
   it is sitting in a `HoldQueue` or `RequestQ`. The library can put it back. It still cannot re-enter
   the body, but it narrows how many types need author code.
3. **Process-level coasting forward.** Re-run the process from the top with each suspending call
   before the named target reduced to a no-op. This is the PDES coast-forward idea (§7) applied at
   process granularity. Valid *iff* the body has no external side effects up to the target point — a
   real restriction, but declarable and partly checkable. It is the only route that resumes an
   **unmodified** process body, and is the most interesting to prototype.

### 5.2 The scoping consequence

Cloning does not rescue the process view: a freshly constructed `Entity` has no process running at
all, so the clone path lands in the same place as the data path. This is what motivates conclusion 2:

> **Clone the event-scheduling view; describe-and-restart the process view.**

Two mechanisms chosen by what each part of the model can actually support. Entities in processes get
the Tier A descriptor plus an author-written resume point, and refuse loudly until they have one.

---

## 6. On PD §3.3 being overstated

PD states that continuations cannot be copied or serialized. The accurate and narrower statement:

> There is no supported, stable API for copying or serializing a Kotlin continuation, and doing
> better requires depending on compiler internals or writing a compiler plugin.

Kotlin coroutines are CPS-transformed at compile time — a suspended coroutine's locals live in *heap
object fields*, not on a native stack. The generated classes are compiler-internal and unstable,
which is a cost and a risk, not a physical barrier. Supporting evidence:

- **Quasar** (JVM) supported *serializable fibers* via bytecode instrumentation that reified the
  stack into a heap object.
- The **IntelliJ coroutine debugger** reads suspended coroutines' locals today — the reading half is
  demonstrably available.
- Scheme and Smalltalk web frameworks (Seaside, Cocoon) have had serializable continuations for
  decades, always because the compiler was in on it.

The design should restate §3.3 accordingly. As currently written it is the kind of claim a reviewer
will immediately counterexample, and it closes off PD's own "narrower mechanism to investigate"
option prematurely.

---

## 7. Prior research

This problem has been solved repeatedly in at least five literatures that largely do not cite each
other. **Citations below are from recall and should be verified before entering a design document.**

### 7.1 Parallel discrete-event simulation — the closest match

Optimistic synchronization (Jefferson, *Virtual Time*, 1985) requires a logical process to roll state
back when a straggler message arrives. This community has done snapshot-and-restore in DES
continuously since the mid-1980s, at supercomputer scale, and its option list nearly matches PD §4:

- *copy state saving* (PD's B) and *incremental state saving* (PD's E), with published theory on when
  each wins;
- *infrequent/periodic state saving with coasting forward* — checkpoint every k events and reach
  intermediate states by re-executing forward, with optimal-checkpoint-interval analysis;
- *reverse computation* (Carothers, Perumalla & Fujimoto, ~1999) — write an **inverse** event handler
  that undoes the event; cost proportional to the event, not the model. Perumalla built a
  **source-to-source reverse compiler** generating inverse handlers from forward code.

**Their conclusion matches this discussion's:** the successful systems (ROSS, GTW, WARPED) imposed a
declaration discipline — all logical-process state in a designated state struct, with handlers
written against it. Nobody shipped automatic capture of arbitrary host-language state. That is strong
empirical support for design space C, and it also indicates the *shape* that worked: **one designated
state object per element, not annotations scattered over fields.**

### 7.2 Checkpoint/restart in HPC

The settled distinction is *system-level* (CRIU, BLCR — PD's option H: automatic, huge, non-portable)
versus *application-level* (programmer declares what to save: small, portable, manual). Serious
scientific computing uses application-level.

**Compiler-assisted checkpointing** is a real subfield and bears directly on the source-analysis
question: `libckpt` (Plank et al., mid-1990s) introduced *memory exclusion*, where the programmer
annotates dead or read-only regions; the Cornell Checkpoint Compiler (C³ — Bronevetsky, Marques,
Pingali, Stodghill) did source-to-source transformation using **live variable analysis**. That is the
standard answer to "can parsing help": yes, for the *reduction* problem (what can be safely skipped),
not for the *extent* problem (what counts as state).

### 7.3 Orthogonal persistence — the cautionary tale

Atkinson and Morrison's persistent programming languages (PS-algol, Napier88) and especially
**PJama**, a serious attempt at persistent Java heaps. It aimed to persist arbitrary object graphs
including threads, and was abandoned. It foundered on exactly PD §3.8 and §3.3: external resources
and execution state. The most ambitious "just automate it" attempt on the JVM failed on the two
things PD already identifies.

### 7.4 Where the formalism already answers it

DEVS defines a model as ⟨S, δ_int, δ_ext, λ, ta⟩ — the state set S is *part of the formalism*, so
DEVS simulators get snapshot essentially for free (Zeigler, *Theory of Modeling and Simulation*).
Design space C, promoted to a modeling formalism.

An empirical confirmation from the RL side: environments supporting `clone_state`/`restore_state`
(ALE, MuJoCo) are exactly those whose state is a fixed declared struct; environments whose state grew
organically as host-language objects universally do not support it. Same pattern, three fields, forty
years apart.

### 7.5 Time-travel debugging — and why it matters most

There is a substantial literature: Boothe on bidirectional debugging; Lewis's Omniscient Debugger for
Java; Pothier and Tanter's TOD; Mozilla's `rr`; WinDbg Time Travel Debugging; UndoDB.

**The finding worth carrying into the design document:** they do not snapshot to go backwards. They
**checkpoint periodically and deterministically replay.** Almost the entire engineering budget of
`rr` goes into *making execution deterministic* — recording syscalls, thread interleavings, signals —
so that replay reproduces the original run exactly.

**KSL already has that property for free.** A fixed seed gives an identical trajectory by design; its
only nondeterminism source is the streams (§3.6), a handful of doubles each. The expensive
precondition that reverse debuggers spend years achieving is a guarantee the library already makes.

There is also a mechanism worth noting for its confirmation of an intuition raised in discussion — a
JVM debugger can name local variables because *the compiler emitted a state descriptor*: the
`LocalVariableTable` and the stack maps the JIT and GC rely on. The code did embed a representation
of its own state; the compiler wrote it down in a side table. JVMTI permits reading and even setting
locals, and popping frames — but offers no way to *copy* a stack. Read yes, clone no.

### 7.6 Two options missing from PD §4

- **Option I — reverse computation.** Do not save state; generate or hand-write an inverse event
  handler. Cost proportional to the event. Has a source-to-source generation precedent (§7.1).
- **Option J — periodic checkpoint plus coast-forward.** Combine infrequent snapshots with
  deterministic replay to reach any intermediate state. Cost is O(checkpoint interval), not O(t),
  which directly attacks PD §3.9's objection to replay.

Option J deserves first-class treatment: two independent communities converged on it for exactly
KSL's reasons, and it has a further property worth naming — **it makes incompleteness recoverable
rather than fatal.** A coarse, hand-coded, partially incomplete snapshot plus a short forward replay
may reach a correct state where the snapshot alone would not, because replay re-derives what the
snapshot omitted. That is a materially different risk posture from anything in PD §4, and it matters
given that hand-coding is unavoidable.

PD §4.G should therefore be reclassified: replay-from-seed is not only the correctness oracle of
§3.10, it is half of a viable production mechanism.

---

## 8. Reducing the hand-coding burden

The working premise, accepted early in the discussion and supported by every literature in §7: **full
automation is not achievable.** The library's job is therefore to **bound, locate, and check** the
hand-written portion. Three deliverables, none of which is a snapshot mechanism:

1. tell the author exactly what they must write, and where;
2. make *drift* — model changes after the state code was written — impossible to ignore;
3. give them an oracle that says whether what they wrote is right.

### 8.1 Move omission to compile time

The highest-leverage move, given hand-coding is unavoidable, is making the **compiler** the
incompleteness detector:

- **Generated state records with no default values.** A tool emits
  `data class ClinicState(val onHand: Int, val backorders: Int, …)` with every parameter required.
  The author writes capture and prime by hand against it. Add a field to the model, regenerate, and
  every hand-written capture site **fails to compile** until addressed. Drift stops being silent.
- **Sealed resumption labels with exhaustive `when`.** Generate an enum or sealed hierarchy of the
  suspension points found in each `KSLProcess` body — these are syntactically identifiable calls —
  plus a `when` skeleton with `TODO()` per branch. Kotlin's exhaustiveness checking then guarantees
  every resumption point has been considered.

### 8.2 Source analysis: three tiers, and an honest limit

| Tier | What | Reliability |
|---|---|---|
| 1 | Enumerate every `var` and mutable-collection `val` on each `ModelElement` subclass | High. Available from PSI (F13) or KSP. Produces a checklist |
| 2 | Walk bodies for assignments to `this.x` and mutating calls on collection fields; separate "assigned once at construction" (structure) from "mutated during the run" (state) | Good for direct assignment; degrades through aliases and helper calls. This is the tier that makes Tier 1's checklist usable rather than noisy |
| 3 | Dataflow, escape and alias analysis | Recommend ruling out. High cost, unstable compiler APIs, and still cannot answer the extent question |

**The framing that makes tiers 1–2 worth building:** use them to produce **lower bounds on omission,
never claims of completeness.** *"You have 14 mutable properties; your state code mentions 9; here
are the 5 it does not"* is true, useful, and cheap. *"Your snapshot is complete"* is not a statement
source analysis can make.

Two properties make this cheaper than it sounds: it is an **advisory build-time tool**, so being
occasionally wrong does not break compilation; and because it never runs in production, PD §3.1's
objection to reflection largely evaporates — a test-scope tool can require `--add-opens` without
imposing anything on user runs.

**Note on what code can and cannot tell you.** The code contains the state *type* and the state
*transition function*, but not the state *extent*. Which part of the reachable graph is semantically
state — versus derived cache, back-reference, or structure — is a modeling judgment not written down
anywhere. This is why parsing cannot finish the job.

### 8.3 Completeness detection (Q7) — four complementary detectors

1. **Structural refusal at registration.** Reflect over each `ModelElement` subclass at build time;
   any mutable property neither declared nor explicitly excluded makes the model *refuse* to
   snapshot, with a named list. The direct answer to Q10.
2. **Differential mutation audit.** In a test mode, take a full reflective fingerprint of every
   reachable mutable field at t₁ and t₂ alongside the declared snapshot. Any field whose fingerprint
   changed but is not covered by the declaration is either missed state or a cache — report it, have
   the author classify it, record the classification so it does not re-fire. This is a conformance
   test an author can run against their **own** model, which is what Q7 asks for.
3. **Replay-from-seed differential** (PD §3.10). The strong oracle, but slow and horizon-limited.
   Best as CI over reference models rather than as a user-facing tool.
4. **Lock-step divergence check.** Run parent and primed copy in parallel for k events comparing the
   event stream (time, element, event name). Cheap, catches a large fraction of omissions in seconds,
   could ship as a built-in `verify` mode.

**A research-grade idea worth one prototype.** There is a mechanically checkable definition of state:
**whatever is read after the snapshot instant that was written before it** — read-before-write across
a time boundary. That set *is* the state, empirically, for that trajectory. It is measurable by
instrumentation, expensive, and trajectory-dependent, but definitive in a way no static analysis is.
It is also the dynamic-slicing/live-variable idea from §7.2 under a different name.

**Static and dynamic are complements, not alternatives.** The two-set comparison is more informative
than either alone: statically flagged but never observed to change → probably structure; observed to
change but not statically visible → mutation through aliases or library internals; statically visible
but never observed → rare-path state a test run did not exercise. *Their disagreement is the report.*

### 8.4 Two burden reductions worth designing in

- **KSL's own classes are under maintainer control.** "Built-in constructs were not designed to be
  primed" is true of the *public* API; internal priming paths for `Queue`, `SResource`, `TWResponse`
  are additive and invisible to users. The irreducible hand-coding is at the **user-subclass** and
  **process** layers — a much smaller target than "everything." Worth confirming early.
- **Separate dynamics-relevant state from reporting state.** `Response`/`Counter` accumulators affect
  output, not trajectory. C1 rollouts need only the former and explicitly want the latter discarded
  (PD §3.5). Categorizing the declaration this way shrinks the lookahead consumer's hand-written
  surface to what actually drives behavior.

---

## 9. Serialization

### 9.1 What it buys directly

- **C4 checkpoint/restart** — its native use; nearly free once the traversal exists.
- **C8 debugging** — underrated. A serialized state from just before a defect is a *file that can be
  committed to the repository*. The three-hour reproduction becomes a regression fixture. Requires
  durability specifically; a clone cannot do it.
- **Distributed rollouts** — not in PD's consumer list. If a state can cross a process boundary, C1's
  thousands of candidates can be farmed across JVMs or machines, changing the §3.9 performance
  argument considerably.

### 9.2 What it buys for warm start: harvest–edit–prime

Not the direct thing — a serialized model is shaped like KSL's *implementation*, not like the domain,
and no analyst will author one from a spreadsheet. But this path does work, and may be the strongest
available answer to C2:

1. Build the model and run it briefly, to any representative state.
2. Serialize. This is a *complete, valid* state description, produced mechanically.
3. The analyst **edits** it to match reality — on-hand from 47 to 12, today's patients in the waiting
   room, remaining processing time on machine 3.
4. Deserialize and run.

This converts authoring-from-nothing into editing-a-template, and it dissolves the completeness
asymmetry of §3: the harvested template supplies correct values for everything, including the
hundred fields nobody could know; the analyst overwrites only what they observe. **Completeness
becomes the harvester's responsibility rather than the analyst's** — the right place for it, because
the harvester is mechanical and the analyst is not. It also means no authoring format has to be
designed up front: the serialization format *is* the authoring format, correct by construction.

**Qualification.** Editing raw internals is fragile and would expose analysts to implementation
detail. The serialized form should be a **curated per-element projection** — each element decides what
appears — rather than a mechanical field dump. Which is the same per-element hook, emitting data.

### 9.3 What blocks it, in order of difficulty

1. **Cycles and shared references — the determining constraint.** kotlinx.serialization (already in
   KSLCore, F12) is a **tree serializer**: no reference identity, no cycles. A KSL model graph is
   cyclic in the ordinary case (parent↔child) and full of sharing. Java's built-in serialization
   handles cycles and identity natively — a genuine point in its favour — but is a poor long-term JVM
   bet. **The practical route is explicit symbolic ids** (element paths, entity ids, allocation ids),
   serializing references as ids rather than nested objects. This is PD §3.4's aliasing problem solved
   by making references data instead of pointers.
2. **Continuations.** Same wall as clone; the describe-and-restart split of §5 is the answer.
3. **Stream state.** The eighteen doubles are private with no accessor (PD §3.6). Small additive
   change, but a prerequisite — and needed for the clone path too. This is the clearest item for Q9.
4. **Observers and external resources.** Trace files, database observers, Welch collectors, loggers
   must be excluded and re-attached by the target model on restore. Under the decoupling of §3, what a
   primed model has attached is a property of *that model*, not of the snapshot — a cleaner answer to
   Q11 than PD's framing.

### 9.4 The convergence

Attempting to serialize a KSL model properly with modern Kotlin tooling *forces* symbolic ids and a
per-element declared projection. That is **design space C, arrived at from the opposite direction.**
Serialization does not avoid the declared specification — it requires one. This is reassuring rather
than discouraging: it means the two paths are not competing designs, which is what makes the
one-traversal-two-visitors architecture of §4 available.

---

## 10. Revisions the problem description warrants

Specific, and each is independently checkable:

| PD location | Issue | Suggested revision |
|---|---|---|
| §3.2 | Implies action objects must be rebound during a copy | A freshly constructed element builds its own inner action objects (F2). The residual problem is **correspondence** — mapping an existing scheduled event to the right new action — which `KSLEvent.name` addresses (F3) |
| §3.3 | "Continuations cannot be copied" is stronger than the evidence | Restate as "no supported, stable API exists; doing better requires compiler-level work." Cite Quasar as the counterexample (§6) |
| §3.3 | Understates the current position | KSL already tracks named suspension points, suspension type, current process, remaining delay, and allocations (F5–F9). Tier A of the descriptor largely exists |
| §4 | Two options missing | Add **I — reverse computation** and **J — periodic checkpoint plus coast-forward** (§7.6) |
| §4.G | Classified as oracle-only | Reclassify: with periodic checkpoints it is half of a production mechanism, and it makes incompleteness recoverable rather than fatal |
| §4, closing | "C and F are natural partners" overstated | The two directions need different *completeness*. Keep the shared **vocabulary**, drop the shared **artifact** (§3) |
| §3.9 | Rules out replay on O(t) grounds | O(checkpoint interval) with periodic checkpointing; the PDES literature has optimal-interval theory |
| §5 | Model-author burden listed as a hidden expense | Distinguish KSL's own classes (maintainer-controlled, internal priming APIs are additive) from user classes (the real burden) — §8.4 |

---

## 11. Open questions and next experiments

Ordered by how much they would change the design's shape, and all are small.

**E1 — Restore one element end to end.** Pick `Queue` or `SResource`. Write `restoreFrom` before
`snapshot`. Can a queue be restored with correct queue-time statistics, and a resource with correct
allocations against capacity? *Settles whether the whole element-by-element plan is tractable, for the
cost of one class.*

**E2 — Dump the Tier A descriptor from a real process-view model.** For every suspended entity at some
time *t*, emit `(currentProcessName, currentSuspendType, currentSuspendName, delayEvent.time − time,
allocations)`. Is that description sufficient to describe the system **to a person**? *If yes, Tier A
is confirmed and the process-view problem reduces to Tier C, which is a scoping decision rather than
a research problem.*

**E3 — Serialize a KSLExamples model and hand it to an analyst.** Could a practitioner find and change
the five fields they care about? *If the file is 4,000 lines of nested internals, the harvest–edit–prime
story needs a much more curated projection, and that changes how much design effort §9.2 requires.*

**E4 — Tier 1 + 2 source extraction** on one existing model, extending the PSI parser (F13) to walk
bodies. *How big and how noisy is the checklist really?*

**E5 — Read-before-write boundary instrumentation** on the same model. *How far does the empirical
state set differ from the syntactic one? If close, parsing is a strong tool; if far, parsing is only a
checklist and the design should say so.*

**E6 — Graph sharing survey.** How much of a real model's object graph is genuinely shared or cyclic
beyond the parent/child backbone? *If sharing is mostly structural, the tree-serializer limitation is
minor; if models routinely share mutable data objects, it is a significant constraint.*

**E7 — Process-level coast-forward prototype** (§5.1, route 3). *The only route that resumes an
unmodified process body; worth knowing early whether it works at all.*

### 11.1 Status of the problem description's questions

| Q | Where the discussion landed |
|---|---|
| Q1 — one mechanism or two? | **Two artifacts, one traversal, two visitors** (§4). Decoupled features, shared walk and vocabulary |
| Q2 — automatic or declared? | Declared, with automatic **auditing** rather than automatic capture (§8.2, §8.3). All five literatures in §7 reached the same place |
| Q3 — process view in v1? | **Split by view**: clone the event-scheduling view, describe-and-restart the process view (§5.2). Tier A in v1; Tier C is the scoping decision |
| Q4 — copy or rebuild? | Yes, differently per problem: copy for P1, rebuild-and-prime for P2 (§3.1) |
| Q5 — statistics on restore | Becomes a **filter over a categorized description** rather than distinct operations. Reinforced by separating dynamics-relevant from reporting state (§8.4) |
| Q6 — stream policy | Same treatment: streams are a category; restore / omit / restore-common are three filter settings. Prerequisite: expose MRG32k3a state (§9.3) |
| Q7 — detecting incompleteness | Four complementary detectors (§8.3), plus the recoverability property of option J (§7.6). No single detector is a proof; the design should say so |
| Q8 — statistical status of shared-snapshot replications | **Not discussed.** Remains fully open; PD is right that it needs the output-analysis literature |
| Q9 — required API additions | Confirmed: stream state accessors. Added: element identity/path scheme, event re-scheduling by name, capability/pre-flight audit, entity suspension descriptor accessors |
| Q10 — obligation on a model element | Default-refusal hook is the right answer, strengthened by pre-flight audit (§4.1 A3) so refusal is discoverable before the run |
| Q11 — observers and external resources | Under decoupling, attachment is a property of the **target model**, not of the snapshot (§9.3) |
| Q12 — smallest useful first version | Candidate: **P1 only** — event-scheduling view, in-memory clone, isolation-focused, serving C1/C5/C7/C8, with the shared naming scheme adopted from the start so P2 can follow without rework |

---

## 12. Reading list additions

Beyond PD §9. **Citations are from recall and need verification.**

- **PDES rollback:** Jefferson, *Virtual Time* (1985); the copy vs. incremental vs. periodic state
  saving literature with its cost analyses; Carothers, Perumalla & Fujimoto on reverse computation
  (~1999) and Perumalla's reverse compiler; ROSS/GTW/WARPED on the declared state-struct discipline.
- **Compiler-assisted checkpointing:** `libckpt` (Plank et al.) and memory exclusion; the Cornell
  Checkpoint Compiler (C³, Bronevetsky et al.) and live variable analysis.
- **Orthogonal persistence:** PJama (Atkinson, Jordan) — why it was abandoned.
- **Time-travel debugging:** Boothe on bidirectional debugging; Lewis's Omniscient Debugger; Pothier
  & Tanter's TOD; `rr` (O'Callahan et al.) — specifically the checkpoint-plus-deterministic-replay
  architecture.
- **Serializable continuations:** Quasar's serializable fibers; the Scheme/Smalltalk web-continuation
  line (Queinnec; Graunke et al.; Seaside).
- **Formalism:** Zeigler, *Theory of Modeling and Simulation* — DEVS as design space C promoted to a
  formalism.
