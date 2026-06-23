# Kotlin Style Guide for interlockSim Migration

This document defines the code style and conventions for the interlockSim Java-to-Kotlin migration. The guiding principle is **conservative conversion** - we're translating Java syntax to Kotlin while preserving the original structure, logic, and readability.

## Table of Contents

1. [Philosophy](#philosophy)
2. [Code Style Rules](#code-style-rules)
3. [Kotlin Conversion Conventions](#kotlin-conversion-conventions)
4. [Tooling](#tooling)
5. [Dependency Injection with Koin](#dependency-injection-with-koin)
6. [Common Patterns](#common-patterns)
7. [Examples](#examples)

## Philosophy

### Conservative Approach

This is not a modernization project. This is a **syntax migration** from Java to Kotlin with these priorities:

1. **Preserve Structure** - Maintain Java class/method organization
2. **Preserve Logic** - No refactoring or redesign during conversion
3. **Maintain Readability** - Code should be familiar to Java developers
4. **Enable Gradual Improvement** - Kotlin idioms can be introduced later

### Why These Rules?

The interlockSim codebase is a working 2007 BSc thesis project with:
- Complex simulation logic that must remain correct
- Integration with jDisco library (Java 6 compatible)
- 237 tests that must continue to pass
- Historical value - preserving original design intent

**Rule of thumb**: When in doubt, choose the more explicit, Java-like Kotlin syntax.

## Code Style Rules

### Indentation and Formatting

**Tabs, not spaces** - Match existing Java code style:

```kotlin
// CORRECT - Uses tabs (width 4)
class Train(val id: Int) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun move() {
		if (id > 0) {
			logger.debug { "Moving train $id" }
		}
	}
}

// WRONG - Uses spaces
class Train(val id: Int) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun move() {
        if (id > 0) {
            logger.debug { "Moving train $id" }
        }
    }
}
```

**Line length**: Maximum 120 characters (matches Java)

**Encoding**: UTF-8 with LF line endings

**Final newline**: All files must end with a newline

### Configuration Files

#### .editorconfig

The `.editorconfig` file enforces these rules in all editors:

```ini
[*.kt]
charset = utf-8
indent_style = tab
indent_size = 4
tab_width = 4
max_line_length = 120
```

#### build.gradle.kts

Ktlint and Detekt plugins are configured in `build.gradle.kts`:
- **Ktlint 1.5.0** - Code formatting (respects .editorconfig)
- **Detekt 1.23.7** - Static analysis (conservative rules in detekt.yml)

## Kotlin Conversion Conventions

### Null Safety

**Explicit nullable types** - Be deliberate about nullability:

```kotlin
// GOOD - Clear nullability
val context: Context? = null              // Nullable
val railway: Railway = getRailway()       // Non-null

// AVOID - Platform types from Java
val result = javaMethod()                 // Type is String! (platform type)

// BETTER - Explicit type
val result: String? = javaMethod()        // Explicitly nullable
```

**Safe calls over !!** - Use safe call operator unless absolutely certain:

```kotlin
// GOOD
val length = train?.length ?: 0.0

// ACCEPTABLE with justification
val length = train!!.length  // train is guaranteed non-null here by assertion check above

// WRONG - No justification
val length = train!!.length
```

### Properties vs Fields

**Convert fields to properties** - Use Kotlin properties instead of Java fields:

```kotlin
// Java
private final double length;
private TrackOccupant in;

public double getLength() { return length; }
public TrackOccupant getIn() { return in; }
public void setIn(TrackOccupant value) { in = value; }

// Kotlin - Properties
private val length: Double    // val = immutable (final)
private var `in`: TrackOccupant?  // var = mutable, backticks for keyword

// Note: No explicit getters/setters needed unless custom logic
```

### Immutability

**Prefer val over var** - Use immutable variables when possible:

```kotlin
// GOOD
val maxSpeed = 100.0         // Cannot be reassigned
val trains = mutableListOf<Train>()  // Reference is immutable, content mutable

// ACCEPTABLE - Variable needs to change
var currentSpeed = 0.0       // Must be var if value changes
```

### Constructors

**Primary constructor in declaration** - Use Kotlin's concise constructor syntax:

```kotlin
// Java
public class SimpleTrack extends AbstractTrack {
    private final PathSeparator end1;
    private final PathSeparator end2;
    private final double length;

    public SimpleTrack(PathSeparator end1, PathSeparator end2, double length) {
        this.end1 = end1;
        this.end2 = end2;
        this.length = length;
    }
}

// Kotlin - Primary constructor
class SimpleTrack(
	private val end1: PathSeparator,
	private val end2: PathSeparator,
	private val length: Double
) : AbstractTrack() {
	// Body
}
```

### Type Inference

**Explicit types for public API** - Be explicit at boundaries:

```kotlin
// Public API - EXPLICIT types
fun getTrains(): List<Train> = trains.toList()
val maxSpeed: Double = 100.0

// Local variables - INFERENCE okay
val count = trains.size           // Type inferred as Int
val name = "Locomotive"          // Type inferred as String
```

### Collections

**Specify mutability explicitly** - Make collection mutability clear:

```kotlin
// Immutable (read-only)
val trainList: List<Train> = listOf(train1, train2)
val trainSet: Set<Train> = setOf(train1, train2)
val trainMap: Map<Int, Train> = mapOf(1 to train1, 2 to train2)

// Mutable
val trainList: MutableList<Train> = mutableListOf()
val trainSet: MutableSet<Train> = mutableSetOf()
val trainMap: MutableMap<Int, Train> = mutableMapOf()

// From Java - Preserve behavior
// Java: List<Train> trains = new ArrayList<>();
val trains: MutableList<Train> = ArrayList()  // Keep Java type if modified
```

### String Templates

**Use string templates instead of concatenation**:

```kotlin
// Java
logger.info("Train " + train.getId() + " at position " + position);

// Kotlin - String templates
logger.info("Train ${train.id} at position $position")

// Complex expressions - Use braces
logger.info("Train ${train.getId()} speed ${train.getSpeed()}")
```

### Data Classes

**Use data classes for simple value objects**:

```kotlin
// Java
public class TrainInfo {
    private final int id;
    private final String name;

    public TrainInfo(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    @Override
    public boolean equals(Object o) { /* ... */ }

    @Override
    public int hashCode() { /* ... */ }

    @Override
    public String toString() { /* ... */ }
}

// Kotlin - Data class
data class TrainInfo(
	val id: Int,
	val name: String
)
// equals(), hashCode(), toString(), copy() auto-generated
```

**When NOT to use data classes**:
- Classes with complex behavior (business logic)
- Classes with mutable state that shouldn't be copied
- Classes that extend other classes (except Any)
- Classes with custom equals/hashCode logic

### When Expressions

**Use when instead of switch**:

```kotlin
// Java
switch (state) {
    case FREE:
        return true;
    case RESERVED:
        return from == separator;
    case OCCUPIED:
        return false;
    default:
        throw new IllegalStateException("Unknown state: " + state);
}

// Kotlin - when expression
return when (state) {
	State.FREE -> true
	State.RESERVED -> from == separator
	State.OCCUPIED -> false
	// No default needed if exhaustive
}
```

### Extension Functions

**Gradually introduce extension functions** - Use sparingly during conversion:

```kotlin
// Utility class method (keep during initial conversion)
object TrackUtil {
	fun findById(tracks: List<Track>, id: Int): Track? {
		return tracks.find { it.id == id }
	}
}

// Extension function (introduce later)
fun List<Track>.findById(id: Int): Track? {
	return find { it.id == id }
}

// Usage
val track = tracks.findById(5)
```

#### Multiplatform-First Extension Patterns

When adding extension functions to existing data structures, prefer pure Kotlin stdlib over JVM-specific types to ensure compatibility with Kotlin Multiplatform (JS/Native targets).

**Example: Array2DMap Pathfinding Extensions** (`Array2DMapExtensions.kt`)

The `Array2DMap` class provides grid-based spatial representation for railway tracks. Extension functions were added for pathfinding and grid navigation using multiplatform-compatible approaches:

**Key Design Principles:**

1. **Avoid JVM-specific types**: Use `filter()`, `minWithOrNull()`, `maxWithOrNull()` instead of `NavigableSet`, `SortedSet`
2. **Lazy evaluation**: Use `asSequence()` for efficient spatial queries (neighbors, regions)
3. **Pure Kotlin types**: All function signatures use stdlib types only

**Navigation Extensions:**
```kotlin
// Grid boundary queries
fun <V> Array2DMap<V>.firstPoint(): Point?     // Smallest point in grid order
fun <V> Array2DMap<V>.lastPoint(): Point?      // Largest point in grid order

// Relative navigation (ordered by row first, then column)
fun <V> Array2DMap<V>.higherPoint(point: Point): Point?   // Next point after
fun <V> Array2DMap<V>.lowerPoint(point: Point): Point?    // Previous point before
fun <V> Array2DMap<V>.ceilingPoint(point: Point): Point?  // Point >= reference
fun <V> Array2DMap<V>.floorPoint(point: Point): Point?    // Point <= reference
```

**Range Query Extensions:**
```kotlin
// Submap views (efficient filtering without copying)
fun <V> Array2DMap<V>.subMap(fromPoint: Point, toPoint: Point): Map<Point, V>
fun <V> Array2DMap<V>.headMap(toPoint: Point): Map<Point, V>
fun <V> Array2DMap<V>.tailMap(fromPoint: Point): Map<Point, V>
```

**Pathfinding Extensions:**
```kotlin
// Neighbor queries (lazy sequences for efficiency)
fun <V> Array2DMap<V>.neighbors4(point: Point): Sequence<Point>     // 4-connected
fun <V> Array2DMap<V>.neighbors8(point: Point): Sequence<Point>     // 8-connected
fun <V> Array2DMap<V>.neighborEntries4(point: Point): Sequence<Pair<Point, V>>
fun <V> Array2DMap<V>.neighborEntries8(point: Point): Sequence<Pair<Point, V>>

// Spatial queries
fun <V> Array2DMap<V>.pointsWithinManhattan(point: Point, distance: Int): Sequence<Point>
fun <V> Array2DMap<V>.pointsInRegion(minX: Int, minY: Int, maxX: Int, maxY: Int): Sequence<Point>
```

**Multiplatform Implementation Example:**
```kotlin
// GOOD - Multiplatform-compatible (pure Kotlin)
fun <V> Array2DMap<V>.higherPoint(point: Point): Point? {
	val comparator = Array2DMap.POINT_COMPARATOR
	return keys.filter { comparator.compare(it, point) > 0 }
		.minWithOrNull(comparator)  // Pure Kotlin stdlib
}

// AVOID - JVM-only (breaks JS/Native compatibility)
fun <V> Array2DMap<V>.higherPoint(point: Point): Point? {
	val sorted = keys.toSortedSet()  // NavigableSet - JVM-specific
	return sorted.higher(point)      // Not available in Kotlin/JS
}
```

**Lazy Evaluation for Performance:**
```kotlin
// Returns Sequence (lazy) not List (eager)
fun <V> Array2DMap<V>.neighbors4(point: Point): Sequence<Point> =
	sequence {
		val candidates = listOf(
			Point(point.x, point.y - 1),  // up
			Point(point.x, point.y + 1),  // down
			Point(point.x - 1, point.y),  // left
			Point(point.x + 1, point.y)   // right
		)
		for (candidate in candidates) {
			if (containsKey(candidate)) {
				yield(candidate)  // Lazy generation
			}
		}
	}

// Usage in pathfinding (only evaluates needed neighbors)
val adjacentCells = grid.neighbors4(currentPoint)
	.filter { !visited.contains(it) }
	.take(1)  // Stops after first match
```

**Use Cases:**
- A* pathfinding algorithm (see PR #74 context)
- Dijkstra's shortest path
- Grid traversal and region queries
- Railway track connectivity analysis

For complete API reference, see `src/main/kotlin/cz/vutbr/fit/interlockSim/util/Array2DMapExtensions.kt`.

### Scope Functions

**Use scope functions judiciously** - Don't overuse:

```kotlin
// GOOD - Clear intent
val train = Train(id, length, speed).apply {
	setPosition(x, y)
	setVelocity(v)
}

// GOOD - Null safety
train?.let { t ->
	t.move()
	t.updatePosition()
}

// BAD - Too complex, hard to read
train?.let {
	it.apply {
		position = getPosition()?.let { p ->
			p.apply {
				x += velocity * time
			}
		}
	}
}
```

**Nesting limit**: Maximum 2 levels of scope functions

### Companion Objects

**Convert static members**:

```kotlin
// Java
public class Main {
    public static final String PROGRAM_NAME = "InterlockSim";
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) { /* ... */ }
}

// Kotlin - Companion object
class Main {
	companion object {
		const val PROGRAM_NAME = "InterlockSim"  // const for primitive/String constants
		private val logger = LoggerFactory.getLogger(Main::class.java)

		@JvmStatic
		fun main(args: Array<String>) { /* ... */ }
	}
}
```

**@JvmStatic annotation** - Use when Java code needs to call static methods

### Lambda Syntax

**Use trailing lambda syntax**:

```kotlin
// Java
trains.forEach(new Consumer<Train>() {
    @Override
    public void accept(Train train) {
        train.move();
    }
});

// Kotlin - Trailing lambda
trains.forEach { train ->
	train.move()
}

// Or with implicit 'it' for single parameter
trains.forEach {
	it.move()
}
```

### Java Interoperability

**Maintain clean Java interop** - jDisco is Java 6 compatible:

```kotlin
// Use @JvmStatic for static methods called from Java
companion object {
	@JvmStatic
	fun getInstance(): Main = instance
}

// Use @JvmField for public fields accessed from Java
companion object {
	@JvmField
	val PROGRAM_NAME = "InterlockSim"
}

// Use @JvmOverloads for default parameters
@JvmOverloads
fun createTrack(
	length: Double,
	maxSpeed: Double = 100.0
): Track { /* ... */ }
```

## Tooling

### Ktlint (Code Formatting)

Ktlint enforces consistent code formatting based on `.editorconfig`.

**Run ktlint check**:
```bash
./gradlew ktlintCheck
```

**Auto-fix formatting issues**:
```bash
./gradlew ktlintFormat
```

**Add pre-commit hook** (optional):
```bash
./gradlew addKtlintCheckGitPreCommitHook
```

**Output**: Reports in multiple formats
- Console output (plain text)
- `build/reports/ktlint/` - Checkstyle XML and HTML reports

### Detekt (Static Analysis)

Detekt performs static code analysis configured in `detekt.yml`.

**Run detekt**:
```bash
./gradlew detekt
```

**Generate baseline** (ignore existing issues):
```bash
./gradlew detektBaseline
```

**Output**: Reports in multiple formats
- Console output
- `build/reports/detekt/detekt.html` - HTML report
- `build/reports/detekt/detekt.xml` - XML report
- `build/reports/detekt/detekt.txt` - Text report

### Configuration Summary

#### Ktlint Settings (Lenient)
- Respects `.editorconfig` (tabs, 120 char lines)
- Standard Kotlin rules without experimental features
- No Android-specific rules

#### Detekt Settings (Conservative)
- **Complexity**: Increased thresholds for legacy code
  - CyclomaticComplexMethod: 20 (default 15)
  - LongMethod: 100 lines (default 60)
  - LargeClass: 800 lines (default 600)
- **Style**: Minimal enforcement
  - Allow tabs (NoTabs: false)
  - Allow both var and val during conversion
  - Don't enforce Kotlin idioms during initial conversion
- **Naming**: Standard Kotlin conventions
- **Potential Bugs**: Enabled for critical issues
- **Comments**: No documentation requirements during migration

See `detekt.yml` for complete configuration with detailed comments.

## Dependency Injection with Koin

### Overview

InterlockSim uses **Koin 3.5.6** for dependency injection - a lightweight (~1MB), Kotlin-native DI framework. Koin provides clean dependency management without static fields, code generation, or AOP/proxies.

**Key Benefits:**
- ✅ Eliminates companion object proliferation
- ✅ Simplifies testing with Mockito integration
- ✅ Zero overhead (direct instantiation)
- ✅ Future-proof (compatible with DSOL/Kalasim migration)

### Quick Reference

```kotlin
// Define dependencies in module
val myModule = module {
	single<XMLContextFactory> { XMLContextFactory() }  // Singleton
	single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }  // Factory for processes
	// Note: Do NOT inject contexts as singletons - they need fresh state
	// Instead, inject factories that create contexts
}

// Start Koin in Main.kt
fun main(args: Array<String>) {
	startKoin {
		modules(interlockSimModule)
	}
	// ... existing main logic
}

// Inject dependencies - Property delegation (preferred)
class MyClass {
	private val factory: XMLContextFactory by inject()
}

// Or constructor injection
class MyClass(private val factory: XMLContextFactory)

// Testing with Koin
@Test
fun myTest() {
	startKoin {
		modules(module {
			single<MyDependency> { mock() }
		})
	}
	// Test code
}

@AfterEach
fun tearDown() {
	stopKoin()  // CRITICAL: Always cleanup in tests
}
```

### Critical DI Rules

#### 1. sim/ Package — Koin Injection Allowed

**Rule:** Koin injection is allowed in `sim/` package — kDisco Phase 1 migration complete (2026-03-20).

**Rationale:** Physics calculations and simulation correctness must still be validated thoroughly after DI changes; the DI restriction itself is lifted.

```kotlin
// Prefer constructor injection in sim/ (easier to test and reason about)
class Train(private val context: SimulationContext)  // ✅ Constructor parameter

// Property injection also allowed
class Train {
	private val context: SimulationContext by inject()  // ✅ Allowed since 2026-03-20
}
```

#### 2. Contexts are NOT Singletons

**Rule:** Always inject factories, never contexts directly.

**Rationale:** Contexts must be fresh instances to prevent state bleeding between simulation runs.

```kotlin
// WRONG - Context as singleton
val contextModule = module {
	// ❌ Don't do this - contexts need fresh state per simulation
	single<SimulationContext> { 
		DefaultSimulationContext(100, 100, get<SimulationProcessFactory>())
	}
}

// CORRECT - Factory pattern preserved
val contextModule = module {
	single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }
	single<SimulationContextFactory> { XMLContextFactory() }  // ✅ Factory is singleton
}

// Usage
private val factory: SimulationContextFactory by inject()
val context = factory.createContext(file)  // Fresh instance each time
```

#### 3. Preserve Factory Patterns

**Rule:** Inject factories for objects that need parameterized construction.

```kotlin
// Module definition
val contextModule = module {
	// XMLContextFactory as singleton (stateless)
	single<XMLContextFactory> { XMLContextFactory() }

	// Interface bindings to XMLContextFactory
	single<EditingContextFactory> { get<XMLContextFactory>() }
	single<SimulationContextFactory> { get<XMLContextFactory>() }
}
```

### Common Koin Patterns

#### Singleton Pattern

```kotlin
// Define in module
val myModule = module {
	single<XMLContextFactory> { XMLContextFactory() }
}

// Use in code - Property delegation
class MyClass {
	private val factory: XMLContextFactory by inject()
}

// Or constructor injection
class MyClass(private val factory: XMLContextFactory)
```

#### Factory Pattern (Non-Singleton)

```kotlin
// Define in module - Example of parameterized factory (not currently used in interlockSim)
val myModule = module {
	factory<SimulationContext> { (cols: Int, rows: Int) ->
		DefaultSimulationContext(cols, rows, get<SimulationProcessFactory>())
	}
}

// Use in code with parameters
val context: SimulationContext = get { parametersOf(100, 100) }

// NOTE: In interlockSim, we use XMLContextFactory instead of Koin factory pattern
// because contexts need complex initialization from XML files
```

#### Scoped Instances

```kotlin
// Define scope - Example pattern (not currently used for contexts)
val myModule = module {
	scope<SimulationScope> {
		scoped<SimulationContext> { 
			DefaultSimulationContext(100, 100, get<SimulationProcessFactory>())
		}
	}
}

// Use scope
val scope = getKoin().createScope<SimulationScope>()
val context = scope.get<SimulationContext>()
// ... use context
scope.close()  // Close scope when done
```

### Testing with Koin

#### Before: Hand-Written Mock (268 lines)

```kotlin
class MockSimulationContext : SimulationContext {
	private val delegate: DefaultSimulationContext
	// ... 260+ lines of manual delegation
}

@Test
fun testShuntingLoop() {
	val mock = MockSimulationContext()
	val loop = ShuntingLoop(mock)
}
```

#### After: Koin + Mockito (10 lines)

```kotlin
@Test
fun testShuntingLoop() {
	startKoin {
		modules(module {
			single<SimulationContext> { mock() }
		})
	}

	val context: SimulationContext = get()
	whenever(context.time()).thenReturn(10.0)

	val loop: ShuntingLoop = get()
	verify(context, times(1)).run()
}

@AfterEach
fun tearDown() {
	stopKoin()  // CRITICAL: Always cleanup
}
```

### Common Koin Issues and Solutions

#### Issue: Koin not started

```
org.koin.core.error.NoBeanDefFoundException: No definition found
```

**Solution:** Ensure `startKoin { modules(...) }` is called in Main.kt before any `inject()` usage.

#### Issue: Multiple Koin instances

```
org.koin.core.error.KoinAppAlreadyStartedException
```

**Solution:** Stop Koin in test teardown: `stopKoin()` in `@AfterEach`.

#### Issue: Circular dependency

```
org.koin.core.error.InstanceCreationException: Could not create instance
```

**Solution:** Refactor to break circular dependency or use `lazy<T>()` instead of `get<T>()`.

#### Issue: Context state bleeding

**Symptoms:** Tests fail intermittently, context data persists between tests.

**Solution:** Always inject factories, not contexts. Verify `stopKoin()` in `@AfterEach`.

### Module Structure

InterlockSim DI modules are defined in `src/main/kotlin/cz/vutbr/fit/interlockSim/di/InterlockSimModule.kt`:

- **utilModule** - Utility classes (ready for expansion)
- **xmlModule** - XML parsing, XMLContextFactory
- **contextModule** - Context lifecycle management
- **objectsModule** - Domain model (minimal by design)
- **guiModule** - Swing components (ready for expansion)
- **sim/** - ✅ **ALLOWED** (kDisco Phase 1 migration complete 2026-03-20)

**Note:** Objects module is intentionally minimal. Domain objects (RailSwitch, RailSemaphore, tracks) are created optimally via XML reflection. Only extend when runtime construction is needed (e.g., Goal 16: Signal Explanation UI).

### Resources

- **Koin Documentation:** https://insert-koin.io/docs/reference/koin-core/
- **Koin GitHub:** https://github.com/InsertKoinIO/koin
- **Project Status:** See CLAUDE.md "Dependency Injection with Koin" section

## Common Patterns

### Logger Declaration

```kotlin
// Java
private static final Logger logger = LoggerFactory.getLogger(ClassName.class);

// Kotlin - Companion object
companion object {
	private val logger = LoggerFactory.getLogger(ClassName::class.java)
}

// Kotlin - Property (if logger is per-instance)
private val logger = LoggerFactory.getLogger(javaClass)
```

### SLF4J Logging with Lambda

```kotlin
// Java style (still valid)
logger.debug("Train {} at position {}", train.id, position)

// Kotlin style (lazy evaluation)
logger.debug { "Train ${train.id} at position $position" }

// With complex computation (lambda prevents unnecessary work)
logger.debug { "State: ${computeExpensiveState()}" }
```

### Assertions

```kotlin
// Java
assert in == null;
assert state != null : "state is null";

// Kotlin
assert(in == null)
assert(state != null) { "state is null" }

// Or use require/check for better errors
require(length > 0) { "length must be positive" }
check(state != null) { "state is null" }
```

### Inner Classes

```kotlin
// Java - Non-static inner class
public class Outer {
    private class Inner {
        // Has implicit reference to Outer
    }
}

// Kotlin - Inner class (has outer reference)
class Outer {
	inner class Inner {
		// Has implicit reference to Outer via this@Outer
	}
}

// Kotlin - Nested class (no outer reference, like Java static)
class Outer {
	class Nested {
		// No reference to Outer
	}
}
```

### Enum Classes

```kotlin
// Java
public enum State {
    FREE, RESERVED, OCCUPIED;
}

// Kotlin
enum class State {
	FREE,
	RESERVED,
	OCCUPIED
}

// With properties
enum class State(val displayName: String) {
	FREE("Free"),
	RESERVED("Reserved"),
	OCCUPIED("Occupied")
}
```

### Abstract Classes and Interfaces

```kotlin
// Java
public abstract class AbstractTrack implements Track {
    public abstract double length();

    public void printInfo() {
        System.out.println("Track length: " + length());
    }
}

// Kotlin
abstract class AbstractTrack : Track {
	abstract fun length(): Double

	fun printInfo() {
		println("Track length: ${length()}")
	}
}
```

## Examples

### Before and After: SimpleTrack.java to SimpleTrack.kt

**Original Java** (simplified):

```java
package cz.vutbr.fit.interlockSim.objects.tracks;

import java.util.IdentityHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;

public abstract class SimpleTrack extends AbstractTrack
        implements TrackSection, TrackFacility {

    private static final Logger logger = LoggerFactory.getLogger(SimpleTrack.class);
    private final IdentityHashMap<PathSeparator, Double> speeds = new IdentityHashMap<>();
    private final double length;
    private final PathSeparator[] ends;
    private TrackOccupant in;
    private PathSeparator from;
    private State state = State.FREE;

    public SimpleTrack(PathSeparator end1, PathSeparator end2,
                       double length, double maxSpeed1, double maxSpeed2) {
        super();
        if (length < MIN_LENGTH || maxSpeed1 < MINIMAL_MAX_SPEED ||
            maxSpeed2 < MINIMAL_MAX_SPEED) {
            throw new IllegalArgumentException("length or maxspeed is very small");
        }
        this.length = length;
        this.ends = new PathSeparator[]{end1, end2};
        this.speeds.put(end2, maxSpeed2);
        this.speeds.put(end1, maxSpeed1);
    }

    public void enter(TrackOccupant occupant) {
        if (logger.isInfoEnabled()) {
            logger.info("Block {} ENTRY: occupant={}, state={}->OCCUPIED",
                this, occupant, state);
        }
        assert in == null;
        in = occupant;
        from = null;
    }

    public boolean isFreeFrom(PathSeparator seg) {
        final boolean isFree = state == State.FREE;
        if (logger.isDebugEnabled()) {
            logger.debug("Track {} isFreeFrom: from={}, state={}, result={}",
                this, seg, state, isFree);
        }
        return isFree;
    }

    @Override
    public double length() {
        return length;
    }
}
```

**Converted Kotlin** (structure-preserving):

```kotlin
package cz.vutbr.fit.interlockSim.objects.tracks

import java.util.IdentityHashMap
import org.slf4j.LoggerFactory
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator

abstract class SimpleTrack(
	end1: PathSeparator,
	end2: PathSeparator,
	private val length: Double,
	maxSpeed1: Double,
	maxSpeed2: Double
) : AbstractTrack(), TrackSection, TrackFacility {

	companion object {
		private val logger = LoggerFactory.getLogger(SimpleTrack::class.java)
	}

	private val speeds: IdentityHashMap<PathSeparator, Double> = IdentityHashMap()
	private val ends: Array<PathSeparator>
	private var `in`: TrackOccupant? = null
	private var from: PathSeparator? = null
	private var state: State = State.FREE

	init {
		require(length >= MIN_LENGTH) { "length is very small" }
		require(maxSpeed1 >= MINIMAL_MAX_SPEED) { "maxSpeed1 is very small" }
		require(maxSpeed2 >= MINIMAL_MAX_SPEED) { "maxSpeed2 is very small" }

		this.ends = arrayOf(end1, end2)
		this.speeds[end2] = maxSpeed2
		this.speeds[end1] = maxSpeed1
	}

	fun enter(occupant: TrackOccupant) {
		if (logger.isInfoEnabled) {
			logger.info("Block {} ENTRY: occupant={}, state={}->OCCUPIED",
				this, occupant, state)
		}
		assert(`in` == null)
		`in` = occupant
		from = null
	}

	fun isFreeFrom(seg: PathSeparator): Boolean {
		val isFree = state == State.FREE
		if (logger.isDebugEnabled) {
			logger.debug("Track {} isFreeFrom: from={}, state={}, result={}",
				this, seg, state, isFree)
		}
		return isFree
	}

	override fun length(): Double {
		return length
	}
}
```

**Key conversion decisions**:
1. Primary constructor parameters (end1, end2, length, etc.)
2. `in` became `` `in` `` (backticks for keyword)
3. `final` → `val`, mutable fields → `var`
4. Nullable types: `in` and `from` are `?` (can be null)
5. Logger in companion object (static equivalent)
6. `IllegalArgumentException` → `require()` (more Kotlin-idiomatic)
7. Preserved method structure and logging exactly

### Before and After: Doubleton.java to Doubleton.kt

**Original Java** (simplified):

```java
package cz.vutbr.fit.interlockSim.util;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @deprecated should be replaced in kotlin with library thing like Pair
 */
public class Doubleton<T, V> extends AbstractSet<T> {

    enum IteratorState {
        INIT, FIRST, SECOND;
    }

    final class DoubletonIterator implements Iterator<T> {
        IteratorState state = IteratorState.INIT;

        public boolean hasNext() {
            assert state != null;
            return state != IteratorState.SECOND;
        }

        public T next() {
            assert state != null;
            if (state == IteratorState.INIT) {
                state = IteratorState.FIRST;
                return first;
            } else if (state == IteratorState.FIRST) {
                state = IteratorState.SECOND;
                return second;
            } else {
                throw new NoSuchElementException();
            }
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private final T first;
    private final T second;
    private final V info;

    public Doubleton(T first, T second, V info) {
        this.first = first;
        this.second = second;
        this.info = info;
    }

    public T getFirst() { return first; }
    public T getSecond() { return second; }
    public V getInfo() { return info; }

    @Override
    public Iterator<T> iterator() {
        return new DoubletonIterator();
    }

    @Override
    public int size() {
        return 2;
    }
}
```

**Converted Kotlin** (structure-preserving):

```kotlin
package cz.vutbr.fit.interlockSim.util

import java.util.NoSuchElementException

/**
 * @deprecated should be replaced in kotlin with library thing like Pair
 */
@Deprecated("Use Kotlin Pair or data class instead")
class Doubleton<T, V>(
	private val first: T,
	private val second: T,
	private val info: V
) : AbstractSet<T>() {

	enum class IteratorState {
		INIT,
		FIRST,
		SECOND
	}

	inner class DoubletonIterator : Iterator<T> {
		private var state: IteratorState = IteratorState.INIT

		override fun hasNext(): Boolean {
			check(state != null)
			return state != IteratorState.SECOND
		}

		override fun next(): T {
			check(state != null)
			return when (state) {
				IteratorState.INIT -> {
					state = IteratorState.FIRST
					first
				}
				IteratorState.FIRST -> {
					state = IteratorState.SECOND
					second
				}
				IteratorState.SECOND -> throw NoSuchElementException()
			}
		}
	}

	fun getFirst(): T = first
	fun getSecond(): T = second
	fun getInfo(): V = info

	override fun iterator(): Iterator<T> {
		return DoubletonIterator()
	}

	override val size: Int
		get() = 2
}
```

**Key conversion decisions**:
1. Primary constructor with properties
2. `final` inner class → `inner class` (needs outer reference)
3. Enum stays enum class
4. `next()` method: if-else chain → when expression (more Kotlin-idiomatic)
5. `size()` method → `size` property override
6. Preserved @Deprecated (kept Java comment, added Kotlin annotation)
7. `assert` → `check()` (throws IllegalStateException)

## Summary

### Do's
- Match existing Java code style (tabs, line length)
- Use explicit types for public APIs
- Be explicit about nullability (?, !!)
- Convert utility methods to extension functions gradually
- Use data classes for simple value objects
- Use when expressions instead of if-else chains
- Use string templates instead of concatenation
- Preserve structure and logic during conversion

### Don'ts
- Don't use spaces (use tabs)
- Don't exceed 120 character line length
- Don't over-use scope functions (max 2 levels deep)
- Don't use !! without justification
- Don't refactor or redesign during conversion
- Don't introduce advanced Kotlin features without careful consideration
- Don't break Java interoperability (jDisco compatibility)

### When in Doubt
- Choose the more explicit, Java-like Kotlin syntax
- Preserve the original structure
- Ask for clarification before making significant changes
- Remember: This is a syntax migration, not a modernization

## Code Quality Enforcement Levels

The interlockSim project uses a dual-level approach to code quality enforcement, recognizing the difference between legacy converted code and new Kotlin code.

### Level 1: Legacy/Converted Code (Permissive)

**Applies to:** All existing Kotlin files converted from Java

**Configuration:** `detekt.yml` (default)

**Rules:** Permissive - focuses on critical bugs, allows legacy patterns

**Philosophy:** Preserve Java structure, avoid forcing Kotlin idioms on converted code

**Run:**
```bash
./gradlew detekt
```

**Characteristics:**
- Allows var usage (mutability)
- Permits complex methods and classes (higher thresholds)
- No enforcement of Kotlin idioms (data classes, expression syntax)
- Flexible exception handling
- Optional documentation
- **TAB INDENTATION** (matches original Java code)

### Level 2: New Kotlin Code (Strict)

**Applies to:** New Kotlin files written from scratch (not Java conversions)

**Configuration:** `detekt-strict.yml`

**Rules:** Strict - enforces Kotlin best practices, immutability, documentation

**Philosophy:** New code should follow modern Kotlin idioms and best practices

**Location Markers:**
- Place new Kotlin code in `src/main/kotlin/**/new/` subdirectory
- Example: `src/main/kotlin/cz/vutbr/fit/interlockSim/new/MyNewClass.kt`

**Run:**
```bash
./gradlew detektStrict
```

**Enforced Rules:**
- **Immutability:** Prefer `val` over `var` (VarCouldBeVal)
- **Null Safety:** No unsafe calls or casts (UnsafeCallOnNullableType, UnsafeCast)
- **Documentation:** Public APIs must be documented (UndocumentedPublicClass/Function)
- **Complexity:** Lower thresholds - max 10 cyclomatic complexity, 60 line methods
- **Kotlin Idioms:**
  - Use data classes where appropriate
  - Prefer expression body syntax
  - Extract magic numbers to constants
  - Use require/check for preconditions
  - Prefer immutable collections
- **Code Quality:**
  - No unused private members
  - Limit return statements (max 2)
  - Use safe casts
  - Prefer structural equality over referential
- **Exception Handling:**
  - No printStackTrace
  - Require exception messages
  - No generic exception catching/throwing
- **Same TAB INDENTATION** as legacy code for consistency

### Migration Path

As the codebase matures:

1. **Phase 1 (Current):** All code uses permissive rules (detekt.yml)
2. **Phase 2:** Write new Kotlin features in `new/` directories with strict rules
3. **Phase 3:** Gradually refactor legacy code to meet strict rules
4. **Phase 4:** Eventually migrate all code to strict rules

### Tab Indentation for Both Levels

**CRITICAL:** Both permissive and strict configurations use **tabs** for indentation to maintain consistency with the original Java code style.

- `.editorconfig`: `indent_style = tab`, `indent_size = 4`, `max_line_length = 120`
- `detekt.yml`: `NoTabs: active: false`, `Indentation: active: false` (allows tabs, lets ktlint use .editorconfig)
- `detekt-strict.yml`: `NoTabs: active: false`, `Indentation: active: false` (same as permissive)
- Ktlint: Respects `.editorconfig` automatically

**Why tabs?** The original Java code from the develop branch uses tab indentation with 4-space visual width. To maintain consistency across the codebase and simplify the migration, all Kotlin code (both legacy and new) uses the same indentation style.

### Running Quality Checks

```bash
# Check legacy/converted code (permissive rules)
./gradlew detekt

# Check new Kotlin code (strict rules)
./gradlew detektStrict

# Check code formatting (preserves tabs)
./gradlew ktlintCheck

# Auto-format code (preserves tab indentation)
./gradlew ktlintFormat

# Run all checks
./gradlew build  # Includes detekt, ktlintCheck, tests
```

### Verification

To verify that tab indentation is preserved:

```bash
# Show tabs in a Kotlin file (tabs show as ^I)
cat -A src/main/kotlin/cz/vutbr/fit/interlockSim/util/Doubleton.kt | head -20

# Format a file and verify tabs remain
./gradlew ktlintFormat
cat -A <file>.kt | grep '^	'  # Should show leading tabs
```

## Test Fixtures and Utilities

### Overview

The `testutil` package provides centralized test fixtures and utilities to reduce duplication and improve test maintainability. **Always use these fixtures instead of creating inline test data.**

### TestFixtures: XML Configuration Loading

`TestFixtures` provides standardized loading of XML configuration files.

**Usage Pattern:**

```kotlin
import cz.vutbr.fit.interlockSim.testutil.TestFixtures

@Test
fun testWithShuntingLoop() {
    TestFixtures.loadShuntingXml().use { stream ->
        val context = factory.createContext(stream)
        // Test with context
    }
}
```

**Available Fixtures:**

- `loadShuntingXml()` - Main shunting loop (vyhybna.xml)
- `loadLinearTrackXml()` - Simple A→B track
- `loadMinimalNetworkXml()` - Minimal valid network
- `loadSwitchBasicXml()` - Basic switch topology
- `loadSemaphoreBasicXml()` - Basic semaphore configuration
- Plus 10+ other fixtures (see TestFixtures.kt)

**Benefits:**
- ✅ Consistent resource paths (no hardcoded strings)
- ✅ Proper resource management (returns InputStream)
- ✅ Clear error messages when fixtures missing
- ✅ Single source of truth for fixture locations

### TestTopologies: Programmatic Network Creation

`TestTopologies` provides reusable network topologies for tests that don't need XML.

**Context Type Variants:**

Each topology has two variants:
- **EditingContext** - For editor/topology tests (e.g., `simpleLinearPath()`)
- **SimulationContext** - For simulation/train tests (e.g., `simpleLinearPathSimulation()`)

**Example: EditingContext Variant**

```kotlin
import cz.vutbr.fit.interlockSim.testutil.TestTopologies

@Test
fun testTopologyNavigation() {
    TestTopologies.simpleLinearPath().use { context ->
        val navigator = context.getTopologyNavigator()
        // Test navigation logic
    }
}
```

**Example: SimulationContext Variant**

```kotlin
@Test
fun testTrainMovement() {
    TestTopologies.simpleLinearPathSimulation().use { context ->
        val trainService = context.getTrainNavigationService()
        // Test train navigation
    }
}
```

**Available Topologies:**

| Topology | EditingContext | SimulationContext | Description |
|----------|----------------|-------------------|-------------|
| Simple Linear (A→B) | `simpleLinearPath()` | `simpleLinearPathSimulation()` | 100m track, 80 m/s |
| Linear with Semaphore | `linearPathWithSemaphore(allowing)` | `linearPathWithSemaphoreSimulation(allowing)` | A→[S]→B with signal |
| Dead-End | `deadEndSingleInOut()` | `deadEndSingleInOutSimulation()` | Single InOut, no connections |

**When to Use Which:**
- **XML Fixtures** - For complex real-world topologies (vyhybna.xml, switches with multiple branches)
- **TestTopologies** - For simple, focused test scenarios (linear paths, single signals)
- **TestContextBuilder** - Only when you need custom coordinates or unique configurations

### Resource Management with .use {}

**All contexts implement `AutoCloseable` - always use `.use {}`:**

**Pattern 1: Single Context**

```kotlin
@Test
fun testSingleContext() {
    TestTopologies.simpleLinearPath().use { context ->
        // Context auto-closes at end of block
    }
}
```

**Pattern 2: Nested Contexts (XML → Editing → Simulation)**

```kotlin
@Test
fun testWithXmlContext() {
    TestFixtures.loadShuntingXml().use { xmlStream ->
        editingFactory.createContext(xmlStream).use { editingCtx ->
            simulationFactory.createContext(editingCtx).use { simCtx ->
                // All 3 resources auto-close in reverse order
            }
        }
    }
}
```

**Pattern 3: Shared Context (setUp/tearDown)**

When context is shared across multiple tests:

```kotlin
class MyTestSuite {
    private lateinit var context: SimulationContext

    @BeforeEach
    fun setUp() {
        context = TestTopologies.simpleLinearPathSimulation()
    }

    @AfterEach
    fun tearDown() {
        context.close()  // Manual close in tearDown
    }

    @Test
    fun test1() {
        // Use shared context
    }

    @Test
    fun test2() {
        // Use shared context
    }
}
```

### Anti-Patterns to Avoid

❌ **Don't hardcode XML paths:**
```kotlin
// BAD
val file = File("src/main/resources/.../vyhybna.xml")
val stream = javaClass.getResourceAsStream("/cz/.../vyhybna.xml")

// GOOD
val stream = TestFixtures.loadShuntingXml()
```

❌ **Don't create duplicate topologies:**
```kotlin
// BAD - duplicated 15 times across tests
val context = TestContextBuilder()
    .withInOut("A", 1, 1, true)
    .withInOut("B", 5, 5, false)
    .withConnection(1, 1, 5, 5, 100.0, 80.0)
    .buildEditingContext()

// GOOD - reusable fixture
val context = TestTopologies.simpleLinearPath()
```

❌ **Don't forget to close contexts:**
```kotlin
// BAD - resource leak
val context = factory.createContext(stream)
// ... test code ...
// context never closed!

// GOOD - auto-closes
factory.createContext(stream).use { context ->
    // ... test code ...
}
```

❌ **Don't mix context types:**
```kotlin
// BAD - simulation test using EditingContext
val context = TestTopologies.simpleLinearPath()  // Returns EditingContext
context.getTrainNavigationService()  // Won't work!

// GOOD - use simulation variant
val context = TestTopologies.simpleLinearPathSimulation()
context.getTrainNavigationService()  // Works!
```

### TestContextBuilder: Custom Topologies

Use `TestContextBuilder` only when TestTopologies doesn't fit:

```kotlin
// When you need custom coordinates or unique topology
val context = TestContextBuilder()
    .withInOut("Entry", 2, 3, true)     // Custom position
    .withInOut("Exit", 8, 9, false)
    .withConnection(2, 3, 8, 9, 150.0, 100.0)  // Custom length/speed
    .buildEditingContext()
```

### Summary: Decision Tree

```
Need test network?
├─ Is it vyhybna.xml or other real-world config?
│  └─ Use: TestFixtures.loadShuntingXml()
│
├─ Is it simple A→B, A→[S]→B, or dead-end?
│  ├─ For editor/topology tests?
│  │  └─ Use: TestTopologies.simpleLinearPath()
│  └─ For simulation/train tests?
│     └─ Use: TestTopologies.simpleLinearPathSimulation()
│
└─ Need custom coordinates or unique topology?
   └─ Use: TestContextBuilder()
```

## Parameterized Tests (JUnit 5)

**Status:** Adopted February 2026. Parameterized tests reduce duplication and improve coverage by running the same assertion logic across multiple inputs.

### When to Use Parameterized Tests

Use parameterized tests when:
- Testing the same behavior across multiple enum values or input combinations
- Verifying mathematical/geometric operations with known input→output pairs
- Testing matrix combinations (e.g., type × spatialType → expected segments)

**Do not** use parameterized tests for single-case tests or when each case needs substantially different setup logic.

### Pattern Reference

#### 1. `@EnumSource` — Exhaustive Enum Testing

Tests all values of an enum automatically. New enum values are covered without test changes.

```kotlin
@ParameterizedTest
@EnumSource(Segment::class)
fun `segment transformations work correctly`(segment: Segment) {
	assertThat(anti(anti(segment)))
		.withMessage("anti(anti($segment)) should be identity")
		.isSameInstanceAs(segment)
}
```

#### 2. `@EnumSource` with `names` — Filtered Enum Values

Tests a subset of enum values. Use when some values need different treatment.

```kotlin
@ParameterizedTest(name = "transition from STOP to {0}")
@EnumSource(value = Signal::class, names = ["FREE", "S30", "S40", "S60", "S80", "S100"])
fun `semaphore transitions from STOP to other aspects`(targetSignal: Signal) {
	assertThat(semaphore.signal).isEqualTo(Signal.STOP)
	semaphore.signal = targetSignal
	assertThat(semaphore.signal).isEqualTo(targetSignal)
}
```

#### 3. `@CsvSource` — Simple Input→Output Pairs

Best for tabular data. JUnit auto-converts CSV strings to enums, ints, booleans.

```kotlin
@ParameterizedTest(name = "neighbors{0} at ({1},{2}) returns {3} neighbors")
@CsvSource(
	"4, 1, 1, 4",   // center has 4 neighbors in 4-connectivity
	"4, 0, 0, 2",   // corner has only 2 neighbors in 4-connectivity
	"8, 1, 1, 6",   // center has 6 neighbors in 8-connectivity
	"8, 0, 0, 3"    // corner has 3 neighbors in 8-connectivity
)
fun `neighbor algorithm returns correct count`(
	connectivity: Int, x: Int, y: Int, expectedCount: Int
) {
	val neighbors = when (connectivity) {
		4 -> map.neighbors4(Point(x, y)).toList()
		8 -> map.neighbors8(Point(x, y)).toList()
		else -> error("Invalid connectivity: $connectivity")
	}
	assertThat(neighbors.size).isEqualTo(expectedCount)
}
```

#### 4. `@ValueSource` — Primitive Value Lists

For testing a single parameter across multiple values.

```kotlin
@ParameterizedTest(name = "d2r(r2d({0})) = {0}")
@ValueSource(ints = [-5, -1, 0, 1, 5, 10])
fun `d2r and r2d are inverse operations`(d: Int) {
	val roundTripped = r2d(d2r(d))
	assertThat(roundTripped)
		.withMessage("Round-trip conversion for d=$d")
		.isEqualTo(d)
}
```

#### 5. `@MethodSource` — Complex Objects via Companion

Use when test data is too complex for CSV. Define a `@JvmStatic` method in a companion object.

```kotlin
companion object {
	@JvmStatic
	fun cellTypePrefixes() = listOf(
		Arguments.of(RailSemaphore::class.java, "S1"),
		Arguments.of(RailSwitch::class.java, "SW1"),
		Arguments.of(InOut::class.java, "IO1"),
	)
}

@ParameterizedTest(name = "first name for {0} is {1}")
@MethodSource("cellTypePrefixes")
fun `generates correct first name for cell type`(
	cellClass: Class<out NodeCell>, expectedName: String
) {
	val name = AutoNameGenerator.generateName(cellClass, context)
	assertThat(name).isEqualTo(expectedName)
}
```

#### 6. `@ArgumentsSource` — Custom Provider Class

Use for complex matrices reusable across test files. Provider lives in `testutil/providers/`.

```kotlin
// In testutil/providers/SwitchActiveSegmentsProvider.kt
class SwitchActiveSegmentsProvider : ArgumentsProvider {
	override fun provideArguments(context: ExtensionContext): Stream<Arguments> = Stream.of(
		Arguments.of(Type.SIMPLE_LEFT_FALSE, SpatialType.HORIZONTAL, Segment.A, Segment.F, Segment.A, Segment.E),
		Arguments.of(Type.SIMPLE_RIGHT_FALSE, SpatialType.HORIZONTAL, Segment.A, Segment.F, Segment.A, Segment.G),
		// ... more combinations
	)
}

// In DynamicRailSwitchTest.kt
@ParameterizedTest(name = "{0}/{1}: MAIN({2},{3}), BRANCH({4},{5})")
@ArgumentsSource(SwitchActiveSegmentsProvider::class)
fun `getActiveSegments returns correct segments for type`(
	type: Type, spatialType: SpatialType,
	mainSeg1: Segment, mainSeg2: Segment,
	branchSeg1: Segment, branchSeg2: Segment
) { /* ... */ }
```

### Gotchas

**`@MethodSource` in `@Nested` inner classes:** Must use fully qualified name with `#` separator.

```kotlin
// WRONG — won't find the method from @Nested inner class:
@MethodSource("cellTypePrefixes")

// CORRECT — FQN syntax:
@MethodSource("cz.vutbr.fit.interlockSim.context.AutoNameGeneratorTest#cellTypePrefixes")
```

**Companion must be on outer class** when inner `@Nested` classes reference it.

**`@CsvSource` auto-conversion:** String `"STOP"` auto-converts to `Signal.STOP` if the parameter type is `Signal`. No explicit converter needed.

**Test display names:** Use `{0}`, `{1}`, etc. in `@ParameterizedTest(name = "...")` to reference arguments by position. Complex objects use their `toString()`.

### Decision Guide

```
Which parameterized annotation to use?
├─ Testing all/some values of an enum?
│  └─ @EnumSource (with optional names filter)
│
├─ Simple input→output pairs (primitives, enums, strings)?
│  └─ @CsvSource
│
├─ Single parameter, multiple primitive values?
│  └─ @ValueSource
│
├─ Complex objects or data classes as test input?
│  ├─ Used in one test class only?
│  │  └─ @MethodSource (companion @JvmStatic method)
│  └─ Reusable across multiple test classes?
│     └─ @ArgumentsSource (provider in testutil/providers/)
```

## Build & Development Environment

### Dependency Management

Dependencies are managed via Gradle with fallback strategy:
- **jDisco 1.2.0** - Discrete event simulation library (external Maven dependency, Java 6 compatible)
  - Repository: https://github.com/bedaHovorka/jdisco
  - Published to GitHub Packages: `https://maven.pkg.github.com/bedaHovorka/jdisco`
  - Fallback order: `mavenLocal()` (cache) → GitHub Packages → build fails
  - Requires GitHub authentication for package download (see below)
- **JUnit 5.11.4** - Testing framework (JUnit Jupiter API and Engine)
- **AssertK 0.28.1** - Fluent Kotlin assertion library
- **MockK 1.13.14** - Kotlin-native mocking framework (supports sealed classes, coroutines)
- **Mockito 5.21.0** - Java mocking framework (deprecated, being phased out in favor of MockK)
- **kotlin-logging-jvm 7.0.3** - Kotlin logging wrapper (lambda-based lazy evaluation)
- **SLF4J 2.0.17** + **Logback 1.5.23** - Logging backend (used by kotlin-logging)
- **Koin 3.5.6** - Kotlin-native dependency injection framework (adopted 2026-01-12, migration complete)

Gradle automatically downloads dependencies during the build. Configuration files:
- `build.gradle.kts` - Build configuration and dependency declarations
- `settings.gradle.kts` - Project settings
- `gradle.properties` - Version management and build properties

**GitHub Packages Authentication:**

To download jDisco from GitHub Packages, set these environment variables:
```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
```

Or create `~/.gradle/gradle.properties`:
```properties
gpr.user=your-github-username
gpr.key=your-personal-access-token
```

**Note:** In GitHub Actions CI/CD, authentication is automatic via `GITHUB_TOKEN`.

### Gradle Build Commands

**Clean and build (includes tests and uber JAR):**
```bash
./gradlew clean build
```

**Build only (compiles, tests, creates JAR):**
```bash
./gradlew build
```

**Run tests:**
```bash
./gradlew test                    # Unit tests only
./gradlew integrationTest         # Integration tests only
./gradlew test integrationTest    # All tests
```

**Create uber JAR (all dependencies included):**
```bash
./gradlew shadowJar
```

**Run application:**
```bash
./gradlew runSim                  # Pre-configured shunting loop example
./gradlew runEditor               # Launch editor GUI
./gradlew runExampleGui           # Animated GUI simulation (NEW)
./gradlew runExample -PexampleName=shuntingLoop -PendTime=300  # Custom example
```

**Generate JavaDoc documentation:**
```bash
./gradlew javadoc
```

**Show dependency tree:**
```bash
./gradlew dependencies
```

### Running Manually from JAR

After building with `./gradlew shadowJar`, run from the project root:

**Simulation mode:**
```bash
java -jar build/libs/interlockSim.jar sim [xmlFile]
```

**Editor mode:**
```bash
java -jar build/libs/interlockSim.jar edit [xmlFile]
```

**Built-in examples (console output):**
```bash
java -jar build/libs/interlockSim.jar example [exampleName] [endTime]
```

**Built-in examples with animated GUI:**
```bash
java -jar build/libs/interlockSim.jar exampleGui [exampleName] [endTime]

# Example: Run shunting loop with animation for 300 time units
java -jar build/libs/interlockSim.jar exampleGui shuntingLoop 300
```

To list available examples:
```bash
java -jar build/libs/interlockSim.jar example
```

### Docker Development Environment

**Prerequisites:** Docker/Docker Compose and X11 server (Linux: usually installed; macOS: XQuartz; Windows: VcXsrv/Xming)

**Docker Services:**
- **app:** Java application with X11 GUI support
- **text:** LaTeX thesis compilation

**Build services:**
```bash
# Set GitHub credentials for jDisco download
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token

# Build app (jDisco downloaded from GitHub Packages or uses local cache)
docker compose build app

# Build thesis
docker compose build text
```

**Run editor GUI:**
```bash
# Method 1 (Recommended): Use .Xauthority file (more secure)
docker compose up app

# Method 2: If you get authorization errors, allow X11 connections from Docker
xhost +local:docker
docker compose up app

# When done with Method 2, revoke access for security:
xhost -local:docker
```

**Run simulation example:**
```bash
docker compose run app java -jar interlockSim.jar example shuntingLoop 60
```

**Run simulation with custom XML:**
```bash
docker compose run -v $(pwd)/myfile.xml:/app/myfile.xml app java -jar interlockSim.jar sim myfile.xml
```

**Build thesis PDF:**
```bash
docker compose up text
# PDF will be available in artifacts/text/bakalarka.pdf
```

**Extract compiled JAR:**
```bash
docker compose build app
# JAR will be available in artifacts/app/interlockSim.jar
```

**Docker Architecture:**

Multi-stage Dockerfile: Builder stage (Temurin 21 JDK, compiles, tests, creates uber JAR) → Runner stage (Temurin 21 JRE, X11 support). `text/Dockerfile` for LaTeX thesis compilation (Debian Bookworm, TeX Live).

**X11 Forwarding Troubleshooting:**

If you encounter `java.awt.AWTError: Can't connect to X11 window server`:

1. **Check DISPLAY variable:**
   ```bash
   echo $DISPLAY  # Should show :0, :1, or :0.0
   ```

2. **Verify X11 is running:**
   ```bash
   xdpyinfo | head  # Should show display information
   ```

3. **Use xhost as fallback:**
   ```bash
   xhost +local:docker
   docker compose up app
   # When done:
   xhost -local:docker
   ```

4. **Check .Xauthority permissions:**
   ```bash
   ls -la ~/.Xauthority  # File should exist and be readable
   ```

5. **For Wayland users (Fedora 43+):**
   ```bash
   # Wayland uses a different socket location
   export DISPLAY=:0
   # Or set XDG_SESSION_TYPE=x11 to force X11 session
   ```

6. **For Fedora with SELinux (Fedora 43+):**
   ```bash
   # If you encounter AVC denial errors, configure SELinux:
   # See docs/FEDORA_DOCKER_X11_SETUP.md for detailed instructions

   # Option 1: Use pre-generated policy (fastest)
   sudo semodule -i desktop-ui/docker-x11/docker-x11-complete.pp

   # Option 2: Generate from audit log
   xhost +local:docker
   sudo ausearch -c 'java' --raw | sudo audit2allow -M docker-x11-complete
   sudo semodule -i docker-x11-complete.pp
   ```

**Artifacts:**

Build outputs copied to `./artifacts/`: `app/interlockSim.jar`, `text/bakalarka.pdf`

## Logging Configuration

### Overview

InterlockSim uses **kotlin-logging** (SLF4J wrapper) with Logback backend. Configuration: `src/main/resources/logback.xml`

### Usage in Kotlin Code

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

logger.debug { "Message with $variable" }  // Lambda-based lazy evaluation
logger.info { "Train ${train.id} at position $position" }
logger.warn { "State: ${computeExpensiveState()}" }  // Lambda prevents unnecessary work
```

### Benefits of Lambda-Based Logging

```kotlin
// Old style (always evaluates arguments)
logger.debug("State: " + computeExpensiveState())  // computeExpensiveState() called even if debug disabled

// New style (lazy evaluation)
logger.debug { "State: ${computeExpensiveState()}" }  // computeExpensiveState() only called if debug enabled
```

### Changing Log Levels

**Edit logback.xml:**
```xml
<configuration>
  <root level="DEBUG">  <!-- Change to DEBUG, INFO, WARN, ERROR -->
    <appender-ref ref="CONSOLE" />
    <appender-ref ref="FILE" />
  </root>
</configuration>
```

**Runtime override (without editing XML):**
```bash
# From command line
java -Dlogback.level=DEBUG -jar interlockSim.jar ...

# From Docker
docker compose run -e ROOT_LOG_LEVEL=DEBUG app java -jar interlockSim.jar ...
```

### Output Configuration

**Default output:**
- Console output (stdout)
- File output: `logs/interlockSim.log`

**Log format:**
```
2026-02-05 10:30:45.123 [main] DEBUG c.v.f.i.TrackSection - Block A ENTRY: occupant=Train1, state=FREE->OCCUPIED
```

## Code Quality Enforcement

### SonarQube Integration

SonarQube integration provides: code smells, security vulnerabilities, coverage (JaCoCo), duplication, complexity metrics, technical debt.

**Configuration files:**
- `build.gradle.kts` - SonarQube plugin and JaCoCo configuration (primary)
- `sonar-project.properties` - Additional SonarQube settings (optional)
- `.github/workflows/sonarqube.yml` - CI/CD integration for automated analysis

### Running SonarQube Analysis

**SonarCloud (recommended):** Sign up at https://sonarcloud.io, generate token, run:
```bash
./gradlew clean test jacocoTestReport sonar \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.organization=<your-org> \
  -Dsonar.token=<your-token>
```

**Local server:** `docker run -d -p 9000:9000 sonarqube:lts-community`, then use `-Dsonar.host.url=http://localhost:9000`

### Code Coverage with JaCoCo

Generate with `./gradlew test jacocoTestReport`. View HTML at `build/reports/jacoco/test/html/index.html`. Configure thresholds in `build.gradle.kts`.

### Quality Gates and CI/CD

Quality gates permissive by default. Enable strict: `sonar.qualitygate.wait=true`. CI/CD via `.github/workflows/sonarqube.yml` (requires SONAR_TOKEN/SONAR_ORGANIZATION secrets).

## Code Modification Guidelines

**Conservative approach differentiated by component type:**

### Restrictions for sim/ Package (Logic Only — Koin Now Allowed)

**Simulation Core (`sim/` package):**
- **Minimal changes only** - Be extremely conservative with simulation logic
- **No refactoring** - Do not restructure working simulation code
- **Tests required** - Any changes MUST have comprehensive test coverage first
- **No unsolicited improvements** - Only make explicitly requested changes
- **No hallucinated solutions** - Bugfixes must reference working tag behavior with minimal diffs; no speculative spaghetti code
- **Koin injection allowed** - The Koin restriction was lifted 2026-03-20 (kDisco Phase 1 complete)

**kDisco Library:**
- **Do not modify** - kDisco is maintained as a separate project at https://github.com/bedaHovorka/kdisco
- Report issues at the kDisco repository

### Flexible Development (Other Components)

**GUI (`gui/` package), Editor, Utilities, Context System:**
- **Modernization allowed** - Can refactor, improve, and apply Kotlin idioms
- **Tests required** - Must have test coverage before and after changes
- **Alignment required** - Changes must align with LONG_TERM_GOALS.md goals and architecture
- **Code quality** - Apply detekt-strict.yml rules for new Kotlin code
- **Rationale:** These components can be improved independently without affecting simulation correctness

### General Rules for All Changes

1. **Tests are mandatory** - Any modified code MUST be covered by tests (before and after)
2. **Align with goals** - Check that changes support or enable LONG_TERM_GOALS.md objectives
3. **No breaking changes** - Maintain backward compatibility with existing XML configurations and APIs
4. **Document decisions** - Update relevant documentation for architectural changes
5. **Quality gates** - All changes must pass: `./gradlew build detekt ktlintCheck test`

### Examples of Appropriate Changes

**ALLOWED (with tests):**
- Refactoring GUI components for Goal 20 (Accessibility)
- Adding new editor features for Goal 16 (Signal Explanation)
- Improving context serialization for Goal 5 (Save/Restore State)
- Modernizing utility classes with Kotlin idioms
- Adding metrics collection infrastructure for Goal 6

**RESTRICTED (sim/ package — conservative approach required):**
- Changing Train physics calculations
- Modifying kDisco process scheduling
- Restructuring simulation event handling
- Changing core simulation algorithms

**PROHIBITED:**
- Changes that break existing XML configurations
- Modifications that fail existing tests
- Changes that conflict with LONG_TERM_GOALS.md
- kDisco library modifications (maintain at https://github.com/bedaHovorka/kdisco)

## Project Architecture Context

### Static/Dynamic Separation Pattern

The interlockSim architecture separates **static configuration** from **dynamic simulation state** using the wrapper pattern.

**Applies to:** InOut, RailSwitch, RailSemaphore, TrackBlock

**Key Method:** `Context.toDynamic(static) → dynamic`

#### Pattern Overview

**Static Objects** (Editing Context):
- Immutable configuration set during network design
- Grid position, names, types, connections, speeds
- Used in `EditingContext` (mutable operations allowed)
- Examples: `InOut`, `RailSwitch`, `RailSemaphore`, `SimpleTrackBlock`

**Dynamic Wrappers** (Simulation Context):
- Mutable simulation state (occupancy, signals, reservations)
- Wraps static object (delegates configuration queries)
- Used in `SimulationContext` (immutable network structure)
- Examples: `DynamicInOut`, `DynamicRailSwitch`, `DynamicRailSemaphore`, `DynamicTrack`

**Identity Contract:**
- Wrappers use `System.identityHashCode(static)` for stable hash
- Same static object always returns same wrapper (IdentityHashMap caching)
- `===` reference equality based on wrapped object

#### Example: InOut (Entry/Exit Points)

**Static InOut:**
```kotlin
// In editing context (network design)
val editingContext: EditingContext = factory.createContext()
val staticInOut = InOut(
    name = "A",
    isEntry = false,  // Exit point
    spatialType = SpatialType.HORIZONTAL
)
editingContext.putCell(Point(11, 8), staticInOut)

// Static configuration queries:
staticInOut.getName()  // "A"
staticInOut.isEntry    // false
staticInOut.getPoint() // Point(11, 8)
```

**Dynamic InOut:**
```kotlin
// Transform to simulation context (immutable network)
val simContext = ContextTransformer.createSimulationContext(editingContext, factory)

// Get dynamic wrapper
val dynamicInOut = simContext.toDynamic(staticInOut) as DynamicInOut

// Wrapper delegates configuration to static:
assert(dynamicInOut.name == staticInOut.getName())      // ✓ Same
assert(dynamicInOut.isEntry == staticInOut.isEntry)      // ✓ Same
assert(dynamicInOut === simContext.toDynamic(staticInOut)) // ✓ Same wrapper instance

// Wrapper manages simulation state:
dynamicInOut.lastTrain = train  // Mutable operation (OK in simulation)
```

**Railway Domain Context:**
- **InOut elements** represent network boundaries (train spawn/despawn points)
- **Minimum 1 InOut required** per network (validated by XMLContextFactory)
- **Entry points** (`isEntry == true`) - trains enter network here
- **Exit points** (`isEntry == false`) - trains leave network here
- With bidirectional operation, a single InOut can serve as both entry and exit

**Why This Pattern:**
1. **Immutable editing context** - No simulation state during network design
2. **Mutable simulation context** - State changes isolated from configuration
3. **Clear separation of concerns** - Configuration vs runtime state
4. **Easy context transformation** - Editing → simulation conversion
5. **Stable identity** - Object identity preserved across state changes (enables deterministic testing)

**Usage Rule:** Always use `context.toDynamic(element)` before state operations in simulation code.

**Documented in:** `docs/STATIC_DYNAMIC_SEPARATION_ARCHITECTURE.md` (983 lines, comprehensive pattern documentation)

### Navigation Services Architecture

Three specialized services handle path finding and reservation using **scope-per-context** pattern:

**1. TopologyNavigator** - Static topology navigation (pure graph traversal, no state dependencies)
- Interface: `context/navigation/TopologyNavigator`
- Implementation: `context/navigation/DefaultTopologyNavigator`
- **Use Case**: Editor validation, network analysis without dynamic state
- **Access**: `EditingContext.getTopologyNavigator()` or `SimulationEnvironment.getTopologyNavigator()`
- **Methods**: `findPath()`, `getNextTrackSection()`, `findPathToNextSemaphore()`

**2. PathReservationService** - Dispatcher logic (find FREE paths, reserve atomically)
- Interface: `context/navigation/PathReservationService`
- Implementation: `context/navigation/DefaultPathReservationService`
- **Use Case**: Dispatcher finding available routes, interlocking path setup
- **Access**: `SimulationEnvironment.getPathReservationService()`
- **Features**: Atomic reservation, all-or-nothing semantics, TOCTOU race condition fix
- **Methods**: `reservePath()`, `releasePath()`, `findReservablePaths()`, `reservePathToAnyNextSemaphore()`

**3. TrainNavigationService** - Train-specific navigation (follow RESERVED paths only)
- Interface: `context/navigation/TrainNavigationService`
- Implementation: `context/navigation/DefaultTrainNavigationService`
- **Use Case**: Train requesting next track section (only through owned blocks)
- **Access**: `SimulationEnvironment.getTrainNavigationService()`
- **Features**: Explicit ownership validation, null = "not reserved for THIS train"
- **Methods**: `findReservedPathForTrain()`, `isPathReservedForTrain()`, `getReservedBlocks()`

**4. PathReservationRegistry** - Bidirectional train↔block ownership tracking
- Class: `context/navigation/PathReservationRegistry`
- **Features**: O(1) queries, scoped lifetime (one per context), shared by all services
- **Methods**: `register()`, `unregister()`, `getBlocks()`, `getOwner()`, `isOwnedBy()`

**Koin DI Integration:**
- Scope-per-context pattern (one registry per context, isolated between contexts)
- Services share ONE registry within context (consistent ownership view)
- Automatic cleanup via `Context.close()` (AutoCloseable pattern)

**Documentation:**
- `docs/PATH_DISCOVERY_ARCHITECTURE.md` - Design rationale, trade-offs, implementation phases (808 lines)
- `docs/PATH_DISCOVERY_MIGRATION_GUIDE.md` - Migration guide with before/after examples (547 lines)
- `docs/PATH_RESERVATION_ARCHITECTURE.md` - Original reservation service design (1069 lines)

### Advanced Koin Patterns

**Scope-per-Context Pattern:**

Each context (`DefaultEditingContext` and `DefaultSimulationContext`) creates its own Koin scope and passes itself as the scope source:

```kotlin
// In DefaultSimulationContext constructor:
val scope = GlobalContext.get().createScope(
    scopeId = System.identityHashCode(this).toString(),
    qualifier = named<DefaultSimulationContext>(),
    source = this  // Context accessible via getSource()
)
```

**Why Scoped, Not Singleton or Factory?**
- **singleton**: ❌ State bleeding between simulation runs
- **factory**: ❌ Each get() creates new instance, components don't share state
- **scoped**: ✅ One registry per context, shared by all components, isolated between contexts

**Services Retrieve Context via `getSource()`:**

No redundant `parametersOf(context)` - context is the scope source:

```kotlin
// In InterlockSimModule.kt:
scope<DefaultSimulationContext> {
    scoped<TopologyNavigator> {
        val context = getSource<DefaultSimulationContext>()
        DefaultTopologyNavigator(context)
    }

    scoped<PathReservationRegistry> { PathReservationRegistry() }

    scoped<PathReservationService> {
        val context = getSource<DefaultSimulationContext>()
        val navigator: TopologyNavigator = get()  // Shared within scope
        val registry: PathReservationRegistry = get()  // Shared within scope
        DefaultPathReservationService(navigator, context, registry)
    }
}
```

**Usage Patterns:**

```kotlin
// Production code - services accessed via context API
val context = buildSimulationContext()
val pathService = context.getPathReservationService()
val trainService = context.getTrainNavigationService()

// Both services share the same registry within this context
pathService.reservePath("train1", start, end)
val blocks = trainService.getReservedBlocks("train1") // Sees the same reservation

// Test code - direct scope access when needed
val navigator = context.scope.get<TopologyNavigator>()  // No parameters!

// Clean up scope when done (AutoCloseable pattern)
context.close()  // Idempotent
```

**Key Benefits:**
- No redundant `parametersOf(context)` - context is the scope source
- Shared registry within context, isolated between contexts
- Type-safe service retrieval via `get<T>()`
- Resource cleanup via `AutoCloseable` pattern
- Both `EditingContext` and `SimulationContext` support

### SimulationProcessFactory Pattern

**Factory Pattern (Phase 2, 2026-01-14):**

DefaultSimulationContext uses dependency injection to obtain a `SimulationProcessFactory` rather than directly instantiating simulation classes. This:
- Follows Dependency Inversion Principle (depends on abstraction, not concrete classes)
- Enables testing with mock factories
- Prepares for jDisco→DSOL/Kalasim migration

**Module Configuration:**

```kotlin
val simulationModule = module {
    single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }
}
```

**Usage in Context:**

```kotlin
class DefaultSimulationContext(
    cols: Int,
    rows: Int,
    private val processFactory: SimulationProcessFactory  // Injected via Koin
) : BaseContext(cols, rows), SimulationContext {
    // Factory creates simulation processes (Generator, InOutWorker)
}
```

## Railway Domain Rules

### Minimum InOut Requirement

**Requirement:** Every railway network must have at least 1 InOut element (entry/exit point).

**Rationale:**
- With bidirectional train operation (PR #356), a single InOut can serve as both entry and exit
- Train can enter, travel through the network, reverse direction, and exit through the same InOut
- Networks with < 1 InOuts are invalid (no way for trains to enter/exit)

**Validation:**
- **Editor**: GUI prevents saving contexts with < 1 InOuts (Issue #80)
- **XML loading**: XMLContextFactory validates during parse and throws `IllegalArgumentException`
- **Test coverage**: See `InOutValidationTest` (Issue #79)

**Example Valid Network (single InOut):**
```xml
<RailwayNet>
  <InOut name="EntryExit" x="1" y="1" entry="true" />
  <!-- At least 1 InOut required; with bidirectional trains, this can serve as both entry and exit -->
</RailwayNet>
```

## Resources

- [Kotlin Official Style Guide](https://kotlinlang.org/docs/coding-conventions.html)
- [Kotlin for Java Developers](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
- [Detekt Documentation](https://detekt.dev/)
- [Ktlint Documentation](https://pinterest.github.io/ktlint/)

## Maintenance

This style guide should be updated as the migration progresses and patterns emerge. After Phase 3 conversion is complete, we may gradually introduce more Kotlin idioms in Phase 4+.

**Last Updated**: 2026-02-05 (Major update: Added Build & Development Environment, Logging Configuration, Code Quality Enforcement, Code Modification Guidelines, Project Architecture Context sections. Expanded from 1634 to ~2400 lines as part of CLAUDE.md compaction effort.)
