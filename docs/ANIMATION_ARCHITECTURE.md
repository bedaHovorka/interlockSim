# Animation Architecture - AnimatedSim Milestone

**Document Version:** 1.0
**Last Updated:** 2026-02-04
**Status:** Complete (AnimatedSim milestone shipped)

---

## Overview

The AnimatedSim architecture provides real-time visual simulation of railway operations with 30 FPS train animation, dynamic track state visualization, and event logging. The system is built on three core principles:

1. **Immutability** - State snapshots are immutable for thread-safe rendering
2. **Event-Driven** - Updates triggered by simulation events (no polling)
3. **Performance** - Optimized caching and efficient update rates

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         AnimatedSim Architecture                          │
└─────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│                          jDisco Simulation Thread                          │
│                                                                            │
│  ┌──────────────────────┐         ┌──────────────────────────────────┐  │
│  │ SimulationContext    │         │ Simulation Processes             │  │
│  │ - PropertyChange     │◄────────┤ - Train (physics, movement)      │  │
│  │   Support            │         │ - InOutWorker (train generation) │  │
│  │ - report() method    │         │ - Generator (scheduling)         │  │
│  └──────────┬───────────┘         └──────────────────────────────────┘  │
│             │                                                              │
│             │ PropertyChangeEvent                                          │
│             │ (on simulation thread)                                       │
└─────────────┼──────────────────────────────────────────────────────────────┘
              │
              │ SwingUtilities.invokeLater {...}
              │ (marshal to EDT)
              ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                        Swing Event Dispatch Thread                         │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ AnimationController (PropertyChangeListener)                        │ │
│  │                                                                      │ │
│  │  ┌──────────────────┐    ┌──────────────────┐    ┌───────────────┐│ │
│  │  │ propertyChange() │───>│ captureState()   │───>│ updateState() ││ │
│  │  │ (from sim thread)│    │ (creates         │    │ (@Volatile)   ││ │
│  │  │                  │    │  immutable       │    │               ││ │
│  │  │ [EDT marshaling] │    │  snapshot)       │    │ currentState  ││ │
│  │  └──────────────────┘    └──────────────────┘    └───────┬───────┘│ │
│  │                                                           │        │ │
│  │  ┌──────────────────────────────────────────────────────┘        │ │
│  │  │ Timer (30 FPS)                                                 │ │
│  │  │ - 33ms interval                                                │ │
│  │  └──────────────────────────────────────────────────────┐        │ │
│  │                                                           │        │ │
│  └───────────────────────────────────────────────────────────┼────────┘ │
│                                                               │          │
│              canvas.repaint() every 33ms                      │          │
│                                                               ▼          │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ RailwayNetGridCanvas.paintComponent(g)                             │ │
│  │                                                                      │ │
│  │  ┌────────────────────────────────────────────────────────────────┐│ │
│  │  │ AnimatedSimulationCellRenderer.render()                        ││ │
│  │  │                                                                 ││ │
│  │  │  state = animationController.getCurrentState()  [thread-safe]  ││ │
│  │  │                                                                 ││ │
│  │  │  for each cell:                                                ││ │
│  │  │    - Query state.trackStates[block] → color (Gray/Yellow/Red) ││ │
│  │  │    - Query state.trainStates[id] → position, velocity         ││ │
│  │  │    - Query state.signalStates[sem] → RED/GREEN                ││ │
│  │  │    - Query state.switchStates[sw] → MAIN/BRANCH               ││ │
│  │  │    - Draw visual representation                                ││ │
│  │  └────────────────────────────────────────────────────────────────┘│ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │ Frame (UI Container)                                                 │ │
│  │                                                                      │ │
│  │  ┌────────────────────┐  ┌──────────────────┐  ┌─────────────────┐│ │
│  │  │ ControlPanel       │  │ EventTimelinePanel│  │ Timer (10 Hz)   ││ │
│  │  │ - Time display     │  │ - Event log       │  │ - Read state    ││ │
│  │  │   (HH:MM:SS.mmm)   │  │ - Type filtering  │  │ - Update time   ││ │
│  │  │ - Status indicator │  │ - Auto-scroll     │  │   display       ││ │
│  │  └────────────────────┘  └──────────────────┘  └─────────────────┘│ │
│  └─────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘

                                 Data Flow Legend:
                                 ──────> Method call
                                 ======> Event/Notification
                                 ······> Timer trigger
```

---

## Component Responsibilities

### SimulationContext (jDisco Thread)
- **Purpose:** Execute discrete-event simulation and propagate state changes
- **Thread:** jDisco simulation thread (NOT EDT)
- **Key Methods:**
  - `report(ReportType, message)` - Trigger PropertyChangeEvent
  - `toDynamic(track)` - Convert static→dynamic state
  - `addPropertyChangeListener()` - Register animation controller

### AnimationController (EDT)
- **Purpose:** Bridge simulation thread and EDT, manage rendering lifecycle
- **Thread:** Swing EDT (enforced via `require()`)
- **Key Responsibilities:**
  - Receive PropertyChangeEvents from simulation thread
  - Marshal state capture to EDT via `SwingUtilities.invokeLater`
  - Drive 30 FPS repaint loop via Swing Timer
  - Manage resource cleanup (timers, listeners, caches)
- **Performance Optimization:**
  - Cache semaphores/switches at startup (avoid O(n²) grid scans)
  - Single grid scan, reused throughout animation

### AnimationState (Immutable Data)
- **Purpose:** Thread-safe state snapshot for rendering
- **Thread Safety:** Immutable data classes, `@Volatile` reference
- **Structure:**
  ```kotlin
  data class AnimationState(
      val simulationTime: Double,
      val trainStates: Map<Int, TrainState>,        // Train ID → position/velocity
      val trackStates: Map<TrackBlock, TrackState>,  // Block → FREE/RESERVED/OCCUPIED
      val signalStates: Map<RailSemaphore, SignalState>, // Semaphore → RED/GREEN
      val switchStates: Map<RailSwitch, SwitchState>     // Switch → MAIN/BRANCH
  )
  ```

### AnimationStateCapture (Stateless Utility)
- **Purpose:** Create immutable state snapshots from SimulationContext
- **Thread:** EDT (called via `SwingUtilities.invokeLater`)
- **Optimization:** Uses pre-built caches from AnimationController

### AnimatedSimulationCellRenderer (EDT)
- **Purpose:** Render cells using AnimationState data
- **Thread:** EDT (called during `paintComponent()`)
- **Key Features:**
  - InOut-based color coding for train direction
  - State-based track coloring (Gray/Yellow/Red)
  - Signal visualization (RED/GREEN semaphores)
  - Switch position display (MAIN/BRANCH)

### Frame + ControlPanel + EventTimelinePanel (EDT)
- **Purpose:** UI container and supplementary displays
- **Thread:** EDT
- **Update Rates:**
  - ControlPanel time: 10 Hz (100ms timer, reads AnimationState)
  - EventTimelinePanel: Real-time event display with filtering

---

## Threading Model

### Critical Thread Safety Rules

1. **SimulationContext methods** - Called on jDisco simulation thread
2. **AnimationController.propertyChange()** - Receives events on simulation thread, marshals to EDT
3. **AnimationController.start/stop()** - Must be called on EDT
4. **All rendering** - Must occur on EDT
5. **State snapshots** - Immutable, safe to read from EDT

### Thread Marshaling Flow

```
jDisco Thread:
  SimulationContext.report("Train 1 position: 42.5m")
    └─> PropertyChangeSupport.firePropertyChange()
          └─> AnimationController.propertyChange() [on simulation thread]
                └─> SwingUtilities.invokeLater {
                      captureAndUpdateState() [now on EDT]
                        └─> AnimationStateCapture.captureState() [EDT]
                              └─> updateState(newState) [EDT, @Volatile write]
                    }

EDT:
  Timer fires every 33ms
    └─> canvas.repaint()
          └─> AnimatedSimulationCellRenderer.render()
                └─> state = controller.getCurrentState() [@Volatile read, thread-safe]
```

---

## Performance Characteristics

### Frame Rates
- **Rendering:** 30 FPS (33ms Swing Timer)
- **Time Display:** 10 Hz (100ms timer, decoupled from rendering)
- **Event Timeline:** Real-time (event-driven)

### Optimization Strategies

1. **Caching (AnimationController)**
   - Cache semaphores/switches at startup (single O(n²) scan)
   - Reuse cached lists for all state captures
   - **Result:** 20-80× faster state updates

2. **Position Calculation (TrainPositionCalculator)**
   - O(1) track block lookup cache
   - **Result:** 2,500× faster position queries

3. **Event-Driven Updates**
   - No polling loops
   - Updates triggered only when simulation state changes
   - **Result:** Zero CPU overhead when simulation paused

### Memory Management
- **Timers:** Stopped and nulled on cleanup (prevent leaks)
- **Listeners:** Unregistered from PropertyChangeSupport
- **Caches:** Cleared on animation stop (allow GC)
- **State Snapshots:** Old snapshots replaced, eligible for GC

---

## State Lifecycle

### Animation Start (Frame → RailwayNetGridCanvas → AnimationController)
```
1. Frame.setContext(simulationContext)
2. RailwayNetGridCanvas.setContext(simulationContext)
   └─> stopAnimation() [cleanup previous]
   └─> startAnimation(simulationContext)
       └─> AnimationController(context, canvas, eventPanel)
       └─> controller.start()
           ├─> context.addPropertyChangeListener(this)
           ├─> Build caches (semaphores, switches)
           ├─> captureAndUpdateState() [initial state]
           └─> repaintTimer.start() [30 FPS]
```

### State Update (Simulation Event → EDT Update)
```
1. SimulationContext.report() [simulation thread]
2. PropertyChangeEvent fired [simulation thread]
3. AnimationController.propertyChange() [simulation thread]
4. SwingUtilities.invokeLater { captureAndUpdateState() } [marshal to EDT]
5. AnimationStateCapture.captureState() [EDT, using caches]
6. updateState(newState) [EDT, @Volatile write]
```

### Rendering (30 FPS Timer → Canvas Paint)
```
1. Timer fires every 33ms [EDT]
2. canvas.repaint() [EDT]
3. RailwayNetGridCanvas.paintComponent(g) [EDT]
4. AnimatedSimulationCellRenderer.render() [EDT]
   ├─> state = controller.getCurrentState() [@Volatile read]
   ├─> Query state.trainStates, trackStates, signalStates, switchStates
   └─> Draw visual representation
```

### Animation Stop (Context Switch or Exit)
```
1. RailwayNetGridCanvas.setContext(editingContext)
   └─> stopAnimation()
       └─> animationController?.stop() [EDT enforced]
           ├─> repaintTimer.stop() [stop 30 FPS timer]
           ├─> context.removePropertyChangeListener(this)
           ├─> semaphoreCache = null, switchCache = null [allow GC]
           └─> isRunning = false

2. Frame.stopAnimationUpdates() [EDT enforced]
   ├─> animationUpdateTimer?.stop() [stop 10 Hz time display timer]
   └─> animationUpdateTimer = null [allow GC]
```

---

## Key Design Decisions

### Why Immutable State Snapshots?
**Problem:** Simulation runs on separate thread from GUI rendering (EDT)
**Solution:** Capture immutable snapshots of simulation state, marshal to EDT
**Benefits:**
- Thread-safe without locking
- Consistent state during entire render cycle
- Simple mental model (snapshot = point in time)

### Why Event-Driven Instead of Polling?
**Problem:** Polling every frame (30 FPS) creates O(n²) overhead scanning grid
**Solution:** Simulation fires PropertyChangeEvents when state changes
**Benefits:**
- Zero overhead when simulation paused
- Updates only when necessary
- 20-80× performance improvement with caching

### Why 30 FPS Rendering + 10 Hz Time Display?
**Rendering (30 FPS):**
- Smooth train movement
- Standard for real-time visualization
- 33ms interval perceptually smooth for human eye

**Time Display (10 Hz):**
- Responsive enough for time feedback
- Lower rate reduces CPU overhead
- Decoupled from rendering loop

### Why Separate NavigationServices? (Issue #292)
**Problem:** Original `pathToNextSemaphore()` mixed three concerns:
1. Static topology navigation (graph traversal)
2. Dynamic reservation (dispatcher logic)
3. Train navigation (ownership validation)

**Solution:** Three specialized services (see PATH_DISCOVERY_ARCHITECTURE.md)
**Benefits for Animation:**
- Clean state queries for rendering
- Proper ownership tracking (no race conditions)
- Simplified animation state capture

---

## Integration Points

### With Simulation Core
- **SimulationContext.report()** - Trigger events for animation updates
- **PropertyChangeSupport** - Event propagation mechanism
- **DynamicTrack/DynamicRailSemaphore** - Dynamic state wrappers

### With GUI Framework
- **Swing Timer** - Drive 30 FPS rendering, 10 Hz time display
- **SwingUtilities.invokeLater** - Thread marshaling from simulation to EDT
- **JComponent.paintComponent()** - Render entry point

### With Navigation Services (Issue #292)
- **TopologyNavigator** - Static graph traversal for validation
- **PathReservationService** - Track ownership for coloring
- **TrainNavigationService** - Train path visualization

---

## Testing Strategy

### Automated Tests (1,321+ tests)
- **Unit Tests:** AnimationState, AnimationStateCapture, TrainPositionCalculator
- **Integration Tests:** AnimationIntegrationTest (end-to-end animation)
- **Golden Output:** Validates simulation behavior unchanged
- **Regression Tests:** Zero failures after architectural changes

### Manual Testing (Issue #273)
- **Resource Cleanup:** Verified via code review (timers, listeners, caches)
- **Code Quality:** Zero detekt violations, 100% KDoc coverage
- **Visual Quality:** 30 FPS confirmed, smooth animation, correct coloring

---

## Known Limitations

1. **jDisco Framework Constraints**
   - Simulations cannot be paused (only started/stopped)
   - No built-in rewind/fast-forward capabilities
   - Future migration to DSOL/Kalasim planned (LONG_TERM_GOALS.md)

2. **Rendering Performance**
   - No anti-aliasing (optional future enhancement)
   - No train tooltips (velocity/acceleration) - optional enhancement

3. **Event Filtering**
   - EventTimelinePanel filters by type only
   - No search/regex filtering (future enhancement)

---

## Future Enhancements (Post-Milestone)

1. **Anti-Aliasing** - Smooth train/track rendering
2. **Train Tooltips** - Show velocity/acceleration on hover
3. **Playback Controls** - Pause/resume (requires jDisco migration)
4. **Event Search** - Regex filtering in EventTimelinePanel
5. **Camera Controls** - Pan/zoom for large networks
6. **Export** - Save animation as video/GIF

---

## References

### Primary Documentation
- `docs/IMPLEMENTATION_SUMMARY_ISSUE_205.md` - Complete implementation guide
- `docs/MANUAL_TEST_PLAN_ISSUE_205.md` - Testing procedures
- `docs/ISSUE_273_TEST_EXECUTION_REPORT.md` - Quality verification report
- `docs/ANIMATED_SIM_MILESTONE_PREP.md` - Milestone retrospective
- `docs/ANIMATED_SIM_STATUS_MEETING_2026_02_04.md` - Team status summary

### Related Architecture
- `docs/PATH_DISCOVERY_ARCHITECTURE.md` - Navigation services design
- `docs/PATH_DISCOVERY_MIGRATION_GUIDE.md` - API migration guide
- `docs/PATH_RESERVATION_ARCHITECTURE.md` - Reservation system design

### Source Code
```
src/main/kotlin/cz/vutbr/fit/interlockSim/gui/animation/
├── AnimationController.kt         (375 lines)
├── AnimationState.kt              (data classes)
├── AnimationStateCapture.kt       (state capture logic)
├── AnimatedSimulationCellRenderer.kt
├── ControlPanel.kt                (time display)
├── EventTimelinePanel.kt          (event log)
├── SimulationEvent.kt             (event data)
├── TrainPositionCalculator.kt     (position calculation)
└── AnimationColors.kt             (color constants)
```

---

## Glossary

- **EDT** - Event Dispatch Thread (Swing GUI thread)
- **FPS** - Frames Per Second (animation frame rate)
- **jDisco** - Discrete/continuous simulation library by Keld Helsgaun
- **PropertyChangeSupport** - Java Beans event propagation mechanism
- **InOut** - Entry/exit point in railway network
- **Semaphore** - Railway signal (RED/GREEN)
- **FREE** - Track block available for reservation
- **RESERVED** - Track block reserved for specific train (Yellow)
- **OCCUPIED** - Track block currently occupied by train (Red)

---

**Document Status:** ✅ COMPLETE
**Milestone:** AnimatedSim (2026-01-22 to 2026-02-04)
**Version:** 1.0 (shipped with AnimatedSim milestone)

---

**END OF DOCUMENT**
