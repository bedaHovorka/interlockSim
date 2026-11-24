# CLAUDE.md — :desktop-ui

**Last Updated:** 2026-10-29

Guidance for Claude Code when working in `:desktop-ui`. Repo-wide rules
(English-only output, heavy-test policy, Koin rules, code quality) live in the
[root CLAUDE.md](../CLAUDE.md) and are not repeated here.

`:desktop-ui` is the JVM application module: the Swing editor and animated
simulation GUI, the Koin bootstrap, and the `Main` entry point.

## Entry Point and Modes

`cz.vutbr.fit.interlockSim.Main` handles six modes (see `VALID_MODES` in
`Main.kt`): `sim`, `simgui`, `edit`, `example`, `exampleGui`, `aiSweep`.
The `aiSweep` mode is documented in
[dispatcher-agent/CLAUDE.md](../dispatcher-agent/CLAUDE.md) — it is manual-only
and never runs in CI.

**Run JSON of an `example` run (Issue #1327):** with
`-Dinterlocksim.dispatcher.runsRoot=<dir>` (`DispatcherRunConfig.PROP_RUNS_ROOT`) an
`example` run writes its run JSON to `<dir>/<arm>/`, whatever its wiring, for example
`java -Dinterlocksim.dispatcher.runsRoot=build/runs -jar desktop-ui/build/libs/interlockSim.jar example cervenyUjezd 600`.
Without the property only a run with a dispatcher agent writes one, to
`build/reports/dispatcher-runs/`. The native `fast-sim` writes the same JSON with
`--runs-root=<dir>`, filed under the `*Sync` twin's name (see [fast-sim/CLAUDE.md](../fast-sim/CLAUDE.md));
`RunRecordJsonReadBackTest` holds the two equal.

## Run and Package Tasks

```bash
./gradlew runSim              # Pre-configured shunting loop (example shuntingLoop 60)
./gradlew runEditor           # Editor GUI
./gradlew runExample          # -PexampleName=... -PendTime=...
./gradlew runExampleGui       # Animated GUI; -PxmlFile=... switches to simgui mode
./gradlew runExampleAIGui     # shuntingLoopAI with the LLM dispatcher (needs Ollama)
./gradlew runSimFromXml       # -PxmlFile=... (required)
./gradlew :desktop-ui:shadowJar   # Fat jar: desktop-ui/build/libs/interlockSim.jar
```

The plain `jar` task is disabled; `shadowJar` produces the only artifact
(`interlockSim.jar`, no classifier). Manual launch:
`java -jar desktop-ui/build/libs/interlockSim.jar exampleGui shuntingLoop 1024`.

## GUI and Speed Control (Goal 7)

AnimatedSim renders physics-accurate train movement (Issue #268). Speed control:

- `SimulationRunner` — wall-clock throttling without changing event semantics
- `SimulationController` — owns lifecycle, persists the selected speed,
  reapplies it on the next simulation start
- `SimulationControlPanel` — `0.1x`-`10.0x` slider plus preset buttons up to `50x`
- `StatusBar.updateSpeedIndicator()` — shows the multiplier whenever speed ≠ `1.0x`
- `SimulationKeyBindings` — global shortcuts in simulation mode:
  `1`-`5` → 0.5x/1x/2x/5x/10x, `+`/`-` → ×1.5 / ÷1.5, `Space` → pause/resume
  (toggles `SimulationRunner.isPaused`), `S` → step one event, `T` → step by a time delta
  (Goal 8 is complete)

For an XML file loaded in the desktop UI, start the run with
**Simulation → Start...**, then adjust the speed with the slider, the presets,
or the shortcuts above.

Details: [../docs/ANIMATION_ARCHITECTURE.md](../docs/ANIMATION_ARCHITECTURE.md)
and [../docs/SIMULATION_SPEED_CONTROL.md](../docs/SIMULATION_SPEED_CONTROL.md).

## KPI Dashboard (Goal 6 SP3)

`MetricsDashboardPanel` sits in `Frame`'s right-hand side dock, under the speed controls, in both
modes (#1421); it polls only in simulation mode and is cleared in editing mode. It reads only
`MetricsCollectionService.latestSnapshot()` and `latestOpenContentions()`, and on its final poll the volatile
`runRecord()`, all cross-thread safe, from a
`SwingWorker` driven by a `javax.swing.Timer`, never simulation-thread objects. `stop(manual)` at STOPPED,
after any stop (natural or manual), makes that final poll, which reads the record (set at
`RunEnded`) for the stall and the end cause. A manual stop reports STOPPED before the simulation thread
delivers `RunEnded`, so a final poll without a record re-reads it from a one-shot timer (10 × 100 ms). With
no record after that, the end cause reads "stopped manually" after a manual stop and "–" otherwise (#1326). With `MetricGroup.TRAIN_WAIT` off, Queued, Blocked and
Open contentions show "–" (not measured).

## Main Window Layout (Goal 6, #1421)

One layout for both modes, so nothing jumps when the mode changes (design:
`docs/superpowers/specs/2026-11-24-main-window-layout-design.md`):

- NORTH: toolbar and a run bar (simulation mode only): `ControlPanel` (time, status, Stop), then
  `SimulationControlPanel.stepControls` (Pause, Step Event, Step Time, delta). The clock shows
  `SimulationRunner.pacedSimulationTime()`, which moves smoothly between events.
- CENTER: a `JSplitPane` — canvas on top, `bottomTabs` below (Path Preview, Warnings (n),
  Timeline). The mode enables the tabs that have content; a CRITICAL warning selects Warnings.
- EAST: `SideDock`, 320 px — Simulation Speed, Metrics (KPI), Dispatcher, Conflict Resolution.
  Editing mode disables the speed and dispatcher controls instead of hiding them.
- SOUTH: `StatusBar` only.
- Start size: `WindowGeometry` (85% of the usable screen, at least 1024x818); the tab area starts at
  30% of the window height. The application (not tests: `windowLayoutModule` is loaded only by
  `main`) saves bounds and tab-area height in `java.util.prefs` (`WindowStateStore`) on exit.

## Run JSON Name of a GUI Run (Goal 6, #1328)

Every GUI start hands a run name to `Frame.startSimulation(runName)`; the STOPPED handler passes it to
`DispatcherRunSummaries.finishAndPersist`, which writes it as the run JSON's `example`:

- `exampleGui <name>` (`Main.runExampleGui`) records the example name (`ExampleNames` constant).
- `simgui <file>` (`Main.loadSimWithGui`) and **Simulation → Start...** (`startSimulationFromFile` in
  `MenuBar.kt`, which takes the name before it clears the current file) record
  `DispatcherRunSummaries.fileRunName`: the file's base name **with** its extension, never a path.
  No example name contains a dot (pinned), so a file with an extension (`vyhybna.xml`) never pools with
  an example; an extensionless file named like an example would. Two files with the same name in
  different directories do pool in the sweep report.

## Verification and Benchmark Tasks

```bash
./gradlew verifyKoinConfiguration   # Koin phase report (also runs in CI)
./gradlew koinStatus                # Quick Koin status
./gradlew printConfig               # Print resolved configuration
./gradlew :desktop-ui:jmh           # JMH benchmarks → build/reports/jmh/
```

Ktlint is disabled for the JMH source set only (wildcard imports and underscore
names are idiomatic there) — the one sanctioned exception to the repo-wide
ktlint rule. See `src/jmh/kotlin/README.md`.

## Package Structure

```
desktop-ui/src/
├── main/kotlin/cz/vutbr/fit/interlockSim/
│   ├── Main.kt              - Application entry point
│   ├── AppMetadata.kt       - Application name, version, window titles
│   ├── ExampleRegistry.kt   - Built-in console and GUI examples
│   ├── AiSweepCommand.kt    - aiSweep CLI mode (grid runner front end)
│   ├── DispatcherRunLifecycle.kt, DispatcherRunSummaries.kt, RunCompletionCheck.kt,
│   │                          SimulationTimeTracker.kt
│   ├── di/                  - Desktop Koin modules
│   └── gui/                 - Swing editor + animated GUI (action/, animation/,
│                              conflict/, gridcanvas/, warning/)
├── test/kotlin/             - Desktop tests (+ testutil/ with its own README.md)
└── jmh/kotlin/              - JMH benchmarks (own README.md)
```

## Notes

- Test utilities guide: `src/test/kotlin/cz/vutbr/fit/interlockSim/testutil/README.md`.
- Docker X11 forwarding on Fedora needs the SELinux modules in `docker-x11/`
  (`sudo semodule -i desktop-ui/docker-x11/docker-x11-complete.pp`); see
  [../docs/FEDORA_DOCKER_X11_SETUP.md](../docs/FEDORA_DOCKER_X11_SETUP.md).
- A `heavyTest` task exists here too; run it only per the root CLAUDE.md
  heavy-test policy.
