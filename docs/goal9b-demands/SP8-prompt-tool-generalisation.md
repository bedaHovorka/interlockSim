# SP8: prompt and tool generalisation (Goal 1B, Issue #1155)

Goal 9B demands recorded while removing the vyhybna literals from the LLM dispatcher's system prompt
and tool descriptions (acceptance B6). Inputs read first: `SP2-station-abstraction.md` (demand D1,
capacity in the agent stack), `SP3-cerveny-ujezd-scenario.md` (demands D5 and D6), the #992
interface-versus-capacity audit (posted on #992 before the first prompt change), and
`GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md`.

## Prompt facts that needed per-station data

| Fact | Before | Source now |
|---|---|---|
| Admission cap in the system prompt ("fewer than N trains are currently active", both variants) | `RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS` (2) on every station | The running loop's cap (`ShuntingLoop.stationCapacity`, scenario override included), declared by the wiring layer as `StationCapacity`; without a loop, `Station.capacity()` |
| Admission cap in every cycle message ("Active (approved) trains right now: n / N") | The same constant | The same value, handed to the agent through `AgentService.createDispatchAgent` |
| Admission cap the live applier enforces | `DispatchDecisionApplier`'s default (2) | `ExampleRegistry.wireDispatcherAgent` passes `loop.stationCapacity` |
| Block-ID warning in STATION TOPOLOGY (`"kA"` vs InOut `"A"`) | vyhybna literal | `StationTopologySerializer.confusableBlockExample`: the station's own `k` + InOut block, e.g. `"kN-Bypass"` vs `"N-Bypass"` on Praha |
| Block-ID example in both `request_route` endpoint descriptions (`such as "kA"`) | vyhybna literal | The same rendered block |
| "Do NOT pass X as an endpoint" in the worked example | First block that is not one of two InOuts | First block that is not an endpoint name. On Červený Újezd a block bounded by an unnamed switch takes its signal's name (`e30`), so the old rule called a valid Signal a Block ID |
| `approve_train` description ("…into the active ShuntingLoop simulation") | Class name of the vyhybna loop | "…into the active simulation" |
| Route list size | Every route, up to 16 per ordered InOut pair | Every route while the section fits `MAX_ROUTE_SECTION_TOKENS` (3,000 estimated tokens); above it one route per pair first, then a note with the number left out |

The frozen BASELINE text and vyhybna's whole assembled REVISED system prompt are byte-identical to
before (SHA-256 pinned by `StationPromptGeneralisationTest`). On vyhybna only the `approve_train`
description changed (the class name is gone).

## Prompt size

Measured with the real `Qwen/Qwen2.5-7B-Instruct` tokenizer on the rendered REVISED system prompt
(instructions + STATION TOPOLOGY), Station block names applied as a run applies them:

| Station | Before | After | Tool descriptions |
|---|---|---|---|
| vyhybna | 1,173 | 1,173 | 730 |
| Červený Újezd | 2,605 | 2,610 | 736 |
| Praha hlavní nádraží | **31,291** | 5,581 | 734 |

The context window the dispatcher requests is 16,384 tokens (`OllamaExecutorConfig.contextWindowTokens`),
and Ollama trims an over-long prompt instead of rejecting it, so the unbounded Praha prompt could
never have reached the model whole. The route list alone was 28,733 tokens. The test budget is 6,144
estimated tokens for the Praha system prompt (three eighths of the window).

`PromptTokenEstimate` counts the pieces the Qwen2.5 pre-tokenizer splits a text into (letter runs,
single digits, single punctuation characters); it is within 3 % of the real tokenizer on the
unbounded prompts and within 6 % on the bounded Praha prompt. A characters-per-token ratio does not
work: it ranges from 1.5 (Praha's ids such as `SW10-S_8_8`) to 3.9 (prose).

## What an LLM dispatcher needs for a 50-switch station

- **No static enumeration of routes.** Praha has 216 routes between its 11 InOuts, at up to 16 per
  pair. Any list the model has to read every cycle grows with the station and pays its full cost on
  every cycle. The per-train NEXT SECTION line (from `CandidateTarget`, SP5) is the scalable form:
  one line per active train, whatever the station size.
- **A stated token budget per prompt part.** System prompt, tool schemas, cycle message, history
  (`historyN` multiplies the cycle message) and the reply share one window. Today only the system
  prompt has a budget; the cycle message grows with the number of active trains (capacity 9 on
  Praha) and has none.
- **A name for every element the prompt mentions.** Červený Újezd's 12 switches have no names, so its
  prompt says `Switches: none`; blocks between two unnamed switches all render as `unknown`; a block
  bounded by one named signal takes that signal's name and appears in both the Signals and the
  Blocks list. #1273 named the signals; switches and blocks need the same (SP3 demand D6).
- **Worked examples taken from the station's structure, not its alphabet.** The examples pick
  `InOuts[0]` and `signals[0]`/`signals[1]` alphabetically: on Praha they suggest `N-Bypass -> S11`
  and `S11 -> S12`, which are not adjacent. A real first section starts at the train's home signal
  (`Station.entryExitBlocks[].signals`), and NEXT SECTION never offers an InOut origin at all.
  Changing the examples changes vyhybna's prompt, so it needs a measured A/B arm (follow-up).
- **Capacity per line and per train** (SP2 D3, SP3 D4): the prompt states one number per station.
- **A legality gate for "acted but skipped a train".** A cycle that emits anything (one `no_op` is
  enough) skips the rule-based fallback, so a train whose NEXT SECTION was offered and ignored waits
  until a later cycle requests it. On a busy station the chance that some train is skipped in a
  cycle grows with the number of trains (#992 audit, finding F8).
- **The NEXT SECTION source for stations `ShuntingLoop` rejects.** Praha has no loop that publishes
  block inputs today (`ShuntingLoop.compatibilityIssues`), so its cycle message would carry no NEXT
  SECTION line. SP10 (#1157) wires Praha on `Station`.

## LLM pass rate

Recorded, never gated (B6): see the measurement comment on #1155.
