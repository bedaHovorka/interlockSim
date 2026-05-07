# Simulation Speed Control

This branch does **not** expose interactive Goal 7 speed controls in the desktop GUI yet.

What is currently available is a fixed real-time animated simulation mode for built-in GUI examples. This document describes the current user-visible behavior so the desktop UI is not documented more broadly than it is implemented.

## Current Status

The animated GUI currently provides:

- a top `ControlPanel` with **Time** and **Status**
- an `EventTimelinePanel` for simulation events
- real-time synchronization for the built-in GUI example path

The animated GUI currently does **not** provide:

- a speed slider
- speed preset buttons
- speed-related menu items
- keyboard shortcuts for speed changes
- pause/resume controls
- a speed indicator in the status bar during simulation mode

## Quick Start

Launch the animated GUI example:

```bash
./gradlew runExampleGui
```

Or run the JAR directly after building:

```bash
java -jar build/libs/interlockSim.jar exampleGui shuntingLoop 300
```

## Current GUI Behavior

When `exampleGui` starts, the application shows:

- the animated railway canvas
- the top control panel with `Time` and `Status`
- the event timeline

During simulation mode, `Frame` hides the `StatusBar`, so there is no separate speed readout in the current UI.

## What “Speed Control” Means on This Branch

For the built-in animated GUI example, `ExampleRegistry` creates `ShuntingLoop` with:

- `enableRealTimeSync = true`
- `speedMultiplier = 1.0`

That means the current GUI example runs at a fixed real-time rate intended for observation, not live user adjustment.

The console example path still runs without this GUI-specific real-time synchronization.

## Technical Details

- `exampleGui` launches the animated desktop UI from `Main.runExampleGui(...)`.
- The GUI example uses `ShuntingLoop(..., enableRealTimeSync = true, speedMultiplier = 1.0)`.
- `Main.runExampleGui(...)` starts `context.run()` on a background thread.
- `Frame` updates the animated `ControlPanel` time display with a 10 Hz Swing timer.
- `Frame.switchToSimulationMode()` hides `StatusBar` and shows `ControlPanel` plus `EventTimelinePanel`.
- A standalone `SimulationRunner` class exists in `desktop-ui`, but it is not wired into `Frame` or the current animated GUI flow on this branch.

## Limitations

- No runtime speed adjustment is available in the desktop UI.
- No keyboard shortcut support is available for speed changes or pause/resume.
- No speed indicator is visible during simulation mode because `StatusBar` is hidden there.
- The built-in animated GUI example is fixed to `1.0x` real-time synchronization.

## Troubleshooting

- **I expected a speed slider or preset buttons:** they are not part of the current GUI on this branch.
- **I cannot find a speed indicator:** the status bar is hidden in simulation mode.
- **The simulation seems fixed to real time:** that is the current behavior of `exampleGui`.
- **I need a faster run:** use the console example path when animation is not required.
