# Phase 4: Static/Dynamic Property Separation - Design Document

**Issue:** bedaHovorka/interlockSim#92 - Phase 4  
**Date:** 2026-01-14  
**Status:** Design Phase - Seeking Clarification

## Problem Analysis

Current domain objects (`Track`, `RailSwitch`, `RailSemaphore`, `InOut`) mix two types of properties:

### Static Properties (Editing-Time, Immutable During Simulation)
- **Track**: `length`, `maxSpeed` (from each end), `ends` (PathSeparators)
- **RailSwitch**: `type`, `speeds` (MAIN/BRANCH speeds), `confs` (topology), `spatialType`
- **RailSemaphore**: `orientation`, `spatialType`
- **InOut**: `name`, `orientation`, `spatialType`

### Dynamic Properties (Simulation-Time, Mutable State)
- **Track**: `state` (FREE/RESERVED/OCCUPIED), `in` (current occupant), `from` (reservation direction)
- **RailSwitch**: `conf` (current position: MAIN/BRANCH), `locked` (lock state)
- **RailSemaphore**: `signal` (current signal: STOP/S30/S40/S60/S80/S100/FREE)
- **InOut**: signal states (via embedded semaphores)

## Design Challenge

The issue requests:
> For each RailSemaphore, RailSwitch, InOut, ..., Track(Block):
> - must contain only "static" properties
> - must exist some Dynamic* class for dynamic properties
> - this dynamic class should be linked with its static instance by delegation

However, this creates several challenges:

### Challenge 1: Widespread Usage
- `RailSemaphore.getSignal()` used in 9+ places in simulation code
- `RailSwitch.getConf()`, `changeConf()`, `lock()`, `unlock()` used throughout
- `Track` state methods (`enter()`, `leave()`, `setUpPath()`, etc.) used extensively
- Changing all this code is **not minimal changes**

### Challenge 2: Type System Impact
- If `Track` becomes static-only, existing code expecting mutable state breaks
- If we create `DynamicTrack extends Track`, we lose type safety
- If we create separate hierarchies, we need dual APIs everywhere

### Challenge 3: Interface Contracts
- `Track` interface defines `isFreeFrom()`, `setUpPath()` - these are simulation operations
- `TrackSection` interface defines `enter()`, `leave()` - these are dynamic operations  
- Do we split interfaces too? This cascades through the entire codebase

## Proposed Conservative Approach

### Option A: Gradual Migration with Marker Interfaces (RECOMMENDED)

**Step 1:** Add marker interfaces to clarify intent (no breaking changes)
```kotlin
/**
 * Marker interface for objects with static properties only
 */
interface StaticProperties

/**
 * Marker interface for objects with dynamic simulation state
 */
interface DynamicProperties
```

**Step 2:** Document properties with annotations
```kotlin
class RailSemaphore(...) {
    // @StaticProperty
    val orientation: Boolean
    
    // @DynamicProperty  
    private var signal: Signal = Signal.STOP
}
```

**Step 3:** Create parallel Dynamic* classes for NEW code
```kotlin
class DynamicRailSemaphore(val static: RailSemaphore) {
    var signal: Signal = Signal.STOP
    // Delegate static operations to static
}
```

**Step 4:** Update SimulationContext to manage mapping (optional)
```kotlin
interface SimulationContext {
    fun getDynamic(static: RailSemaphore): DynamicRailSemaphore
    // ... other Dynamic* mappings
}
```

**Benefits:**
- ✅ Non-breaking: existing code continues to work
- ✅ Gradual: can migrate one subsystem at a time
- ✅ Clear: documentation shows intent
- ✅ Extensible: new code can use Dynamic* pattern

**Drawbacks:**
- ❌ Doesn't enforce separation at compile time
- ❌ Two parallel APIs (old and new)
- ❌ Migration takes multiple PRs

### Option B: Full Refactoring (HIGH RISK)

**Step 1:** Make existing classes static-only (BREAKING CHANGE)
```kotlin
// Remove all mutable state
class RailSemaphore(val orientation: Boolean, val spatialType: SpatialType) {
    // NO signal field
}
```

**Step 2:** Create Dynamic* wrappers
```kotlin
class DynamicRailSemaphore(val static: RailSemaphore) {
    var signal: Signal = Signal.STOP
}
```

**Step 3:** Update ALL usage sites (hundreds of changes)
```kotlin
// Old code
semaphore.getSignal()

// New code  
dynamicSemaphore.getSignal()
```

**Benefits:**
- ✅ Clean separation enforced by type system
- ✅ Clear architectural intent
- ✅ Better for future multiplatform

**Drawbacks:**
- ❌ VERY high risk of breaking tests
- ❌ Hundreds of files to update
- ❌ Not "minimal changes"
- ❌ Difficult to review
- ❌ Hard to test incrementally

## Recommendation

Given the constraint of **"minimal modifications"** and **"conservative changes"**, I recommend:

### Hybrid Approach: Option A with Proof of Concept

1. **Document current state** (this document) ✅
2. **Create Dynamic* classes** for one domain object (RailSemaphore) as proof of concept
3. **Add marker interfaces** and documentation
4. **Show example** of how SimulationContext would manage Dynamic* objects
5. **Get feedback** before proceeding with full migration
6. **Keep existing classes working** - don't remove dynamic properties yet

This allows us to:
- Demonstrate the pattern
- Validate the design
- Get maintainer feedback
- Avoid breaking 728 tests
- Proceed incrementally

## Open Questions for Maintainer

1. **Is full refactoring expected in this PR?** Or should this be split into multiple PRs?
2. **Should existing classes lose their dynamic properties?** Or keep them for backwards compatibility?
3. **What's the migration strategy?** Big bang or gradual?
4. **Are interface changes in scope?** (e.g., splitting `Track` interface)
5. **What's the timeline?** This could be weeks of work if done fully

## Next Steps

**Awaiting maintainer input on:**
- Preferred approach (Option A vs Option B vs Hybrid)
- Scope of this PR (proof of concept vs full implementation)
- Breaking changes acceptable? (YES/NO)
- Timeline expectations

Once direction is clear, implementation can proceed with confidence.

---

**Related:**
- Issue: bedaHovorka/interlockSim#92
- Design doc: CONTEXT_REFACTORING_DESIGN.md
- Conservative approach: CLAUDE.md guidelines
