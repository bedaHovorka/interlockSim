# Design: RouteFinder API and Route Value Type (#595)

**Issue:** [bedaHovorka/interlockSim#595](https://github.com/bedaHovorka/interlockSim/issues/595)  
**Goal 2 SP3** — Define the public API surface for automatic path finding so that editor and simulation code can request ranked routes from the routing engine.

**Status:** Design approved 2026-06-30. Ready for implementation planning.

---

## 1. Summary

This slice adds a user-facing, domain-level routing API on top of the Dijkstra engine delivered in PR #594 (`AutomaticPathFindingService`). The API is intentionally decoupled from the low-level engine:

- **`RouteFinder`** is the public contract consumed by editor and simulation code.
- **`Route`** is an immutable value type describing a planned route between two `InOut`s.
- **`NetworkState`** is introduced as a forward-looking facade so future slices can add dynamic constraints (block reservations, occupancy, semaphore aspects) without breaking callers.
- **`DefaultRouteFinder`** is a thin wrapper that delegates to the existing `AutomaticPathFindingService` and translates between domain objects (`InOut`) and engine primitives (`PathSeparator`, `PathFindingResult`).

The existing simulation-oriented [`Path`](../../core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/paths/Path.kt) interface is left untouched; the new value type is called `Route` to avoid name collision and semantic confusion.

---

## 2. Motivation

PR #594 built the engine:

- `AutomaticPathFindingService` operates on `PathSeparator`s.
- `PathFindingResult` exposes `sections: List<TrackSection>` and `totalCost: Double`.

That API is correct for internal use but is not ideal for editor/simulation callers because:

1. It leaks engine-level concepts (`PathSeparator`, `PathCostFunction`) in its primary signature.
2. It does not carry a domain `start InOut → target InOut` model.
3. It has no extension point for dynamic network state.

Issue #595 requests a higher-level API surface. This design keeps the proven engine and adds a stable façade.

---

## 3. Public API Surface

### 3.1 `NetworkState`

**Location:** `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/NetworkState.kt`

```kotlin
/**
 * Snapshot of the railway network state used by the routing engine.
 *
 * In this slice the interface is intentionally empty: route search is purely
 * topological and does not consult reservations, occupancy, or signal aspects.
 * Future slices can add query methods (e.g. `isReserved`, `isOccupied`,
 * `semaphoreAspect`) without changing [RouteFinder] method signatures.
 */
interface NetworkState
```

Both context types will expose a `NetworkState` instance. The simplest option is to have `EditingContext` and `SimulationEnvironment` extend/implement `NetworkState` as a marker interface, so callers can pass the context/environment directly (e.g. `context.findRoutes(from, to, context)`). A dedicated wrapper can be introduced later if state needs to be separated from the context object.

### 3.2 `Route`

**Location:** `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/paths/Route.kt`

```kotlin
/**
 * A planned route between two InOut elements.
 *
 * This is the value type returned by automatic path finding. It is intentionally
 * separate from [Path], which represents the concrete track sequence a train
 * actually traverses during simulation (including dynamic wrappers and reservation
 * metadata).
 *
 * @property start entry/exit point where the route begins
 * @property target entry/exit point where the route ends
 * @property segments ordered track sections from [start] to [target]
 * @property cost total cost according to the cost function used to rank the route
 * @property costBreakdown per-segment cost contribution
 */
data class Route(
    val start: InOut,
    val target: InOut,
    val segments: List<TrackSection>,
    val cost: Double,
    val costBreakdown: List<SegmentCost>
) {
    val elementCount: Int get() = segments.size
    val totalLength: Double get() = segments.sumOf { it.length() }
}

/**
 * Per-segment cost breakdown.
 *
 * @property section the track section
 * @property cost cost contribution for this section
 */
data class SegmentCost(
    val section: TrackSection,
    val cost: Double
)
```

`Route` is immutable, multiplatform-safe, and only depends on core domain types.

### 3.3 `RouteFinder`

**Location:** `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinder.kt`

```kotlin
/**
 * Public API for automatic route planning between InOut elements.
 *
 * Consumers (editor and simulation) request ranked routes without knowing the
 * underlying Dijkstra implementation or [PathSeparator] types.
 */
interface RouteFinder {
    /**
     * Return all valid routes from [from] to [to], sorted from cheapest to most
     * expensive according to [costFunction].
     *
     * The [state] parameter is accepted now so future implementations can apply
     * dynamic constraints (reserved blocks, occupancy, signal aspects). In this
     * slice the state is ignored and search is purely topological.
     *
     * @param from starting InOut
     * @param to target InOut
     * @param state network state snapshot (ignored in this slice)
     * @param maxRoutes upper bound on returned routes
     * @param costFunction metric used for ranking
     * @return ranked list of routes; empty when no route exists
     */
    fun findRoutes(
        from: InOut,
        to: InOut,
        state: NetworkState,
        maxRoutes: Int = DEFAULT_MAX_ROUTES,
        costFunction: PathCostFunction = PathCostFunctions.BY_ELEMENT_COUNT
    ): List<Route>

    /**
     * Convenience check for route existence.
     */
    fun isRouteAvailable(
        from: InOut,
        to: InOut,
        state: NetworkState
    ): Boolean = findRoutes(from, to, state, maxRoutes = 1).isNotEmpty()

    companion object {
        const val DEFAULT_MAX_ROUTES: Int = 100
    }
}
```

The interface is intentionally placed in `context/` because it is a context-provided service, like `TopologyNavigator` and `TrainNavigationService`.

### 3.4 `DefaultRouteFinder`

**Location:** `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/pathfinding/DefaultRouteFinder.kt`

```kotlin
/**
 * Default [RouteFinder] implementation.
 *
 * Delegates to the existing [AutomaticPathFindingService] and maps the engine
 * result to the domain [Route] type. Hides all [PathSeparator] and
 * [PathFindingResult] details from public callers.
 */
class DefaultRouteFinder(
    private val engine: AutomaticPathFindingService
) : RouteFinder {

    override fun findRoutes(
        from: InOut,
        to: InOut,
        state: NetworkState,
        maxRoutes: Int,
        costFunction: PathCostFunction
    ): List<Route> {
        val start = normalize(from)
        val target = normalize(to)

        return engine.findAllPaths(
            start = start,
            target = target,
            maxPaths = maxRoutes,
            costFunction = costFunction
        ).map { result ->
            Route(
                start = from,
                target = to,
                segments = result.sections,
                cost = result.totalCost,
                costBreakdown = buildBreakdown(result.sections, start, costFunction)
            )
        }
    }

    private fun normalize(separator: PathSeparator): PathSeparator =
        CellUtilities.assertNodeCell(separator)

    private fun buildBreakdown(
        sections: List<TrackSection>,
        start: PathSeparator,
        costFunction: PathCostFunction
    ): List<SegmentCost> {
        val breakdown = mutableListOf<SegmentCost>()
        var currentSeparator: PathSeparator = start
        for (section in sections) {
            breakdown.add(SegmentCost(section, costFunction.cost(section, currentSeparator)))
            currentSeparator = section.getSecondEnd(currentSeparator)
        }
        return breakdown
    }
}
```

### 3.5 Context exposure

Add `getRouteFinder()` to the two public context interfaces:

- `EditingContext` in `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/EditingContext.kt`
- `SimulationEnvironment` in `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt`

Implementation in `DefaultEditingContext` and `DefaultSimulationContext` delegates to the per-context Koin scope.

### 3.6 Koin wiring

In `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/di/CoreModule.kt`, add to both scopes:

```kotlin
// Inside scope<DefaultEditingContext> and scope<DefaultSimulationContext>
scoped<RouteFinder> {
    DefaultRouteFinder(get<AutomaticPathFindingService>())
}
```

`AutomaticPathFindingService` is already scoped in both contexts, so the dependency resolves naturally.

---

## 4. Data Flow

```
Editor / Simulation
       |
       v
EditingContext.getRouteFinder() / SimulationEnvironment.getRouteFinder()
       |
       v
DefaultRouteFinder.findRoutes(from, to, state)
       |
       |-- normalizes InOut / DynamicInOut to static PathSeparator
       |
       v
AutomaticPathFindingService.findAllPaths(start, target, costFunction)
       |
       |-- Dijkstra over topology graph, switch-constrained
       |
       v
List<PathFindingResult>
       |
       |-- mapped to List<Route>
       |
       v
Caller
```

`NetworkState` flows through the call chain but is not consulted in this slice. Future dynamic-constraint implementations can subclass `DefaultRouteFinder` or extend `AutomaticPathFindingService` with a `PathConstraint` parameter.

---

## 5. Error Handling and Edge Cases

This section is documented explicitly because the behavior must be predictable for both editor and simulation callers.

### 5.1 No route exists

- `findRoutes` returns an empty `List<Route>`.
- `isRouteAvailable` returns `false`.
- No exception is thrown.

### 5.2 Start equals target

- The engine already returns a single result with empty `sections` and cost `0.0` when `start === target`.
- `findRoutes` therefore returns `[Route(from, from, emptyList(), 0.0, emptyList())]`.
- This is consistent with bidirectional InOut semantics (a single InOut can act as both entry and exit, PR #356).

### 5.3 Disconnected InOuts

- If `from` and `to` belong to disconnected graph components, `findAllPaths` returns an empty list and `findRoutes` propagates that.

### 5.4 Dynamic wrappers

- Simulation callers pass `DynamicInOut` instances.
- `DefaultRouteFinder.normalize` delegates to `CellUtilities.assertNodeCell`, which extracts the static `InOut` reference. This matches the normalization already used by `DefaultAutomaticPathFindingService` and ensures map equality works across static/dynamic contexts.

### 5.5 `NetworkState` is null or unsupported

- `findRoutes` requires a non-null `NetworkState`.
- Passing a state object from a different context is a programming error and is not validated at runtime in this slice; behavior is undefined.

### 5.6 Infinite-cost edges

- Some cost functions (e.g. `BY_TRAVEL_TIME`) may return `Double.POSITIVE_INFINITY` for a section with zero max speed.
- Dijkstra treats infinite cost as non-viable, so such edges will not be selected in any returned route.
- Callers should not rely on infinite-cost edges producing routes; they simply prevent that edge from being chosen.

### 5.7 `maxRoutes` cap

- `maxRoutes` is passed directly to the engine as `maxPaths`.
- If fewer routes exist, the smaller list is returned.

---

## 6. Testing Plan

New test class: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinderTest.kt`. This uses the existing JUnit 5 + Koin test base (`KoinTestBase`) so we can inject context factories and navigation services the same way `DefaultAutomaticPathFindingServiceTest` does.

Tests must assert against the public `RouteFinder`/`Route` API only and must not depend on `PathFindingResult` internals:

1. **Linear network** — `findRoutes(A, B)` returns exactly one `Route` with the expected segments and positive cost.
2. **Multiple alternatives** — on the shunting loop network, `findRoutes(A, B)` returns more than one route sorted by cost.
3. **Disconnected InOuts** — `findRoutes(A, B)` returns empty list and `isRouteAvailable` returns `false`.
4. **Start equals target** — returns a single route with empty segments and zero cost.
5. **Cost breakdown** — `Route.costBreakdown` sums to `Route.cost`.
6. **Context exposure** — both `EditingContext` and `SimulationEnvironment` expose a non-null `RouteFinder`.
7. **Switch constraints** — impossible straight-to-branch switch transitions yield no route.
8. **Simulation compatibility** — `RouteFinder` works when called from a `SimulationContext` with `DynamicInOut` inputs.

Existing `DefaultAutomaticPathFindingServiceTest` should remain unchanged; the new tests focus on the façade translation.

---

## 7. Branch and PR Plan

- **Branch:** `feat/goal-2-sp3-595-route-finder-api` (already created).
- **Target PR branch:** `goal-2`.
- **PR title:** `feat(goal-2): PathFinder API and Route value type (#595)`.
- **PR description:** Include the acceptance criteria from #595, mapped to the `RouteFinder`/`Route` naming, and note that `NetworkState` is an empty forward-looking facade in this slice.
- **Quality gates:** `./gradlew build detekt ktlintCheck test` must pass.

---

## 8. Future Slices (out of scope here)

- Populate `NetworkState` with reservation/occupancy/semaphore queries.
- Add dynamic constraints to `AutomaticPathFindingService` (e.g. `PathConstraint`) and surface them through `RouteFinder`.
- UI/editor integration: highlight planned routes, route selection dialog.
