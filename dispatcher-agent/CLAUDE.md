# CLAUDE.md — :dispatcher-agent

**Last Updated:** 2026-08-29

Guidance for Claude Code when working in `:dispatcher-agent`. Repo-wide rules
(English-only output, heavy-test policy, Koin rules, code quality) live in the
[root CLAUDE.md](../CLAUDE.md) and are not repeated here.

`:dispatcher-agent` holds the Goal 10 dispatcher: Koog agent definitions, the
Ollama executor, run observation/metrics, plan adapters, and the `aiSweep` grid
runner. Plain JVM module.

**Dependency direction (do not break):** `:core` ← `:dispatcher-agent` ←
`:desktop-ui`. `:fast-sim` must NEVER depend on this module — the native binary
must not pull LLM dependencies. `RuleBasedDispatcher` and `DispatchDecision`
deliberately live in `:core` to avoid a dependency cycle. Which
`DispatchDecision` subtypes are live in production is audited in
[../docs/GOAL_10_SP2C25_DECISION_VOCABULARY_AUDIT.md](../docs/GOAL_10_SP2C25_DECISION_VOCABULARY_AUDIT.md).

## Build and Test

```bash
./gradlew :dispatcher-agent:test              # Unit tests (no integration/heavy/ollama tags)
./gradlew :dispatcher-agent:integrationTest   # Probes Ollama; see below
./gradlew :dispatcher-agent:heavyTest         # Manual only — see root CLAUDE.md testing policy
./gradlew :dispatcher-agent:dispatcherReliabilityReport   # Renders report from stored run JSONs
```

- **`integrationTest` probes `localhost:11434` before it runs.** If Ollama is
  reachable, `ollama-test`-tagged tests are included. If not reachable **locally**,
  the task fails with instructions (`ollama serve` or
  `docker compose up -d ollama`). If not reachable **in CI**, it warns and skips
  the `ollama-test` tag.
- **`ollama-test` tag:** tests that need a live Ollama with `qwen2.5:7b-instruct`.
  This is the only module using that tag.
- **`dispatcherReliabilityReport`** runs with `workingDir = rootProject.projectDir`
  on purpose (Issue #847): `DefaultRunSnapshotStore.DEFAULT_ROOT` is the relative
  path `build/reports/dispatcher-runs`, so a module-relative working directory
  would silently produce an all-zero report. It reads existing JSON only.
- **MockWebServer is pinned to 5.x** because koog-agents' transitive aws-smithy
  forces okhttp 5.x, and mockwebserver 4.x crashes on the removed
  `okhttp3.internal.Util`.
- This module declares its **own `sonar {}` block**, as do `:core` and
  `:desktop-ui` (Issue #762 — the root Sonar config lists no module paths, so
  no module is indexed twice). Keep it here.

## Configuration

`src/main/resources/cz/vutbr/fit/interlockSim/dispatcher/dispatcher-defaults.properties`
is the middle tier of a 3-tier precedence chain:

1. `-Dinterlocksim.dispatcher.*` system property (highest)
2. the shipped properties resource
3. code fallback constant (lowest)

Missing or malformed values log WARN and fall through — never a failure. The
shipped values are locked by `DispatcherDefaultsResourceTest`; change both
together, and only with a measured justification (see the reliability report
below for how the current defaults were chosen).

**Koog limitation** (version comes from `koogVersion` in
[../gradle.properties](../gradle.properties)): no sampling seed can reach Ollama
on the tool-calling path, and `format` must never be combined with `tools`.
Details and evidence:
[../docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md](../docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md).

**Timing regime:** the standing F1 paused-clock ruling (#849) — the simulation
clock freezes only during the LLM emission window, with binding constraints on
any implementation — is recorded in
[../docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md](../docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md).

## Manual-only dispatcher sweep (`aiSweep`) — never in CI

`aiSweep` is a CLI mode of the [desktop-ui jar](../desktop-ui/CLAUDE.md), not a
Gradle task, and it belongs in the same **manual-only** category as `heavyTest`:
it needs a live Ollama, forks one JVM per run, and a full grid takes hours.
Nothing in `test`, `integrationTest`, `build` or CI invokes it, and nothing
should.

It exists because Goal 10's amended A4 acceptance criterion (#822 §7) is a
*measured* success rate over N ≥ 10 runs — a claim that cannot be made without
actually performing those runs and recording each one.

**No grid files are committed.** Write the grid for the campaign you are running,
keep it out of `docs/`, and record its content in that campaign's report. The
swept axes are `example`, `model`, `temperature`, `tickPeriodMs`, `historyN`,
`maxActionsPerTick`, `inferenceTimeoutSeconds` (per-cycle LLM inference deadline)
and `promptVariant` (DISPATCHER system-prompt revision); there is deliberately no
`seed` axis, because the pinned Koog version has no path to Ollama's `seed`
option. An axis omitted from a grid keeps its default — the example below omits
`inferenceTimeoutSeconds` and `promptVariant` on purpose.

```jsonc
// grid.json — the LLM arm. Drop the "model" axis and use "example": ["shuntingLoop"] for the
// rule-based control arm.
{
  "endTimeSeconds": 600,
  "repeat": 10,
  "perRunTimeoutSeconds": 900,
  "axes": {
    "example": ["shuntingLoopAI"],
    "model": ["qwen2.5:7b-instruct"],
    "temperature": [0.28],
    "tickPeriodMs": [0],
    "historyN": [0],
    "maxActionsPerTick": [3]
  }
}
```

Run from the repository root:

```bash
./gradlew :desktop-ui:shadowJar

# Check the grid without launching anything
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep --grid grid.json --dry-run

# The real thing (hours; resumable — re-invoke after an interrupt and it continues)
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid grid.json --out build/reports/dispatcher-sweep

# The rule-based baseline, into the same directory so one report compares both arms (~30 s)
java -jar desktop-ui/build/libs/interlockSim.jar aiSweep \
  --grid baseline-grid.json --out build/reports/dispatcher-sweep
```

Each run writes one JSON under `<out>/<arm>/`, and the sweep renders
`<out>/report.md` when it finishes. A run that exceeds `perRunTimeoutSeconds` is
killed and recorded as `TIMEOUT_ABORT` rather than left absent — an absent failed
run would silently improve the arm's measured rate.

Results and interpretation live in two reports, and only these two:
[../docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md](../docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md)
(Issue #837 — the measured A4 outcome, and the surviving record of every campaign
run so far) and
[../docs/GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md](../docs/GOAL_10_SP2C15_FRONTIER_DIAGNOSTIC_SETUP.md)
(Issue #838 — the larger-model diagnostic, prepared but never run, which is the
next open step).

## Ollama Setup

- Default endpoint `http://localhost:11434`; model `qwen2.5:7b-instruct`.
- Start natively (`ollama serve`) or with `docker compose up -d ollama`
  (the compose file defines an `ollama` profile).
- Executor setup and troubleshooting:
  [../docs/SP1_5_OLLAMA_EXECUTOR_SETUP.md](../docs/SP1_5_OLLAMA_EXECUTOR_SETUP.md).
- Model evaluation history (why qwen2.5:7b):
  [../docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md](../docs/GOAL_10_SP3_1_LLM_MODEL_EVALUATION.md).
