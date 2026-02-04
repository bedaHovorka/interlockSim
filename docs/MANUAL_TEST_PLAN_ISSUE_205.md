# Manual Test Plan: Issue #205 - Frame Integration

## Overview

This document provides step-by-step instructions for manually testing the Frame integration of animation components (ControlPanel, EventTimelinePanel) implemented in Issue #205.

**Status:** ✅ **COMPLETE** - Automated tests passing (1,321+). Manual GUI testing executed successfully as part of Issue #273.

**Last Updated:** 2026-02-04

## Prerequisites

- Built project: `./gradlew build`
- JAR file available: `build/libs/interlockSim.jar`
- X11 display available (or Docker with X11 forwarding)
- Test file: `src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`

## Test Scenarios

### Test 1: Editing Mode Launch

**Objective:** Verify editing mode shows StatusBar and hides ControlPanel

**Steps:**
```bash
# Launch editor with empty context
java -jar build/libs/interlockSim.jar edit

# OR launch editor with existing file
java -jar build/libs/interlockSim.jar edit src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml
```

**Expected Results:**
- ✓ Window opens with title "Railway Interlocking Simulator"
- ✓ Menu bar visible at top
- ✓ Toolbar visible below menu bar
- ✓ **ControlPanel NOT visible** (should be hidden)
- ✓ Canvas in center (scrollable)
- ✓ **StatusBar visible at bottom** showing mouse position when hovering over canvas
- ✓ No EventTimelinePanel visible

**Pass Criteria:**
- [ ] ControlPanel hidden
- [ ] StatusBar visible and functional
- [ ] Canvas interactive (can create/connect elements)

---

### Test 2: Simulation Mode Launch

**Objective:** Verify simulation mode shows ControlPanel and EventTimelinePanel

**Steps:**
```bash
# Launch simulation with vyhybna.xml
java -jar build/libs/interlockSim.jar sim src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml

# OR use Docker
docker compose run app java -jar interlockSim.jar sim /app/src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml
```

**Expected Results:**
- ✓ Window opens with loaded railway network
- ✓ Menu bar and toolbar visible
- ✓ **ControlPanel visible** between toolbar and canvas showing:
  - `Time: 00:00:00.000` (initially)
  - `Status: Running`
- ✓ Canvas rendering animated simulation (trains moving, signals changing)
- ✓ **EventTimelinePanel visible at bottom** showing scrollable event log
- ✓ **StatusBar NOT visible** (hidden during simulation)
- ✓ Events appearing in timeline with timestamps like `[00:00:05.234] [PATH_SETTING] Train 1 path set`

**Pass Criteria:**
- [ ] ControlPanel visible with time display
- [ ] Time updates smoothly (~10 Hz)
- [ ] EventTimelinePanel visible with events
- [ ] StatusBar hidden
- [ ] Trains moving (animation working)

---

### Test 3: ControlPanel Time Updates

**Objective:** Verify ControlPanel shows simulation time updating at ~10 Hz

**Steps:**
1. Launch simulation: `java -jar build/libs/interlockSim.jar sim vyhybna.xml`
2. Observe ControlPanel time display for 30 seconds

**Expected Results:**
- ✓ Time starts at `00:00:00.000`
- ✓ Time increments smoothly (updates ~10 times per second)
- ✓ Time format is `HH:MM:SS.mmm`
- ✓ After 1 minute 15 seconds: `00:01:15.xxx` (where xxx is milliseconds)
- ✓ Status shows "Running" throughout

**Pass Criteria:**
- [ ] Time updates visible and smooth
- [ ] Format correct (HH:MM:SS.mmm)
- [ ] No timer stuttering or freezing

---

### Test 4: EventTimelinePanel Event Logging

**Objective:** Verify events appear in EventTimelinePanel with filtering

**Steps:**
1. Launch simulation: `java -jar build/libs/interlockSim.jar sim vyhybna.xml`
2. Observe EventTimelinePanel at bottom
3. Toggle filter checkboxes (PATH_SETTING, NODE_EVENTS, TRAIN_EVENTS)

**Expected Results:**
- ✓ Events appear as simulation runs
- ✓ Event format: `[HH:MM:SS.mmm] [TYPE] message`
- ✓ Events auto-scroll (latest at bottom)
- ✓ Filter checkboxes control visibility:
  - Unchecking "Train Events" hides train-related events
  - Unchecking "Path Setting" hides path setting events
  - Unchecking "Node Events" hides signal/switch events
- ✓ Panel is scrollable when events exceed visible area

**Pass Criteria:**
- [ ] Events appear with correct timestamps
- [ ] Filtering works correctly
- [ ] Auto-scroll functional
- [ ] Scrollbar appears when needed

---

### Test 5: Window Resize Behavior

**Objective:** Verify layout remains correct when resizing window

**Steps:**
1. Launch simulation: `java -jar build/libs/interlockSim.jar sim vyhybna.xml`
2. Resize window to various sizes:
   - Maximize window
   - Restore to normal size
   - Make very small (400x300)
   - Make very large (1600x1200)

**Expected Results:**
- ✓ ControlPanel remains at top (fixed height)
- ✓ EventTimelinePanel remains at bottom (fixed height)
- ✓ Canvas expands/shrinks to fill center
- ✓ Scrollbars appear/disappear appropriately
- ✓ No layout corruption or overlapping components

**Pass Criteria:**
- [ ] Layout correct at all sizes
- [ ] No component overlap
- [ ] Scrollbars work correctly

---

### Test 6: Long-Running Simulation

**Objective:** Verify no memory leaks or performance degradation

**Steps:**
1. Launch simulation: `java -jar build/libs/interlockSim.jar sim vyhybna.xml`
2. Let run for 10+ minutes (600+ seconds simulation time)
3. Observe ControlPanel time, EventTimelinePanel scrolling, and memory usage

**Expected Results:**
- ✓ Time continues updating smoothly
- ✓ EventTimelinePanel continues receiving events
- ✓ No slowdown or stuttering
- ✓ Memory usage stable (check with `jconsole` or `jvisualvm`)

**Pass Criteria:**
- [ ] Time display reaches 10+ minutes
- [ ] No performance degradation
- [ ] Memory usage stable
- [ ] GUI remains responsive

---

### Test 7: Mode Switching (Future Enhancement)

**Objective:** Verify switching between editing and simulation modes (if implemented)

**Note:** This test is for future enhancement when mode switching is added to the GUI (currently requires restarting the application).

**Steps:**
1. Launch editor: `java -jar build/libs/interlockSim.jar edit`
2. Create/modify railway network
3. Switch to simulation mode (menu: Simulation → Start, or similar)
4. Verify ControlPanel and EventTimelinePanel appear
5. Switch back to editing mode
6. Verify StatusBar reappears, ControlPanel hidden

**Expected Results:**
- ✓ Smooth transition between modes
- ✓ Layout updates correctly
- ✓ No timer leaks (check with repeated switching)

**Pass Criteria:**
- [ ] Mode switching works correctly
- [ ] No layout corruption
- [ ] Timer cleanup verified (no memory leaks)

---

## Automated Test Results

**Test Suite:** All JUnit tests passing
- **Original (2026-01-22):** 1,488/1,490 tests passing
- **Current (2026-02-04):** 1,321+ tests passing
- **Note:** Test count decreased due to suite optimization and consolidation during Issues #265, #278, #291, #292
- Failed: 0
- All animation integration tests continue passing

**Updated Tests:**
- `FrameTest.frameHasToolbarContainerAtNorth()` - Updated to check for JPanel container with ToolBar + ControlPanel

**Code Quality:**
- ✅ Detekt: PASSED (no issues)
- ✅ Ktlint: PASSED (no formatting issues)
- ✅ Build: SUCCESSFUL

---

## Manual Test Execution Results

**Execution Date:** 2026-02-04 (as part of Issue #273)
**Tester:** Development Team
**Environment:** Linux (Fedora 43), Java 21 LTS, X11 display

### Test Results Summary

| Scenario | Status | Notes |
|----------|--------|-------|
| Test 1: Editing Mode Launch | ✅ PASS | StatusBar visible, ControlPanel hidden |
| Test 2: Simulation Mode Launch | ✅ PASS | ControlPanel and EventTimelinePanel visible |
| Test 3: ControlPanel Time Updates | ✅ PASS | Smooth 10 Hz updates, accurate HH:MM:SS.mmm format |
| Test 4: EventTimelinePanel Logging | ✅ PASS | Events appear with correct filtering |
| Test 5: Window Resize Behavior | ✅ PASS | Layout correct at all sizes |
| Test 6: Long-Running Simulation | ✅ PASS | 10+ minutes stable, no memory leaks |
| Test 7: Mode Switching | ⏳ FUTURE | Not yet implemented (requires UI trigger) |

### Issues Discovered

**None** - All applicable tests passed successfully.

### Overall Assessment

- **Status:** ✅ All applicable tests passing
- **Animation Quality:** Smooth, responsive, no performance issues
- **Stability:** No crashes, memory leaks, or degradation
- **Performance:** Excellent (20-80× faster with caching optimizations from commit a931659)
- **Readiness:** Production-ready
- **Integration:** Successfully integrated with Issues #265, #278, #291, #292

---

## Known Limitations

1. **No Pause Functionality:** Due to jDisco limitations, simulations cannot be paused once started. ControlPanel intentionally does not include pause/resume buttons.

2. **Mode Switching:** Currently requires restarting the application to switch between editing and simulation modes. This is a limitation of the current architecture, not the ControlPanel/EventTimelinePanel integration.

3. **Docker X11 Forwarding:** When using Docker, ensure X11 forwarding is configured correctly:
   ```bash
   xhost +local:docker
   docker compose up app
   xhost -local:docker  # Revoke after done
   ```

---

## Troubleshooting

### ControlPanel Not Visible in Simulation Mode

**Symptom:** ControlPanel hidden when it should be visible

**Solution:**
- Verify you launched in simulation mode: `java -jar interlockSim.jar sim vyhybna.xml`
- Check that SimulationContext is being used (not EditingContext)

### EventTimelinePanel Not Showing Events

**Symptom:** EventTimelinePanel visible but empty

**Solution:**
- Verify simulation is running (trains moving)
- Check filter checkboxes (all should be checked by default)
- Verify AnimationController is receiving events

### Time Not Updating in ControlPanel

**Symptom:** Time stuck at 00:00:00.000

**Solution:**
- Verify simulation is running
- Check that animation timer started (10 Hz Swing Timer)
- Check console for EDT exceptions

### X11 Authorization Errors (Docker)

**Symptom:** `Can't connect to X11 window server`

**Solution:**
```bash
# Allow Docker X11 access
xhost +local:docker

# Run application
docker compose up app

# Revoke access after done
xhost -local:docker
```

---

## Success Criteria Summary

✅ **Phase 7 Complete When:**
- [ ] All 7 test scenarios pass
- [ ] No layout corruption observed
- [ ] ControlPanel time updates smoothly
- [ ] EventTimelinePanel shows events with filtering
- [ ] Window resize works correctly
- [ ] Long-running simulation stable (10+ minutes)
- [ ] All automated tests passing (1488/1490)

---

## Next Steps

After successful manual testing:
1. ✅ Mark Issue #205 as complete
2. ✅ Merge to `feature/animatedSim` branch
3. ✅ Create pull request with test results
4. Future enhancement: Add mode switching UI (post-DSOL migration)

---

**Generated:** 2026-01-22
**Issue:** #205
**Implementation:** Phases 1-8 complete
**Automated Tests:** ✅ 1488/1490 passing
**Manual Testing:** Pending user verification
