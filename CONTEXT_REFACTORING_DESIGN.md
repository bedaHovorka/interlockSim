# Context Refactoring Design Document

**Issue:** Divide DefaultContext to Editing and Simulation Implementation  
**Date:** 2026-01-14  
**Status:** Design Phase

## Problem Statement

`DefaultContext` currently violates several SOLID principles:

1. **Dependency Inversion Violation**: Directly instantiates concrete simulation classes (`Generator`, `InOutWorker`, `ShuntingLoop`)
2. **Single Responsibility Violation**: Handles both editing and simulation concerns
3. **Tight Coupling**: Context package depends on sim/ package concrete implementations
4. **Testing Issues**: Cannot test context without simulation dependencies

### Problematic Code

```kotlin
// DefaultContext.kt lines 26-29
import cz.vutbr.fit.interlockSim.sim.Generator
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop

// DefaultContext.kt lines 787-788
if (mainProcess == null) mainProcess = Generator(this)

// DefaultContext.kt lines 794-796
for (i in inouts) {
    workers[i] = InOutWorker(this, i)
}

// DefaultContext.kt lines 963-965
fun setMainProcess(loop: ShuntingLoop) {
    mainProcess = loop
}
```

## Design Constraints

### Hard Constraints

1. **sim/ Package Restriction**: Cannot add Koin DI to sim/ package (traffic-simulation-expert requirement)
2. **jDisco Migration**: Future migration to DSOL/Kalasim planned - design must accommodate this
3. **Test Compatibility**: All 662 tests must pass
4. **Backwards Compatibility**: Existing code using contexts must continue to work
5. **Conservative Changes**: Minimal modifications to working code

### Soft Constraints

1. **Performance**: No significant performance degradation
2. **Maintainability**: Clear separation of concerns
3. **Extensibility**: Easy to add new simulation process types

## Proposed Solution

### Overview

Use **Factory Pattern** with **Dependency Injection** to abstract simulation object creation:

1. Create `SimulationProcessFactory` interface for process creation
2. Implement concrete factory with current simulation classes
3. Inject factory into contexts that need simulation
4. Keep editing contexts free of simulation dependencies

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    Context Interfaces                       │
├─────────────────────────────────────────────────────────────┤
│  Context  ◄──  EditingContext  ◄──  SimulationContext      │
└─────────────────────────────────────────────────────────────┘
                      ▲                        ▲
                      │                        │
         ┌────────────┴────────────┬───────────┴──────────────┐
         │                         │                          │
┌────────────────────┐   ┌─────────────────────┐   ┌──────────────────────┐
│DefaultEditingContext│   │DefaultSimulationContext│   │ SimulationProcess   │
│                    │   │                     │   │      Factory         │
│ - No sim imports   │   │ - Uses factory      │   │                      │
│ - Editing ops only │   │ - Simulation ops    │   │ + createMain(...): LoopProcess│
│                    │   │ - Delegates to      │   │ + createWorker(...): InOutWorker│
│                    │   │   factory           │   └──────────────────────┘
└────────────────────┘   └─────────────────────┘              ▲
                                                               │
                                                  ┌────────────┴────────────┐
                                                  │ DefaultSimulationProcess│
                                                  │        Factory          │
                                                  │                         │
                                                  │ - Creates Generator     │
                                                  │ - Creates InOutWorker   │
                                                  │ - Knows sim/ classes    │
                                                  └─────────────────────────┘
```

## Detailed Design

### Phase 1: Create Factory Interface

Create `SimulationProcessFactory` interface in context package (no dependency on sim/):

```kotlin
// context/SimulationProcessFactory.kt
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.InOutWorker

/**
 * Factory interface for creating simulation processes.
 * 
 * Decouples context from concrete simulation class implementations.
 * Allows different simulation engines to be plugged in.
 * 
 * Future: Will be updated when migrating from jDisco to DSOL/Kalasim.
 */
interface SimulationProcessFactory {
    /**
     * Create the main simulation process (e.g., Generator)
     * 
     * @param context The simulation context
     * @return Main process for the simulation
     */
    fun createMainProcess(context: SimulationContext): LoopProcess
    
    /**
     * Create worker process for an InOut point
     * 
     * @param context The simulation context
     * @param inOut The InOut point to create worker for
     * @return Worker process for the InOut
     */
    fun createInOutWorker(context: SimulationContext, inOut: InOut): InOutWorker
}
```

**Note**: This interface still references `LoopProcess` and `InOutWorker` from sim/, but:
1. The context package doesn't *create* these directly
2. Only the factory implementation needs sim/ knowledge
3. Easy to swap factory implementations during jDisco migration

### Phase 2: Implement Default Factory

Create concrete factory in sim/ package (knows about concrete classes):

```kotlin
// sim/DefaultSimulationProcessFactory.kt
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut

/**
 * Default implementation using jDisco-based simulation classes.
 * 
 * This factory knows about concrete Generator and InOutWorker classes.
 * Future: Replace with DSOL/Kalasim implementation.
 */
class DefaultSimulationProcessFactory : SimulationProcessFactory {
    override fun createMainProcess(context: SimulationContext): LoopProcess {
        return Generator(context)
    }
    
    override fun createInOutWorker(context: SimulationContext, inOut: InOut): InOutWorker {
        return InOutWorker(context, inOut)
    }
}
```

### Phase 3: Split DefaultContext

#### 3A: Create DefaultEditingContext

```kotlin
// context/DefaultEditingContext.kt
package cz.vutbr.fit.interlockSim.context

/**
 * Default implementation of EditingContext.
 * 
 * Handles mutable railway network editing:
 * - Adding/removing cells
 * - Joining cells with track blocks
 * - Moving cells
 * - Configuration (max speed, track length, names)
 * 
 * Does NOT contain simulation-specific logic.
 */
open class DefaultEditingContext(
    cols: Int,
    rows: Int
) : EditingContext {
    // Most of current DefaultContext implementation
    // WITHOUT:
    // - mainProcess field
    // - workers map
    // - run() method
    // - stop() method
    // - errorStop() method
    // - getWorkerFor() method
    // - setMainProcess() method
    // - report() methods
    // - All simulation-specific fields
    
    // All grid operations, path finding, graph management stay here
}
```

#### 3B: Create DefaultSimulationContext

```kotlin
// context/DefaultSimulationContext.kt
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess

/**
 * Default implementation of SimulationContext.
 * 
 * Extends DefaultEditingContext with simulation capabilities:
 * - Running simulation
 * - Managing simulation processes
 * - Simulation reporting
 * 
 * Uses SimulationProcessFactory for process creation (no direct dependencies).
 */
class DefaultSimulationContext(
    cols: Int,
    rows: Int,
    private val processFactory: SimulationProcessFactory
) : DefaultEditingContext(cols, rows), SimulationContext {
    
    private var mainProcess: LoopProcess? = null
    private var workers: MutableMap<InOut, InOutWorker> = IdentityHashMap()
    
    override fun run() {
        // Validation...
        
        // Use factory instead of direct instantiation
        if (mainProcess == null) {
            mainProcess = processFactory.createMainProcess(this)
        }
        
        for (inOut in inouts) {
            workers[inOut] = processFactory.createInOutWorker(this, inOut)
        }
        
        // Activate process...
    }
    
    // All simulation-specific methods
    override fun stop() { /* ... */ }
    override fun errorStop(error: Throwable) { /* ... */ }
    override fun getWorkerFor(inOut: InOut): InOutWorker { /* ... */ }
    override fun report(...) { /* ... */ }
    // etc.
    
    /**
     * Allow setting custom main process (used by examples like ShuntingLoop)
     */
    fun setMainProcess(process: LoopProcess) {
        mainProcess = process
    }
}
```

### Phase 4: Update XMLContextFactory

```kotlin
// xml/XMLContextFactory.kt
class XMLContextFactory : EditingContextFactory, SimulationContextFactory {
    
    // Inject factory from Koin
    private val processFactory: SimulationProcessFactory by inject()
    
    private inner class XMLContext(
        cols: Int,
        rows: Int
    ) : DefaultSimulationContext(cols, rows, processFactory) {
        // No additional implementation needed
    }
    
    override fun createEmptyContext(): EditingContext {
        // Return editing-only context for editor
        return DefaultEditingContext(DEFAULT_GRID_SIZE, DEFAULT_GRID_SIZE)
    }
    
    override fun createContext(editingContext: EditingContext): SimulationContext {
        // Convert editing to simulation by copying data into new simulation context
        // This already exists, just update to use DefaultSimulationContext
    }
}
```

### Phase 5: Configure Koin DI

```kotlin
// di/InterlockSimModule.kt
val simulationModule: Module = module {
    // Factory for simulation processes
    single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }
    
    single<SimulationContextFactory> { get<XMLContextFactory>() }
    single<ExampleRegistry> { ExampleRegistry() }
}
```

## Migration Strategy

### Step-by-Step Implementation

1. **Create factory interface** (context package)
   - No dependencies on existing code
   - Can be done in parallel with current implementation

2. **Create factory implementation** (sim package)
   - Minimal new code in sim/ (allowed - it's a new class)
   - Encapsulates existing creation logic

3. **Create DefaultEditingContext**
   - Copy most of DefaultContext
   - Remove simulation-specific code
   - Make DefaultContext extend it temporarily for compatibility

4. **Create DefaultSimulationContext**
   - Extends DefaultEditingContext
   - Adds simulation features
   - Uses injected factory

5. **Update DefaultContext to extend DefaultSimulationContext**
   - Preserves backwards compatibility
   - Deprecate DefaultContext (optional - could keep as alias)

6. **Update XMLContextFactory**
   - Use new context classes
   - Inject factory from Koin

7. **Update examples and tests**
   - Update type references where needed
   - Most code should work unchanged

### Backwards Compatibility

To ensure existing code continues working:

```kotlin
// Keep DefaultContext as deprecated alias
@Deprecated(
    "Use DefaultEditingContext or DefaultSimulationContext directly",
    ReplaceWith("DefaultSimulationContext")
)
abstract class DefaultContext(
    cols: Int,
    rows: Int,
    processFactory: SimulationProcessFactory
) : DefaultSimulationContext(cols, rows, processFactory)
```

Or simply make the old DefaultContext extend the new one:

```kotlin
// DefaultContext.kt - updated
abstract class DefaultContext(
    cols: Int,
    rows: Int,
    processFactory: SimulationProcessFactory
) : DefaultSimulationContext(cols, rows, processFactory) {
    // Can eventually be removed once all references updated
}
```

## Testing Strategy

### Unit Tests

1. **SimulationProcessFactory Tests**
   - Mock factory for testing contexts without simulation
   - Verify factory methods called correctly
   - Test different process types

2. **DefaultEditingContext Tests**
   - All editing operations work without simulation
   - No simulation dependencies
   - Can be tested in isolation

3. **DefaultSimulationContext Tests**
   - Simulation operations work correctly
   - Factory integration works
   - Process creation delegated properly

### Integration Tests

1. **Example Simulations**
   - All existing examples still work
   - ShuntingLoop custom process still works
   - Generated output matches golden output

2. **XML Loading**
   - Contexts created from XML still work
   - Editing contexts can be converted to simulation
   - All 662 tests pass

## Benefits

### Immediate Benefits

1. **Separation of Concerns**: Editing ≠ Simulation
2. **Dependency Inversion**: Context depends on abstraction, not concrete classes
3. **Testability**: Can test editing without simulation
4. **Flexibility**: Easy to add new process types

### Future Benefits

1. **jDisco Migration**: Factory pattern makes it easy to swap simulation engines
2. **Multiple Simulation Engines**: Can have different factories for different engines
3. **Custom Processes**: Users can provide custom factories for specialized simulations

## Risks & Mitigation

### Risk 1: Breaking Existing Tests

**Mitigation**: 
- Keep DefaultContext as compatibility layer initially
- Update gradually
- Run full test suite after each change

### Risk 2: Performance Overhead

**Mitigation**:
- Factory pattern has negligible overhead (single method call)
- Koin DI is zero-overhead (direct instantiation)
- Measure before/after if concerned

### Risk 3: Complexity Increase

**Mitigation**:
- Well-documented design
- Clear separation of concerns actually reduces complexity
- Factory pattern is well-known design pattern

### Risk 4: sim/ Package Modification

**Mitigation**:
- Only adding new factory class to sim/
- Not modifying existing simulation logic
- Factory is minimal and isolated

## Open Questions

1. **Should DefaultContext remain as alias?**
   - **Option A**: Keep as deprecated alias for compatibility
   - **Option B**: Remove and update all references
   - **Recommendation**: Keep initially, remove in future PR

2. **Where should SimulationProcessFactory interface live?**
   - **Option A**: context/ package (current proposal)
   - **Option B**: sim/ package
   - **Recommendation**: context/ - it's an abstraction used by contexts

3. **Should we create EditingContext implementation without ANY sim references?**
   - **Option A**: DefaultEditingContext has no sim/ imports at all
   - **Option B**: Keep some sim/ references for type compatibility
   - **Recommendation**: Option A - clean separation

4. **How to handle setMainProcess(ShuntingLoop)?**
   - **Current**: Public method on DefaultContext
   - **Option A**: Keep on DefaultSimulationContext (type-safe)
   - **Option B**: Make it setMainProcess(LoopProcess) (more flexible)
   - **Recommendation**: Option B - less coupling to ShuntingLoop specifically

## Future Work

### Phase 6 (Future PR): Separate Static/Dynamic Properties

Per issue comments, domain objects should eventually have:
- **Static properties**: Track length, max speed, cell types (editing time)
- **Dynamic properties**: Train position, semaphore state (simulation time)

This would be a larger refactoring and should be done separately.

### Phase 7 (Future PR): Full Context Separation

Eventually, contexts should not implement both interfaces:
- `EditingContextImpl implements EditingContext` (only)
- `SimulationContextImpl implements SimulationContext` (only)
- Conversion between them via factory

Current design is a stepping stone toward this goal.

## Approval Checklist

- [ ] Design reviewed by maintainer (@bedaHovorka)
- [ ] No objections to sim/ package additions
- [ ] Factory pattern acceptable
- [ ] Backwards compatibility strategy approved
- [ ] Testing strategy sufficient
- [ ] Timeline acceptable

## References

- Issue: "Divide DefaultContext to Editing and Simulation implementation"
- CLAUDE.md: sim/ package restrictions
- LONG_TERM_GOALS.md: jDisco migration plans
- Design Patterns: Factory Pattern, Dependency Injection

---

**Next Steps**: Await approval, then proceed with Phase 1 implementation.
