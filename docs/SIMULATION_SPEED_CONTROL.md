# Simulation Speed Control

The animated simulation GUI supports live wall-clock speed control.

The control changes how fast events are presented to the user. It does **not** change simulation semantics, event ordering, or physics calculations.

## Overview

Use speed control when you want to:

- slow the model down for teaching or demos
- run near real time while watching train movement
- fast-forward through long scenarios
- pause temporarily while preparing for deeper Goal 8 debugging workflows

Supported range:

- **0.1x** minimum speed
- **50x** highest one-click preset
- **100x** absolute runner limit

## Quick Start

Start the animated GUI:

```bash
./gradlew runExampleGui
```

Or run the JAR directly after building:

```bash
java -jar build/libs/interlockSim.jar exampleGui shuntingLoop 300
```

For your own XML file, open the desktop UI and use **Simulation → Start...**.

When simulation mode is active, the GUI shows:

- the animated control panel (`Time`, `Status`, `Stop`)
- the speed control panel (`Speed` slider, presets, live speed label)
- the status bar speed indicator when speed is not `1.0x`

## GUI Controls

### Speed slider

- Range: **0.1x to 10.0x**
- Step: **0.1x**
- Best for fine adjustment while the simulation is already running

### Preset buttons

The panel and **Simulation → Speed** menu provide these presets:

- `0.1x`
- `0.5x`
- `1x`
- `2x`
- `5x`
- `10x`
- `50x`

`50x` is intentionally above the slider range. The slider stays clamped at its maximum visual position while the runner continues at `50x`.

### Status indicator

The status bar shows `Speed: X.Xx` whenever the current speed differs from `1.0x`. At the default speed the indicator is hidden to keep the bar uncluttered.

![Simulation speed control panel](images/simulation-speed-control-panel.png)

![Simulation speed status indicator](images/simulation-speed-status-indicator.png)

## Keyboard Shortcuts

Shortcuts are active in **simulation mode** while the application window has focus.

| Shortcut | Action |
| --- | --- |
| `1` | Set speed to `0.5x` |
| `2` | Set speed to `1x` |
| `3` | Set speed to `2x` |
| `4` | Set speed to `5x` |
| `5` | Set speed to `10x` |
| `+` | Increase speed by `×1.5` |
| `-` | Decrease speed by `÷1.5` |
| `Space` | Pause or resume the active simulation |

Notes:

- On many keyboards, `+` requires **Shift+`=`**.
- Numpad `+` and `-` are also supported.

## Common Use Cases

- **Educational demo:** start at `0.5x` or `1x`, then drop to `0.1x` before a switch or semaphore change.
- **Normal observation:** keep the model at `1x` or `2x` and watch the event timeline.
- **Long scenario review:** jump to `10x` or `50x` to move through uneventful periods quickly.
- **Pre-debug pause workflow:** press `Space`, inspect the current state, then resume.

## Technical Details

- Speed control is implemented by `SimulationRunner`.
- The runner wraps `SimulationContext.run()` on a dedicated simulation thread.
- Wall-clock throttling uses `sleep(simDelta / speedMultiplier)`.
- `SimulationController` owns lifecycle, remembers the desired speed, and reapplies it on the next run.
- Swing updates stay on the EDT; simulation execution stays off the EDT.
- Pause blocks the simulation thread without advancing simulation time.

## Limitations

- The slider covers **0.1x to 10x**; preset buttons and the **Simulation → Speed** menu extend this to **50x**.
- To reach values above `50x`, press `+` repeatedly — the runner caps at **100x**.
- Speeds above roughly **10x** are useful for throughput, but animation becomes progressively harder to follow visually.
- At **50x** and above, small simulation deltas often round down to **0 ms sleep**, so execution becomes effectively CPU-bound — expect higher CPU usage and less smooth animation than at `1x`.
- Console-mode runs do not expose the GUI speed controls.

## Troubleshooting

- **I cannot see the controls:** make sure you started the animated GUI, not the console example.
- **The speed indicator disappeared:** that is expected at `1.0x`.
- **`Space` does nothing:** a simulation must be running and the window must have keyboard focus. Click anywhere in the window to regain focus, then try again.
- **The simulation feels too fast to follow:** use a preset such as `1x`, `0.5x`, or `0.1x`.
- **CPU usage is high at very high speeds:** reduce speed to re-enable more wall-clock throttling.
