# Goal 10 SP2c Wave 2 — actionable-rate metric redesign (#927) + throttle double-count fix (#926)

**Branch/worktree:** `goal10-wave2-metric-throttle` (`.worktrees/goal10-wave2-metric-throttle`), based on `origin/goal-10` @ `d7c97015`.

## Context

The 2026-08-15 "ollamaSuccess 88%→50%" investigation concluded **no code regression** — the
number is a measurement artifact from two independent defects, filed as #926 and #927
(both P1). Per the owning traffic-simulation-expert's ruling recorded in both issue bodies,
**these two must land in the same milestone, in this order, closing with a re-baseline
`aiSweep` run** — #926 roughly doubles-to-quadruples measured cycle counts, and #927 changes
what the rate even counts, so the existing A4 thresholds (and #925's `>0.80` gate) are
meaningless across that boundary if landed separately or partially.

Full issue text (acceptance criteria, evidence, proposed fixes) is in GitHub #926, #927, #822
(§7), #925 — read via `gh issue view <n> --repo bedaHovorka/interlockSim`. Do not re-derive
what is already stated there.

## Global Constraints

- Task order is fixed: **Task 1 (#927) → Task 2 (#926) → Task 3 (re-baseline + docs)**. Task 2
  must never land without Task 1 already merged into this branch (per both issues' explicit
  "never land #926 alone" instruction) — that is the reason for the ordering, not a preference.
- `sim/`-adjacent code: `AgentLoopDriver` and `KoogAgentPlanAdapter` live in `dispatcher-agent`,
  not `:core`, so the Koin-injection-allowed / conservative-`sim/` rules from CLAUDE.md do not
  bind here, but the general "tests mandatory, no unsolicited refactors" rule does.
- `:core` stays untouched (SP2c.0 / C10 from #822) unless a task explicitly says otherwise —
  none do.
- English-only logs/comments/strings (CLAUDE.md language rule).
- Every new/changed enum branch, field, or gate predicate needs a test that would fail without
  the change — this is a TDD execution (red test first, watched fail, minimal code, green).
- Full gate (`./gradlew build detekt ktlintCheck test integrationTest`) before any push —
  `feedback_push_integration_test.md`. Never push without an explicit in-turn confirmation
  from the user afterward (`feedback_no_push_without_asking.md`) — this plan does not push;
  it stops at "ready to review" and reports back.
- Never merge automatically (`feedback_no_auto_merge.md`).

## Task 1 — #927: actionable-rate metric redesign

**File:** `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/TickOutcome.kt`

1. Add a new enum value `LLM_SILENT_NONACTIONABLE` to `TickOutcome`, documented per the
   existing table-row convention (meaning, `tickClass`, "counts as LLM success" = no, and a
   new "counts toward actionable-rate denominator" = no). Class-level KDoc table needs a new
   row.
2. `tickClass`: map `LLM_SILENT_NONACTIONABLE` to a class that is **not** `SUCCESS` and
   **not** `RUN_FAILURE`, since it is neither a genuine dispatch success nor a fallback
   dispatching event — extend `TickClass` with `NONACTIONABLE` if no existing bucket fits (do
   not force it into `DEGRADED`, which currently means "the harness had to intervene with a
   no-dispatching-action outcome"; a silent-but-correct tick is not degraded — check whether
   `TickClass` needs a 4th member or whether `DEGRADED` is a legitimate fit at your
   discretion, but explain the choice in the KDoc).
3. `toActionAuthor`: `LLM_SILENT_NONACTIONABLE` maps to `ActionAuthor.LLM` (a silent, correct,
   nothing-to-do tick is still an LLM cycle, not a fallback dispatch — no decisions were
   posted either way so the author tag only affects attribution bookkeeping, not safety).
4. Add a new extension property `TickOutcome.countsTowardActionableRate: Boolean` (or
   equivalent name — keep it consistent with `countsAsLlmSuccess`'s style) that is `true` for
   every outcome except `LLM_SILENT_NONACTIONABLE`.

**File:** `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/KoogAgentPlanAdapter.kt`

5. In the `EMPTY_NO_TOOLS` branch (currently lines ~333-345, the `else` after
   `isIdleStation`), still call `fallbackDispatcher.decide(observation)` (needed either way to
   get real decisions or discover there are none), but branch the **reported** `TickOutcome`
   on whether the returned decision list is empty:
   - `decide()` returns `> 0` decisions → report `TickOutcome.RULE_FALLBACK` (unchanged
     behaviour — a genuine miss, fallback actually dispatches).
   - `decide()` returns `0` decisions → report `TickOutcome.LLM_SILENT_NONACTIONABLE` instead
     of `RULE_FALLBACK`.
   `runFallback()` (lines ~382-392) currently hardcodes `TickOutcome.RULE_FALLBACK` — refactor
   so the caller (or `runFallback` itself, inspecting its own return value before reporting)
   picks the right outcome for this one call site. The other two `runFallback` call sites
   (`TIMEOUT`, `EXCEPTION`) are unaffected — read the issue text again before changing them;
   #927 only targets `EMPTY_NO_TOOLS`.
6. Preserve every existing log message's meaning; update log text only if it now says
   something factually wrong (e.g. "applying rule-based fallback" when nothing was applied) —
   keep messages in English per CLAUDE.md.

**File:** `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/DispatcherRunSnapshot.kt`

7. Bump `CURRENT_SCHEMA_VERSION` from `5` to `6` (line 187). Add whatever new snapshot
   field(s) are needed to carry the actionable-rate (follow the existing `llmSuccessRate`
   property's pattern at line 136 — likely a new `actionableTickRate: Double` property with
   the same "fraction, 0.0 when totalTicks=0" contract). Read the file's own KDoc
   (lines ~25-58) on what incrementing `schemaVersion` requires and follow the documented
   default-value/back-compat convention for old snapshot files.

**File:** `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/DefaultDispatcherRunRecorder.kt`

8. Compute the new `actionableTickRate` alongside the existing `llmSuccessRate` computation
   (~line 247): numerator = ticks where `countsAsLlmSuccess`, denominator = ticks where
   `countsTowardActionableRate`. Update the per-run log line (~line 200) to also print the new
   rate. Follow the `#834 comparability-warning precedent` already cited at
   `MeasuringPlanAdapter.kt:185-191` for how a metric-semantics change should warn when reading
   pre-bump snapshots — apply the same style of warning here for schema version < 6.

**File:** `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/planner/RunReportAggregator.kt`

9. `runPassed()` (lines 201-202) currently checks `completedNaturally && !terminalFallbackEngaged
   && c7Clean` — no rate check. Per #927's "gate must be actionable-rate AND railway outcome",
   add an actionable-rate threshold check here (or at the `ArmReport`/`aggregate()` level,
   whichever matches how `MIN_RUN_COUNT`/`MIN_PASSING_RUNS` at lines 56/59 are structured —
   read that code before deciding where the threshold check belongs). Do **not** invent a
   specific numeric threshold yourself — Task 3 sets the real number after the re-baseline
   sweep; for Task 1, wire the mechanism using a named constant (e.g.
   `MIN_ACTIONABLE_RATE`) with a clearly-provisional placeholder value and a KDoc comment
   saying it is provisional pending the Task 3 re-baseline. Add the new rate to the rendered
   report table (`appendArmComparison`, ~line 212-218, and the per-run table, ~line 255-264).

**Tests (TDD — write first, watch fail):**
- `TickOutcome`: a test asserting `LLM_SILENT_NONACTIONABLE.countsAsLlmSuccess == false`,
  `.countsTowardActionableRate == false`, `.toActionAuthor == ActionAuthor.LLM`, and its
  `tickClass`.
- `KoogAgentPlanAdapterTest` (find the existing test file for this class): a case where the
  LLM emits nothing on a non-idle station AND the fallback dispatcher's `decide()` returns an
  empty list → asserts the reported outcome is `LLM_SILENT_NONACTIONABLE`, not
  `RULE_FALLBACK`. A second case where `decide()` returns ≥1 decision → asserts
  `RULE_FALLBACK` is still reported (regression guard for the unchanged path).
- `DefaultDispatcherRunRecorderTest`: a run snapshot with a mix of outcomes including
  `LLM_SILENT_NONACTIONABLE` ticks → asserts `actionableTickRate` excludes them from both
  numerator and denominator, and `llmSuccessRate`'s existing contract is unchanged (still
  counts every tick in its denominator per its own docstring at line 79 — confirm this against
  the issue text: #927 does not ask to change `llmSuccessRate` itself, only to introduce the
  new rate and gate on it; if the issue text says otherwise, follow the issue text and update
  this plan note in your task report).
- `DispatcherRunSnapshotTest`: schema version 6 round-trips; a version-5 fixture still loads
  with `actionableTickRate` defaulted sanely (mirrors whatever back-compat pattern the file's
  KDoc documents for prior bumps).
- `RunReportAggregatorTest`: `runPassed()`/gate behavior with a snapshot below vs. at/above
  the placeholder actionable-rate threshold, combined with railway-outcome fields, to prove
  the "AND" gate composition (not just railway outcome, not just rate alone).

## Task 2 — #926: throttle double-count fix

**Depends on Task 1 being complete in this branch/worktree.**

**File:** `dispatcher-agent/src/main/kotlin/cz/vutbr/fit/interlockSim/dispatcher/AgentLoopDriver.kt`

1. In `runCycle()` (~lines 295-346), measure wall-clock time elapsed specifically around
   `planner.plan()` (line 313) — the call that can block on LLM inference. Use a
   `TimeSource.Monotonic` mark (consistent with `cycleStart`/`elapsedNow()` usage already
   present elsewhere in the adapter, e.g. `KoogAgentPlanAdapter.kt`'s `cycleStart` pattern —
   check `AgentLoopDriver.kt` for whatever timing utility it already imports before adding a
   new one).
2. At the PACE step (~lines 338-341), instead of `controller.throttle(simDelta)` directly,
   compute the adjusted delta so the wall time already spent inside `planner.plan()` is
   subtracted before throttling, per the approved design: convert wall-elapsed to sim-time
   units using the controller's active speed multiplier, then
   `controller.throttle(max(0.0, simDelta - wallElapsedInSimUnits))`.
3. `SimulationController` (`core/.../context/SimulationController.kt`) currently exposes no
   getter for the active speed multiplier — `AgentLoopDriver` only holds a `controller:
   SimulationController` reference. Add a minimal read-only accessor to the interface (e.g.
   `fun currentSpeedMultiplier(): Double`), implement it in every concrete
   `SimulationController` (`SimulationRunner.kt`, `ThrottlingSimulationController.kt`,
   `DelegatingSimulationController.kt`, `NoOpSimulationController` if present, and any
   `SimulationController` in `SimulationController.kt` itself around line 176) and every test
   fake that implements the interface (`FakeSimulationController.kt`,
   `PausedClockSpikeHarness.kt`, `AgentLoopDriverTest.kt`'s inline fakes,
   `DelegatingSimulationControllerTest.kt`, `DispatchTickLoopTest.kt`,
   `DispatchTickLoopStabilityHeavyTest.kt`, `TickBudgetTest.kt`,
   `TimingRegimesOllamaTest.kt`, `PromptDeterminismTest.kt`,
   `CollisionDetectionActivationTest.kt`, `ControlledLoopOverheadBenchmark.kt`,
   `DefaultSimulationContextControllerTest.kt`'s fake if any). This is a mechanical,
   same-shape addition across many files — batch it as one dispatch rather than one subagent
   per file (see `subagent-driven-development`'s batching guidance).
   - This is a **judgment call beyond what the brainstorming session settled** (the user chose
     "convert in `AgentLoopDriver`, don't change `throttle()`'s signature" but that choice
     structurally requires exposing the multiplier somehow — `throttle()`'s signature stays
     unchanged, satisfying the letter of that decision; the interface still gains one new
     read-only method). Record this as a ruling in the SDD ledger when you reach it.
4. `NoOpSimulationController` and any implementation with no real notion of a multiplier
   should return `1.0`.
5. `awaitMinimumCyclePeriod()` (referenced at line 342, defined below `runCycle`) is a
   *separate* minimum-period floor — do not fold it into this fix; it is unrelated to the
   double-count defect per the issue text.

**Tests (TDD — write first, watch fail):**
- `AgentLoopDriverTest`: `throttleCalledOncePerCycle()` (existing, line ~446) — extend or add
  a sibling test where a fake `planner.plan()` sleeps a known wall-clock duration inside a
  cycle whose `simDelta` is known, and asserts `controller.throttle()` was called with
  `simDelta - (wallElapsed * multiplier)`, clamped at `0.0` when the wall time spent exceeds
  the sim-time budget (i.e. inference took longer than simDelta/multiplier — must not throttle
  a negative duration).
- A test asserting the previous (buggy) behavior — full `simDelta` passed regardless of wall
  time spent — now fails against the new code (this is the red test that proves the fix; keep
  it or replace it with the corrected assertion, whichever the TDD cycle naturally produces).
- `SimulationController` fakes: a compile-time check is implicit (every implementer must add
  the new method to build), but add one direct unit test per real (non-fake) implementation of
  `currentSpeedMultiplier()` — `SimulationRunner`, `ThrottlingSimulationController`,
  `DelegatingSimulationController` — asserting it reflects the configured/delegated multiplier.

## Task 3 — Re-baseline sweep + downstream doc updates (the milestone's closing gate)

**Depends on Task 1 AND Task 2 both complete and merged into this branch.**

This task is **manual-only measurement + documentation**, not a code-review-gated
implementation task — no TDD applies to steps 1-2, but step 3's threshold constant change (the
placeholder from Task 1 step 9) does need its own passing test update.

1. Build the shadow jar and run `aiSweep` fresh against
   `docs/measurement/sp2c24-sweep-grid.json` (LLM arm) and
   `docs/measurement/sp2c24-baseline-grid.json` (rule-based arm), per the commands already
   documented in `CLAUDE.md`'s "Manual-only dispatcher sweep" section and
   `docs/GOAL_10_SP2C24_SWEEP_REPORT.md`'s "Reproducing this" section. This is the "milestone's
   final gate" re-baseline explicitly required by both #926 and #927 — do not skip it or
   substitute a smaller run. Expect materially higher cycle counts than the SP2c.24 report
   (that report's 18-26 cycles/run was the throttle bug's signature) and materially different
   rates (Task 1's actionable-rate excludes non-actionable silent ticks).
2. Update `MIN_ACTIONABLE_RATE` (the Task 1 step 9 placeholder in `RunReportAggregator.kt`)
   to the real threshold implied by the new measured data, with a KDoc citing the new sweep's
   date and run. Update the corresponding test(s) in `RunReportAggregatorTest` that currently
   assert against the placeholder value.
3. Write a new `docs/GOAL_10_SP2C_WAVE2_REBASELINE_REPORT.md` (or extend
   `GOAL_10_SP2C24_SWEEP_REPORT.md` with a clearly-dated addendum section — your call, but do
   not silently overwrite the SP2c.24 report's original findings; the throttle-bug-era numbers
   remain historically valid data about *that* defect) documenting: the new measured rates,
   the gate verdict (pass/fail per arm) under the new actionable-rate+railway-outcome gate, and
   an explicit note of what changed in the metric so a reader does not compare old and new
   numbers directly.
4. Update GitHub issue #925: replace the note about the `>0.80` gate being stale
   (currently in a comment on #925, quoted in this plan's Context section) with the new
   threshold semantics — `gh issue comment 925 --repo bedaHovorka/interlockSim --body "..."`
   summarizing what changed and citing the new report. Do not edit the original issue body
   text; add a comment (matches the pattern already used on #925 for the prior correction).
5. Update GitHub issue #822 to reflect the new A4 acceptance numbers in §7 terms — a comment
   summarizing the measured actionable-rate result against the `≥8/10` structure, analogous to
   how #925 was commented on. Do not rewrite §7's table in place without the user's explicit
   go-ahead in this session — comment first; ask before editing #822's body if a body edit
   still seems warranted after the comment.
6. **Do not push, merge, or open a PR in this task** — stop once the branch is green and the
   two GitHub comments are posted, and report back what's ready for review.

## Verification (all tasks)

`./gradlew build detekt ktlintCheck test integrationTest` must pass on this branch before
Task 3's sweep run, and again after Task 3's threshold-constant edit. `heavyTest` is not
required by CLAUDE.md's own rules for this change (no `sim/`, `context/navigation/`, or
kDisco-integration code is touched — `dispatcher-agent` is a separate module) but running it is
a reasonable extra precaution given the concurrency-adjacent nature of the throttle fix; use
your judgment per the plan's Global Constraints, not as a hard requirement.
