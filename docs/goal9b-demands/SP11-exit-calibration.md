# Goal 9B demand — SP11: exit calibration and 20-train measurement

**Date:** 2026-11-03
**Source:** [#1346](https://github.com/bedaHovorka/interlockSim/issues/1346), #591 part 1 (the scope of the
closed Goal 1B SP11, [#1158](https://github.com/bedaHovorka/interlockSim/issues/1158)), acceptance **B8** of
Goal 1B ([#1147](https://github.com/bedaHovorka/interlockSim/issues/1147)).
**Purpose:** calibrate the Goal 1B exit gate before #591 part 2 flips it into `integration-test`: the
low-traffic seeded test on 10 JVM seeds and 1 native seed, the wall-time budget, the 20-train and
shoulder measurement rows, and the Goal 9B demands that follow from them (capacity limit, the traffic
level at which blocking begins).

Wall times are taken only from runs on a quiet machine (see "Wall-time budget"). Wall times seen
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

**Mean gap: distribution parameter, not per-seed mean (TSE ruling, PR #1349).** Inter-arrival gaps are
drawn from an exponential distribution with mean 120 s (the owner's parameter); the per-seed realized
mean entry gap of 100.7–173.9 s is sampling scatter, and the five seeds below 120 s passed under
heavier-than-nominal traffic. The parameter and the seeds stay unchanged.

## Test layout

| Class | Source set | Tag | What it does |
| --- | --- | --- | --- |
| `LowTrafficExitScenario` | `core` `commonTest` | (helper, not a test) | the parameters, the arrival builder, one run on Praha (`runScenario`), the functional assertions (`assertFunctional`) and the failure-cause classification (`MultiTrainRun.failureCause`) |
| `LowTrafficExitScenarioTest` | `core` `commonTest` | untagged (every gate, JVM and native) | pins the seed list and the spacing rules of the arrivals for all 10 seeds, and the cause classification; runs no simulation |
| `LowTrafficExitCalibrationTest` | `core` `jvmTest` | `integration-test` (since the #591 part 2 flip; `heavy-test` before) | `@RepeatedTest(10)`, repetition `i` runs seed `i`; `@Timeout(120 s, SEPARATE_THREAD)` as a safety ceiling only |
| `LowTrafficExitNativeSeedTest` | `core` `nativeTest` (new) | untagged (every `linuxX64Test`) | seed 1 on `linuxX64`, functional assertions plus the JVM seed-1 counts pinned; no `@Timeout` |
| `ExitCalibrationRowsTest` | `core` `jvmTest` | `heavy-test` | the 20-train and shoulder measurement rows, log-only |

`nativeTest` is the source set `applyDefaultHierarchyTemplate()` creates between `commonTest` and
`linuxX64Test`; the directory did not exist before. Putting the native seed there, not in
`commonTest`, keeps it off the JVM `test` task (the JVM runs every seed in the integration-test class).

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

Measured 2026-11-03 at 18:06–18:09 in a quiet window: no gate, heavy test, sweep, agent build or other
Gradle client ran; the run waited until the 1-minute load average was below 1.5, was pinned to the
P-cores (`taskset -c 0-7`, 5.5 GHz) and ran Gradle with `--no-daemon` and `--rerun`. A power guard
read the enforced GPU power limit before the window and after every step and would have stopped the
run below 90 W. Machine state at the start (tag **QUIET**; the GPU limit stayed at 95 W through the
end of the window):

- `sha=ce4496f3 loadavg=[0.89 0.64 0.48] busyJava=[none] pin=taskset 0-7 (P-cores) AC=1 docker=1 gpu=[enforced limit 95.00 W, 10.76 W, P8] cpu=[PL1 95 W, PL2 160 W, powersave/balance_performance]` at 2026-11-03T18:06:23+01:00

**Re-measured without the GPU power cap.** The first quiet windows (07:54–08:17 the same day, head
`9e069813`, same commands) ran while the firmware held the GPU at a 35 W power limit (`nvidia-powerd`:
SBIOS disabled Dynamic Boost; seen in the same session at 06:42 and again after the windows). Their
state line read `power.limit`, which this GPU reports as `[N/A]`, so it did not show the cap. After the
owner restarted `nvidia-powerd` (limit 95 W), the windows were run again; the numbers below replace
them. The capped run was 5–10 % slower (JVM total 14.7 s, native seed 25.8 s, native binary 71.5 s)
and did not change any verdict. `ce4496f3` differs from `9e069813` only in this document.

| Seed | JVM wall (s) | Budget | Within |
| ---: | ---: | ---: | --- |
| 1 | 1.47 | 15 s | yes |
| 2 | 1.52 | 15 s | yes |
| 3 | 1.38 | 15 s | yes |
| 4 | 1.33 | 15 s | yes |
| 5 | 1.32 | 15 s | yes |
| 6 | 1.63 | 15 s | yes |
| 7 | 1.16 | 15 s | yes |
| 8 | 1.33 | 15 s | yes |
| 9 | 1.18 | 15 s | yes |
| 10 | 0.96 | 15 s | yes |
| **Total, 10 seeds** | **13.3** (JUnit suite time 13.5 s) | 3 min | yes |

The slowest seed uses 11 % of its per-run budget; the 10 seeds together use 7 % of the total budget.
The counts of the quiet run are identical to the functional table above (10 of 10 pass).

| Native measure | Quiet wall | Move-out limit | Within |
| --- | ---: | ---: | --- |
| Seed 1 (`LowTrafficExitNativeSeedTest`, debug test binary) | 24.6 s | 60 s | yes |
| Whole `:core` native test binary (1,316 tests, includes seed 1) | 67.5 s | 3 min | yes |

The native seed costs about 17 times the JVM seed-1 run (debug binary). Both native numbers are inside
the move-out rule below, so the seed **stays in `linuxX64Test`**.

### Native seed: move-out rule (TSE ruling, PR #1349)

The native seed runs in every `:core:linuxX64Test`, so in every gate and in the CI step whose limit
is 5 minutes. Its cost is accepted for now. It **moves out** of `linuxX64Test` into a manual task if
the quiet-window native seed takes **more than 60 s**, or the whole quiet `:core:linuxX64Test` takes
**more than 3 min** (60 % of the 5-minute CI step). After such a move, B8's native evidence is
manual. Only quiet-window numbers decide this; times taken while other builds ran are not used.

## Measurement rows: wall time (quiet window)

Same quiet window (`sha=ce4496f3`, GPU enforced limit 95 W, 2026-11-03T18:06:45+01:00). Counts as in the functional table above (all runs 20/20 entered and
exited, no entry failure, no stall).

| Row (cap 20, 1,800 attempts, rotating legal pairs) | Runs | Max concurrent | Blocking begins at (s) | Wall min (s) | Wall mean (s) | Wall max (s) | Wall total (s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20-train high traffic, headway 5 s | 10 | 15 | 26 | 1.15 | 1.17 | 1.19 | 11.7 |
| Shoulder traffic, gaps 15–30 s (seeds 1–10) | 10 | 4–5 | 118–184 | 1.11 | 1.21 | 1.59 | 12.1 |

## #895 re-baseline

Rule-based `aiSweep` control arm, grid `endTimeSeconds 600, repeat 10, perRunTimeoutSeconds 900,
example: ["shuntingLoop", "cervenyUjezd"]`, railway outcomes summed over the 10 runs. The base arm is
the shared wave-5 base arm; the head arm was built from this branch.

| Example | Revision | Runs | Journeys | Entered | Exited | Max concurrent | Block transitions | Conflicts | Failed reservations |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| shuntingLoop | base 375c54b1 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| shuntingLoop | head 9e069813 | 10 | 150 | 150 | 150 | 2 | 300 | 0 | 20 |
| cervenyUjezd | base 375c54b1 | 10 | 180 | 190 | 170 | 2 | 380 | 0 | 100 |
| cervenyUjezd | head 9e069813 | 10 | 180 | 190 | 170 | 2 | 380 | 0 | 100 |

Every run `NATURAL_COMPLETION`, no unresolved reservation at run end. Base == head, as expected for a
test-and-docs-only change.

## Verdicts

### SP7: still needed for 10 of 10?

**Method.** The 10 seeds run on `09ee668b`, the first parent of SP7's merge `b26e2792` (a squash
merge with a single parent), with this branch's calibration files and only the two APIs SP7 added
stubbed out (`MultiTrainLoop.stall` → no report, `getWaitingTrainNames()` → empty). Before SP7 there
is no deadlock watchdog, so any `@Timeout`, any `EntryFailure` or entered ≠ exited means **"SP7
needed"**. The result is functional only; the counts may differ from head. 10 of 10 means "SP7 not
needed for the exit", but SP7 is not removed in this wave. The numbers are tagged LOADED when they
were not taken in the quiet window.

Run 2026-11-03 on `09ee668b` with this branch's calibration files and the two stubs (tag **LOADED**:
`loadavg=[4.75 8.31 13.68]`, other builds running; timings not used):

| Seed | Specs | Entered | Exited | Max concurrent (cap 4) | Entry failures | `@Timeout` | Result |
| ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 1 | 18 | 18 | 18 | 3 | 0 | no | pass |
| 2 | 26 | 26 | 26 | 3 | 0 | no | pass |
| 3 | 24 | 24 | 24 | 3 | 0 | no | pass |
| 4 | 23 | 23 | 23 | 3 | 0 | no | pass |
| 5 | 23 | 23 | 23 | 2 | 0 | no | pass |
| 6 | 29 | 29 | 29 | 3 | 0 | no | pass |
| 7 | 21 | 21 | 21 | 3 | 0 | no | pass |
| 8 | 24 | 24 | 24 | 3 | 0 | no | pass |
| 9 | 21 | 21 | 21 | 2 | 0 | no | pass |
| 10 | 17 | 17 | 17 | 2 | 0 | no | pass |

**Verdict: SP7 is not needed for 10 of 10 at the exit parameters.** All 10 seeds pass before SP7 with
the same counts as at head. SP7 stays in place; it is not removed in this wave.

### SP14: must it open?

No seed fails, either at head (10 of 10, quiet window and functional runs) or on SP7's parent (10 of
10), so no seed has a failure cause. Every run logs `firstEntryFailureAt=none` and `stallAt=none`, and
the cap of 4 is never filled (`capHoldBeginsAt=never`).

**Verdict: SP14 (incremental reservation) does not open.** No capacity cause exists at the exit
parameters.

### B8: the flip (#591 part 2)

On 2026-11-05 `LowTrafficExitCalibrationTest` moved from `heavy-test` to `integration-test` (commit
`813e6442`), so the 10 seeds run in every `integrationTest` gate and in the CI step "Run integration
tests". The 120 s safety ceiling per seed stays; `ExitCalibrationRowsTest` stays `heavy-test`.

| Run | Machine state | Seeds | Per-seed time (s) | Total (s) |
| --- | --- | ---: | --- | ---: |
| Local gate, `:core:integrationTest` | LOADED (three agent builds in parallel) | 10 of 10 | not used | 27.3 |
| CI run 42040274099 (attempt 2), GitHub-hosted runner | CI (shared runner, not quiet) | 10 of 10 | 3.08, 5.72, 3.73, 3.58, 3.53, 5.08, 3.18, 3.73, 3.19, 2.63 | 37.4 |

The CI step "Run integration tests" (all modules) took 2 min 22 s. Every CI seed stays far inside the
15 s per-run budget and the CI total inside the 3 min budget, although a shared runner is slower than the
quiet-window machine (1.0–1.6 s per seed). The budget itself stays a quiet-window figure; it is never
asserted.

**Verdict: B8 is met.** The Goal 1B exit test is green in `integrationTest`, locally and in CI.

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

**D1 — Capacity limit, concurrent trains versus wall time.** "Max concurrent" counts *admitted* trains,
including those still waiting for their entry route.

| Configuration | Peak admitted / cap | Wall per run (quiet) |
| --- | --- | ---: |
| Exit gate, low traffic (mean gap 120 s), 1 h simulated | 2–3 / 4 | 1.0–1.6 s |
| Row: shoulder traffic (gaps 15–30 s), 20 trains | 4–5 / 20 | 1.1–1.6 s |
| Row: 20-train high traffic (headway 5 s) | 15 / 20 | 1.1–1.2 s |

Wall time does not grow with the number of admitted trains in this range: one simulated hour on Praha
costs about 1–2 s on the JVM at 3, 5 or 15 admitted trains. Simulation cost is not the limit at the
Goal 1B traffic levels; Goal 9B can plan with full-route reservation at these levels.

**D2 — Traffic level at which blocking begins.** Kept per configuration; the cap-4 exit gate and the
cap-20 rows are never compared.

- **Exit gate, cap 4:** at the low-traffic rate (exponential mean 120 s; realized mean gaps
  100.7–173.9 s) every seed already sees at least one refused entry (first at 40–2,062 s), but no run
  fills the cap and no refusal turns into an entry failure.
- **Rows, cap 20, 1,800 attempts, rotating legal pairs:** at shoulder gaps (15–30 s) the first
  refusal comes at 118–184 s; at the 5 s headway at 26 s. All refusals resolve (20/20 in every run).

Demand for Goal 9B: on Praha, route conflicts between full entry-to-exit reservations begin already
at low traffic; they cost waiting time, not lost trains. A Goal 9B dispatcher must expect refused
entries at every traffic level and treat them as a normal retry, not an error. Incremental
reservation (SP14) is not needed to reach the Goal 1B exit.
