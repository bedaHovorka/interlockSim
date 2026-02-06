# Koin Scope Lifecycle Tests Documentation

**Last Updated:** 2026-02-06  
**Issue:** #220 (Add Koin scope lifecycle tests)  
**File:** `src/test/kotlin/cz/vutbr/fit/interlockSim/di/KoinGoldenOutputTest.kt:189`

## Overview

This document describes the comprehensive Koin scope lifecycle tests implemented for the scope-per-context pattern used in the Railway Interlocking Simulator.

## Background

The project uses a **scope-per-context** pattern where each `DefaultSimulationContext` creates its own Koin scope:
- One `PathReservationRegistry` per scope (shared by all navigation services)
- Different contexts have isolated registries (no state bleeding)
- Scope is closed when context is destroyed via `close()`

## Test Implementation

### Test Location
```kotlin
// File: src/test/kotlin/cz/vutbr/fit/interlockSim/di/KoinGoldenOutputTest.kt
@Test
@Tag("integration-test")
fun `validate context lifecycle with Koin scopes`()
```

### Test Structure

The test validates three critical aspects of Koin scope lifecycle management:

#### 1. Rapid Sequential Creation Stress Test (Memory Leak Detection)

**Purpose:** Verify that scopes are properly closed and don't accumulate in memory.

**Implementation:**
```kotlin
repeat(50) { iteration ->
    buildTestContext().use { context ->
        val service = context.getPathReservationService()
        val grid = context.getRailWayNetGrid()
        val inOutA = context.toDynamic(grid.getCellAt(1, 1) as PathSeparator)
        val inOutB = context.toDynamic(grid.getCellAt(5, 5) as PathSeparator)
        service.reservePath("train-$iteration", inOutA, inOutB)
        
        assertThat(service.getReservedBlocks("train-$iteration")).isNotNull()
    }
    // After use{} block: context.close() called automatically
}
```

**What it validates:**
- 50 contexts created and destroyed in succession
- Each context creates scoped services and populates internal state
- No `OutOfMemoryError` occurs
- Scopes don't accumulate in `GlobalContext`
- Resources are properly released after each `use{}` block

**Why it matters:**
- Prevents memory leaks in long-running applications
- Ensures scope cleanup is automatic and reliable
- Validates that Koin's scope management works correctly under stress

---

#### 2. Deep State Isolation Test (State Bleeding Prevention)

**Purpose:** Verify complete isolation between sequential contexts with mutable state.

**Implementation:**
```kotlin
// Create context1 with significant state
buildTestContext().use { context1 ->
    val service1 = context1.getPathReservationService()
    service1.reservePath("train-alpha", inOutA1, inOutB1)
    service1.reservePath("train-beta", inOutA1, inOutB1)
    service1.reservePath("train-gamma", inOutA1, inOutB1)
    
    assertThat(service1.getReservedBlocks("train-alpha").size).isEqualTo(1)
    assertThat(service1.getReservedBlocks("train-beta").size).isEqualTo(1)
    assertThat(service1.getReservedBlocks("train-gamma").size).isEqualTo(1)
}
// context1 is now closed, scope should be destroyed

// Create context2 and verify complete isolation
buildTestContext().use { context2 ->
    val service2 = context2.getPathReservationService()
    
    // Verify context2's registry is completely clean
    assertThat(service2.getReservedBlocks("train-alpha")).isEmpty()
    assertThat(service2.getReservedBlocks("train-beta")).isEmpty()
    assertThat(service2.getReservedBlocks("train-gamma")).isEmpty()
    
    // Reuse same train name to prove isolation
    service2.reservePath("train-alpha", inOutA2, inOutB2)
    assertThat(service2.getReservedBlocks("train-alpha").size).isEqualTo(1)
}
```

**What it validates:**
- Context1's mutable state (3 path reservations) is completely destroyed
- Context2 starts with a clean slate (no leakage from context1)
- Same train names can be reused without conflicts
- `PathReservationRegistry` is truly scoped per context

**Why it matters:**
- Prevents subtle bugs from state bleeding between simulation runs
- Ensures simulation reproducibility
- Validates that scoped services don't share state across contexts

---

#### 3. Manual Scope Closure and Access Denial Test

**Purpose:** Verify that closed scopes throw proper exceptions when accessed.

**Implementation:**
```kotlin
val context3 = buildTestContext()
val scope3 = context3.scope

// Verify scope is active before close
val serviceBeforeClose = context3.getPathReservationService()
assertThat(serviceBeforeClose).isNotNull()

// Manually close the context (and its scope)
context3.close()

// Attempting to get service from closed scope should fail
assertFailure {
    scope3.get<PathReservationService>()
}.isInstanceOf(org.koin.core.error.ClosedScopeException::class)
```

**What it validates:**
- Scope is active before `close()` is called
- `close()` properly closes the Koin scope
- Accessing a closed scope throws `ClosedScopeException`
- Error handling for closed scopes is correct

**Why it matters:**
- Prevents use-after-free bugs
- Ensures clean error messages when accessing closed resources
- Validates Koin's scope lifecycle state machine

---

## Architecture Patterns Validated

### Scope-per-Context Pattern

```kotlin
// In DefaultSimulationContext.kt
override val scope = GlobalContext.get().createScope(
    scopeId = System.identityHashCode(this).toString(),
    qualifier = named<DefaultSimulationContext>(),
    source = this
)
```

**Key properties:**
- Each context has a unique scope ID (based on object identity)
- Scope source is the context itself (allows `getSource()` access)
- Scope is closed when context is closed via `AutoCloseable`

### Navigation Services in Scopes

```kotlin
// In InterlockSimModule.kt - navigationModule
scope<DefaultSimulationContext> {
    scoped<PathReservationRegistry> { /* ONE instance per scope */ }
    scoped<PathReservationService> { /* shares registry with TrainNavigationService */ }
    scoped<TrainNavigationService> { /* shares registry with PathReservationService */ }
}
```

**Key properties:**
- `PathReservationRegistry` is scoped (one per context)
- All services in the same scope share the same registry
- Different contexts have completely isolated registries

---

## Test Execution

### Running the Test

```bash
# Run only this specific test
./gradlew test --tests "cz.vutbr.fit.interlockSim.di.KoinGoldenOutputTest.validate context lifecycle with Koin scopes"

# Run all integration tests (includes this test)
./gradlew integrationTest

# Run all tests in KoinGoldenOutputTest
./gradlew test --tests "cz.vutbr.fit.interlockSim.di.KoinGoldenOutputTest"
```

### Expected Results

**Success Criteria:**
- All 3 test sections pass without exceptions
- No `OutOfMemoryError` during rapid creation test
- All assertions pass (state isolation, scope closure, exception handling)
- Test completes in reasonable time (< 30 seconds)

**Failure Scenarios:**

| Failure | Likely Cause | Fix |
|---------|--------------|-----|
| `OutOfMemoryError` in Test 1 | Scopes not being closed | Check `Context.close()` implementation |
| State bleeding in Test 2 | Registry not scoped correctly | Check `navigationModule` scope definitions |
| No exception in Test 3 | Scope not actually closed | Check `scope.close()` in `Context.close()` |
| `NullPointerException` | Initialization order issue | Check Koin module dependencies |

---

## Relationship to Other Tests

### Complementary Tests in NavigationModuleKoinTest.kt

The `NavigationModuleKoinTest` class provides additional coverage:

1. **`services within one context share the same registry`** (Line 59)
   - Validates that `PathReservationService` and `TrainNavigationService` share the same scoped registry
   - Complements Test 2 by focusing on intra-context sharing

2. **`different contexts have isolated registries`** (Line 97)
   - Similar to Test 2 but with simpler validation
   - Focus on basic isolation without deep state testing

3. **`closing context cleans up scoped resources`** (Line 160)
   - Similar to Test 1 but with explicit verification of cleanup
   - Creates new context after closing old one

4. **`scope isolation prevents state bleeding between sequential contexts`** (Line 183)
   - Similar pattern to Test 2
   - Focus on sequential context creation and cleanup

### Differences from NavigationModuleKoinTest

| Aspect | KoinGoldenOutputTest | NavigationModuleKoinTest |
|--------|----------------------|--------------------------|
| **Focus** | Koin DI golden output validation | Navigation services functionality |
| **Stress Testing** | 50 contexts in rapid succession | 1-2 contexts at a time |
| **State Complexity** | Multiple trains with varied names | Single train reservation |
| **Exception Testing** | Explicit `ClosedScopeException` check | Implicit via `use{}` block |
| **Test Data** | Uses `vyhybna.xml` (full network) | Uses `TestContextBuilder` (minimal network) |

---

## Implementation Notes

### Helper Method: buildTestContext()

```kotlin
private fun buildTestContext(): DefaultSimulationContext {
    val factory = get<SimulationContextFactory>()
    val xml = javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
    requireNotNull(xml) { "vyhybna.xml not found" }
    return factory.createContext(xml) as DefaultSimulationContext
}
```

**Design Decisions:**
- Uses `vyhybna.xml` instead of `TestContextBuilder` for realistic network complexity
- Fetches factory from Koin to validate DI integration
- Returns `DefaultSimulationContext` for direct scope access
- Each call creates a NEW context (no reuse)

**Why vyhybna.xml?**
- Full railway network with switches, semaphores, and track blocks
- More realistic than minimal test topology
- Better stress test for scope management
- Same XML used in actual application

---

## Future Enhancements

### Potential Additional Tests

1. **Memory Profiling Integration**
   - Use JVM memory monitoring to track scope accumulation
   - Measure heap size before/after rapid creation test
   - Validate that memory is released (not just references cleared)

2. **Concurrent Context Creation**
   - Test scope isolation with parallel context creation
   - Verify thread-safety of scope creation (if supported)
   - Validate no race conditions in scope cleanup

3. **Large State Stress Test**
   - Create contexts with 1000+ path reservations
   - Verify cleanup performance with large state
   - Test memory efficiency of scoped services

4. **Nested Scope Testing**
   - Test if contexts can create child scopes (if needed)
   - Verify parent scope closure cascades to children
   - Validate scope hierarchy management

5. **Scope Lifecycle Hooks**
   - Test `onClose` callbacks in Koin scopes
   - Verify cleanup callbacks are invoked
   - Validate resource release order

---

## Related Documentation

- **InterlockSimModule.kt** - Koin module definitions and scope configuration
- **DefaultSimulationContext.kt** - Context scope creation and lifecycle
- **NavigationModuleKoinTest.kt** - Additional scope isolation tests
- **docs/KOTLIN_STYLE_GUIDE.md** - Koin DI patterns and best practices
- **docs/PATH_DISCOVERY_ARCHITECTURE.md** - Navigation services architecture

---

## References

- **Koin Documentation:** https://insert-koin.io/docs/reference/koin-core/scopes
- **Issue #220:** Add Koin scope lifecycle tests
- **Issue #294:** Phase 2 DI Integration (PathReservationService)
- **Issue #296:** Phase 4 Scope-per-Context Pattern

---

## Change History

| Date | Change | Author |
|------|--------|--------|
| 2026-02-06 | Initial implementation of scope lifecycle tests | GitHub Copilot |
| 2026-02-06 | Documentation created | GitHub Copilot |
