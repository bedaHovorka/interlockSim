# Goal 9B demand — SP11: exit calibration and 20-train measurement

**Date:** 2026-11-03
**Source:** [#1346](https://github.com/bedaHovorka/interlockSim/issues/1346), #591 part 1 (the scope of the
closed Goal 1B SP11, [#1158](https://github.com/bedaHovorka/interlockSim/issues/1158)), acceptance **B8** of
Goal 1B ([#1147](https://github.com/bedaHovorka/interlockSim/issues/1147)).
**Purpose:** calibrate the Goal 1B exit gate before #591 part 2 flips it into `integration-test`: the
low-traffic seeded test on 10 JVM seeds and 1 native seed, the wall-time budget, the 20-train and
shoulder measurement rows, and the Goal 9B demands that follow from them (capacity limit, the traffic
level at which blocking begins).

Sections marked `MEASURED BY COORDINATOR` are filled from runs on a quiet machine. Wall times seen
while other builds ran are not results and are not recorded here.

## Exit parameters (owner-approved)

| Parameter | Value | Where it lives |
| --- | --- | --- |
| Arrivals | `kotlin.random.Random(seed)`, built test-side | `LowTrafficExitScenario.arrivals` |
| Inter-arrival time | exponential, mean 120 s | `MEAN_INTER_ARRIVAL_SECONDS` |
| Minimum gap per InOut | 30 s between two injections naming the same InOut, as entry **or as exit** (stricter than the #1158 wording, see below) | `MIN_GAP_PER_IN_OUT_SECONDS` |
| Minimum gap, global | 20 s between any two consecutive injections | `MIN_GAP_GLOBAL_SECONDS` |
| Concurrency cap | `maxConcurrentTrains = 4`, passed to `MultiTrainLoop` | `MAX_CONCURRENT_TRAINS` |
| Horizon | `endTime` 3,600 s | `END_TIME` |
| Last injection | at most `endTime − 600 s` = 3,000 s | `RUN_END_MARGIN_SECONDS` |
| Integration step | `dtMax` 1e-3 | set by the loop's generator (`Generator.startAction`); every run logs the effective value |
| Seeds | 1 to 10, fixed before the first run | `LOW_TRAFFIC_SEEDS` |
| Entry attempts | `MultiTrainLoop.DEFAULT_MAX_ENTRY_ATTEMPTS` (3,600) | not an exit parameter; the loop default |

Topology: the existing stress path of `MultiTrainScaleStressTest`, the Praha fixture
(`praha-hlavni-nadrazi.xml`) and its eight switch-legal `(entry, exit)` pairs
`MultiTrainStressSpecs.LEGAL_PAIRS` (#1179). Each low-traffic arrival draws its pair uniformly from
that list. Train length 40 m.

How the arrival builder applies the gaps: each gap is drawn from the exponential distribution and
counted from the previous injection; an injection that would come too early for either minimum gap
is moved later, and the next gap counts from the moved time. Every injection is rounded up to a whole
second, because the loop's generator injects on its one-second ticks anyway (whole seconds also keep
the gaps exact). The builder stops at the first injection later than 3,000 s. The seeds give 17 to 29
trains per run.

**Per-InOut gap on both InOuts (TSE ruling, PR #1349).** #1158 asks for "minimum gap 30 s per
InOut". The builder applies it to the exit InOut of a spec as well as to its entry InOut. That is
stricter than the #1158 wording and lowers the offered traffic slightly (an injection is moved later
more often than with the entry-only reading).

**Whole-second rounding (TSE ruling, PR #1349).** Accepted. Every run logs its effective mean entry
gap (`meanEntryGap`, the mean gap between consecutive injections, `(last − first) / (n − 1)`); the
functional table below has a column for it. That value is the realized sample mean of one seed's
16 to 28 gaps, not the distribution parameter: the exponential mean is 120 s for every seed, and the
minimum gaps and the rounding only ever move an injection later, but the sample mean still scatters
around 120 s (100.7 to 173.9 s over the 10 seeds; seeds 2, 3, 6, 8 and 9 fall below 120 s).

## Test layout

| Class | Source set | Tag | What it does |
| --- | --- | --- | --- |
| `LowTrafficExitScenario` | `core` `commonTest` | (helper, not a test) | the parameters, the arrival builder, one run on Praha (`runScenario`), the functional assertions (`assertFunctional`) and the failure-cause classification (`MultiTrainRun.failureCause`) |
| `LowTrafficExitScenarioTest` | `core` `commonTest` | untagged (every gate, JVM and native) | pins the seed list and the spacing rules of the arrivals for all 10 seeds, and the cause classification; runs no simulation |
| `LowTrafficExitCalibrationTest` | `core` `jvmTest` | `heavy-test` | `@RepeatedTest(10)`, repetition `i` runs seed `i`; `@Timeout(120 s, SEPARATE_THREAD)` as a safety ceiling only |
| `LowTrafficExitNativeSeedTest` | `core` `nativeTest` (new) | untagged (every `linuxX64Test`) | seed 1 on `linuxX64`, functional assertions plus the JVM seed-1 counts pinned; no `@Timeout` |
| `ExitCalibrationRowsTest` | `core` `jvmTest` | `heavy-test` | the 20-train and shoulder measurement rows, log-only |

`nativeTest` is the source set `applyDefaultHierarchyTemplate()` creates between `commonTest` and
`linuxX64Test`; the directory did not exist before. Putting the native seed there, not in
`commonTest`, keeps it off the JVM `test` task (the JVM runs every seed in the heavy class).

**Functional assertions only.** Entered == exited == number of specs, zero occupied block resources at
run end, observed concurrency at most the cap. No wall-time, real-time-ratio or other timing assertion
exists in the new code: the wall time of each run is logged (`wall=` in the run line) and the budget
is a measurement. A failing seed is a finding: its cause is classified as `WATCHDOG_FIRED`
(`MultiTrainLoop.stall`, #1154), `ENTRY_ATTEMPTS_USED_UP`, `MAX_CONCURRENCY_REACHED` (generated trains
still held back by the cap at run end) or `OTHER`, and the full spec list is logged. A flake files an
issue with that spec list; the assertions are never loosened and a seed is never replaced.

**Per-run log line** (logger `cz.vutbr.fit.interlockSim.sim.ExitCalibration`, visible on the JVM through
the `EXIT_CALIBRATION` stdout appender of `core/src/jvmTest/resources/logback-test.xml`; the
calibration test, the native test and both rows print the same line): label (seed), specs,
`meanEntryGap`, entered, exited, max concurrent and cap, occupied, entry failures, cause,
`blockingBeginsAt` (simulation time of the first entry refusal that made a train wait; `never` when
none), `capHoldBeginsAt` (first tick at which the cap held a generated train back; `never` when
none), `firstEntryFailureAt` (simulation time of the first `MultiTrainLoop.EntryFailure`; `none` when
none), `stallAt` (simulation time of the deadlock watchdog's report; `none` when none), the number of
`ReservationExpired` events, the effective `dtMax`, and the wall time.

**"Blocking begins" (TSE ruling, PR #1349).** Accepted as defined above: the first retryable
`reservePath` refusal, recorded exactly at the dispatcher tick that made it. The first entry failure
and the watchdog stall are logged next to it so a run that blocks and then fails shows both times.

The two blocking times are observed without touching `MultiTrainLoop`: the helper wraps the loop's
`PathReservationService` in a recording delegate and runs a `MultiTrainLoop` subclass whose tick reads
the loop's public counters before the unchanged `MultiTrainLoop.iteration`. A train's first entry
attempt is always made on a dispatcher tick, so the first refusal of a run carries the exact tick time.

**Note on "max concurrent".** `MultiTrainLoop.getMaxConcurrentTrains` is the peak number of
*admitted* trains: a train admitted under the cap that is still waiting for its entry route counts.
It is not the number of trains moving at the same time.

## Functional results

Seen on the development machine on 2026-11-03 at base `375c54b1` plus this change (the counts do not
depend on machine load; wall times taken then are not recorded).

### Low traffic, JVM (10 seeds)

| Seed | Specs | Mean entry gap (s) | Entered | Exited | Max concurrent (cap 4) | Occupied | Blocking begins at (s) | Cap hold | First entry failure | Stall | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | 18 | 173.9 | 18 | 18 | 3 | 0 | 1,294 | never | none | none | pass |
| 2 | 26 | 109.5 | 26 | 26 | 3 | 0 | 246 | never | none | none | pass |
| 3 | 24 | 119.6 | 24 | 24 | 3 | 0 | 2,062 | never | none | none | pass |
| 4 | 23 | 126.2 | 23 | 23 | 3 | 0 | 40 | never | none | none | pass |
| 5 | 23 | 125.3 | 23 | 23 | 2 | 0 | 1,232 | never | none | none | pass |
| 6 | 29 | 100.7 | 29 | 29 | 3 | 0 | 148 | never | none | none | pass |
| 7 | 21 | 129.2 | 21 | 21 | 3 | 0 | 1,834 | never | none | none | pass |
| 8 | 24 | 109.2 | 24 | 24 | 3 | 0 | 442 | never | none | none | pass |
| 9 | 21 | 118.8 | 21 | 21 | 2 | 0 | 968 | never | none | none | pass |
| 10 | 17 | 146.5 | 17 | 17 | 2 | 0 | 1,206 | never | none | none | pass |

10 of 10 seeds pass; no entry failure, no watchdog report, no reservation expiry, `dtMax` 0.001 in
every run. Every seed has at least one refused entry, but no run ever filled the cap of 4.

### Low traffic, native (seed 1)

`linuxX64` seed 1: 18 specs, 18 entered, 18 exited, max concurrent 3, occupied 0, blocking begins at
1,294 s — identical to the JVM seed-1 run. The test pins entered/exited = 18 and max concurrent = 3.

### Measurement rows (non-gating)

Both rows run the stress configuration of `MultiTrainScaleStressTest` — **cap 20, 1,800 attempts,
rotating legal pairs** (3,600 s horizon) — and every row log line carries that label. Only the entry
gaps differ between the two rows. (TSE ruling, PR #1349: accepted.) **The rows' blocking points must
never be compared with the blocking points of the exit gate, whose cap is 4:** a different cap and
attempt budget change when and why an entry is refused. "Max concurrent" counts admitted trains,
including those still waiting for their entry route (see the note above).

| Row (cap 20, 1,800 attempts, rotating legal pairs) | Runs | Mean entry gap (s) | Entered and exited (all specs) | Max concurrent (cap 20) | Blocking begins at (s) | First entry failure | Stall |
| --- | ---: | ---: | --- | ---: | ---: | --- | --- |
| 20-train high traffic, headway 5 s | 10 | 5.0 | 10 of 10 (20/20) | 15 in every run | 26 in every run | none | none |
| Shoulder traffic, gaps 15–30 s (seeds 1–10) | 10 | 20.3–24.6 | 10 of 10 (20/20) | 4–5 | 118–184 | none | none |

The 20-train row is deterministic (identical specs), so its counts repeat exactly; only the wall time
varies between its runs.

## Wall-time budget (quiet window)

Budget: at most 15 s wall per run and 3 min total for the 10 JVM seeds. A measurement, never an
assertion.

<!-- MEASURED BY COORDINATOR: quiet-window wall-time table. One row per seed 1-10 (JVM wall ms from
the `wall=` field of each run line) plus the total, each against 15 s / 3 min; the native seed-1 wall
time and the whole quiet `:core:linuxX64Test` time against the move-out rule below; and a
machine-state line (load average, other Gradle daemons/JVMs running, CPU governor or power profile,
date and commit). -->

### Native seed: move-out rule (TSE ruling, PR #1349)

The native seed runs in every `:core:linuxX64Test`, so in every gate and in the CI step whose limit
is 5 minutes. Its cost is accepted for now. It **moves out** of `linuxX64Test` into a manual task if
the quiet-window native seed takes **more than 60 s**, or the whole quiet `:core:linuxX64Test` takes
**more than 3 min** (60 % of the 5-minute CI step). After such a move, B8's native evidence is
manual. Only quiet-window numbers decide this; times taken while other builds ran are not used.

## Measurement rows: wall time (quiet window)

<!-- MEASURED BY COORDINATOR: 20-train row (10 runs: entered, exited, max concurrent, wall ms,
blocking begins at; min/mean/max wall) and shoulder row (seeds 1-10, same columns), with the same
machine-state line. -->

## #895 re-baseline

<!-- MEASURED BY COORDINATOR: #895 re-baseline table, base 375c54b1 vs head of this branch (same
columns as the earlier SP re-baselines: revision, runs, journeys, entered, exited, max concurrent,
block transitions, conflicts, failed reservations). This change touches no production code, so the
two rows are expected to be identical. -->

## Verdicts

### SP7: still needed for 10 of 10?

**Method.** The 10 seeds run on `09ee668b`, the first parent of SP7's merge `b26e2792` (a squash
merge with a single parent), with this branch's calibration files and only the two APIs SP7 added
stubbed out (`MultiTrainLoop.stall` → no report, `getWaitingTrainNames()` → empty). Before SP7 there
is no deadlock watchdog, so any `@Timeout`, any `EntryFailure` or entered ≠ exited means **"SP7
needed"**. The result is functional only; the counts may differ from head. 10 of 10 means "SP7 not
needed for the exit", but SP7 is not removed in this wave. The numbers are tagged LOADED when they
were not taken in the quiet window.

<!-- MEASURED BY COORDINATOR: SP7 verdict. Per seed on 09ee668b: pass/fail and failure cause
(LOADED tag where applicable), then the verdict by the method above. -->

### SP14: must it open?

<!-- MEASURED BY COORDINATOR: SP14 verdict. Cause per failing seed (WATCHDOG_FIRED,
ENTRY_ATTEMPTS_USED_UP, MAX_CONCURRENCY_REACHED, OTHER); SP14 (incremental reservation) opens only
on a capacity cause. On this branch's head all 10 seeds pass, so no seed has a cause. -->

## Why other wave-5 issues cannot change these numbers

The numbers were taken on `375c54b1`. Three wave-5 issues change routing or dispatch code; none of
them changes what `MultiTrainLoop` does in these runs (checked by grep over `MultiTrainLoop` and the
runs' logs):

- **#1299** changes `ReservationTargetPolicy` (wait for the timetable exit). `MultiTrainLoop` never
  consults that policy: it reserves one InOut-to-InOut route with
  `PathReservationService.reservePath`; the policy is reached only through `RuleBasedDispatcher` and
  the dispatcher observation (`chosenTargetName`).
- **#1303** changes how the agent stack (`:dispatcher-agent`) constructs `RuleBasedDispatcher`.
  `MultiTrainLoop` uses neither `RuleBasedDispatcher` nor the agent stack.
- **#1310** guards the `ReservationExpired` emit against a throwing raw `onSimulationEvent`
  subscriber. `MultiTrainLoop` calls `PathReservationService.expireStaleReservations` on every tick,
  but no expiry happened in any run of any seed or row above (`reservationExpiries=0` throughout) and
  these runs register no raw subscriber, so #1310 cannot change these numbers.

## Goal 9B demands

<!-- MEASURED BY COORDINATOR: capacity limit, concurrent trains versus wall time, from the quiet
window: low traffic (peak admitted 2-3 of cap 4), shoulder (4-5 of cap 20) and 20-train (15 of cap
20) against their wall times; state that "max concurrent" counts admitted trains, including those
still waiting for a route. -->

<!-- MEASURED BY COORDINATOR: traffic level at which blocking begins. Functional data above, kept
per configuration (the cap-4 exit gate and the cap-20 rows are never compared): exit gate, cap 4 —
at the low-traffic rate (exponential mean 120 s, realized mean gaps 100.7-173.9 s) every seed
already sees at least one refused entry (first at 40-2,062 s) but never fills the cap; rows, cap 20,
1,800 attempts, rotating legal pairs — at shoulder gaps (15-30 s) the first refusal comes at
118-184 s, at the 5 s headway at 26 s. Confirm or amend from the quiet-window runs and state the
demand for Goal 9B. -->
