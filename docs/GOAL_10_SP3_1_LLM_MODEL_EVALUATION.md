# Goal 10 / SP3.1 — LLM Model Evaluation for the DISPATCHER Agent Role

**Issue:** [#534](https://github.com/bedaHovorka/interlockSim/issues/534) (SP3.1) ·
sub-issue of [#533](https://github.com/bedaHovorka/interlockSim/issues/533) (SP3) ·
subproject of [#532](https://github.com/bedaHovorka/interlockSim/issues/532) (Goal 10)

**Status:** ✅ Final — SP3.1 deliverable (comparison in `.md` only, no implementation;
measured benchmarks are produced later by [§7](#7-benchmark-protocol-to-execute-in-sp35--sp2b9))
**Last Updated:** 2026-07-06
**Authoring scope:** finalized against the **rewritten #532 issue body + superseding
companion-plan comment of 2026-07-06**, which are the canonical description of Goal 10.
Per that body, this evaluation is **DISPATCHER-only**: LLM train agents were ruled out
permanently on 2026-07-04 (see §1.2).

**Hard constraint (owner comment on #534):** **max. VRAM = 8 GB.** Every recommended model
must run inside an 8 GB GPU *alongside* the JVM + kDisco simulation. Models whose default
Ollama quantisation exceeds this budget are flagged and only kept as stretch candidates.

> ⚠️ **No measured benchmarks yet.** This document ranks candidates from public model cards,
> Ollama metadata, and the SP3 tool/latency design in #533. The latency and tool-call success
> numbers required by #534 (p50/p95, success rate, context tokens, Czech vocabulary quality)
> must be produced by the scripted harness described in
> [§7 Benchmark protocol](#7-benchmark-protocol-to-execute-in-sp35--sp2b9) once the SP3.4/SP3.5
> tool surface (`requestRoute`, `blockOccupancy`, …) exists. Treat the rankings here as
> *priors* that tell the benchmark which models to try first.

---

## 1. Context: the two agent roles

From the SP3 architecture (#533, §1 ESA 11 → InterlockSim mapping) there are two agent
roles, of which **only the DISPATCHER is LLM-eligible** (#532 body, Non-goals). The
interlocking kernel — never the model — owns safety, so the LLM only produces *intent* and
every actuator tool is a thin RPC that the kernel independently re-validates. Per the #532
staging, the LLM dispatcher sits **behind the same `Dispatcher` seam** as the deterministic
`RuleBasedDispatcher` (refactored from `ShuntingLoop`, SP0.1 #540 / SP2b.2 #557) in the new
**`:dispatcher-agent`** module — both first-class from the start, both gated on
`vyhybna.xml` (Goal 10, first stage).

| Role | SP | Style | Trigger | Latency budget | LLM? |
|---|---|---|---|---|---|
| **DISPATCHER** | SP2b (#538) | Deliberative | On events (new `RouteRequest`, conflict) — **not every tick** | ≤ 2 s per decision | **Yes** — the project's only LLM-driven role |
| **TRAIN** | SP2a (#537) | Reactive | Control-loop hot path | ≤ 50 ms per decision | **Never** (firm decision 2026-07-04) — algorithmic only |

### 1.1 Why latency is forgiving for the DISPATCHER

kDisco is **discrete-event**: the simulation clock does not advance while the dispatcher LLM is
thinking (#533, §8). A 1–2 s inference therefore cannot create a real-time safety violation —
it only slows wall-clock throughput. Combined with "call the LLM only on events, cache/debounce
identical decisions, and cap `maxIterations`", this means the DISPATCHER can afford a mid-sized
(7B–12B) model even on modest hardware.

Two wall-clock constraints from #532 still apply and must be checked in §7:
- **A6 gate:** the vyhybna run must keep real-time ratio ≥ 1×. LLM inference is the dominant
  wall-clock cost, so per-decision latency directly determines whether A6 holds.
- **Speed control (owner decision, 2026-07-06):** agent-driven runs are allowed **only slow
  simulation speeds** from the Goal 7 speed-control range (#187) — the `SimulationRunner`
  throttle is not permitted to fast-forward past the dispatcher's inference latency.

### 1.2 Why the TRAIN agent is algorithmic — permanently

The #532 body (Non-goals, decided 2026-07-04) is explicit: **"LLM train agents — never."**
SP2a (#537) stays algorithmic; the DISPATCHER is the project's only LLM-driven role. This is
no longer a "v0 recommendation to revisit later" — it is a closed decision, consistent with
the physics: a < 50 ms hot-path budget is below the cold-start + per-token latency of any
locally hosted LLM at useful quality. The TRAIN agent is implemented as a deterministic rule
loop (`TrainDecisionPolicy`, SP2a.4 #555) and this document therefore evaluates **no models
for the TRAIN role**.

---

## 2. Evaluation criteria & weights

Reproduced from #534 with the 8 GB VRAM cap promoted to a hard gate.

| Criterion | Weight | Pass condition |
|---|---|---|
| Tool-calling reliability | **Critical** | Reliably emits valid JSON for `requestRoute` / `blockOccupancy` / `releaseRoute`; malformed calls are caught by Koog structured-output validation |
| Multi-step reasoning | High | Chains sense → decide → act across several tool turns (worked example *c*, #533 §6) |
| Context window | High | Holds network state (block list, pending requests) — estimate **4–8 k tokens** for a medium station. Station **topology is loaded once at agent start** (SP2b.8 #695); per-turn tool calls carry only dynamic state, so the working context stays small on vyhybna |
| Latency on dev HW | High | DISPATCHER ≤ 2 s, measured on Intel Arrow Lake + NVIDIA GPU; must sustain real-time ratio ≥ 1× on vyhybna (A6) under the slow-speed-only agent constraint (#187) |
| Czech / railway vocabulary | Medium | Understands Czech signal terms (`Stůj`, `Volno`, `Výstraha`, `posun`, `úsek obsazen`) without fine-tuning |
| Model size / VRAM | **Gate** | **≤ 8 GB VRAM** including KV-cache headroom alongside JVM + kDisco |
| Fallback compatibility | Medium | On stall / invalid call, does not loop excessively → deterministic "deny + hold" fallback engages cleanly |

---

## 3. Candidate models (Ollama, ≤ 8 GB tiers)

The candidate table in #534 had blank model names; below is the proposed concrete shortlist that
fills those rows with real Ollama tags and maps each to a Koog `OllamaModels.*` constant where
one is known to exist. Sizes are the **default Q4 download size**; VRAM at runtime is roughly
*download size + KV-cache* (KV-cache grows with context length, ≈ 0.5–1.5 GB at 8 k tokens).

> **Koog mapping caveat (#533 §8 / SP1):** Koog 1.0 ships a curated `OllamaModels` set
> (`OllamaModels.Meta.*`, `OllamaModels.Alibaba.*`, …). Constants below marked *"verify"* must be
> confirmed against the resolved `ai.koog:koog-agents:1.0.0` artifact during SP1.2 (#547); any
> model not pre-listed can still be used by constructing an `LLModel`/`OllamaModelCard` with the
> Ollama tag directly. **This doc proposes mappings; it does not edit the Koin module (that is
> SP1.3 #548 / SP1.5 #550, in the `:dispatcher-agent` module).**

### Tier D — DISPATCHER candidates (deliberative, fits 8 GB)

| # | Ollama tag | ~Size (Q4) | Issue row | Tool calling | Proposed Koog constant | Notes |
|---|---|---|---|---|---|---|
| D1 | `qwen2.5:7b-instruct` | ~4.7 GB | ~5 GB "solid baseline" | Native | `OllamaModels.Alibaba.QWEN_2_5_*` *(verify)* | Strong tool-calling + multilingual; best size/quality trade-off in budget |
| D2 | `llama3.1:8b` | ~4.9 GB | ~4 GB "widely tested" | Native | `OllamaModels.Meta.LLAMA_3_1_*` *(verify)* | Mature, well-documented function calling; good multi-step reasoning |
| D3 | `mistral-nemo:12b` | ~7.1 GB | ~7 GB "stronger tool calling than base Mistral" | Native | construct from tag *(verify)* | 128k context; near the VRAM ceiling — leaves little KV headroom |
| D4 | `command-r7b` | ~5.0 GB | ~5 GB "strong function-calling scores" | Native (tuned for RAG/tools) | construct from tag *(verify)* | Cohere tool-use tuning; good JSON discipline |
| D5 | `gemma3:4b` | ~3.3 GB | ~3 GB "Gemma 3, fast" | Gemma-3 tool calling | `OllamaModels.Google.GEMMA_3_*` *(verify)* | Fast; referenced in SP3 notes; weaker multi-step than 7B+ |

### Not evaluated — TRAIN role (closed) and ultra-light tier

The earlier draft carried a "Tier R" (`llama3.2:3b`, ~2.0 GB, `OllamaModels.Meta.LLAMA_3_2`
*(verify)*) for a hypothetical LLM TRAIN agent. Per the #532 Non-goals decision of 2026-07-04
(**LLM train agents — never**), that tier is dropped from evaluation. `llama3.2:3b` fills the
"~2 GB, very fast, already in Koog Meta" row of the #534 candidate table for completeness only;
if an ultra-light *dispatcher* is ever needed below `gemma3:4b`, it would be the first tag to
try, but it is not a recommended DISPATCHER candidate (weak multi-step reasoning).

### Stretch — over budget, documented for completeness

| # | Ollama tag | ~Size (Q4) | Issue row | Why excluded |
|---|---|---|---|---|
| S1 | `qwen2.5:14b-instruct` | ~9.0 GB | ~8 GB "better reasoning, fits 8 GB" | Default Q4 ≈ 9 GB **exceeds the 8 GB gate**. Only viable at Q3_K_M (~7.5 GB) with tiny KV-cache; keep as a quality-ceiling reference, not a runtime target. |

---

## 4. Comparison matrix (qualitative priors)

Scores are **priors** (◎ excellent / ○ good / △ marginal / ✗ fail) from model cards and the
8 GB gate — **to be replaced by measured results** from §7. Tool-calling reliability is the
critical gate; ties are broken by Czech vocabulary and VRAM headroom.

| Model | Tool calling (Critical) | Multi-step | 8 k context | Latency (dev HW)¹ | Czech vocab | VRAM ≤ 8 GB | Overall (DISPATCHER) |
|---|---|---|---|---|---|---|---|
| **D1 qwen2.5:7b** | ◎ | ◎ | ◎ (32k) | ○ | ◎ (strong multilingual) | ◎ (~4.7 GB) | **◎ top pick** |
| D2 llama3.1:8b | ◎ | ◎ | ◎ (128k) | ○ | ○ | ◎ (~4.9 GB) | ◎ close second |
| D3 mistral-nemo:12b | ◎ | ◎ | ◎ (128k) | △ (largest) | ○ | △ (~7.1 GB, low headroom) | ○ |
| D4 command-r7b | ◎ (tool-tuned) | ○ | ◎ (128k) | ○ | △ | ◎ (~5.0 GB) | ○ |
| D5 gemma3:4b | ○ | △ | ○ (8k+) | ◎ (fastest in tier D) | ○ | ◎ (~3.3 GB) | ○ (fast fallback) |
| R1 llama3.2:3b | ○ | △ | ○ | ◎ | △ | ◎ (~2.0 GB) | △ (completeness row only — not a candidate, see §3) |
| S1 qwen2.5:14b | ◎ | ◎ | ◎ | ✗ (slow) | ◎ | ✗ (~9 GB) | ✗ (over budget) |

¹ Latency column reflects *relative model size* on a single 8 GB GPU; absolute p50/p95 numbers
are pending the §7 harness.

---

## 5. Recommendations

### 5.1 DISPATCHER agent — primary: `qwen2.5:7b-instruct` (D1)

**Justification**
- **Tool calling (critical):** Qwen 2.5 Instruct has first-class, well-tested function-calling
  and produces disciplined JSON, which Koog's structured-output validation can police.
- **Multi-step reasoning:** Strong on chained sense → decide → act, required for the conflict
  example (#533 §6c).
- **Context:** 32 k native context comfortably exceeds the 4–8 k medium-station estimate, with
  room for tool transcripts.
- **Czech vocabulary:** Qwen 2.5 is strongly multilingual and handles Czech operating terms
  (`úsek obsazen`, `Stůj`, `Volno`, `posun`) better than most same-size Western models — directly
  relevant to evaluation task 4 ("Proč jsi zvolil tuto cestu?").
- **VRAM:** ~4.7 GB leaves ≈ 3 GB for KV-cache + JVM/kDisco GPU spillover inside the 8 GB gate.

**Primary fallback model: `llama3.1:8b` (D2)** — near-identical footprint, mature tool calling,
already familiar via Koog `OllamaModels.Meta`. Swap in if Qwen's Czech rationale or JSON
discipline underperforms in §7.

**Fast/degraded model: `gemma3:4b` (D5)** — when running on tighter VRAM or when only single-turn
routing (task 1) is needed; lower multi-step quality is acceptable there.

### 5.2 TRAIN agent — **no LLM, ever** (closed decision)

Per the #532 Non-goals (decided 2026-07-04), the TRAIN agent is algorithmic **permanently** —
there is no "revisit later" clause. It is implemented as a deterministic reactive rule loop
behind `TrainDecisionPolicy` (SP2a.4 #555); no Ollama model is bound to it and no model
evaluation applies. The DISPATCHER is the project's only LLM-driven role.

### 5.3 Proposed Koog / Koin binding (for SP1 in `:dispatcher-agent` — *not implemented here*)

This is a **proposal** for the future **`:dispatcher-agent`** Koin module (SP1.3 #548, executor
SP1.5 #550); SP3.1 does not edit code. The `:dispatcher-agent` module is a sibling of `:core` /
`:desktop-ui` / `:fast-sim`, depends on `:core`, owns all Koog/Ollama dependencies, and is never
depended on by `:fast-sim` (#532 body, Critical files; SP0.6 #545).

```kotlin
// PROPOSED — to be wired in SP1 (#546–#551) in :dispatcher-agent, not in this SP3.1 doc task.
// Default DISPATCHER model; overridable via config/env for benchmarking.
val dispatcherLlmModel = OllamaModels.Alibaba.QWEN_2_5   // verify constant name in Koog 1.0
// Fallback: OllamaModels.Meta.LLAMA_3_1 (llama3.1:8b)
// Fast/degraded: OllamaModels.Google.GEMMA_3 (gemma3:4b)
```

Make the model id a **single injectable config value** (e.g. Koin `named("dispatcher.model")`) so
the §7 benchmark can sweep all candidates without code edits, and so the 8 GB gate can be enforced
by configuration rather than recompilation. The same config surface must expose the **Ollama
sampling seed**, because A4 acceptance runs are seed-pinned (§7).

---

## 6. Fallback strategy (deterministic "deny + hold" → `RuleBasedDispatcher`)

Aligned with the #532 body (A2: "a deterministic rule-based fallback (#566 SP2b.9) defers to
safety if the LLM stalls"), SP3.6 (#574, pluggable planner) and #533. Both dispatchers sit
behind the same **`Dispatcher` seam** in `:dispatcher-agent`, so the fallback target is the
shipped deterministic **`RuleBasedDispatcher`** (SP2b.2 #557, refactored from `ShuntingLoop`) —
mandatory reuse of Goal 9 machinery (`AutoConflictResolutionService`, `ConflictResolutionRanker`,
`DispatcherPreferenceStore`), never a re-implementation. The model is *never* in the safety
path; these are liveness/robustness guards:

1. **Structured-output rejection** — if the model emits a malformed tool call, Koog's validation
   rejects it before it reaches the `InterlockingFacade`. Count the rejection.
2. **Iteration cap** — cap Koog `maxIterations` (e.g. 4). On exceed, abort the LLM turn.
3. **Loop/timeout guard** — if the model repeats the same denied call or exceeds the ≤ 2 s budget,
   abort the LLM turn.
4. **Deterministic safe default** — on any of the above, the `Planner` falls back to rule-based
   **"deny + hold"**: issue `RouteDenial` + `HoldOrder` (train stays at `Stůj`). This is always
   safe because the interlocking already refuses unsafe routes; denying is the conservative action.
5. **Degraded model swap** — optionally retry once with the fast/degraded model (gemma3:4b) before
   the deterministic default, for resilience without unbounded looping.

Because deny + hold is the conservative default, **a model that fails tool-call validation
degrades to a safe, if less efficient, dispatcher** — never to an unsafe one.

---

## 7. Benchmark protocol (to execute in SP3.5 / SP2b.9)

The numeric acceptance evidence required by #534 must be produced once the SP3.4 `InterlockingFacade`
(#572) and SP3.5 ToolSets (#573) exist; the full-run gate lands with the LLM dispatcher in
SP2b.9 (#566). For **each** Tier D candidate, run the four scripted scenarios from #534 against
the real tool surface and record the metrics.

**Run conditions (from the #532 body, first stage):**
- **Network:** `vyhybna.xml` — the paramount example (2 InOuts, 2 switches, 6 semaphores,
  ~2 concurrent trains). Praha scale (#591) is downstream and out of scope here.
- **Seed pinning (A4):** the local Ollama sampling **seed is pinned** per run. Acceptance is
  **outcome-gated**, not decision-for-decision: across N consecutive runs, all trains exit, no
  conflict events, no operator action, rationale recorded per run. The `RuleBasedDispatcher`
  (A3) carries the reproducibility guarantee; the LLM is compared, not the anchor.
- **Speed:** agent runs use only slow Goal 7 speed multipliers (#187, owner decision 2026-07-06);
  the run must still keep real-time ratio ≥ 1× (A6).
- **Comparison metrics (A5):** sourced from Goal 6 SP1 (#672) `MetricsCollectionService` (delay,
  conflicts, throughput, utilization) + `DispatcherPreferenceStore.getChoices()` (decision count,
  rationale entries) + the existing `realTimeRatio` scaffolding. **Reported, not gated.**

**Scenarios (from #534):**
1. **Single-junction routing** — one `RouteRequest`, two blocks `VOLNO` → expect one `requestRoute`,
   `RouteGrant`.
2. **Conflict resolution** (example *c*, #533 §6) — two simultaneous `RouteRequest` for the same
   block → `requestRoute` for winner, `RouteDenial` + `HoldOrder` for loser.
3. **Degraded-mode refusal** — `requestRoute` returns `DENIED:úsek obsazen` → `HoldOrder`, **no
   retry loop**.
4. **Explanation query** — after routing prompt *"Proč jsi zvolil tuto cestu?"* → Czech rationale
   referencing the chosen route.

**Metrics to record per model (fills the §4 matrix with real numbers):**

| Metric | How |
|---|---|
| Tool-call success rate | valid calls / total calls (Koog validation outcome) |
| Decision latency p50 / p95 | wall-clock per dispatcher decision on Arrow Lake + NVIDIA GPU |
| Context tokens used | prompt + tool transcript token count at decision time |
| Czech vocabulary quality | qualitative 1–5 score on task 4 rationale |
| Peak VRAM | `nvidia-smi` during run — must stay ≤ 8 GB |
| Fallback events | count of iteration-cap / loop-guard / deny-hold / `RuleBasedDispatcher` handover activations |
| Outcome contract (A4) | per seed-pinned run: all trains exit, 0 conflict events, 0 operator actions, rationale recorded |
| Real-time ratio (A6) | ≥ 1× on vyhybna under slow-speed agent constraint |

Promote whichever candidate maximises tool-call success and Czech quality **within** the 2 s and
8 GB gates while holding the A4 outcome contract and A6 real-time ratio; update §5 and the SP1
Koin binding in `:dispatcher-agent` accordingly.

---

## 8. Decisions, open questions & traceability

**Decisions (final for SP3.1; model ranking still subject to §7 measurement):**
- **D-1:** DISPATCHER primary = `qwen2.5:7b-instruct`; fallback model = `llama3.1:8b`;
  fast/degraded = `gemma3:4b`.
- **D-2:** TRAIN agent is algorithmic — **never LLM** (#532 Non-goals, decided 2026-07-04).
  No model is evaluated for the TRAIN role.
- **D-3:** Model id (and Ollama sampling seed, for A4 seed pinning) are injectable config values
  in the `:dispatcher-agent` Koin module to enable benchmark sweeps and enforce the 8 GB gate.
- **D-4:** `qwen2.5:14b` is **out** (default Q4 ≈ 9 GB > 8 GB gate); kept only as a quality reference.
- **D-5:** On any LLM stall/invalid-call, control degrades to deterministic "deny + hold" and,
  per #566 (SP2b.9), to the `RuleBasedDispatcher` behind the same `Dispatcher` seam.

**Open questions for SP1/SP3.4:**
- Exact Koog 1.0 `OllamaModels` constant names (verify against resolved artifact during SP1.2 #547).
- KV-cache size at 8 k context per candidate (confirms real VRAM headroom under the gate).
- Whether `command-r7b`'s tool-tuning beats Qwen's multilingual edge on the Czech rationale task.
- Which Goal 7 speed multipliers count as "slow" for agent runs (#187 range is 0.1x–100x; owner
  comment on #532 restricts agents to the slow end — exact cap to be fixed in SP2b.9 #566).

**Traceability:**
- Canonical Goal 10 definition, staging (A1–A6), determinism clause, Non-goals ← #532 issue body
  (rewritten 2026-07-06) + superseding companion-plan comment.
- Two-role split, latency reasoning, deny+hold fallback ← #533 §1, §8.
- Tool surface (`requestRoute`, `blockOccupancy`, `releaseRoute`) ← #533 §5.3.
- Conflict example *c* ← #533 §6c.
- 8 GB VRAM hard gate ← owner comment on #534.
- TRAIN agents never LLM ← #532 Non-goals (decided 2026-07-04); #537/#539.
- Pluggable planner (rule ↔ search ↔ LLM) ← SP3.6 (#574); LLM + fallback run ← SP2b.9 (#566).
- One-time topology load at agent start ← SP2b.8 (#695).
- Slow-speed-only agent runs ← owner comment on #532 (2026-07-06) referencing Goal 7 (#187).
- Runtime wiring (Koog + Koin + Ollama, `:dispatcher-agent` module) ← SP1 (#546–#551),
  Ollama executor SP1.5 (#550).
