# RouteFinder API and Route Value Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public, domain-level automatic-routing API (`RouteFinder`, `Route`, `NetworkState`) on top of the existing Dijkstra engine, consumable from both editor and simulation contexts.

**Architecture:** A thin `DefaultRouteFinder` façade delegates to the proven `AutomaticPathFindingService` from PR #594 and translates `InOut`-level calls into `PathSeparator`-level engine calls. `EditingContext` and `SimulationEnvironment` expose the new service via their per-context Koin scopes. The value type `Route` lives alongside the existing simulation `Path` interface but does not replace it.

**Tech Stack:** Kotlin Multiplatform (`core/commonMain`), Koin 3.5.6, JUnit 5, AssertK, ktlint, detekt, Gradle.

## Global Constraints

- Java 21 LTS is required.
- All source files use tabs (width 4), max line length 120.
- Code must pass `./gradlew build detekt ktlintCheck test`.
- `core/commonMain` must remain JVM-free (no `java.*`/`javax.*` imports); use `./gradlew :core:checkCoreCommonMainPurity`.
- Do not modify the existing simulation `Path` interface or `AutomaticPathFindingService` engine behavior.
- Do not disable ktlint or detekt; fix violations.
- Commit after each task with a focused message ending `Co-Authored-By: Claude <noreply@anthropic.com>`.

---

## File Map

| File | Responsibility |
|------|----------------|
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/NetworkState.kt` | Empty marker interface for future dynamic state. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/paths/Route.kt` | Immutable `Route` and `SegmentCost` value types. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinder.kt` | Public `RouteFinder` interface with `findRoutes` / `isRouteAvailable`. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/pathfinding/DefaultRouteFinder.kt` | Façade implementation delegating to `AutomaticPathFindingService`. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/EditingContext.kt` | Add `getRouteFinder()` to interface. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt` | Add `getRouteFinder()` to interface and make it extend `NetworkState`. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultEditingContext.kt` | Implement `getRouteFinder()` via scope. Make `EditingContext` extend `NetworkState`. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt` | Implement `getRouteFinder()` via lazy scope instance. Make `SimulationEnvironment` extend `NetworkState`. |
| `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/di/CoreModule.kt` | Add `scoped<RouteFinder>` in both editing and simulation scopes. |
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/testutil/CoreTestModule.kt` | Add `scoped<RouteFinder>` in both editing and simulation test scopes. |
| `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinderTest.kt` | Unit tests for the public API. |

---

## Task 1: Add `NetworkState` marker interface

**Files:**
- Create: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/NetworkState.kt`
- Test: compile-only check via `./gradlew :core:compileCommonMainKotlinMetadata`

**Interfaces:**
- Produces: `interface NetworkState` (empty).

- [ ] **Step 1: Create the interface**

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

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

- [ ] **Step 2: Verify commonMain compiles**

Run: `./gradlew :core:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/NetworkState.kt
git commit -m "feat(#595): add NetworkState marker interface for routing state facade

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: Add `Route` value type

**Files:**
- Create: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/paths/Route.kt`
- Test: compile-only check via `./gradlew :core:compileCommonMainKotlinMetadata`

**Interfaces:**
- Consumes: `InOut`, `TrackSection`.
- Produces: `data class Route(...)`, `data class SegmentCost(...)`.

- [ ] **Step 1: Create the value types**

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

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
	/**
	 * Number of track elements in the route.
	 */
	val elementCount: Int
		get() = segments.size

	/**
	 * Total physical length of the route in meters.
	 */
	val totalLength: Double
		get() = segments.sumOf { it.length() }
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

- [ ] **Step 2: Verify commonMain compiles**

Run: `./gradlew :core:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/objects/paths/Route.kt
git commit -m "feat(#595): add Route and SegmentCost value types

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Add `RouteFinder` interface

**Files:**
- Create: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinder.kt`
- Test: compile-only check via `./gradlew :core:compileCommonMainKotlinMetadata`

**Interfaces:**
- Consumes: `NetworkState`, `Route`, `PathCostFunction`, `PathCostFunctions`.
- Produces: `interface RouteFinder` with `findRoutes(...): List<Route>` and `isRouteAvailable(...): Boolean`.

- [ ] **Step 1: Create the interface**

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunction
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunctions

/**
 * Public API for automatic route planning between InOut elements.
 *
 * Consumers (editor and simulation) request ranked routes without knowing the
 * underlying Dijkstra implementation or [cz.vutbr.fit.interlockSim.objects.core.PathSeparator]
 * types.
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
	 *
	 * @param from starting InOut
	 * @param to target InOut
	 * @param state network state snapshot (ignored in this slice)
	 * @return true if at least one route exists
	 */
	fun isRouteAvailable(
		from: InOut,
		to: InOut,
		state: NetworkState
	): Boolean = findRoutes(from, to, state, maxRoutes = 1).isNotEmpty()

	companion object {
		/**
		 * Default cap for returned alternative routes.
		 */
		const val DEFAULT_MAX_ROUTES: Int = 100
	}
}
```

- [ ] **Step 2: Verify commonMain compiles**

Run: `./gradlew :core:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinder.kt
git commit -m "feat(#595): add RouteFinder interface for public routing API

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: Add `DefaultRouteFinder` façade implementation

**Files:**
- Create: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/pathfinding/DefaultRouteFinder.kt`
- Test: compile-only check via `./gradlew :core:compileCommonMainKotlinMetadata`

**Interfaces:**
- Consumes: `RouteFinder`, `NetworkState`, `AutomaticPathFindingService`, `PathFindingResult`, `CellUtilities.assertNodeCell`, `PathCostFunction`.
- Produces: `class DefaultRouteFinder(engine: AutomaticPathFindingService) : RouteFinder`.

- [ ] **Step 1: Create the implementation**

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.pathfinding

import cz.vutbr.fit.interlockSim.context.NetworkState
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.paths.SegmentCost
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection

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

	/**
	 * Normalize an InOut or DynamicInOut to its static [PathSeparator] reference.
	 *
	 * This keeps [RouteFinder] usable from both [cz.vutbr.fit.interlockSim.context.EditingContext]
	 * (static objects) and [cz.vutbr.fit.interlockSim.context.SimulationContext]
	 * (dynamic wrappers).
	 */
	private fun normalize(separator: PathSeparator): PathSeparator =
		CellUtilities.assertNodeCell(separator)

	/**
	 * Compute per-segment cost contributions by walking the sections in order.
	 */
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

- [ ] **Step 2: Verify commonMain compiles**

Run: `./gradlew :core:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/pathfinding/DefaultRouteFinder.kt
git commit -m "feat(#595): add DefaultRouteFinder facade over AutomaticPathFindingService

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: Wire `RouteFinder` into Koin scopes

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/di/CoreModule.kt`
- Test: compile-only check via `./gradlew :core:compileCommonMainKotlinMetadata`

**Interfaces:**
- Consumes: `DefaultRouteFinder`, `AutomaticPathFindingService`.
- Produces: `scoped<RouteFinder>` available in editing and simulation scopes.

- [ ] **Step 1: Add imports and scoped bindings**

Add imports at the top of `CoreModule.kt`:

```kotlin
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.pathfinding.DefaultRouteFinder
```

Add inside `scope<DefaultEditingContext>` after the existing `scoped<AutomaticPathFindingService>` block:

```kotlin
		// RouteFinder: scoped to editing context
		// Builds on AutomaticPathFindingService (same scope)
		scoped<RouteFinder> {
			DefaultRouteFinder(get<AutomaticPathFindingService>())
		}
```

Add inside `scope<DefaultSimulationContext>` after the existing `scoped<AutomaticPathFindingService>` block:

```kotlin
		// RouteFinder: scoped to simulation context
		// Builds on AutomaticPathFindingService (same scope)
		scoped<RouteFinder> {
			DefaultRouteFinder(get<AutomaticPathFindingService>())
		}
```

- [ ] **Step 2: Verify commonMain compiles**

Run: `./gradlew :core:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/di/CoreModule.kt
git commit -m "feat(#595): wire RouteFinder into editing and simulation Koin scopes

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: Expose `RouteFinder` and `NetworkState` on context interfaces and implementations

**Files:**
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/EditingContext.kt`
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt`
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultEditingContext.kt`
- Modify: `core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt`
- Test: `./gradlew :core:compileCommonMainKotlinMetadata :core:compileKotlinJvm`

**Interfaces:**
- `EditingContext` extends `NetworkState` and exposes `getRouteFinder(): RouteFinder`.
- `SimulationEnvironment` extends `NetworkState` and exposes `getRouteFinder(): RouteFinder`.
- `DefaultEditingContext` implements `getRouteFinder()` via `scope.get()`.
- `DefaultSimulationContext` implements `getRouteFinder()` via a new lazy scope instance.

- [ ] **Step 1: Update `EditingContext` interface**

Change the interface declaration from:

```kotlin
interface EditingContext : Context<AbstractCell, TrackBlock> {
```

To:

```kotlin
interface EditingContext :
	Context<AbstractCell, TrackBlock>,
	NetworkState {
```

Add the new method after `getAutomaticPathFindingService()` (around line 324):

```kotlin
	/**
	 * Get the route finder for automatic route planning between InOut elements.
	 *
	 * The returned [RouteFinder] is scoped to this editing context and computes
	 * purely topological routes. It is safe to call before a simulation is started.
	 *
	 * @return RouteFinder instance for this editing context
	 * @see RouteFinder
	 * @see NetworkState
	 */
	fun getRouteFinder(): cz.vutbr.fit.interlockSim.context.RouteFinder
```

- [ ] **Step 2: Update `SimulationEnvironment` interface**

Change the interface declaration from:

```kotlin
interface SimulationEnvironment {
```

To:

```kotlin
interface SimulationEnvironment : NetworkState {
```

Add the new method after `getAutomaticPathFindingService()` (around line 213):

```kotlin
	/**
	 * Get the route finder for automatic route planning between InOut elements.
	 *
	 * The returned [RouteFinder] is scoped to this simulation context. In this slice
	 * it performs purely topological search; future slices will consult the supplied
	 * [NetworkState] for dynamic constraints.
	 *
	 * @return RouteFinder instance for this simulation context
	 * @see RouteFinder
	 * @see NetworkState
	 */
	fun getRouteFinder(): cz.vutbr.fit.interlockSim.context.RouteFinder
```

- [ ] **Step 3: Update `DefaultEditingContext` implementation**

Add after `getAutomaticPathFindingService()` (line 178):

```kotlin
	override fun getRouteFinder(): cz.vutbr.fit.interlockSim.context.RouteFinder =
		scope.get()
```

- [ ] **Step 4: Update `DefaultSimulationContext` implementation**

Add a new lazy instance after `automaticPathFindingServiceInstance` (around line 229):

```kotlin
	/**
	 * Route finder for automatic route planning between InOut elements.
	 * Lazy-initialized; scoped to this simulation context.
	 */
	private val routeFinderInstance: cz.vutbr.fit.interlockSim.context.RouteFinder by lazy {
		scope.get<cz.vutbr.fit.interlockSim.context.RouteFinder>()
	}
```

Add the override after `getAutomaticPathFindingService()` (line 497):

```kotlin
	override fun getRouteFinder(): cz.vutbr.fit.interlockSim.context.RouteFinder =
		routeFinderInstance
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :core:compileCommonMainKotlinMetadata :core:compileKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/EditingContext.kt \
        core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/SimulationEnvironment.kt \
        core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultEditingContext.kt \
        core/src/commonMain/kotlin/cz/vutbr/fit/interlockSim/context/DefaultSimulationContext.kt
git commit -m "feat(#595): expose RouteFinder and NetworkState on context interfaces

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: Update `CoreTestModule` with `RouteFinder` binding

**Files:**
- Modify: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/testutil/CoreTestModule.kt`
- Test: `./gradlew :core:compileTestKotlinJvm`

**Interfaces:**
- Consumes: `DefaultRouteFinder`, `AutomaticPathFindingService`.
- Produces: `scoped<RouteFinder>` in both editing and simulation test scopes.

- [ ] **Step 1: Add import and scoped bindings**

Add import:

```kotlin
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.pathfinding.DefaultRouteFinder
```

Add inside `scope<DefaultEditingContext>` after `scoped<AutomaticPathFindingService>`:

```kotlin
		scoped<RouteFinder> {
			DefaultRouteFinder(get<AutomaticPathFindingService>())
		}
```

Add inside `scope<DefaultSimulationContext>` after `scoped<AutomaticPathFindingService>`:

```kotlin
		scoped<RouteFinder> {
			DefaultRouteFinder(get<AutomaticPathFindingService>())
		}
```

- [ ] **Step 2: Verify JVM test compilation**

Run: `./gradlew :core:compileTestKotlinJvm`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/testutil/CoreTestModule.kt
git commit -m "test(#595): add RouteFinder binding to CoreTestModule

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: Write `RouteFinderTest`

**Files:**
- Create: `core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinderTest.kt`
- Test: `./gradlew :core:test --tests "cz.vutbr.fit.interlockSim.context.RouteFinderTest"`

**Interfaces:**
- Consumes: `EditingContext`, `SimulationContext`, `RouteFinder`, `Route`, `TestTopologies`, `TestFixtures`, `PathCostFunctions`.
- Produces: Passing JUnit 5 test coverage for the public API.

- [ ] **Step 1: Create the test class**

```kotlin
/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunctions
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RouteFinder")
class RouteFinderTest : KoinTestBase() {
	private var context: EditingContext? = null

	@AfterEach
	fun closeContext() {
		(context as? cz.vutbr.fit.interlockSim.context.DefaultEditingContext)?.close()
		context = null
	}

	private fun linearContext(): EditingContext = TestTopologies.simpleLinearPath().also { context = it }

	private fun semaphoreContext(): EditingContext =
		TestTopologies.linearPathWithSemaphore(semaphoreAllowing = false).also { context = it }

	private fun yJunctionContext(): EditingContext = TestTopologies.yJunctionWithSwitch().also { context = it }

	private fun shuntingLoopContext(): EditingContext {
		val ctx = TestFixtures.loadShuntingXml()
			.let { file ->
				cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory()
					.createContext(file) as EditingContext
			}
		context = ctx
		return ctx
	}

	private fun findInOut(ctx: EditingContext, name: String): InOut =
		ctx.getInOuts().single { it.getName() == name }

	@Nested
	@DisplayName("findRoutes")
	inner class FindRoutes {
		@Test
		fun `returns single route on linear network`() {
			val ctx = linearContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx)

			assertThat(routes).hasSize(1)
			assertThat(routes[0].segments).hasSize(1)
			assertThat(routes[0].cost).isGreaterThan(0.0)
			assertThat(routes[0].start).isEqualTo(a)
			assertThat(routes[0].target).isEqualTo(b)
		}

		@Test
		fun `returns multiple alternatives on shunting loop sorted by cost`() {
			val ctx = shuntingLoopContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(routes).isNotEmpty()
			assertThat(routes.size).isGreaterThan(1)
			for (i in 0 until routes.size - 1) {
				assertThat(routes[i].cost).isLessThanOrEqualTo(routes[i + 1].cost)
			}
		}

		@Test
		fun `returns empty list for disconnected InOuts`() {
			val ctx = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 5, 5, false)
				.buildEditingContext()
				.also { context = it }
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx)

			assertThat(routes).isEmpty()
		}

		@Test
		fun `returns single empty route when start equals target`() {
			val ctx = linearContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")

			val routes = finder.findRoutes(a, a, ctx)

			assertThat(routes).hasSize(1)
			assertThat(routes[0].segments).isEmpty()
			assertThat(routes[0].cost).isEqualTo(0.0)
			assertThat(routes[0].costBreakdown).isEmpty()
		}

		@Test
		fun `cost breakdown sums to total cost`() {
			val ctx = semaphoreContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_ELEMENT_COUNT)

			assertThat(routes).isNotEmpty()
			val route = routes.first()
			assertThat(route.costBreakdown.sumOf { it.cost }).isEqualTo(route.cost)
		}

		@Test
		fun `respects switch constraints`() {
			val ctx = yJunctionContext()
			val finder = ctx.getRouteFinder()
			val exitMain = findInOut(ctx, "ExitMain")
			val exitBranch = findInOut(ctx, "ExitBranch")

			// Straight-to-branch switch transition is physically impossible.
			assertThat(finder.findRoutes(exitMain, exitBranch, ctx)).isEmpty()
			assertThat(finder.isRouteAvailable(exitMain, exitBranch, ctx)).isFalse()
		}
	}

	@Nested
	@DisplayName("isRouteAvailable")
	inner class IsRouteAvailable {
		@Test
		fun `returns true when route exists`() {
			val ctx = linearContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			assertThat(finder.isRouteAvailable(a, b, ctx)).isTrue()
		}

		@Test
		fun `returns false when nodes are disconnected`() {
			val ctx = TestContextBuilder()
				.withInOut("A", 1, 1, true)
				.withInOut("B", 5, 5, false)
				.buildEditingContext()
				.also { context = it }
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			assertThat(finder.isRouteAvailable(a, b, ctx)).isFalse()
		}
	}

	@Nested
	@DisplayName("context exposure")
	inner class ContextExposure {
		@Test
		fun `editing context exposes non-null route finder`() {
			val ctx = linearContext()
			assertThat(ctx.getRouteFinder()).isNotNull()
		}

		@Test
		fun `simulation context exposes non-null route finder`() {
			val ctx = TestTopologies.simpleLinearPathSimulation()
			try {
				assertThat(ctx.getRouteFinder()).isNotNull()
			} finally {
				ctx.close()
			}
		}
	}

	@Nested
	@DisplayName("simulation compatibility")
	inner class SimulationCompatibility {
		@Test
		fun `works with DynamicInOut inputs from simulation context`() {
			val ctx = TestTopologies.simpleLinearPathSimulation()
			try {
				val finder = ctx.getRouteFinder()
				val inOuts = ctx.getInOuts()
				val a = inOuts.single { it.name == "A" }
				val b = inOuts.single { it.name == "B" }

				val routes = finder.findRoutes(a, b, ctx)

				assertThat(routes).hasSize(1)
				assertThat(routes[0].segments).hasSize(1)
			} finally {
				ctx.close()
			}
		}
	}
}
```

- [ ] **Step 2: Verify the test compiles and passes**

Run: `./gradlew :core:test --tests "cz.vutbr.fit.interlockSim.context.RouteFinderTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add core/src/jvmTest/kotlin/cz/vutbr/fit/interlockSim/context/RouteFinderTest.kt
git commit -m "test(#595): add RouteFinder API tests

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: Run full quality gates

**Files:** None new.
- Test: `./gradlew build detekt ktlintCheck test`

- [ ] **Step 1: Run full build and quality checks**

Run: `./gradlew build detekt ktlintCheck test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: If failures occur, fix and re-run**

Common issues to watch for:
- ktlint line length violations (max 120).
- detekt complexity warnings.
- Missing imports in test class.
- `TestFixtures.loadShuntingXml()` path resolution (core test working dir is `desktop-ui`).

- [ ] **Step 3: Commit any fixes**

```bash
git commit -am "fix(#595): address quality gate issues

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: Open PR

**Files:** None.

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/goal-2-sp3-595-route-finder-api
```

- [ ] **Step 2: Open PR to `goal-2`**

Use `gh` or the GitHub web UI:

```bash
gh pr create \
  --repo bedaHovorka/interlockSim \
  --base goal-2 \
  --head feat/goal-2-sp3-595-route-finder-api \
  --title "feat(goal-2): PathFinder API and Route value type (#595)" \
  --body-file .github/PR_DESCRIPTION.txt
```

PR description should include:
- Summary of changes.
- Naming note: `RouteFinder`/`Route` instead of issue text `PathFinder`/`Path` to avoid collision with existing simulation `Path`.
- Acceptance criteria mapping.
- Quality gate results.

- [ ] **Step 3: Mark issue #595 with a progress comment**

Comment on the issue linking the PR and noting that implementation is complete pending review.

---

## Self-Review Checklist

- [x] **Spec coverage:**
  - `NetworkState` marker interface → Task 1.
  - `Route` value type → Task 2.
  - `RouteFinder` interface → Task 3.
  - `DefaultRouteFinder` implementation → Task 4.
  - Context exposure → Task 6.
  - Koin wiring → Tasks 5 and 7.
  - Unit tests → Task 8.
  - Quality gates → Task 9.
- [x] **Placeholder scan:** No TBD, TODO, or vague requirements.
- [x] **Type consistency:**
  - `RouteFinder.findRoutes` signature is consistent across interface, implementation, and tests.
  - `NetworkState` is passed as the context/environment instance.
  - `DefaultRouteFinder` constructor receives `AutomaticPathFindingService`.
- [x] **No internal leakage:** Public API in `context/` and `objects/paths/`; implementation in `pathfinding/`.
