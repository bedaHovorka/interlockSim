package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.java.KoinJavaComponent.getKoin
import java.io.InputStream

/**
 * Centralized test fixture repository providing consistent access to XML configuration files
 * and common network topologies.
 *
 * ## Usage Patterns
 *
 * ### Loading XML Fixtures
 * ```kotlin
 * // Main resource (shunting loop configuration)
 * TestFixtures.loadShuntingXml().use { stream ->
 *     val context = factory.createContext(stream)
 *     // Use context
 * }
 *
 * // Test fixtures
 * TestFixtures.loadLinearTrackXml().use { stream ->
 *     // Use stream
 * }
 * ```
 *
 * ### Common Network Topologies
 * ```kotlin
 * // Simple A→B linear path (EditingContext for editor/topology tests)
 * TestTopologies.simpleLinearPath().use { context ->
 *     // Use editing context
 * }
 *
 * // Same topology but SimulationContext (for simulation/train tests)
 * TestTopologies.simpleLinearPathSimulation().use { context ->
 *     // Use simulation context
 * }
 *
 * // Linear path with semaphore
 * TestTopologies.linearPathWithSemaphore(semaphoreAllowing = true).use { context ->
 *     // EditingContext variant
 * }
 * ```
 *
 * ## Design Principles
 * - All XML loading returns `InputStream` (caller manages lifecycle via `.use {}`)
 * - Named topologies return `EditingContext` implementing `AutoCloseable`
 * - No inline file paths in test code (all centralized here)
 * - Consistent resource naming conventions
 *
 * @since 2026-02-04
 * @see TestTopologies
 * @see TestContextBuilder
 */
object TestFixtures {
	/**
	 * Loads the main shunting loop configuration (vyhybna.xml).
	 *
	 * This is the primary example network used throughout the codebase for:
	 * - Integration tests
	 * - Simulation examples
	 * - Editor demonstrations
	 *
	 * Note: "vyhybna" is Czech for "shunting loop" - the English name is preferred in API.
	 *
	 * @return InputStream to vyhybna.xml (caller must close)
	 * @throws IllegalStateException if resource not found
	 */
	fun loadShuntingXml(): InputStream {
		return loadMainResource("vyhybna.xml")
	}

	/**
	 * Loads linear-track.xml fixture (simple A→B track).
	 *
	 * @return InputStream to linear-track.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadLinearTrackXml(): InputStream {
		return loadTestFixture("linear-track.xml")
	}

	/**
	 * Loads minimal-network.xml fixture (smallest valid network).
	 *
	 * @return InputStream to minimal-network.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadMinimalNetworkXml(): InputStream {
		return loadTestFixture("minimal-network.xml")
	}

	/**
	 * Loads switch-basic.xml fixture (basic switch topology).
	 *
	 * @return InputStream to switch-basic.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadSwitchBasicXml(): InputStream {
		return loadTestFixture("switch-basic.xml")
	}

	/**
	 * Loads semaphore-basic.xml fixture (basic semaphore configuration).
	 *
	 * @return InputStream to semaphore-basic.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadSemaphoreBasicXml(): InputStream {
		return loadTestFixture("semaphore-basic.xml")
	}

	/**
	 * Loads two-tracks-parallel.xml fixture (parallel track configuration).
	 *
	 * @return InputStream to two-tracks-parallel.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadTwoTracksParallelXml(): InputStream {
		return loadTestFixture("two-tracks-parallel.xml")
	}

	/**
	 * Loads empty-grid.xml fixture (empty network for editor tests).
	 *
	 * @return InputStream to empty-grid.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadEmptyGridXml(): InputStream {
		return loadTestFixture("empty-grid.xml")
	}

	/**
	 * Loads praha-hlavni-nadrazi.xml fixture (complex real-world station).
	 *
	 * @return InputStream to praha-hlavni-nadrazi.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadPrahaHlavniNadraziXml(): InputStream {
		return loadTestFixture("praha-hlavni-nadrazi.xml")
	}

	/**
	 * Loads rudyUjezd.xml fixture (Rudý Újezd station example).
	 *
	 * @return InputStream to rudyUjezd.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadRudyUjezdXml(): InputStream {
		return loadTestFixture("rudyUjezd.xml")
	}

	/**
	 * Loads legacy-network-no-names.xml fixture (network without element names).
	 *
	 * @return InputStream to legacy-network-no-names.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadLegacyNetworkNoNamesXml(): InputStream {
		return loadTestFixture("legacy-network-no-names.xml")
	}

	/**
	 * Loads valid-special-chars-names.xml fixture (tests name validation).
	 *
	 * @return InputStream to valid-special-chars-names.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadValidSpecialCharsNamesXml(): InputStream {
		return loadTestFixture("valid-special-chars-names.xml")
	}

	// Invalid fixtures for negative testing

	/**
	 * Loads invalid-malformed-xml.xml fixture (XML parsing error tests).
	 *
	 * @return InputStream to invalid-malformed-xml.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadInvalidMalformedXml(): InputStream {
		return loadTestFixture("invalid-malformed-xml.xml")
	}

	/**
	 * Loads invalid-missing-grid-size.xml fixture (schema validation tests).
	 *
	 * @return InputStream to invalid-missing-grid-size.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadInvalidMissingGridSizeXml(): InputStream {
		return loadTestFixture("invalid-missing-grid-size.xml")
	}

	/**
	 * Loads invalid-missing-spatial-type.xml fixture (schema validation tests).
	 *
	 * @return InputStream to invalid-missing-spatial-type.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadInvalidMissingSpatialTypeXml(): InputStream {
		return loadTestFixture("invalid-missing-spatial-type.xml")
	}

	/**
	 * Loads invalid-wrong-root-element.xml fixture (schema validation tests).
	 *
	 * @return InputStream to invalid-wrong-root-element.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadInvalidWrongRootElementXml(): InputStream {
		return loadTestFixture("invalid-wrong-root-element.xml")
	}

	/**
	 * Loads invalid-name-special-chars.xml fixture (name validation tests).
	 *
	 * @return InputStream to invalid-name-special-chars.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadInvalidNameSpecialCharsXml(): InputStream {
		return loadTestFixture("invalid-name-special-chars.xml")
	}

	/**
	 * Loads invalid-name-too-long.xml fixture (name validation tests).
	 *
	 * @return InputStream to invalid-name-too-long.xml
	 * @throws IllegalStateException if resource not found
	 */
	fun loadInvalidNameTooLongXml(): InputStream {
		return loadTestFixture("invalid-name-too-long.xml")
	}

	/**
	 * Loads invalid InOut count fixtures (InOut validation tests).
	 *
	 * **Available fixtures:**
	 * - "zero-inouts.xml" - Network with 0 InOut elements
	 * - "single-inout.xml" - Network with 1 InOut element
	 *
	 * **Validation rule:** Minimum 2 InOut elements required (entry and exit points).
	 * Single InOut networks are dead-ends (train enters but cannot exit).
	 *
	 * @param fixtureName Fixture filename (e.g., "zero-inouts.xml", "single-inout.xml")
	 * @return InputStream to invalid InOut fixture
	 * @throws IllegalStateException if fixture not found
	 * @see cz.vutbr.fit.interlockSim.xml.XMLContextFactory
	 */
	fun loadInvalidInOutXml(fixtureName: String): InputStream {
		return loadTestFixture(fixtureName)
	}

	/**
	 * Loads resource from main resources directory.
	 *
	 * @param resourceName Resource filename (e.g., "vyhybna.xml")
	 * @return InputStream to resource
	 * @throws IllegalStateException if resource not found
	 */
	private fun loadMainResource(resourceName: String): InputStream {
		return javaClass.getResourceAsStream(
			"/cz/vutbr/fit/interlockSim/resource/$resourceName"
		) ?: error("Resource not found in main resources: $resourceName")
	}

	/**
	 * Loads resource from test fixtures directory.
	 *
	 * @param fixtureFilename Fixture filename (e.g., "linear-track.xml")
	 * @return InputStream to fixture
	 * @throws IllegalStateException if fixture not found
	 */
	private fun loadTestFixture(fixtureFilename: String): InputStream {
		return javaClass.getResourceAsStream(
			"/cz/vutbr/fit/interlockSim/xml/fixtures/$fixtureFilename"
		) ?: error("Test fixture not found: $fixtureFilename")
	}
}

/**
 * Common network topology fixtures for testing.
 *
 * Provides pre-configured network topologies that appear frequently in tests,
 * eliminating duplication of inline `TestContextBuilder` configurations.
 *
 * ## Available Topologies
 *
 * ### Simple Linear Path (A→B)
 * ```kotlin
 * TestTopologies.simpleLinearPath().use { context ->
 *     // 100m track, 80 m/s speed limit
 *     // InOut A at (1,1) → InOut B at (5,5)
 * }
 * ```
 *
 * ### Linear Path with Semaphore (A→[S]→B)
 * ```kotlin
 * TestTopologies.linearPathWithSemaphore(semaphoreAllowing = true).use { context ->
 *     // InOut A → Semaphore → InOut B
 *     // Semaphore at (3,3), configurable RED/GREEN state
 * }
 * ```
 *
 * ### Y-Junction with Switch (EntryA/EntryB → Junction → ExitC/ExitD)
 * ```kotlin
 * TestTopologies.yJunctionWithSwitch().use { context ->
 *     // Two entries converge at switch, diverge to two exits
 *     // Switch at (30,30), 4 track segments (250m each)
 * }
 * ```
 *
 * ### Linear Path with Multiple Semaphores (A→[S1]→[S2]→...→B)
 * ```kotlin
 * TestTopologies.linearPathWithSemaphoreSequence(semaphoreCount = 3).use { context ->
 *     // Configurable number of semaphores in series
 *     // Each semaphore 5 grid units apart, 100m track segments
 * }
 * ```
 *
 * ### Dead-End Single InOut
 * ```kotlin
 * TestTopologies.deadEndSingleInOut().use { context ->
 *     // Single InOut with no connections (for negative tests)
 * }
 * ```
 *
 * ## Context Type Variants
 *
 * Each topology is available in two variants:
 * - **Base functions** (e.g., `simpleLinearPath()`) - Return `EditingContext`
 *   - Use for: Editor tests, topology validation, static structure tests
 * - **Simulation variants** (e.g., `simpleLinearPathSimulation()`) - Return `SimulationContext`
 *   - Use for: Train navigation, path reservation, simulation execution tests
 *
 * ## Complex Switch Patterns
 *
 * For topologies with multiple switches AND semaphore sequences on each route,
 * use XML fixtures instead of programmatic builders:
 * - Simple switch: `TestFixtures.loadSwitchBasicXml()`
 * - Complex routing: `TestFixtures.loadShuntingXml()` (vyhybna.xml with multiple switches and signals)
 *
 * These patterns are too complex for fluent builders and benefit from visual XML editing.
 *
 * ## Design Notes
 * - Contexts are NOT frozen (mutable for test setup)
 * - Grid coordinates are consistent across similar topologies
 * - Use `.use {}` for automatic resource cleanup
 *
 * @since 2026-02-04
 * @see TestFixtures
 * @see TestContextBuilder
 */
object TestTopologies {
	/**
	 * Creates a simple linear path topology: A→B.
	 *
	 * Configuration:
	 * - InOut "A" at (1,1) - entry point
	 * - InOut "B" at (5,5) - exit point
	 * - Single track connection: 100m length, 80 m/s speed limit
	 * - Grid size: 10x10
	 *
	 * Common use cases:
	 * - Basic path finding tests
	 * - Train movement validation
	 * - Simple reservation scenarios
	 *
	 * @return EditingContext with simple linear topology (must close)
	 */
	fun simpleLinearPath(): EditingContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildEditingContext()

	/**
	 * Creates a linear path with semaphore: A→[S]→B.
	 *
	 * Configuration:
	 * - InOut "A" at (1,1) - entry point
	 * - Semaphore at (3,3) - signal control point
	 * - InOut "B" at (5,5) - exit point
	 * - Two track segments: A→S (100m), S→B (100m), 80 m/s each
	 * - Grid size: 10x10
	 *
	 * Common use cases:
	 * - Signal logic testing
	 * - Path reservation with stops
	 * - Train wait behavior validation
	 *
	 * @param semaphoreAllowing Initial semaphore state (true=GREEN/allowing, false=RED/blocking)
	 * @return EditingContext with semaphore topology (must close)
	 */
	fun linearPathWithSemaphore(semaphoreAllowing: Boolean = false): EditingContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withSemaphore(3, 3, semaphoreAllowing)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 3, 3, 100.0, 80.0)
			.withConnection(3, 3, 5, 5, 100.0, 80.0)
			.buildEditingContext()

	/**
	 * Creates a Y-junction topology: single entry diverges at switch to two exits (diverging Y).
	 *
	 * **IMPORTANT:** A SIMPLE switch only has 3 segments, supporting only 3 connections total.
	 * This topology uses: 1 entry → switch → 2 exits (diverging Y-junction).
	 *
	 * Configuration:
	 * - InOut "Entry" at (5,30) - single entry point from west
	 * - RailSwitch "Junction" at (30,30) - divergence point (SIMPLE_RIGHT_FALSE, HORIZONTAL)
	 * - InOut "ExitMain" at (55,30) - main route exit (straight through, segment F)
	 * - InOut "ExitBranch" at (50,40) - branch route exit (diverging southeast, segment G)
	 * - Three track segments (SIMPLE switch supports exactly 3 connections):
	 *   - Entry → Switch: 250m, 80 m/s (segment A - west)
	 *   - Switch → ExitMain: 250m, 100 m/s (segment F - east, main route)
	 *   - Switch → ExitBranch: 180m, 90 m/s (segment G - southeast, branch route)
	 * - Grid size: 60x50
	 *
	 * Switch segments (SIMPLE_RIGHT_FALSE + HORIZONTAL):
	 * - A (West) - stem/entry from left
	 * - F (East) - main direction to right
	 * - G (Southeast) - branch direction diagonal down-right
	 *
	 * Common use cases:
	 * - Multi-route path finding
	 * - Switch configuration testing
	 * - Diverging traffic flow simulation
	 *
	 * For converging Y-junction (2 entries → 1 exit), see XMLRoundTripTest examples.
	 * For multiple switches with semaphores, use `TestFixtures.loadShuntingXml()`.
	 *
	 * @return EditingContext with valid diverging Y-junction topology (must close)
	 */
	fun yJunctionWithSwitch(): EditingContext {
		val context = DefaultEditingContext(60, 50)

		// Create single entry point (from west)
		val entry = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)

		// Create two exit points
		val exitMain = InOut("ExitMain", true, Cell.SpatialType.HORIZONTAL)
		val exitBranch = InOut("ExitBranch", true, Cell.SpatialType.HORIZONTAL)

		// Create central junction with switch (SIMPLE_RIGHT_FALSE - diverges right/southeast)
		val switch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		switch.setName("Junction")

		// Place elements on grid (aligned for proper segment connections)
		context.putCell(Point(5, 30), entry)        // West of switch
		context.putCell(Point(30, 30), switch)      // Center
		context.putCell(Point(55, 30), exitMain)    // East of switch (straight, segment F)
		context.putCell(Point(50, 40), exitBranch)  // Southeast of switch (diagonal, segment G)

		// Create track connections (only 3 - matching switch's 3 segments)
		val trackEntry = SimpleTrackBlock(entry, switch, 250.0, 80.0)          // Segment A
		val trackMain = SimpleTrackBlock(switch, exitMain, 250.0, 100.0)       // Segment F
		val trackBranch = SimpleTrackBlock(switch, exitBranch, 180.0, 90.0)    // Segment G

		context.joinCells(Point(5, 30), Point(30, 30), trackEntry)
		context.joinCells(Point(30, 30), Point(55, 30), trackMain)
		context.joinCells(Point(30, 30), Point(50, 40), trackBranch)

		return context
	}

	/**
	 * Creates a linear path with multiple semaphores in sequence: A→[S1]→[S2]→...→[Sn]→B.
	 *
	 * Configuration:
	 * - InOut "A" at (1,5) - entry point
	 * - N semaphores evenly spaced (5 units apart) along horizontal path
	 * - InOut "B" at calculated end position - exit point
	 * - Track segments connecting each element (100m, 80 m/s each)
	 * - Grid size: dynamically calculated based on semaphore count
	 *
	 * Common use cases:
	 * - Multi-signal coordination testing
	 * - Complex path reservation scenarios
	 * - Train progression through multiple control points
	 * - Signal sequencing validation
	 *
	 * @param semaphoreCount Number of semaphores (default 3, min 1)
	 * @param semaphoresAllowing Initial state for all semaphores (true=GREEN/allowing, false=RED/blocking)
	 * @return EditingContext with multi-semaphore topology (must close)
	 * @throws IllegalArgumentException if semaphoreCount < 1
	 */
	fun linearPathWithSemaphoreSequence(
		semaphoreCount: Int = 3,
		semaphoresAllowing: Boolean = false
	): EditingContext {
		require(semaphoreCount >= 1) { "semaphoreCount must be at least 1, got $semaphoreCount" }

		// Calculate grid size (entry + semaphores + exit + padding)
		val gridSize = (semaphoreCount + 2) * 5 + 10

		val context = DefaultEditingContext(gridSize, gridSize)

		// Create entry point
		val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(1, 5), inA)

		var previousX = 1
		var previousElement: NodeCell = inA

		// Create semaphores
		for (i in 1..semaphoreCount) {
			val semaphore = RailSemaphore(semaphoresAllowing, Cell.SpatialType.HORIZONTAL)
			semaphore.setName("Sem$i")

			val currentX = 1 + (i * 5)
			context.putCell(Point(currentX, 5), semaphore)

			// Connect previous element to this semaphore
			val track = SimpleTrackBlock(previousElement, semaphore, 100.0, 80.0)
			context.joinCells(Point(previousX, 5), Point(currentX, 5), track)

			previousX = currentX
			previousElement = semaphore
		}

		// Create exit point
		val exitX = 1 + ((semaphoreCount + 1) * 5)
		val outB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(exitX, 5), outB)

		// Connect last semaphore to exit
		val finalTrack = SimpleTrackBlock(previousElement, outB, 100.0, 80.0)
		context.joinCells(Point(previousX, 5), Point(exitX, 5), finalTrack)

		return context
	}

	/**
	 * ## Complex Switch + Semaphore Patterns
	 *
	 * For topologies with multiple switches AND semaphore sequences on each route,
	 * use XML fixtures instead of programmatic builders:
	 *
	 * - Simple switch: `TestFixtures.loadSwitchBasicXml()`
	 * - Complex routing: `TestFixtures.loadShuntingXml()` (vyhybna.xml)
	 *
	 * These patterns are too complex for fluent builders and benefit from
	 * visual XML editing.
	 */

	/**
	 * Creates a dead-end topology: single InOut with no connections.
	 *
	 * Configuration:
	 * - InOut "A" at (1,1) - isolated entry point
	 * - No track connections
	 * - Grid size: 10x10
	 *
	 * Common use cases:
	 * - Negative path finding tests (no path available)
	 * - Editor validation (incomplete networks)
	 * - Error handling verification
	 *
	 * @return EditingContext with dead-end topology (must close)
	 */
	fun deadEndSingleInOut(): EditingContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.buildEditingContext()

	// ========================================================================
	// SimulationContext Variants (for simulation/train tests)
	// ========================================================================

	/**
	 * Creates a simple linear path topology: A→B (SimulationContext variant).
	 *
	 * Same configuration as [simpleLinearPath] but returns SimulationContext
	 * for use in simulation tests (TrainNavigationService, path reservation, etc.).
	 *
	 * @return SimulationContext with simple linear topology (must close)
	 * @see simpleLinearPath
	 */
	fun simpleLinearPathSimulation(): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

	/**
	 * Creates a linear path with semaphore: A→[S]→B (SimulationContext variant).
	 *
	 * Same configuration as [linearPathWithSemaphore] but returns SimulationContext
	 * for use in simulation tests.
	 *
	 * @param semaphoreAllowing Initial semaphore state (true=GREEN/allowing, false=RED/blocking)
	 * @return SimulationContext with semaphore topology (must close)
	 * @see linearPathWithSemaphore
	 */
	fun linearPathWithSemaphoreSimulation(semaphoreAllowing: Boolean = false): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withSemaphore(3, 3, semaphoreAllowing)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 3, 3, 100.0, 80.0)
			.withConnection(3, 3, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

	/**
	 * Creates a Y-junction topology: two entries converge at switch, diverge to two exits (SimulationContext variant).
	 *
	 * Same configuration as [yJunctionWithSwitch] but returns SimulationContext
	 * for use in simulation tests.
	 *
	 * @return SimulationContext with Y-junction topology (must close)
	 * @see yJunctionWithSwitch
	 */
	fun yJunctionWithSwitchSimulation(): SimulationContext {
		val editingContext = yJunctionWithSwitch()
		val processFactory = getKoin().get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	/**
	 * Creates a linear path with multiple semaphores in sequence: A→[S1]→...→[Sn]→B (SimulationContext variant).
	 *
	 * Same configuration as [linearPathWithSemaphoreSequence] but returns SimulationContext
	 * for use in simulation tests.
	 *
	 * @param semaphoreCount Number of semaphores (default 3, min 1)
	 * @param semaphoresAllowing Initial state for all semaphores (true=GREEN/allowing, false=RED/blocking)
	 * @return SimulationContext with multi-semaphore topology (must close)
	 * @see linearPathWithSemaphoreSequence
	 */
	fun linearPathWithSemaphoreSequenceSimulation(
		semaphoreCount: Int = 3,
		semaphoresAllowing: Boolean = false
	): SimulationContext {
		val editingContext = linearPathWithSemaphoreSequence(semaphoreCount, semaphoresAllowing)
		val processFactory = getKoin().get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	/**
	 * Creates a dead-end topology: single InOut with no connections (SimulationContext variant).
	 *
	 * Same configuration as [deadEndSingleInOut] but returns SimulationContext
	 * for use in simulation tests.
	 *
	 * @return SimulationContext with dead-end topology (must close)
	 * @see deadEndSingleInOut
	 */
	fun deadEndSingleInOutSimulation(): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.buildSimulationContext()
}
