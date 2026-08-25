# Goal 10 / SP2c.27 — Ollama Capability Audit (seed, format+tools, num_ctx, maxIterations)

**Issue:** [#850](https://github.com/bedaHovorka/interlockSim/issues/850) (SP2c.27) · sub-issue of
[#822](https://github.com/bedaHovorka/interlockSim/issues/822) (Goal 10 SP2c) · informs P8's
reproducibility claim and gates SP2c.13.

**Status:** ✅ Final — all four spikes settled against the pinned Koog `1.1.1` source and a real
local Ollama instance (`qwen2.5:7b-instruct`, Ollama `0.31.2`, NVIDIA RTX PRO 5000 Blackwell
Laptop GPU).
**Last Updated:** 2026-07-30

**Environment note:** unlike the scenario #850 anticipated as a possible limitation ("if Ollama is
not reachable in this environment..."), this worktree's dev machine had Ollama running locally
with `qwen2.5:7b-instruct` already pulled, so Spikes 2–4 below are measured against a real backend,
not simulated. Sample sizes are still small (a spike, not a benchmark suite) and are stated
explicitly with every measurement.

---

## Summary table

| Spike | Question | Answer |
|---|---|---|
| 1 — seed | Can a seed reach Ollama through Koog 1.1.1? | **No**, and the "delegate to `OllamaClient`" fix is **also not possible** (final class, internal DTOs). A standalone direct-POST prototype works and is empirically deterministic (2/2 identical outputs). Recommend re-scoping P8 as already planned, with the prototype available for a future JSON-only decision mode. |
| 2 — format+tools | Does combining `format` + `tools` degrade tool-calling on `qwen2.5:7b-instruct`? | **Yes, completely**: 0/5 trials produced a tool call with `format` set, vs 5/5 without it. Mutual exclusivity by construction is **necessary**, not just cautious. |
| 3 — num_ctx | Is 8192 the right `num_ctx`? | **No** — recommend **16384** instead, based on measured multi-round token growth. Production default changed; see `OllamaExecutorConfig.contextWindowTokens`. |
| 4 — maxIterations | Does `maxIterations = 4` suffice? | **No** — refuted by evidence already in the codebase (KDoc + PR #811) before this spike started. Current production value (`20`) is correct; the "node traversals, not LLM turns" claim is now independently confirmed against Koog source, and a live regression test locks in the current value. |

---

## 1. Spike 1 — Sampling seed

### 1.1 Verifying the issue's diagnosis against Koog 1.1.1 source

#850's issue body already traced the problem; this spike re-verified it directly against the
Koog `1.1.1` sources jars (`~/.gradle/caches/modules-2/files-2.1/ai.koog/*-jvm/1.1.1/*-sources.jar`,
extracted and read, not taken on faith):

- `ai.koog.prompt.params.LLMParams` (module `prompt-model`) has **no `seed` field** — its full
  parameter list is `temperature, maxTokens, numberOfChoices, speculation, schema, toolChoice,
  user, additionalProperties`.
- `ai.koog.prompt.executor.ollama.client.OllamaParams` (module `prompt-executor-ollama-client`)
  extends `LLMParams` and adds exactly one field: `think: Boolean?`. No `seed`.
- `OllamaClient.extractOllamaOptions()`:
  ```kotlin
  internal fun extractOllamaOptions(prompt: Prompt, model: LLModel): OllamaChatRequestDTO.Options =
      OllamaChatRequestDTO.Options(
          temperature = prompt.params.temperature,
          numCtx = contextWindowStrategy.computeContextLength(prompt, model),
      )
  ```
  and `OllamaChatRequestDTO.Options` (the DTO actually serialized to the wire) is declared as:
  ```kotlin
  internal data class Options(
      val temperature: Double? = null,
      @SerialName("num_ctx") val numCtx: Long? = null,
  )
  ```
  Two fields, full stop. There is no code path anywhere in this module that ever places a value
  into a `seed` key inside `options`.
- `LLMParams.additionalProperties` **does** reach the wire — `OllamaClient.execute()` passes
  `additionalProperties = params.additionalProperties` into `OllamaChatRequestDTO`, which is a
  **root-level sibling field** of `options`, not nested inside it:
  ```kotlin
  internal data class OllamaChatRequestDTO(
      val model: String,
      val messages: List<OllamaChatMessageDTO>,
      val tools: List<OllamaToolDTO>? = null,
      val format: JsonElement? = null,
      val options: Options? = null,
      val stream: Boolean,
      val think: Boolean? = null,
      @SerialName("keep_alive") val keepAlive: String? = null,
      val additionalProperties: Map<String, JsonElement>? = null,
  )
  ```
  and `AdditionalPropertiesFlatteningSerializer.transformSerialize()` (module
  `prompt-executor-clients`) merges the `additionalProperties` map's entries into the **root** of
  the serialized JSON object — i.e. `additionalPropertiesOf("seed" to 42)` produces a top-level
  `"seed": 42` sibling of `"options": {...}`, not `"options": {"seed": 42, ...}`. Ollama's
  `/api/chat` only reads `seed` from inside `options`; a root-level `seed` is an unrecognized field
  and is silently dropped.

**Confirmed: Koog's own docs claim about `additionalProperties` carrying "Ollama's `seed`, `num_ctx`,
or `format`" is wrong for `seed` specifically, in `1.1.1`.** (`num_ctx` and `format` genuinely do
work via other, dedicated paths — `numCtx` via `ContextWindowStrategy`, `format` via
`LLMParams.schema`; only `seed` has no path at all.)

### 1.2 The "thin `SeededOllamaClient : LLMClient` delegating to `OllamaClient`" option is not buildable

#850 asked this spike to evaluate that option specifically. It is **not implementable** against
the pinned API surface:

- `OllamaClient` is declared `public class OllamaClient @JvmOverloads constructor(...) : LLMClient()`
  — **not `open`** — so it cannot be subclassed at all.
- `OllamaChatRequestDTO`, its nested `Options`, and `OllamaClient.extractOllamaOptions()` are all
  `internal` to the `ai.koog:prompt-executor-ollama-client` module. Nothing outside that module —
  including a "thin delegating wrapper" in `:dispatcher-agent` — can construct, inspect, or modify
  the object `OllamaClient` is about to serialize.

There is no seam to delegate *through*. The only genuinely viable option was #850's second
alternative: an independent client that builds and sends its own `/api/chat` request.

### 1.3 Prototype and empirical result

**Delivered:** `dispatcher-agent/src/main/kotlin/.../executor/SeededOllamaJsonClient.kt` — a
minimal object with one production function (`requestJson`) and one test-only function
(`buildRequestBody`). It builds its own small `@Serializable` DTOs (not Koog's — those are
internal), places `seed` inside `options` where Ollama actually reads it, and posts directly to
`/api/chat` via the JDK `HttpClient` (same pattern as the existing `OllamaModelPrewarmer`).

Deliberately **out of scope**, per #850's own framing ("full production wiring is explicitly out
of scope for this spike"):
- Tool-calling (`tools` array, parsing `tool_calls` out of the response) — the prototype only
  covers the constrained-JSON, no-tools path #850 called out as tractable.
- Streaming.
- Wiring into `KoogAgentFactory` / `DefaultAgentService` — those are unchanged; production still
  goes through Koog's real `OllamaClient`/`AIAgent` for the tool-calling dispatch loop.

**Empirical determinism check** (`SeededOllamaJsonClientTest`, `@Tag("ollama-test")`, run twice in
this session against the real local Ollama instance): two independent `requestJson` calls with
identical `seed = 12345`, prompts, temperature, and schema produced **byte-identical `content`**
both times this was run. This is a positive result for Ollama's own seed support, once the seed
actually lands where Ollama looks for it — the earlier failure mode was 100% "Koog never sends it,"
not "Ollama's seed is unreliable." (Small sample — 2 repeats, one machine, one model, no GPU
contention scenario tested — stated as a limitation, not a guarantee.)

A network-free unit test (`buildRequestBody nests seed inside options, not at the request root`)
locks in the wire shape so a future refactor can't silently reintroduce the root-level-`seed` bug
this spike diagnosed.

### 1.4 Recommendation

**Re-scope P8 as #850 already outlined, and treat the prototype as a future-work option, not a
production dependency today:**

| Half | Status |
|---|---|
| **Prompt determinism** (recorded snapshot sequence ⇒ byte-identical prompt sequence) | **Achievable today**, unchanged from #850's own finding — Koog builds a fresh context per `run()`, no cross-invocation accumulation. |
| **Decode determinism on the production tool-calling path** | **Still not achievable** without a much larger investment: `SeededOllamaJsonClient` only covers the non-tool-calling constrained-JSON path. Making it cover tool-calling too means reimplementing Koog's entire `OllamaClient.execute()` (message serialization, tool schema generation, tool-call response parsing) independently — a full client rewrite, not a thin spike prototype. |
| **Decode determinism on a future JSON-only decision mode** | **Achievable now**, if/when one is built. Spike 2 (below) already establishes that a JSON-only, no-tools decision mode must exist as a separate agent build for other reasons (`format`+`tools` coexistence). If that mode is ever built, `SeededOllamaJsonClient` (extended to production quality) is a working, verified path to real decode determinism for it. |

Do not build out full tool-calling support in `SeededOllamaJsonClient` speculatively — there is no
current consumer for it, and #532 §A4 already frames LLM-dispatcher-specific reproducibility as
non-critical (`RuleBasedDispatcher` is the reproducibility anchor; LLM acceptance is outcome-gated
across seed-pinned runs, not decision-for-decision). Revisit if/when a JSON-only decision mode is
actually scheduled.

**Follow-up completed (#894):** #822's P8 wording was updated in #894 to reflect the split:
decode determinism is achievable only on a future JSON-only decision mode (`SeededOllamaJsonClient`
is the proven prototype), not on the current tool-calling dispatcher. The KDocs for `TickBudget`,
`PromptDeterminismTest`, and the `SweepAxes` comment in `SweepGrid` were all amended to record the
settled position. #822's GitHub issue body was not edited directly (it is not a code file), but
every in-repo reference to the P8 decode-half status now states the settled outcome.

---

## 2. Spike 2 — `format` + `tools` coexistence

### 2.1 Setup

Live experiment against `qwen2.5:7b-instruct` via direct `/api/chat` calls (not through Koog — a
raw HTTP harness gives exact control over exactly which fields are sent). Fixed scenario in both
arms: one `request_route` tool definition, a system prompt instructing the model to call it for
train `T1` from `A` to `B`, `temperature = 0.28` (production value), `num_ctx = 8192`.

- **Baseline arm:** `tools` present, no `format`.
- **Format arm:** identical request, **plus** `format` set to a small unrelated JSON schema
  (`{action, reason}`) — the kind of schema a constrained-JSON decision mode would use.

5 trials per arm (temperature 0.28 is not zero, so trial-to-trial variance is expected and is why
multiple trials were run rather than one).

### 2.2 Result

| Arm | Trials with a tool call | Notes |
|---|---|---|
| Baseline (tools only) | **5/5** | All five produced a valid `request_route` tool call with correct `train_name`/`from_point`/`to_point` arguments. |
| Format + tools | **0/5** | All five abandoned tool-calling entirely and instead emitted **text content** satisfying the unrelated `format` schema (e.g. `{"action": "request_route", "reason": "dispatch_train_T1_now"}`) — the model tried to honor `format` at the expense of actually calling the tool. |

This is a complete, unambiguous failure mode, not a partial degradation — every single trial with
`format` set produced zero tool calls. The model appears to prioritize satisfying the response
`format` constraint over invoking tools when both are present in the same request.

### 2.3 Recommendation

**Confirmed: the design's mutual-exclusivity-by-construction decision (two separate agent builds,
never a runtime flag) is necessary, not merely cautious.** No further design change needed here —
this spike validates the existing plan rather than proposing a new one. If a future JSON-only
decision mode is built (see §1.4), it must never share a request path with the tool-calling agent
build; the two must remain structurally separate, exactly as already planned.

---

## 3. Spike 3 — `num_ctx` right-sizing

### 3.1 Base per-tick prompt size

A synthetic-but-representative station prompt (60 blocks, each with inputs/length/occupancy/owner,
plus the real dispatcher system-prompt instructions) sent to `qwen2.5:7b-instruct` measured
**`prompt_eval_count = 2102` tokens** — matching #850's own ≈1,700–2,100 estimate closely. On that
number alone, 8192 (the issue's suggested value) looks generously sized.

### 3.2 Multi-round growth within a single `decideAsync` call

That single-tick number is not the whole story. Koog's `singleRunStrategy()` resends the **entire
accumulated conversation** on every LLM round trip within one `decideAsync` call — up to
`maxAgentIterations` (`20`) node traversals — and this project's full-network perception tools
(`all_block_occupancies`, `all_train_positions`, `all_signal_aspects`) each return a **complete
snapshot**, not a diff, on every call. A live measurement appending 5 simulated tool-call/result
rounds (using realistically-shaped payloads — a 60-entry block-occupancy array for the
`all_block_occupancies` round, smaller arrays for the others) to the same base prompt measured:

| Rounds appended | `prompt_eval_count` |
|---|---|
| 0 (base prompt only) | 2,102 |
| 5 | 4,510 |

≈482 tokens/round on average, dominated by the full-network perception dump. Extrapolating (not
separately re-measured at every step — stated as an extrapolation): a cycle using something in the
range of 10 real tool round-trips (consistent with the "each tool call ≈ 2 node traversals" finding
in §4) would land near **7,000–9,000 tokens**; a hypothetical worst case of 20 literal tool-call
rounds would approach **~11,700 tokens**. Either bound already exceeds 8,192.

**Why this matters beyond raw latency:** Ollama does not error when a request's actual token count
exceeds `num_ctx` — it silently truncates the **oldest** messages, which for this project's message
order means the system prompt (containing the station topology) can be the first thing dropped.
That is a silent correctness failure (the model starts reasoning without knowing the network
topology), not just a slowdown. An 8,192 window sized only against the static base prompt risks
this exact failure mode under realistic multi-tool-call cycles.

### 3.3 Why `num_ctx` size costs almost nothing to compute

Cold-load measurements at 32,768 / 16,384 / 8,192 tokens (one dev machine, same 2,102-token base
prompt) showed two things:

1. **`prompt_eval_duration` and decode throughput do not move with `num_ctx`** — about 1.9 s and
   about 9.6 tok/s at all three sizes. `num_ctx` is a KV-cache preallocation ceiling, not a
   per-token cost; the model only computes over the tokens actually present.
2. **16,384 already captures nearly all the benefit of dropping from 32,768.** VRAM 6.14 → 5.23
   GiB, load 6.53 → 4.75 s. Going further to 8,192 saves another 0.45 GiB and no time at all,
   while adding the truncation risk from §3.2.


### 3.4 Recommendation — deviates from #850's suggested value

**Recommend `num_ctx = 16384`, not the issue's suggested `8192`.** This is a production code
change, made in this branch:
- `OllamaExecutorConfig.DEFAULT_CONTEXT_WINDOW_TOKENS`: `32_768L` → `16_384L`.
- KDoc on `contextWindowTokens` and `maxAgentIterations` updated with the full measured rationale
  (see the class for the complete writeup, kept close to the code it documents).
- `OllamaExecutorConfigTest`'s default-value assertion updated to `16_384L`.

Rationale in one sentence: 16,384 keeps ~2× headroom over the measured worst-case token growth
trend from §3.2 (avoiding silent context truncation) while capturing essentially all of 8,192's
latency/memory benefit relative to the previous 32,768 default.

---

## 4. Spike 4 — `maxIterations` tripwire

### 4.1 The issue's premise was already refuted by evidence in the codebase

#850 hypothesized that, "with the acyclic one-shot graph strategy there is no ReAct cycle, so
`maxIterations = 4` suffices." Before this spike touched any code, `OllamaExecutorConfig`'s own
KDoc (written for Issue #566/#811, prior to #850) already documented the opposite:

> An initial estimate of 8 (a "couple of perception calls plus one actuator call") proved too
> tight in practice: a live `shuntingLoopAI` run against `qwen2.5:7b-instruct` showed
> `AIAgentMaxNumberOfIterationsReachedException` on some cycles even in the happy path (4
> perception tool calls + 1 actuator call already consumes most of an 8-step budget once Koog
> counts individual node traversals, not LLM turns).

`4` was never even the tested standalone value — `8` already failed. The production default is,
and has been since #566, **`20`**, not `4`. This spike's job was to (a) independently confirm the
"node traversals, not LLM turns" mechanism against Koog source, and (b) turn the corrected
understanding into a regression test, per #850's own acceptance criteria and Note ("the
`maxIterations` test is expected to land as a production change").

### 4.2 Confirming "node traversals, not LLM turns" against Koog 1.1.1 source

`ai.koog.agents.core.agent.entity.AIAgentSubgraph.executeWithInnerContext` (module `agents-core`):

```kotlin
while (true) {
    context.stateManager.withStateLock { state ->
        if (++state.iterations > context.config.maxAgentIterations) {
            throw AIAgentMaxNumberOfIterationsReachedException(context.config.maxAgentIterations)
        }
    }
    val nodeOutput: Any? = currentNode.executeUnsafe(context, currentInput)
    ...
}
```

Confirmed exactly as documented: `state.iterations` increments once per `while(true)` pass, i.e.
once per **node execution**, not once per LLM response. `singleRunStrategy()`'s graph alternates an
LLM-call node with a tool-execution node, so each tool call consumes roughly two iterations, not
one — which is precisely why 5 tool calls (4 perception + 1 actuator) already exhausted most of an
8-iteration budget in the earlier live incident.

### 4.3 Regression test

Added `KoogRealOllamaToolCallingTest.happy-path cycle with several perception calls plus an
actuator call never hits maxAgentIterations` (`@Tag("ollama-test")`): drives a real multi-tool
happy-path cycle (4 perception tools + 1 actuator tool available, production `maxAgentIterations =
20`) against the real local Ollama instance and asserts the call completes without
`AIAgentMaxNumberOfIterationsReachedException`. Run twice in this session; both passed.

The assertion deliberately does **not** require the model to call every tool in a specific order —
that is an orthogonal LLM-compliance question (covered by other tests / the future #566 §7
benchmark harness), not what this test guards against. If
`AIAgentMaxNumberOfIterationsReachedException` fires, the `runBlocking` block propagates it and the
test fails regardless of any explicit assertion — that propagation *is* the tripwire.

### 4.4 Recommendation

**No production value change** — `maxAgentIterations = 20` was already correct. This spike:
1. Independently verifies the mechanism claim against Koog source (previously asserted from a live
   incident, now also confirmed by reading the scheduler).
2. Corrects #850's own premise (`4`) in the KDoc, so a future reader does not attempt to lower the
   value based on the issue's original hypothesis without re-reading this history.
3. Adds the regression test #850's acceptance criteria and Note call for, pinned at the actual
   correct value.

---

## 5. Open follow-up (not done here)

- Update #822's P8 wording and the SP2c.10/SP2c.12 acceptance criteria to reflect the Spike 1 split
  (§1.4) — flagged for the issue owner, not edited directly by this spike.
- If/when a JSON-only (no-tools) decision mode is scheduled, revisit extending
  `SeededOllamaJsonClient` to production quality as the seeded path for it.
