# Transition: Sequential Decision Making → a feature branch on `rossetti/KSL`

**Prepared:** 2026-08-29 · **For:** the session that will open the KSL feature branch
**Author:** M.D. Rossetti (rossetti@uark.edu) · **Status:** plan only — nothing has been pushed to `rossetti/KSL`

> **This file is fork-only scaffolding.** It lives on `rossetti-org/tmp4321`,
> branch `feature/sequential-decision`, at `docs/design/ksl-decision-transition.md`,
> so that a session arriving with no context can find it without anyone
> having to hand it over — which is exactly the failure this document's §4
> is about. It is **not** part of the seven-commit replay in §5 and should
> **not** be carried onto `rossetti/KSL`: it describes the move, not the
> subsystem. The design document it asks for in §4 *is* product and does
> belong in the KSL repository.

---

## 0. Read this first

Three things, in order of how badly they bite.

1. **The work is safe and complete on the fork.** `rossetti-org/tmp4321`, branch **`feature/sequential-decision`**, tip **`8e5cf5e`**. 60 commits, 69 files, 186 tests passing. It is *not* on `rossetti/KSL` yet.

2. **The governing design document is gone from disk.** `KSL_Sequential_Decision_Making_OOD.md` lived in `/home/user`, was never committed to any repository, and did not survive the container being re-provisioned. **The maintainer holds the only copies**, as chat attachments. 481 lines of Kotlin in the branch cite it by section number (`§4.4.6`, `ADR-14`, `R15`). Recovering and *committing* it is step 1 of the transition, not an afterthought — see §4.

3. **The move is mechanically easy.** The branch is 68 files added and exactly **one** line-level modification. The full patch applies cleanly onto current `rossetti/KSL` `main` (`55c1641`), verified today. The hard part of this transition is provenance and documentation, not merging.

---

## 1. What the work is

`ksl.modeling.decision` is a **sequential decision-making layer** for KSL. On a model that already works, you declare four things — what a rule may *see* (`observe`), what it may *change* (`lever`), what it is *scored on* (`reward`), and *when* it decides (`every`/`onCalendar`). KSL then runs the decision loop: read observations, call the rule, validate and apply the action against the feasible set, price the interval that just ended, emit the transition.

**It deliberately does not choose the rule.** No value iteration, no Q-learning, no policy gradient. It is the *seam* — it makes the decision point explicit, inspectable, swappable and recordable, so a rule the user writes, a learner trained off-line, or a search run by `ksl.simopt` has somewhere to plug in. That boundary is ADR-1 and is the single most important thing to preserve in review: a reviewer who expects a reinforcement-learning library will read the absence of solvers as an omission rather than as the design.

What a user can do with it today:

- declare a decision on an existing model without restating anything the model already carries;
- swap rules, narrow lever ranges, and change the review period **from outside the model**;
- score on several terms of mixed sense (`COST` / `REWARD`) with signs handled once, at declaration;
- express a feasible set that depends on state, and have infeasible actions refused before any lever is written;
- attach a trajectory sink from `main()` — or capture a whole model in one line — and detach it again;
- read a trajectory back with no live `Model`, fit a rule from it, and put the fitted rule back in the simulator;
- hand a parameterized rule's parameters to `ksl.simopt` with no adapter.

---

## 2. Where the work is, exactly

| | |
|---|---|
| Fork | `https://github.com/rossetti-org/tmp4321` |
| Branch | `feature/sequential-decision` |
| Tip commit | `8e5cf5e` — *"Describe the systems the tutorial models"* |
| Commits on the branch | 60 (ahead of the fork's `main`) |
| Fork base commit | `8522352` (2026-08-09, *"Update manifest.json"*) |
| Fork `main` since that base | 20 commits — **the branch has not been rebased onto them** |
| Target | `https://github.com/rossetti/KSL`, `main` at `55c1641` |

> **A warning about this session's container.** It provisions both clones on the branch `claude/ksl-sequential-decision-making-991ja4`, which is **not** where the work is, and it re-provisions between sessions. The next session must `git fetch origin` and check out `feature/sequential-decision` explicitly. Do not trust a local `HEAD` on arrival. Both clones are also **shallow** — do a full clone before any rebase or history surgery.

---

## 3. Measured facts, and how to re-verify each

Everything below was measured on 2026-08-29 against `origin/feature/sequential-decision` and `rossetti/KSL` `main` at `55c1641`. Re-run each before relying on it; `main` moves.

**The change is additive.** 68 files added, 1 modified.

```
git diff --name-status $(git merge-base origin/main origin/feature/sequential-decision) \
    origin/feature/sequential-decision | awk '{print $1}' | sort | uniq -c
```
→ `68 A`, `1 M`. The single modification is **`docs/guides/README.md`** — one table row added to the guides index.

**Where the files live.**

| Files | Location |
|---|---|
| 9 | `KSLCore/src/main/kotlin/ksl/modeling/decision/` |
| 2 | `KSLCore/src/main/kotlin/ksl/modeling/decision/descriptor/` |
| 3 | `KSLCore/src/main/kotlin/ksl/sdm/capture/` |
| 25 | `KSLCore/src/test/kotlin/ksl/modeling/decision/` (incl. `doc/`) |
| 11 | `KSLExamples/src/main/kotlin/ksl/examples/decision/` (incl. `tutorial/`) |
| 16 | `KSLExamples/src/test/kotlin/ksl/examples/decision/` (incl. `tutorial/`, `tutorial/doc/`) |
| 3 | `docs/guides/` — `ksl-decision.md`, `ksl-decision-tutorial.md`, `README.md` (the modified one) |

**Neither target package exists on KSL `main`.** No `KSLCore/src/main/kotlin/ksl/modeling/decision`, no `.../ksl/sdm`. There is nothing to collide with.

**The patch applies cleanly onto KSL `main`.** Verified read-only, exit 0, plain and three-way:

```
git diff $(git merge-base origin/main origin/feature/sequential-decision) \
    origin/feature/sequential-decision > /tmp/sdm.patch      # 940 KB
cd <KSL clone> && git apply --check /tmp/sdm.patch ; echo $?   # → 0
```

**Every upstream API the branch depends on is present on KSL `main`.** Checked individually: `withinReplicationWeightedSum` (`TWResponse.kt` — this one matters, ADR-13 depends on it and it was added upstream *during* the project), `SResource`, `SingleQStation`, `StationNetwork`, `NHPPEventGenerator`, `TabularOutputFile`, `Solver.createStochasticHillClimberSolver`, `ksl.simopt.problem.OptimizationType`, `KSLControl`, `Emitter`, `ModelElementObserver`, `AnimationSink`, `Model.animationSink`.

**Tests.** 186 tests across 39 classes, all passing at `8e5cf5e`:

```
./gradlew :KSLCore:test --tests 'ksl.modeling.decision.*' --tests 'ksl.sdm.*' \
          :KSLExamples:test --tests 'ksl.examples.decision.*'
```
KSLCore 24 classes / 131 tests; KSLExamples 15 classes / 55 tests. **Expect this to take a while** — the clinic and depot walkthroughs run real multi-replication simulations and the `simopt` handoff test runs two solver searches.

**Publishing.** `KSLCore` publishes to Maven Central (`com.vanniktech.maven.publish`, `publishToMavenCentral()`). **`KSLExamples` does not.** That is a real open question, not a nit — see §7.

**CI.** `.github/workflows/build.yml` triggers on `push` and `pull_request` to `[main]`, JDK 21 / temurin. A PR from a feature branch is built automatically; **no CI configuration change is needed.** There is no PR template in the repo.

---

## 4. The one thing that is lost

Three documents lived in `/home/user`, were delivered to the maintainer as chat attachments, and were **never committed to a repository**. They are gone from this machine.

| Document | What it is | Status |
|---|---|---|
| `KSL_Sequential_Decision_Making_OOD.md` | **The plan of record.** ~3,900 lines. Problem statement, conceptual framework, use cases, class model, invariants, package layering, data specifications, SPI, V&V approach, §17 traceability, §18 remaining work, and **ADRs 1–14** | Maintainer's copy only |
| `KSL_Sequential_Decision_Making_Revised_Plan.md` | The closing plan: D1 (complete, five steps), D2 (reduced), D3 (assessment window) | Maintainer's copy only |
| `KSL_Sequential_Decision_Making_M1_Exit_Report.md` | M1 exit evidence | Maintainer's copy only |

**Why this matters concretely.** 481 added Kotlin lines cite the OOD by number. The heaviest-referenced sections, by count of citing lines:

```
  §4.2.5 (29)   §8.2.3 (24)   §4.4.6 (23)   §6.2 (21)   §4.8.2 (15)
  §4.7   (15)   §4.2.4 (15)   §4.6.4 (14)   §4.10.2.1 (14)
```

A reviewer following `// §4.4.6: the envelope is the truck; 𝒳(s) is what this region is owed` has nowhere to go. The code is unusually self-documenting — the KDoc and test KDoc carry the reasoning and the measured defect behind nearly every decision — so a new session is not helpless. But the numbered references dangle, and a reader cannot tell whether `§4.4.6` is a real place or a fossil.

**Recommended fix, and do it first:** ask the maintainer for the three attachments, put the OOD (at minimum) under **`docs/design/ksl-decision-ood.md`** in the repository, and make that the first commit of the new branch. Two benefits beyond survival: the §-references resolve to a file a reviewer can open, and §17's traceability table becomes *mechanically checkable* — the OOD itself records that it cannot be checked today for exactly one reason, that "this document lives outside the repository: no test in the build can read it." Committing it removes that reason, and the check is ~20 lines modelled on the existing `ImplementationInventoryTest`.

---

## 5. The transition plan

### Step 0 — recover the documents (blocking)
Ask the maintainer for the three `.md` attachments. Do not start the branch without at least the OOD; everything else assumes it.

### Step 1 — a clean, full clone
```
git clone https://github.com/rossetti/KSL.git        # full, not shallow
cd KSL && git checkout -b feature/sequential-decision origin/main
```
Proposed branch name: **`feature/sequential-decision`** — same as the fork's, so the two are obviously the same work. Confirm with the maintainer; KSL's branch names are mostly bare topic names (`station`, `controls`, `time-series`), so `sequential-decision` is also idiomatic.

### Step 2 — replay the work
**Recommended: a small number of curated commits, not all 60.** The fork's 60 commits are a genuine research narrative including reversals ("Fix the LeverRef defect", "Answer the `value = value` question by measurement", "Redraw the M1/M3 boundary") and some add-then-delete churn in the first two commits. That story is valuable, but it is *research* history against a base that is not in KSL's lineage, and the fork remains as the permanent archive either way.

Suggested grouping — each commit builds and passes its own tests:

1. `docs/design/ksl-decision-ood.md` — the design document (Step 0)
2. `ksl.modeling.decision` + `ksl.modeling.decision.descriptor` — the element, DSL, policy hierarchy, action space, rewards, transitions, descriptors and codecs
3. `ksl.sdm.capture` — sinks, `TabularSink`/`TrajectoryFile`, `RollingSink`, `DecisionCapture`
4. `KSLCore` tests — 25 files
5. `KSLExamples` worked models + their tests
6. The tutorial package + its tests
7. `docs/guides/ksl-decision.md`, `ksl-decision-tutorial.md`, and the `README.md` row

*Alternative if the maintainer wants the archaeology:* `git format-patch` the 60 commits and `git am` them. They apply in principle, but they were authored against a different base and the early ones will need care. Ask before choosing.

### Step 3 — rebase reality check
The branch has **never** been rebased onto the 20 commits the fork's `main` gained, nor onto anything newer on KSL `main`. The patch applies, but *applying* is not *compiling*. Before opening a PR:

```
./gradlew :KSLCore:compileKotlin :KSLExamples:compileKotlin
./gradlew :KSLCore:test --tests 'ksl.modeling.decision.*' --tests 'ksl.sdm.*'
./gradlew :KSLExamples:test --tests 'ksl.examples.decision.*'
```

Then the **Level-1 additivity check** that the whole design rests on: run KSL's pre-existing suite and confirm it is unchanged. A model with no decision element must behave exactly as before.

### Step 4 — the deferred documentation item
The plan's D2 reduced to one sentence: add the two new packages to the **existing experimental list in `docs/release-notes.md`** when a release is next cut. `docs/release-notes.md` already carries such a list (search "remain experimental"). Both guides already carry the house experimental banner verbatim, so no other labelling is needed.

### Step 5 — open the PR
Squash is not needed; CI runs on `pull_request: branches: [main]` automatically. There is no PR template. Lead the description with §1 of this document — especially *"it deliberately does not choose the rule"* — because that is the thing a reviewer is most likely to misread.

---

## 6. Constraints and conventions that must survive the move

These were maintained throughout and a new session will break them by default.

- **Author identity.** Every commit is authored and committed as `rossetti <rossetti@uark.edu>`. One commit escaped this once and had to be re-authored and force-pushed. Use `git -c user.name='rossetti' -c user.email='rossetti@uark.edu' commit`.
- **No AI attribution anywhere** — not in commit messages, not in code comments, not in the guides, not in PR bodies. No `Co-Authored-By`, no generated-with footer. This overrides the default commit conventions.
- **No `@RequiresOptIn` / experimental opt-in annotation.** Explicitly rejected: *"It places stupid requirements on user code that nobody likes."* Experimental status is communicated in prose, in the guides' banner and the release notes.
- **Additivity is a design goal, not an accident.** 68 added / 1 modified. If the move requires touching an existing KSL file beyond the `docs/guides/README.md` row, that is a finding to raise, not a detail to absorb.
- **Testing discipline.** Load-bearing assertions are mutation-checked: break the property deliberately, require the test to fail, restore. A test that could pass while measuring nothing carries a control that fails if it is. Several classes exist *because* a green check was found to be measuring nothing — `EmissionTruthTableTest`, and the Level-2 stream assertion that could not fail until `advanceNextSubStreamOption` was turned off.
- **Documentation is verified, not trusted.** Both guides have a compile-only snippet host *and* a test asserting every fenced Kotlin block appears in that host. The tutorial additionally has `SystemDescriptionTest`, which parses its parameter tables and compares every number against the built model. If the guides move, these path literals move with them.
- **Claims are measured, not asserted.** Two corrections in this project came from the maintainer catching claims made from documents rather than from code (a `KSLDatabase` sink that could not work; a module wrongly believed published). When in doubt, read the source and quote it.

---

## 7. What is done, and what is not

**Done and tested.** The decision element and its DSL; the four declarations; positional vectors and the descriptor; settings vs transactions and neutral values; joint constraints (`budget`, `atMost`) including state-dependent ones; the feasible set as an enumerable object with `ActionSet`/`ActionSearch`/`LookaheadPolicy`; mixed-sense reward composition; parameterization from outside the model with failure atomicity; `@KSLControl` on the element's timing; the emission truth table; transition capture attachable and detachable from outside, with durable self-describing trajectories; descriptor codecs (JSON + TOML) with version and structural gates; the off-line training round trip; the `simopt` handoff; a reference guide and a hands-on tutorial, both snippet-verified.

**Not done, and deliberately.**

| Item | Status |
|---|---|
| Lookahead / rollout policies beyond the shipped skeleton | Out of scope; the seam is shipped, the algorithms are user code (ADR-1) |
| A `KSLDatabase` sink | **Dropped with reasons**, not deferred. `KSLDatabase` is a closed 12-table results schema that cannot be extended from outside; transitions are wide and variable-width where its tables are narrow and long; and a trajectory is training data, not results. `TabularSink` already writes an ordinary queryable SQLite file |
| Release-notes line | D2, pending the next release (§5 step 4) |
| The assessment window | D3 — third-party use, a feedback log, and a stop/retain/continue decision. Not code work |

**Open questions to put in front of the maintainer.**

1. **`KSLExamples` is not published to Maven Central; `KSLCore` is.** Verified again today. A Maven user therefore gets the library but neither the worked models nor the two runnable tutorial walkthroughs. The tutorial's Appendix A sends readers to files they may not have. Options: publish `KSLExamples`, relocate the tutorial walkthroughs, or state the limitation in the guides. **Not this project's call.**
2. **§17 traceability cannot be mechanically checked** while the OOD lives outside the repository. Committing it (§4) fixes this; the check is small and the pattern already exists.
3. **Failure policy on a sink write is hard**, deliberately — a throwing sink stops the run, where `AnimationSink` fails soft. A dropped training row is a silent data defect; a dropped animation frame is cosmetic. Worth a reviewer's explicit agreement.
4. **`SimoptTutorialSnippets.kt` is compile-only** — nothing asserts the simopt tutorial's blocks appear in it, so that document can drift from the API while still compiling. The decision tutorial's test is generic; pointing it at `ksl-simopt-tutorial.md` is roughly ten lines. Offered, not done.

---

## 8. Risks

| Risk | Likelihood | What to do |
|---|---|---|
| The OOD is never recovered and the §-references dangle permanently | Medium | Step 0 blocks on it. If it truly cannot be recovered, decide explicitly whether to strip the references or reconstruct the document — do not leave them pointing nowhere |
| KSL `main` moves under the branch between plan and PR | High | Re-run the `git apply --check` and the API-presence checks in §3; they are cheap |
| Applies but does not compile | Low–Medium | The 13 API dependencies were verified present today, but signatures can change. Step 3 compiles before anything else |
| The long test suite is treated as flaky and skipped | Medium | It is slow, not flaky — real simulations and two solver searches. The `simopt` search was verified deterministic across four repeat runs. Budget the time; do not weaken assertions to speed it up |
| A reviewer reads "no solver" as an omission | Medium | Lead the PR description with ADR-1 |
| The fork is deleted before the move completes | Low | `rossetti-org/tmp4321` `feature/sequential-decision` is the only copy of the 60-commit history. Keep it until the KSL branch is merged |

---

## 9. First hour for the new session

1. Ask for the three design documents. Do not proceed without the OOD.
2. Full-clone `rossetti/KSL`. Confirm `main`'s current head; do not assume `55c1641`.
3. Fetch the fork as a second remote and check out `feature/sequential-decision` — **verify you are on it**, and verify the tip is `8e5cf5e` or later.
4. Re-run the two cheap checks from §3: `git apply --check`, and the API-presence greps.
5. Run the full decision suite on the fork branch to see it green *before* moving anything, so a later failure is attributable to the move.
6. Only then create the branch off KSL `main` and begin Step 2.

Everything in this document was measured rather than remembered, and every measurement has its command beside it. Where something could not be established from this machine — the true relationship between the fork's history and KSL's, because both clones are shallow — it is said so rather than guessed.
