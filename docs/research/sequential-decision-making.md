# Sequential Decision Making Under Uncertainty in the KSL — Research Notes

**Status:** Research and investigation only. Not a plan, not a design, not a commitment.
**Date:** 2026-08-01

The question: KSL is a discrete-event simulation library with strong simulation-optimization
support (`ksl.simopt`). What would it take for KSL to become a good *host* for sequential
decision problems — the "sequential decision analytics" framing of Powell, of which
reinforcement learning is one algorithmic family — **without KSL implementing RL algorithms
itself**? The goal is hooks and patterns that let a user wire in existing, well-supported
learning libraries.

---

## 1. The framing worth adopting

Powell's unified framework models any sequential decision problem as the five-tuple:
state variable `S_t`, decision `x_t`, exogenous information `W_{t+1}`, transition function
`S_{t+1} = S^M(S_t, x_t, W_{t+1})`, and an objective over a policy `X^π(S_t)`. Its most
useful contribution here is the claim that **every** solution method falls into one of four
policy classes:

| Class | Form | What it looks like in a simulation |
|---|---|---|
| **PFA** — policy function approximation | An analytic rule `X^π(S_t \| θ)` | `(r, Q)` inventory policy, a dispatching rule, a threshold |
| **CFA** — cost function approximation | Solve a modified/parameterized optimization each epoch | "cheapest vendor whose lead time < τ", myopic LP with a bonus term |
| **VFA** — value function approximation | Decide using `\hat V(S_{t+1})` | Q-learning, ADP, most of deep RL |
| **DLA** — direct lookahead | Solve an approximate model of the future | MPC, rolling-horizon stochastic program, MCTS |

This taxonomy matters because it reframes what KSL already does. **Simulation optimization
over the parameters of a decision rule *is* policy search over PFAs and CFAs.** KSL already
supports two of the four classes — it just doesn't call them that. What is missing is the
vocabulary that makes a decision point a first-class object, and the machinery that exposes
`(S_t, x_t, r_t, S_{t+1})` to something outside the model. Those are what VFA and DLA need.

The framing also gives a defensible scope boundary: KSL supplies the *model* (`S^M`, `W`, the
cost accumulation, the decision epochs) and the *policy interface*; the learning algorithm
lives elsewhere.

---

## 2. Assessment: what KSL already has

Grounded in the current code, not aspiration.

### Strong, directly reusable

- **`ksl.simopt`'s separation of concerns.** `SimulationOracleIfc.simulate(EvaluationRequest)`
  → `Map<ModelInputs, Result<ResponseMap>>` and `EvaluatorIfc.evaluate(...)` already draw the
  exact line an RL setup needs: an outer loop that proposes, an inner engine that simulates and
  reports. `Solver`'s template (`startingPoint()`, `nextPoint()`, `mainIteration()`) is a
  policy-search loop wearing different clothes.
  (`KSLCore/src/main/kotlin/ksl/simopt/evaluator/SimulationOracleIfc.kt`,
  `.../simopt/solvers/Solver.kt`)
- **`ksl.controls`.** `KSLControl`-annotated properties plus `ControlIfc` are already a
  named, typed, external parameter-injection mechanism. A policy parameter vector is a
  control vector. Nothing new is needed to *set* a PFA's parameters from outside.
- **`InventoryPolicyAbstract`** is essentially a prototype `PolicyIfc` that nobody generalized:
  it has `setPolicyParameters(DoubleArray)` / `getPolicyParameters()`, an initial-parameter
  vector restored per replication, and a `policyChangedDuringRep` flag.
  (`.../modeling/supplychain/inventory/InventoryPolicyAbstract.kt`) This is the single best
  evidence that the abstraction wants to exist one level up.
- **Continuation-capturing process view.** `ProcessModel`'s `ProcessCoroutine` implements
  `Continuation<Unit>`, and `KSLProcessBuilder` exposes `suspend(name)` / `suspendFor(Suspension)`
  with `Entity.resumeProcess()` and an immediate-resume path. **A decision point is exactly a
  named suspension whose resumption carries an action.** The mechanism already exists; it has
  never been pointed at this use case.
- **Observer infrastructure.** `ModelElementObserver`, `ResponseTrace`,
  `ReplicationDataCollector`, `ExperimentDataCollector`, plus tabular/db output. A trajectory
  recorder is a specialization, not new plumbing.
- **Stream control.** `StreamTapePolicy` and the design-point stream policies mean KSL can do
  common random numbers *across policy evaluations* — a variance-reduction capability most RL
  training setups simply do not have. This is a differentiator, not a checkbox.
- **`ksl.modeling.agent`** (`AgentLike`, `Statechart`, `AgentMessaging`, `ContractNet`) is a
  natural home for multi-agent decision problems, and statechart transitions are a second
  candidate decision-point locus.

### Present but at the wrong granularity

- **Servers.** `KSLServerRest` is job-oriented (`POST /runs`, poll `/runs/{id}/result`) and the
  MCP suite likewise. Both are *whole-run* transports. RL episode stepping needs a
  per-decision-epoch channel. The transport exists; the granularity does not.
- **Concurrency.** `Model.simulate()` is synchronous on the calling thread;
  `SimulationDispatcher` gives coroutine-based parallelism across replications. Good for
  vectorized environments; says nothing yet about who owns the interactive loop.

### Absent

- Any notion of a decision epoch, action space, feasible-action mask, observation projection,
  or per-interval reward.
- Any `(s, a, r, s', done)` transition record.
- Any inversion of control that lets an external process step a run.
- Any statement of episode semantics (replication↔episode, warm-up↔burn-in, terminal vs.
  truncated, infinite-horizon average reward).

---

## 3. The central architectural question: who owns the loop?

Everything downstream depends on this. There are three answers and they are not exclusive.

### Pattern A — Policy-in-the-loop (the simulation drives)

The model reaches a decision point and *calls* `policy.action(state)`. Synchronous, in-JVM,
no inversion, no IPC.

- Cost: near zero. This works in KSL **today** with an interface and a convention.
- Covers: all PFAs and CFAs, any VFA/DLA whose evaluation is a function call, and — crucially —
  any *already-trained* neural policy loaded via ONNX Runtime.
- Does not cover: online training, where the learner must control episode boundaries and
  collect on-policy rollouts.

### Pattern B — Environment stepping (the learner drives)

Gymnasium-shaped `reset()` / `step(action) -> (obs, reward, terminated, truncated, info)`.
This requires the *whole simulation* to suspend at a decision epoch and yield control outward,
which is a genuinely different thing from suspending one entity.

Two implementations:

- **B1 — thread handoff.** Run the executive on its own thread; rendezvous with the caller via
  a `SynchronousQueue` pair at each decision epoch. Simple, robust, framework-agnostic, and it
  is what essentially every DES↔Gym bridge does in practice (AnyLogic/alpyne, the SimPy-based
  wrappers, ABIDES-Gym). Cost: one thread per environment instance, and disciplined lifecycle
  handling on early termination.
- **B2 — continuation-based.** Make the executive itself suspendable so a decision epoch
  returns to the caller without a thread. Conceptually elegant and closer to KSL's existing
  coroutine machinery, but it means the event loop, not just an entity, becomes a coroutine —
  a much deeper change with replication/warm-up/reset interactions.

B1 is the pragmatic starting point; B2 is a research question in its own right.

### Pattern C — Offline / data generation (nobody drives interactively)

Run under a behavior policy, log trajectories, hand the dataset to offline RL. No inversion of
control at all, and it composes with KSL's existing db/tabular output. Underrated: it also
supports fitted-VFA methods, off-policy evaluation, and imitation of a good heuristic — and it
is the *only* pattern that lets the user exploit CRN across candidate policies cleanly.

**A useful sequencing hypothesis:** A and C are nearly free and cover a surprising amount of
real usage; B is where the actual design work and risk live.

---

## 4. Where the algorithms come from (the "don't write RL" constraint)

This constraint is correct and should be held firmly. Two facts shape everything:

**Fact 1: there is no credible JVM RL *training* ecosystem to leverage.** RL4J is part of the
largely dormant DL4J stack; KotlinDL is not an active bet. Deep Java Library (DJL) is alive and
has a real ONNX Runtime engine, but it is an inference/deep-learning library, not an RL
algorithm library. Betting the design on a JVM trainer would mean writing RL algorithms after
all — exactly what is off the table.

**Fact 2: the entire live ecosystem is Python** — Gymnasium (the de-facto env API, v1.3 as of
2026), Stable-Baselines3, RLlib, CleanRL, Tianshou; PettingZoo for multi-agent; Minari +
d3rlpy for offline datasets and offline algorithms.

Therefore the design question is not "which RL library" but **"where is the process boundary,
and which direction does it face."** Four seams, in increasing order of coupling:

1. **ONNX inference inside the JVM** (pairs with Pattern A). Train anywhere in Python, export
   ONNX, load with ONNX Runtime Java or DJL, implement `PolicyIfc` as a tensor call. KSL runs
   stay self-contained and fast — no per-step IPC. This is the highest value-to-effort seam and
   it makes *deploying* a learned policy inside a normal KSL study trivial. It should probably
   be the first thing tried.
2. **KSL as an external client to a learner** (pairs with Pattern A + a network policy).
   RLlib explicitly supports simulators that own their own execution loop, connecting as
   clients to a policy server (the RLlink protocol), optionally doing local ONNX inference and
   shipping trajectories back for training. **This is the most important finding of this
   investigation:** it removes the hard requirement that the simulator be steppable/invertible.
   A DES engine is precisely the "complex simulator with its own loop" this mode exists for.
   Cost: couples to one framework's protocol.
3. **KSL as a stepped environment behind a Python Gymnasium shim** (Pattern B). Universal
   compatibility with everything in the ecosystem. Highest generality, highest cost, and the
   per-step RPC overhead must be measured against event-processing cost before committing.
4. **Offline datasets** (Pattern C). Export trajectories in the Minari format; train with
   d3rlpy; evaluate off-policy. Cheap, and it gives the simulation community something it
   actually wants — a defensible way to compare a learned policy against a good heuristic.

---

## 5. Candidate hook vocabulary (sketch, for argument's sake)

Not a proposal — a strawman to react to. The bet is that ~8 small abstractions cover it:

- `DecisionPointIfc` — a named locus in the model where a choice is required; emitted at an
  epoch, carrying the requesting entity/element.
- `ObservationIfc` / feature extractor — the *projection* of model state the policy is allowed
  to see. Deliberately distinct from the model's full state: KSL exposes everything, so it is
  trivially easy to write a policy that cheats. Enforcing the projection is a feature.
- `ActionSpaceIfc` — discrete / box / parameterized, plus a **feasibility mask**. Constrained
  action sets are the norm in operational models and are badly handled by generic RL APIs.
- `RewardIfc` — cost/reward accumulated *over the interval between decision epochs*, not per
  event.
- `PolicyIfc: (Observation) -> Action` with implementations spanning the four classes:
  `ParameterizedPolicy` (PFA — generalizes `InventoryPolicyAbstract`), `CfaPolicy`,
  `VfaPolicy`, `LookaheadPolicy`, `ExternalPolicy` (ONNX / RPC).
- `TransitionRecorder` — a `ModelElementObserver` emitting `(s, a, r, s', done, τ)` into the
  existing tabular/db sinks.
- `EpisodeSpec` — replication↔episode mapping, warm-up↔burn-in, terminal vs. truncated,
  seeding/CRN policy.
- A `simopt` adapter that makes the PFA/CFA story explicit: decision variables *are* policy
  parameters, so `Solver` becomes a policy-search algorithm by relabeling.

A distinctive angle worth noting: **KSL can serve as its own lookahead model.** A DLA policy
that runs a nested, short-horizon KSL simulation at each decision epoch is natural here and is
something a pure RL library cannot offer. That is a differentiator, not a nice-to-have.

---

## 6. The hard problems (what the investigation should actually chew on)

These are the reasons this is research and not a weekend of coding.

1. **Semi-MDP time semantics.** DES decision epochs are event-driven and irregularly spaced.
   Rewards integrate over variable-length intervals; discounting is continuous-time (`e^{-βτ}`),
   not per-step `γ`. Gymnasium/SB3 assume uniform steps. Naively pretending each decision is one
   "step" silently changes the objective. This mismatch deserves an explicit position — it is
   the most likely source of subtly wrong results in every existing DES-to-gym bridge.
2. **Who is the agent?** When many entities make decisions, is there one policy queried by many
   entities, or many agents? PettingZoo's AEC (turn-taking) model maps unusually well onto an
   event calendar — better than the parallel-step model. Worth exploring.
3. **Partial observability and leakage.** Full model state is reachable from any
   `ModelElement`. Without a disciplined observation projection, learned policies will exploit
   information no real controller has.
4. **Steady-state vs. episodic.** RL is overwhelmingly episodic/discounted; much of what KSL is
   used for is infinite-horizon average reward with a warm-up. Reconciling warm-up with episode
   boundaries and bootstrap targets is non-obvious.
5. **CRN and reproducibility as an advantage.** KSL's stream control could give low-variance
   policy comparison — but it interacts with exploration and with replay buffers in ways that
   need thought before being claimed as a benefit.
6. **Throughput.** Per-step IPC versus event-processing cost, batching, and vectorized
   environments (N concurrent models — KSL already runs concurrent replications). Needs
   measurement before architecture.
7. **Validation story.** The credible pitch to the simulation community is not "KSL does RL"
   but "KSL lets you compare a learned policy to a well-tuned heuristic, honestly, with CRN and
   confidence intervals." Off-policy evaluation belongs in that story.

---

## 7. Open questions for the next round

- Does the ambition include *authoring* sequential decision problems in the Powell framing
  (state/decision/exogenous/transition as first-class), or only *hooking* learners into
  ordinary KSL models? These lead to different libraries.
- Single-agent first, or is multi-agent in scope early? It changes the API shape substantially.
- Is a Python-side companion package acceptable as a deliverable, or must everything stay JVM?
  (The honest answer to "leverage existing libraries" almost certainly requires a Python
  artifact.)
- What is the reference problem set? Candidates: `(s, S)` inventory (learned vs. optimal —
  a known-answer benchmark), dynamic resource/server allocation, dispatching in the station
  package, and a supply-chain network problem where the DLA angle shines.

---

## 8. Sources

- Powell, *Sequential Decision Analytics* / unified framework —
  [CASTLE, Princeton](https://castle.princeton.edu/sda/),
  [A Universal Framework for Sequential Decision Problems, ORMS Today](https://pubsonline.informs.org/do/10.1287/orms.2023.01.02/full/)
- [Gymnasium: A Standard Interface for RL Environments (arXiv:2407.17032)](https://arxiv.org/abs/2407.17032),
  [Gymnasium docs](https://gymnasium.farama.org/index.html)
- [RLlib external environments and applications](https://docs.ray.io/en/latest/rllib/external-envs.html)
- [Minari — standard format for offline RL datasets](https://github.com/Farama-Foundation/Minari),
  [d3rlpy](https://takuseno.github.io/d3rlpy/)
- [DJL ONNX Runtime engine](https://docs.djl.ai/master/engines/onnxruntime/onnxruntime-engine/index.html)
- DES↔Gym prior art: [ABIDES-Gym (arXiv:2110.14771)](https://arxiv.org/pdf/2110.14771),
  [production scheduling as RL environments via DES + Gym](https://www.sciencedirect.com/science/article/pii/S2405896321008399),
  [AnyLogic RL / alpyne](https://www.anylogic.com/features/artificial-intelligence/reinforcement-learning/)
