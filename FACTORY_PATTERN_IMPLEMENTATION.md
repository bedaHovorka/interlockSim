# Factory Pattern Implementation - Summary

**Date:** 2026-01-14  
**Issue:** Divide DefaultContext to Editing and Simulation Implementation  
**Status:** Phase 2 Complete - Factory Pattern Implemented

## What Was Accomplished

### Problem Solved

DefaultContext was violating Dependency Inversion Principle by directly instantiating concrete simulation classes:

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

1. **New: `SimulationProcessFactory.kt`** (context package)
   - Interface defining factory methods
   - No dependencies on concrete sim/ classes
   - Enables future simulation engine swapping

2. **New: `DefaultSimulationProcessFactory.kt`** (sim package)
   - Concrete factory implementation
   - Creates Generator and InOutWorker instances
   - Only place that needs to know about concrete simulation classes

3. **Modified: `DefaultContext.kt`**
   - Accepts `SimulationProcessFactory` via constructor
   - Uses factory to create simulation processes
   - Removed direct `Generator` import
   - Changed `setMainProcess(ShuntingLoop)` → `setMainProcess(LoopProcess)` for flexibility

4. **Modified: `XMLContextFactory.kt`**
   - Injects `SimulationProcessFactory` from Koin
   - Passes factory to DefaultContext constructor

5. **Modified: `InterlockSimModule.kt`** (DI configuration)
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

**Benefits of split:**
- Editing contexts can exist without any simulation code
- Clearer which operations belong to which phase
- Better alignment with domain model (editing vs running)

**Complexity:**
- Large refactoring (DefaultContext is 984 lines)
- Need to carefully split fields and methods
- Must maintain backwards compatibility
- Extensive testing required

### Phase 4: Static vs Dynamic Properties

Per issue comments, domain objects should eventually have:

```kotlin
// Static properties (editing time)
class Track(val length: Double, val maxSpeed: Double)

// Dynamic properties (simulation time)  
class DynamicTrack(val staticTrack: Track) {
    var currentOccupant: Train? = null
    var isOccupied: Boolean = false
}
```

This is a larger refactoring and should be a separate issue.

## Design Decisions

### Why Factory in sim/ Package?

**Decision:** Place `DefaultSimulationProcessFactory` in sim/ package.

**Rationale:**
- It's the only place that needs concrete sim/ class knowledge
- Keeps simulation implementation details in simulation package
- Factory interface in context/ (abstraction) vs implementation in sim/ (concrete)

### Why Not Split Classes Now?

**Decision:** Defer class splitting to Phase 3 (future PR).

**Rationale:**
- Conservative approach - make minimal changes
- Factory pattern already solves the main SOLID violations
- Large refactoring requires extensive testing
- Gradual approach reduces risk

### Why Keep InOutWorker Import?

**Decision:** Keep `InOutWorker` and `LoopProcess` imports in DefaultContext.

**Rationale:**
- Required for interface method signatures (`getWorkerFor(): InOutWorker`)
- Used in field types (`workers: Map<InOut, InOutWorker>`)
- Not instantiated directly - creation delegated to factory
- Type safety maintained

## Migration Guide

### For Custom Factories

If you want to create a custom simulation factory:

```kotlin
class CustomSimulationProcessFactory : SimulationProcessFactory {
    override fun createMainProcess(context: SimulationContext): LoopProcess {
        return MyCustomGenerator(context)
    }
    
    override fun createInOutWorker(context: SimulationContext, inOut: InOut): InOutWorker {
        return MyCustomWorker(context, inOut)
    }
}

// In DI configuration
val simulationModule = module {
    single<SimulationProcessFactory> { CustomSimulationProcessFactory() }
}
```

### For jDisco → DSOL Migration

When migrating to DSOL:

1. Create `DSOLSimulationProcessFactory` implementing `SimulationProcessFactory`
2. Update return types if DSOL process types differ
3. Update Koin configuration to use DSOL factory
4. All context code continues to work unchanged

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

### Integration Tests Needed

1. Existing simulation examples still work
2. All 662 tests pass
3. XML loading and simulation execution unchanged

## Conclusion

Phase 2 successfully implemented the Factory pattern, addressing the core SOLID violations in DefaultContext. The code is now:

- ✅ More testable
- ✅ More flexible
- ✅ More maintainable
- ✅ Ready for future refactoring
- ✅ Ready for jDisco migration

The full class split (Phase 3) can be done incrementally in future PRs once this foundation is validated through testing.

## References

- Design Document: `CONTEXT_REFACTORING_DESIGN.md`
- Issue: "Divide DefaultContext to Editing and Simulation implementation"
- Pattern: Factory Method (Gang of Four)
- DI Framework: Koin (https://insert-koin.io/)
