# Main Window Layout — Side Dock, Split Bar, Tabs (Issue #1421)

**Date:** 2026-11-24 · **Goal:** 6 · **Issue:** #1421 · **Module:** `:desktop-ui`

## Intent

Issue #1421 asks to optimize the size and position of the main window and its parts, following
common UX advice. The reporter sees three problems, all of them real:

1. In simulation mode the railway canvas gets only about 250–300 px of height. The north side
   stacks the toolbar, `ControlPanel`, `SimulationControlPanel` (three rows) and
   `DispatcherControlPanel` (fixed 400x50). The south side stacks `WarningPanel` (120 px),
   `MetricsDashboardPanel`, `EventTimelinePanel` (150 px), `PathPreviewPanel` and `StatusBar`.
   No part can be resized.
2. The window has a fixed size of 1024x818, whatever the screen is, and nothing is remembered.
3. Panels are cut off or hard to read.

## Goals

- The canvas gets most of the window in both modes.
- The user decides how much height the bottom panels get, by dragging a bar.
- The window starts at a size that fits the screen, and remembers size and bar position.
- The layout never jumps when the mode changes (owner decision).

## Non-goals

- No change to simulation, dispatcher or metrics logic. No `sim/` changes.
- No new look-and-feel, no docking framework, no new dependency.

## Anti-goals

- A control that is cut off in the narrow right column.
- Losing a warning: a critical warning must still be impossible to miss.
- Writing user preferences from tests.

## Approaches

| | Approach | Trade-off |
|---|---|---|
| A | Smart window size only (85% of the screen, lower limit 1024x818) | Smallest change; panels still squeeze the canvas |
| B | A + bottom panels in tabs under a drag bar + remembered size | Fixes all three problems; the top still holds three control rows |
| **C** | **B + speed, dispatcher and conflict panels in a right-side column** | **Uses the wide screen; largest change; the narrow column needs panel fixes** |

The owner picked **C**. Recommended was B; C was chosen to also free the top rows.

## Layout

```
+------------------------------------+-----------+
| menu                               |           |
| toolbar | ControlPanel (time/Stop) | SideDock  |
+------------------------------------+ (EAST,    |
|        canvas JScrollPane          |  320 px)  |
|        (resizeWeight = 1.0)        | - Speed   |
|                                    | - KPI     |
+=========== JSplitPane bar =========+ - Dispat. |
| [Path preview][Warnings (n)]       | - Conflict|
| [Timeline]                         |           |
+------------------------------------+-----------+
| StatusBar (SOUTH)                              |
+------------------------------------------------+
```

## Decided by the owner

- Variant C.
- Same frame in both modes: the right column and the tab area always stay. In edit mode the path
  preview is one more tab and the right column shows disabled panels.
- After the first look at the running app: the KPI dashboard goes into the right column, under
  the speed controls (not a tab), and the tab area must start taller than 200 px.

## Builder's calls (can be changed in review)

- NORTH keeps the toolbar and `ControlPanel` only.
- `SideDock` is a vertical `JScrollPane` 320 px wide (`CONFLICT_RESOLUTION_PANEL_WIDTH`), with
  titled sections. In edit mode the speed and dispatcher sections are disabled, not hidden.
  `ConflictResolutionPanel` keeps its own show/hide behaviour inside the dock.
- `SimulationControlPanel` presets go into a grid, so the row fits 320 px. `DispatcherControlPanel`
  drops its fixed 400x50 size and stacks its controls.
- The KPI dashboard shows one figure per line, so it fits the 320 px column.
- Tabs in this order: Path preview, Warnings, Timeline. Edit mode: only Path preview is
  enabled and selected. Simulation mode: Path preview is disabled, Warnings is selected.
- The Warnings tab title shows the count. A CRITICAL warning selects the Warnings tab. The
  status-bar indicator stays as before.
- `JSplitPane`: `resizeWeight = 1.0`, one-touch buttons; the tab area starts at 30% of the window
  height, at least 200 px.
- Start size: 85% of the usable screen, at least 1024x818, at most the screen, centered.
  Headless: 1024x818.
- `java.util.prefs` stores bounds, maximized state and the tab-area height on close. Saved bounds are used
  only when they still overlap a current screen.
