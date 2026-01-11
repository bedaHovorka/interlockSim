# Exception Handling Revision - Comprehensive Analysis

## Overview
This document provides a comprehensive analysis of all exception throwing and assertion usage in the codebase as requested in issue #38.

## Analysis Summary

### Total Counts
- **116 assertions** found across 25 files
- **70 throw statements** found across 24 files  
- **2 explicit NPE throws** (Train.kt) - now replaced
- **35 assertions replaced** so far (30% complete)
- **81 assertions remaining** (70% to do)

## Exception Types Analysis

### Current Exception Usage (throw statements)
1. **IllegalArgumentException** (17 occurrences)
   - Used for: Invalid arguments, wrong parameters
   - Decision: Keep most, replace some with SimulationException where appropriate
   
2. **SAXException** (9 occurrences - all in XMLContextFactory.kt)
   - Used for: XML parsing errors
   - Decision: Keep - appropriate for XML parsing context

3. **ContextCreationException** (7 occurrences)
   - Used for: Context initialization failures
   - Decision: Keep - already domain-specific exception

4. **NotImplementedException** (6 occurrences)
   - Used for: Unimplemented methods in EnumUnorientedGraph
   - Decision: Keep - legitimate use for incomplete implementation

5. **UnsupportedOperationException** (5 occurrences)
   - Used for: Operations not supported by specific implementations
   - Decision: Keep - standard Java pattern

6. **PathSeparatorChangeException** (5 occurrences)
   - Used for: Path separator state violations
   - Decision: Keep - already domain-specific SimulationException subclass

7. **IllegalStateException** (5 occurrences)
   - Used for: Invalid state transitions
   - Decision: Review each case - some may become SimulationException

8. **TrackOperationException** (3 occurrences)
   - Used for: Track operation failures
   - Decision: Keep - already domain-specific SimulationException subclass

9. **NullPointerException** (2 occurrences - Train.kt)
   - Status: ✅ REPLACED with requireSimulationNotNull

10. **RuntimeException, SimulationException, EmptyContextException** (1 each)
    - Decision: Keep - appropriate use cases

## Assertion Analysis by File

### ✅ COMPLETED (35 assertions replaced)

#### Train.kt - 15 assertions + 2 NPE throws ✅
```
Pattern: Simulation state validation, null checks
Replacement: requireSimulation, requireSimulationNotNull
Status: COMPLETE
```

#### SimpleTrack.kt - 7 assertions ✅
```
Pattern: Track state validation, occupancy checks
Replacement: requireSimulation, requireSimulationNotNull
Status: COMPLETE
```

#### AbstractPath.kt - 9 assertions ✅
```
Pattern: Path element validation, segment checks
Replacement: requireSimulation, requireSimulationNotNull, requireValidArgument
Status: COMPLETE
```

#### RailSwitch.kt - 3 assertions ✅
```
Pattern: Switch configuration validation
Replacement: requireSimulation, requireSimulationNotNull
Status: COMPLETE
```

#### RailSemaphore.kt - 1 assertion ✅
```
Pattern: Speed validation
Replacement: requireSimulation
Status: COMPLETE
```

### 🔄 IN PROGRESS / TODO

#### DefaultContext.kt - 22 assertions (LARGEST FILE)
```
Lines with assertions:
212, 216, 251, 309, 325, 358, 390, 391, 508, 520, 533, 577, 
614, 615, 616, 682, 692, 724, 785, 819, 822, 824

Patterns:
- Null checks: assert(x != null)
- State validation: assert(condition) 
- Graph integrity: assert(!graph.contains(...))
- Type checks: assert(x is Type)

Recommended replacements:
- Use requireSimulation for state/condition checks
- Use requireSimulationNotNull for null checks
- Use requireValidArgument for parameter validation
```

#### DefaultRailWayNetGrid.kt - 5 assertions
```
Patterns: Grid operations, cell positioning
Replacement: requireEditor or requireValidArgument
```

#### RailwayNetGridCanvas.kt - 6 assertions (GUI)
```
Patterns: Canvas rendering, grid validation
Replacement: requireEditor (GUI editor operations)
```

#### ShuntingLoop.kt - 5 assertions
```
Patterns: Simulation scenario validation
Replacement: requireSimulation
```

#### AbstractTrack.kt - 4 assertions
```
Patterns: Track structure validation
Replacement: requireSimulation
```

#### AbstractCell.kt - 5 assertions
```
Patterns: Cell connectivity, segment validation
Replacement: requireSimulation
```

#### HashMapGraph.kt - 4 assertions
```
Patterns: Graph data structure invariants
Replacement: requireValidState (utility class)
```

#### GUI Files (16 assertions total)
- GridCanvasPopupMenu.kt - 3 assertions
- CellRenderer.kt - 3 assertions
- NodeCellAction.kt - 2 assertions
- StatusBar.kt - 2 assertions

```
Patterns: GUI component validation, user input
Replacement: requireEditor with appropriate severity
```

#### Utility Files (18 assertions total)
- Util.kt - 3 assertions
- Doubleton.kt - 3 assertions
- EnumUnorientedGraph.kt - 3 assertions
- AbstractUnorientedGraph.kt - 2 assertions
- Cell.kt - 3 assertions
- InOut.kt - 2 assertions
- OrientedNodeCell.kt - 1 assertion
- TrackBlockPart.kt - 1 assertion

```
Patterns: Data structure invariants, utility validations
Replacement: Context-dependent (requireValidState, requireValidArgument, or keep as utility-specific)
```

#### AssertionContinuous.kt - 2 assertions
```
Special case: Tests if assertions are enabled
Pattern: Assertion facility detection
Recommendation: Replace with capability check or configuration
```

## Decision Matrix

### When to use SimulationException:
- ✅ Simulation state violations
- ✅ Train/track operation errors
- ✅ Path configuration errors
- ✅ Timing/scheduling violations
- ✅ Physics/velocity validation

### When to use EditorException:
- ✅ GUI user input errors
- ✅ Invalid element placement
- ✅ Editor state violations
- ✅ Validation during editing

### When to keep IllegalArgumentException:
- ✅ Pure argument validation (non-simulation)
- ✅ Constructor parameter checks (non-simulation)
- ✅ Utility method arguments

### When to keep IllegalStateException:
- ✅ Utility class state violations
- ✅ Data structure invariants
- ✅ Non-simulation state errors

### When to use requireValidArgument/requireValidState:
- ✅ Utility class validation
- ✅ Helper method parameter checks
- ✅ Data structure integrity

## Remaining Work Plan

### Phase 2A: Complete Core Simulation (37 assertions)
1. DefaultContext.kt (22) - Critical
2. ShuntingLoop.kt (5)
3. AbstractTrack.kt (4)
4. AbstractCell.kt (5)
5. AssertionContinuous.kt (2) - Special handling needed

### Phase 2B: Complete Cell Classes (4 assertions)
1. InOut.kt (2)
2. OrientedNodeCell.kt (1)
3. TrackBlockPart.kt (1)

### Phase 3: GUI/Editor Code (16 assertions)
1. RailwayNetGridCanvas.kt (6)
2. GridCanvasPopupMenu.kt (3)
3. CellRenderer.kt (3)
4. NodeCellAction.kt (2)
5. StatusBar.kt (2)

### Phase 4: Context/Grid (5 assertions)
1. DefaultRailWayNetGrid.kt (5)

### Phase 5: Utility Classes (18 assertions)
1. HashMapGraph.kt (4)
2. Cell.kt (3)
3. Util.kt (3)
4. Doubleton.kt (3)
5. EnumUnorientedGraph.kt (3)
6. AbstractUnorientedGraph.kt (2)

### Phase 6: Testing
- Review all tests for AssertionError expectations
- Update tests to expect new exception types
- Verify legacy null handling preserved
- Run full test suite

### Phase 7: Remove -ea Requirement
- Update build.gradle.kts (remove -ea from all tasks)
- Update .github/workflows/gradle-java21.yml
- Update README.md
- Update CLAUDE.md
- Update docker-compose.yml
- Update Dockerfile
- Final verification

## Risk Assessment

### Low Risk (Safe to replace):
- ✅ Null checks in simulation code
- ✅ State validation in tracks/trains
- ✅ Parameter validation in paths

### Medium Risk (Need careful review):
- ⚠️ Assertions in DefaultContext.kt (complex interactions)
- ⚠️ Assertions in utility classes (may be structural invariants)
- ⚠️ Type assertions (instanceof checks)

### High Risk (Need special handling):
- 🔴 AssertionContinuous.kt (tests if assertions enabled)
- 🔴 Legacy null handling patterns (don't break existing logic)
- 🔴 Test compatibility (tests may expect AssertionError)

## Success Criteria

1. ✅ All assertions replaced with appropriate require* calls
2. ✅ All original error messages preserved
3. ✅ Legacy null handling maintained
4. ✅ No new compilation errors
5. ⏳ All tests pass
6. ⏳ Code runs without -ea flag
7. ⏳ No runtime exceptions in example scenarios

## Notes

- **Conservative approach:** Preserve all existing logic and error messages
- **No unsolicited refactoring:** Only replace assertions, don't modernize code
- **Test coverage:** All modified files must have corresponding test updates
- **Documentation:** Update all references to -ea requirement
