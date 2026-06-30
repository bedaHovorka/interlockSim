# Goal 10 / SP3.1 — LLM Model Evaluation for Agent Roles

**Issue:** [#534](https://github.com/bedaHovorka/interlockSim/issues/534) (SP3.1) ·
sub-issue of [#533](https://github.com/bedaHovorka/interlockSim/issues/533) (SP3) ·
subproject of [#532](https://github.com/bedaHovorka/interlockSim/issues/532) (Goal 10)

**Status:** 🟡 Design proposal — *comparison of variants only, no implementation*
**Last Updated:** 2026-06-30
**Authoring scope (per issue instructions):** "read all goal 10 task tree, and suggest / compare
variants to .md files, not implement now."

**Hard constraint (owner comment on #534):** **max. VRAM = 8 GB.** Every recommended model
must run inside an 8 GB GPU *alongside* the JVM + kDisco simulation. Models whose default
Ollama quantisation exceeds this budget are flagged and only kept as stretch candidates.

> ⚠️ **No measured benchmarks yet.** This document ranks candidates from public model cards,
> Ollama metadata, and the SP3 tool/latency design in #533. The latency and tool-call success
> numbers required by #534 (p50/p95, success rate, context tokens, Czech vocabulary quality)
> must be produced by the scripted harness described in
> [§7 Benchmark protocol](#7-benchmark-protocol-to-execute-in-sp35--sp4) once the SP3.4/SP3.5
> tool surface (`requestRoute`, `blockOccupancy`, …) exists. Treat the rankings here as
> *priors* that tell the benchmark which models to try first.

---

## 1. Context: the two agent roles

From the SP3 architecture (#533, §1 ESA 11 → InterlockSim mapping) there are exactly two
LLM-eligible roles. The interlocking kernel — never the model — owns safety, so the LLM only
produces *intent* and every actuator tool is a thin RPC that the kernel independently
re-validates.

| Role | SP | Style | Trigger | Latency budget | LLM needed? |
|---|---|---|---|---|---|
| **DISPATCHER** | SP2b (#538) | Deliberative | On events (new `RouteRequest`, conflict) — **not every tick** | ≤ 2 s per decision | **Yes** (primary target) |
| **TRAIN** | SP2a (#537) | Reactive | Control-loop hot path | ≤ 50 ms per decision | **No (recommended)** — stay algorithmic |

### 1.1 Why latency is forgiving for the DISPATCHER

kDisco is **discrete-event**: the simulation clock does not advance while the dispatcher LLM is
thinking (#533, §8). A 1–2 s inference therefore cannot create a real-time safety violation —
it only slows wall-clock throughput. Combined with "call the LLM only on events, cache/debounce
identical decisions, and cap `maxIterations`", this means the DISPATCHER can afford a mid-sized
(7B–12B) model even on modest hardware.

### 1.2 Why the TRAIN agent should stay algorithmic

A < 50 ms hot-path budget is below the cold-start + per-token latency of any locally hosted
LLM at useful quality. The SP3 design explicitly says "keep TRAIN agents LLM-free initially"
and promote later only for timetable-aware coasting/energy optimisation. **Recommendation:
implement the TRAIN agent behind the same `Planner` interface as a deterministic rule loop
(SP3.6, #574); do not bind an Ollama model to it for v0.** If an LLM path is ever taken, only
the smallest tier (§3, Tier R) is viable, and only with aggressive caching.

---

## 2. Evaluation criteria & weights

Reproduced from #534 with the 8 GB VRAM cap promoted to a hard gate.

| Criterion | Weight | Pass condition |
|---|---|---|
| Tool-calling reliability | **Critical** | Reliably emits valid JSON for `requestRoute` / `blockOccupancy` / `releaseRoute`; malformed calls are caught by Koog structured-output validation |
| Multi-step reasoning | High | Chains sense → decide → act across several tool turns (worked example *c*, #533 §6) |
| Context window | High | Holds network state (block list, pending requests) — estimate **4–8 k tokens** for a medium station |
| Latency on dev HW | High | DISPATCHER ≤ 2 s; TRAIN (if LLM) ≤ 50 ms, measured on Intel Arrow Lake + NVIDIA GPU |
| Czech / railway vocabulary | Medium | Understands Czech signal terms (`Stůj`, `Volno`, `Výstraha`, `posun`, `úsek obsazen`) without fine-tuning |
| Model size / VRAM | **Gate** | **≤ 8 GB VRAM** including KV-cache headroom alongside JVM + kDisco |
| Fallback compatibility | Medium | On stall / invalid call, does not loop excessively → deterministic "deny + hold" fallback engages cleanly |

---

## 3. Candidate models (Ollama, ≤ 8 GB tiers)

The candidate table in #534 had blank model names; below is the proposed concrete shortlist that
fills those rows with real Ollama tags and maps each to a Koog `OllamaModels.*` constant where
one is known to exist. Sizes are the **default Q4 download size**; VRAM at runtime is roughly
*download size + KV-cache* (KV-cache grows with context length, ≈ 0.5–1.5 GB at 8 k tokens).

> **Koog mapping caveat (#533 §8 / SP1 #536):** Koog 1.0 ships a curated `OllamaModels` set
> (`OllamaModels.Meta.*`, `OllamaModels.Alibaba.*`, …). Constants below marked *"verify"* must be
> confirmed against the resolved `ai.koog:koog-agents:1.0.0` artifact during SP1; any model not
> pre-listed can still be used by constructing an `LLModel`/`OllamaModelCard` with the Ollama tag
> directly. **This doc proposes mappings; it does not edit the Koin module (that is SP1/#536).**

### Tier D — DISPATCHER candidates (deliberative, fits 8 GB)

| # | Ollama tag | ~Size (Q4) | Issue row | Tool calling | Proposed Koog constant | Notes |
|---|---|---|---|---|---|---|
| D1 | `qwen2.5:7b-instruct` | ~4.7 GB | ~5 GB "solid baseline" | Native | `OllamaModels.Alibaba.QWEN_2_5_*` *(verify)* | Strong tool-calling + multilingual; best size/quality trade-off in budget |
| D2 | `llama3.1:8b` | ~4.9 GB | ~4 GB "widely tested" | Native | `OllamaModels.Meta.LLAMA_3_1_*` *(verify)* | Mature, well-documented function calling; good multi-step reasoning |
| D3 | `mistral-nemo:12b` | ~7.1 GB | ~7 GB "stronger tool calling than base Mistral" | Native | construct from tag *(verify)* | 128k context; near the VRAM ceiling — leaves little KV headroom |
| D4 | `command-r7b` | ~5.0 GB | ~5 GB "strong function-calling scores" | Native (tuned for RAG/tools) | construct from tag *(verify)* | Cohere tool-use tuning; good JSON discipline |
| D5 | `gemma3:4b` | ~3.3 GB | ~3 GB "Gemma 3, fast" | Gemma-3 tool calling | `OllamaModels.Google.GEMMA_3_*` *(verify)* | Fast; referenced in SP3 notes; weaker multi-step than 7B+ |

### Tier R — Reactive / fast candidates (only if TRAIN ever goes LLM)

| # | Ollama tag | ~Size (Q4) | Issue row | Tool calling | Proposed Koog constant | Notes |
|---|---|---|---|---|---|---|
| R1 | `llama3.2:3b` | ~2.0 GB | ~2 GB "very fast, already in Koog Meta" | Native | `OllamaModels.Meta.LLAMA_3_2` | Already in Koog; used in the #533 wiring sketch; still unlikely to hit 50 ms |
| R2 | `gemma3:4b` | ~3.3 GB | (shared with D5) | Gemma-3 | `OllamaModels.Google.GEMMA_3_*` *(verify)* | Fast deliberative fallback; borderline for hot path |

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
| R1 llama3.2:3b | ○ | △ | ○ | ◎ | △ | ◎ (~2.0 GB) | △ (reactive only) |
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

### 5.2 TRAIN agent — **no LLM for v0**

Keep TRAIN reactive and **algorithmic** behind the SP3.6 `Planner` interface (#574). The 50 ms
hot-path budget is not realistically achievable with a local LLM at useful quality. Revisit only
when timetable-aware coasting/energy optimisation is in scope; if so, start at `llama3.2:3b` (R1)
with per-state caching and a hard `maxIterations = 1`.

### 5.3 Proposed Koog / Koin binding (for SP1 #536 — *not implemented here*)

This is a **proposal** for the future `:agent` Koin module; SP3.1 does not edit code.

```kotlin
// PROPOSED — to be wired in SP1 (#536), not in this SP3.1 doc task.
// Default DISPATCHER model; overridable via config/env for benchmarking.
val dispatcherLlmModel = OllamaModels.Alibaba.QWEN_2_5   // verify constant name in koog 1.0
// Fallback: OllamaModels.Meta.LLAMA_3_1 (llama3.1:8b)
// Fast/degraded: OllamaModels.Google.GEMMA_3 (gemma3:4b)
```

Make the model id a **single injectable config value** (e.g. Koin `named("dispatcher.model")`) so
the §7 benchmark can sweep all candidates without code edits, and so the 8 GB gate can be enforced
by configuration rather than recompilation.

---

## 6. Fallback strategy (deterministic "deny + hold")

Aligned with #533 ("keep a deterministic rule-based fallback behind the same agent interface")
and SP3.6 (#574, pluggable planner). The model is *never* in the safety path; these are
liveness/robustness guards:

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

## 7. Benchmark protocol (to execute in SP3.5 / SP4)

The numeric acceptance evidence required by #534 must be produced once the SP3.4 `InterlockingFacade`
(#572) and SP3.5 ToolSets (#573) exist. For **each** Tier D candidate (and R1 for the reactive
experiment), run the four scripted scenarios from #534 against the real tool surface and record
the metrics.

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
| Fallback events | count of iteration-cap / loop-guard / deny-hold activations |

Promote whichever candidate maximises tool-call success and Czech quality **within** the 2 s and
8 GB gates; update §5 and the SP1 Koin binding accordingly.

---

## 8. Decisions, open questions & traceability

**Proposed decisions (pending §7 measurement):**
- **D-1:** DISPATCHER primary = `qwen2.5:7b-instruct`; fallback model = `llama3.1:8b`;
  fast/degraded = `gemma3:4b`.
- **D-2:** TRAIN agent stays algorithmic for v0 (no Ollama binding).
- **D-3:** Model id is an injectable config value to enable benchmark sweeps and enforce the 8 GB gate.
- **D-4:** `qwen2.5:14b` is **out** (default Q4 ≈ 9 GB > 8 GB gate); kept only as a quality reference.

**Open questions for SP1/SP3.4:**
- Exact Koog 1.0 `OllamaModels` constant names (verify against resolved artifact).
- KV-cache size at 8 k context per candidate (confirms real VRAM headroom under the gate).
- Whether `command-r7b`'s tool-tuning beats Qwen's multilingual edge on the Czech rationale task.

**Traceability:**
- Two-role split, latency reasoning, deny+hold fallback ← #533 §1, §8.
- Tool surface (`requestRoute`, `blockOccupancy`, `releaseRoute`) ← #533 §5.3.
- Conflict example *c* ← #533 §6c.
- 8 GB VRAM hard gate ← owner comment on #534.
- Pluggable planner (rule ↔ search ↔ LLM) ← SP3.6 (#574).
- Runtime wiring (Koog + Koin + Ollama, `:agent` module) ← SP1 (#536).
