### Objective
Implement pause and single-step functionality to transform the simulator into an interactive lab, enabling detailed analysis of simulation events and a better educational experience.

### Technical Design

#### 1. Architectural Bridge
Introduce a \`SimulationController\` interface in the \`core\` module to allow the simulation engine to communicate with the GUI's control state without creating a circular dependency.

**Interface Definition:**
- \`awaitIfPaused()\`: Blocks the simulation thread if paused.
- \`throttle(simDeltaSeconds: Double)\`: Handles wall-clock pacing.
- \`isPaused()\`: Current pause state.
- \`pollStepEvent()\`: Returns true if a single event step was requested (and resets).
- \`pollStepTime()\`: Returns a time delta if a time-based step was requested (and resets).

#### 2. Core Engine Modifications
Refactor \`DefaultSimulationContext.run()\`:
- Replace the blocking \`sim.run(Double.MAX_VALUE)\` call with a controlled loop.
- In each iteration, the loop will:
    1. Call \`controller.awaitIfPaused()\`.
    2. Determine the next target time based on \`pollStepEvent()\` or \`pollStepTime()\`.
    3. Advance the simulation using \`sim.run(targetTime)\`.
    4. Apply wall-clock throttling via \`controller.throttle()\`.

#### 3. GUI and Runner Implementation
- **\`SimulationRunner\`**: Implement \`SimulationController\`. Add volatile flags for \`stepEventRequested\` and \`stepTimeRequested\`.
- **\`SimulationControlPanel\`**: Add 'Pause/Resume', 'Step Event', and 'Step Time' buttons.
- **\`SimulationKeyBindings\`**:
    - \`Space\`: Toggle Pause.
    - \`S\`: Trigger Step Event.
    - \`T\`: Trigger Step Time.
- **\`StatusBar\`**: Display a \`[PAUSED]\` indicator when the simulation is frozen.

### Verification Plan
- [ ] **Pause/Resume**: Verify that pressing Space immediately stops train movement and the status bar updates.
- [ ] **Event Step**: While paused, pressing \`S\` should advance the simulation by exactly one event and then pause again.
- [ ] **Time Step**: While paused, pressing \`T\` should advance the simulation by the configured delta (e.g., 1s) and then pause again.
- [ ] **Performance**: Ensure the new loop does not introduce jitter or significant overhead during normal high-speed runs.

### Critical Files
- \`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationContext.kt\`
- \`core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt\`
- \`desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/gui/SimulationRunner.kt\`
- \`desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/gui/SimulationControlPanel.kt\`
- \`desktop-ui/src/main/kotlin/cz/vutbr/fit/interlockSim/gui/StatusBar.kt\`
