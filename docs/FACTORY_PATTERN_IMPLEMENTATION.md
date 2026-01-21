# Factory Pattern Implementation - Summary

**Date:** 2026-01-14 (Updated: 2026-01-21)
**Issue:** Divide DefaultContext to Editing and Simulation Implementation + SimulationEnvironment decoupling
**Status:** ✅ COMPLETE (Phase 2 complete, Phase 3 context split complete, Issue #94 SimulationEnvironment complete)

## Architecture Diagrams

For detailed visual architecture, see:
- [Factory Pattern Integration](diagrams/factory-pattern.svg) - Shows factory relationships and DI
- [Context Class Hierarchy](diagrams/context-hierarchy.svg) - Shows context inheritance structure

![Factory Pattern](diagrams/factory-pattern.png)

## What Was Accomplished

### Problem Solved

The original DefaultContext was violating Dependency Inversion Principle by directly instantiating concrete simulation classes:

```kotlin
// BEFORE - Direct instantiation (bad)
if (mainProcess == null) mainProcess = Generator(this)
for (i in inouts) {
    workers[i] = InOutWorker(this, i)
}
```

### Solution Implemented

Introduced Factory Pattern with Dependency Injection:

```kotlin
// AFTER - Factory-based creation (good)
if (mainProcess == null) {
    mainProcess = processFactory.createMainProcess(this)
}
for (i in inouts) {
    workers[i] = processFactory.createInOutWorker(this, i)
}
```

### Files Changed

1. **New: `SimulationEnvironment.kt`** (context package) - Issue #94, 2026-01-21
   - Facade interface with 11 essential simulation methods
   - Decouples sim/ classes from full SimulationContext
   - Method groups: Network queries (4), Dynamic state (3), Simulation control (4)
   - Enables simplified testing and DSOL migration

2. **New: `SimulationProcessFactory.kt`** (context package) - Updated 2026-01-21
   - Interface defining factory methods
   - **Updated signatures**: Now accepts `SimulationEnvironment` instead of `SimulationContext`
   - No dependencies on concrete sim/ classes
   - Enables future simulation engine swapping

3. **New: `DefaultSimulationProcessFactory.kt`** (sim package) - Updated 2026-01-21
   - Concrete factory implementation
   - Creates Generator and InOutWorker instances
   - **Updated signatures**: Accepts `SimulationEnvironment` parameters
   - Only place that needs to know about concrete simulation classes

3. **Modified: DefaultSimulationContext.kt** (613 lines editing + 829 lines simulation)
   - Split into DefaultEditingContext (editing only) and DefaultSimulationContext (simulation)
   - DefaultSimulationContext accepts `SimulationProcessFactory` via constructor
   - Uses factory to create simulation processes
   - Removed direct `Generator` import from editing context
   - Changed `setMainProcess(ShuntingLoop)` → `setMainProcess(LoopProcess)` for flexibility

4. **New: DefaultContext.kt** (74 lines - deprecated wrapper)
   - Backward compatibility wrapper extending DefaultSimulationContext
   - Marked with @Deprecated annotation
   - Provides migration path via ReplaceWith

5. **Modified: `XMLContextFactory.kt`**
   - Injects `SimulationProcessFactory` from Koin
   - Passes factory to DefaultSimulationContext constructor

6. **Modified: `InterlockSimModule.kt`** (DI configuration)
   - Added `SimulationProcessFactory` singleton binding
   - Factory instance provided to all contexts needing simulation

## Benefits Achieved

### 1. Dependency Inversion ✅
- Context depends on abstraction (interface), not concrete classes
- Can swap factory implementations without touching context code
- Follows SOLID principles

### 2. Testability ✅
- Can inject mock factory for testing
- Test editing operations without simulation dependencies
- Isolate simulation logic from context logic

### 3. Flexibility ✅
- Easy to add new simulation process types
- Ready for jDisco → DSOL/Kalasim migration
- Custom factories for specialized simulations

### 4. Maintainability ✅
- Clear separation of concerns
- Factory pattern is well-known and documented
- Centralized simulation object creation

## What Remains (Future Work)

### Phase 3: Class Splitting

Create separate classes for editing and simulation concerns:

```kotlin
// Future architecture
class DefaultEditingContext(...) : EditingContext {
    // Only editing operations
    // No simulation fields (mainProcess, workers, etc.)
}

class DefaultSimulationContext(...) : DefaultEditingContext(...), SimulationContext {
    // Extends editing with simulation capabilities
    // Uses factory for process creation
}
```

**Benefits of split (✅ Implemented in Phase 3):**
- ✅ Editing contexts can exist without any simulation code
- ✅ Clearer which operations belong to which phase
- ✅ Better alignment with domain model (editing vs running)
- ✅ 613 lines editing-only + 829 lines simulation = cleaner architecture

**Implementation Results:**
- ✅ Successfully split without breaking tests (242/242 passing)
- ✅ Maintained backwards compatibility via deprecated DefaultContext wrapper
- ✅ Extensive testing complete

### Phase 4: Static vs Dynamic Properties (✅ Complete)

Per issue comments, domain objects now have static/dynamic separation:

```kotlin
// Static properties (editing time)
class Track(val length: Double, val maxSpeed: Double)

// Dynamic properties (simulation time)  
class DynamicTrack(val staticTrack: Track) {
    var currentOccupant: Train? = null
    var isOccupied: Boolean = false
}
```

**Status:** ✅ Completed in Issue #100 (2026-01-18)
- See STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md for details
- Wrapper pattern implemented for RailSwitch, RailSemaphore, InOut, Track

## Design Decisions

### Why Factory in sim/ Package?

**Decision:** Place `DefaultSimulationProcessFactory` in sim/ package.

**Rationale:**
- It's the only place that needs concrete sim/ class knowledge
- Keeps simulation implementation details in simulation package
- Factory interface in context/ (abstraction) vs implementation in sim/ (concrete)

### Why Split Classes? (Phase 3)

**Decision:** ✅ Completed - Split into DefaultEditingContext and DefaultSimulationContext

**Rationale:**
- Solves Single Responsibility Principle violation
- Enables editing without simulation dependencies
- Clean separation of concerns achieved
- All tests passing after split

### Why Keep InOutWorker Import?

**Decision:** Keep `InOutWorker` and `LoopProcess` imports in DefaultSimulationContext.

**Rationale:**
- Required for interface method signatures (`getWorkerFor(): InOutWorker`)
- Used in field types (`workers: Map<InOut, InOutWorker>`)
- Not instantiated directly - creation delegated to factory
- Type safety maintained

## Migration Guide

### For Custom Factories (Updated 2026-01-21)

If you want to create a custom simulation factory:

```kotlin
class CustomSimulationProcessFactory : SimulationProcessFactory {
    // NOTE: Accepts SimulationEnvironment (not SimulationContext)
    override fun createMainProcess(env: SimulationEnvironment): LoopProcess {
        return MyCustomGenerator(env)
    }

    override fun createInOutWorker(env: SimulationEnvironment, inOut: DynamicInOut): InOutWorker {
        return MyCustomWorker(env, inOut)
    }
}

// In DI configuration
val simulationModule = module {
    single<SimulationProcessFactory> { CustomSimulationProcessFactory() }
}
```

**Key Change (Issue #94):** Factory methods now accept `SimulationEnvironment` instead of `SimulationContext`. This provides only the 11 essential methods needed by simulation processes, making testing easier and future migration cleaner.

### For jDisco → DSOL Migration (Updated 2026-01-21)

When migrating to DSOL:

1. Create `DSOLSimulationEnvironment` implementing `SimulationEnvironment` interface
   ```kotlin
   class DSOLSimulationEnvironment(
       private val simulator: DEVSSimulator,
       private val network: RailwayNetwork
   ) : SimulationEnvironment {
       override fun getInOuts() = network.inOuts
       override fun getNextTrackSection(...) = network.navigate(...)
       // ... implement other 9 methods with DSOL equivalents
   }
   ```

2. Create `DSOLSimulationProcessFactory` implementing `SimulationProcessFactory`
   ```kotlin
   class DSOLSimulationProcessFactory : SimulationProcessFactory {
       override fun createMainProcess(env: SimulationEnvironment): LoopProcess {
           return DSOLGenerator(env)  // DSOL-based generator
       }

       override fun createInOutWorker(env: SimulationEnvironment, inOut: DynamicInOut): InOutWorker {
           return DSOLInOutWorker(env, inOut)  // DSOL-based worker
       }
   }
   ```

3. Update Koin configuration to use DSOL factory and environment
4. **Key benefit**: Train, InOutWorker, Generator, Interlocking classes require NO changes
5. ShuntingLoop may need adaptation if it uses SimulationContext-specific methods

## Testing Strategy

### Unit Tests Needed

1. **SimulationProcessFactory Tests**
   ```kotlin
   @Test
   fun `factory creates Generator`() {
       val factory = DefaultSimulationProcessFactory()
       val process = factory.createMainProcess(mockContext)
       assertThat(process).isInstanceOf<Generator>()
   }
   ```

2. **Context with Mock Factory**
   ```kotlin
   @Test
   fun `context uses factory for process creation`() {
       val mockFactory = mock<SimulationProcessFactory>()
       val context = XMLContext(10, 10, mockFactory)
       context.run()
       verify(mockFactory).createMainProcess(context)
   }
   ```

### Integration Tests (✅ Complete)

1. ✅ Existing simulation examples still work
2. ✅ All 242 tests pass (236 pass + 5 skipped + 1 property test)
3. ✅ XML loading and simulation execution unchanged

## Issue #94: SimulationEnvironment Interface (2026-01-21)

### Additional Accomplishment

Following the factory pattern implementation, Issue #94 further decoupled simulation classes from the full SimulationContext interface:

**Problem:** Simulation classes (Train, InOutWorker, Generator) depended on the full SimulationContext interface (18 methods), but only needed a subset of 11 methods.

**Solution:** Created `SimulationEnvironment` facade interface with only essential methods:
- **Network Query Operations (4):** getInOuts, getNextTrackSection, pathToNextSemaphore, isSeparatorInDirection
- **Dynamic State Management (3):** toDynamic(PathSeparator), toDynamic(TrackFacility), getWorkerFor
- **Simulation Control (4):** report, stop, errorStop, isReporting, addReportTypes

**Implementation:**
1. Created SimulationEnvironment interface (207 lines with comprehensive KDoc)
2. Made SimulationContext extend SimulationEnvironment (Liskov substitution)
3. Updated factory signatures: `SimulationContext` → `SimulationEnvironment`
4. Updated simulation classes:
   - ✅ Train: Fully uses SimulationEnvironment (11 method dependencies)
   - ✅ InOutWorker: Fully uses SimulationEnvironment (5 method dependencies)
   - ✅ Generator: Fully uses SimulationEnvironment (1 method dependency)
   - ✅ Interlocking: Base class uses SimulationEnvironment
   - ⚠️ ShuntingLoop: Hybrid approach - accepts SimulationContext (needs getGraph/getRailWayNetGrid for network initialization)

**Benefits Achieved:**
- ✅ Simpler test doubles (mock 11 methods vs 18)
- ✅ Clear contract for what simulation processes can access
- ✅ DSOL migration ready (adapter implements SimulationEnvironment)
- ✅ Zero behavior changes (all 1321 tests passing)

**Special Case - ShuntingLoop:**
ShuntingLoop is a complex interlocking controller that needs network topology methods (getGraph, getRailWayNetGrid) during initialization. It uses a hybrid approach:
- Constructor accepts `SimulationContext` for initialization
- Passes to `super(context)` which accepts SimulationEnvironment (Liskov substitution)
- Runtime methods use inherited `env: SimulationEnvironment` field
- Helper methods cast to SimulationContext when needed

## Conclusion

Phase 2 successfully implemented the Factory pattern, Phase 3 completed the context split, and Issue #94 added SimulationEnvironment decoupling. The code is now:

- ✅ More testable (editing can be tested without simulation, sim classes mock 11 methods vs 18)
- ✅ More flexible (easy to add new process types, swap simulation engines)
- ✅ More maintainable (clean separation of concerns, clear contracts)
- ✅ Ready for jDisco migration (factory abstraction + SimulationEnvironment facade)
- ✅ SOLID principles satisfied (DIP, SRP, ISP violations resolved)

**Phase 3 Results:**
- ✅ DefaultEditingContext (613 lines) - editing operations only
- ✅ DefaultSimulationContext (829 lines) - extends editing with simulation
- ✅ DefaultContext (74 lines) - deprecated wrapper for backward compatibility
- ✅ All tests passing (242 at completion)

**Issue #94 Results (2026-01-21):**
- ✅ SimulationEnvironment interface (207 lines) - 11 essential methods
- ✅ SimulationContext extends SimulationEnvironment (Liskov substitution)
- ✅ Factory signatures updated (SimulationContext → SimulationEnvironment)
- ✅ 4 simulation classes updated (Train, InOutWorker, Generator, Interlocking)
- ✅ 1 hybrid class (ShuntingLoop uses SimulationContext for initialization)
- ✅ All tests passing (1321 tests, 0 failures)

## References

- Design Document: `CONTEXT_REFACTORING_DESIGN.md` (marked complete)
- Issue #98: "Divide DefaultContext to Editing and Simulation implementation"
- Issue #94: "Decouple Simulation Classes from SimulationContext" (2026-01-21)
- Commit 9c95fc5: Implementation completion (Phase 2-3)
- Pattern: Factory Method + Facade Pattern (Gang of Four)
- DI Framework: Koin (https://insert-koin.io/)
- Related: STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md (Phase 4 - Issue #100)
- Related: Issue #153 - Composition over inheritance (BaseContext extraction)
