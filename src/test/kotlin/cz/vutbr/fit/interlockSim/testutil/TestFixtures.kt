package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
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
	 * Note: For switch topologies, use `TestFixtures.loadSwitchBasicXml()` instead.
	 * Switch configuration requires more complex setup than the fluent builder provides.
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
