# CLAUDE.md — :desktop-ui

**Last Updated:** 2026-08-27

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
  (toggles `SimulationRunner.isPaused`; Goal 8 groundwork)

For an XML file loaded in the desktop UI, start the run with
**Simulation → Start...**, then adjust the speed with the slider, the presets,
or the shortcuts above.

Details: [../docs/ANIMATION_ARCHITECTURE.md](../docs/ANIMATION_ARCHITECTURE.md)
and [../docs/SIMULATION_SPEED_CONTROL.md](../docs/SIMULATION_SPEED_CONTROL.md).

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
│   ├── DispatcherRunSummaries.kt, RunCompletionCheck.kt, SimulationTimeTracker.kt
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
