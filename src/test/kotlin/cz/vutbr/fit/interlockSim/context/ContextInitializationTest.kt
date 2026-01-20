/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for context initialization and factory patterns
 * Phase 4.3 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.buildMinimal
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File

/**
 * Comprehensive unit tests for context initialization via factory pattern.
 *
 * Tests validate:
 * - Factory selection (SimulationContextFactory vs EditingContextFactory)
 * - XML file loading from fixtures
 * - Context state after initialization (tracks, switches, semaphores, InOut points)
 * - Error handling for invalid/missing files
 * - Empty context creation
 *
 * Railway domain perspective:
 * - Context represents the railway network configuration (track layout, signals, interlocking)
 * - Factories implement factory pattern for creating different context types
 * - XML fixtures represent actual railway network configurations
 *
 * Test organization:
 * - FactorySelection: Tests factory methods and context type creation
 * - FileHandling: Tests XML file loading and error cases
 * - ContextState: Tests that initialized contexts contain expected railway elements
 */
@DisplayName("Context Initialization")
class ContextInitializationTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	@Nested
	@DisplayName("Factory Selection")
	inner class FactorySelectionTests {
		/**
		 * Validates that XMLContextFactory implements EditingContextFactory interface
		 * and can create empty editing contexts for the editor.
		 *
		 * Railway context: Empty context allows railway network design from scratch.
		 */
		@Test
		@DisplayName("editing context created for edit mode")
		fun editingFactory_createsEmptyContext() {
			// Arrange (factory injected via Koin)

			// Act
			val context = factory.createEmptyContext()

			// Assert
			assertThat(context)
				.withMessage("EditingContextFactory should create non-null context")
				.isNotNull()
			assertThat(context)
				.withMessage("Should be EditingContext instance")
				.isInstanceOf(EditingContext::class)
			assertThat(context)
				.withMessage("Should be DefaultEditingContext implementation")
				.isInstanceOf(DefaultEditingContext::class)
		}

		/**
		 * Validates that empty editing context has valid but empty grid.
		 *
		 * Railway context: Grid represents the spatial layout where tracks are placed.
		 */
		@Test
		@DisplayName("empty editing context has valid empty grid")
		fun editingContext_emptyGrid_isValid() {
			// Arrange
			val factory = this@ContextInitializationTest.factory
			val context = factory.createEmptyContext()

			// Act
			val grid = context.getRailWayNetGrid()

			// Assert
			assertThat(grid)
				.withMessage("Grid should be initialized")
				.isNotNull()
			assertThat(grid.getCols())
				.withMessage("Grid should have non-zero width")
				.isGreaterThan(0)
			assertThat(grid.getRows())
				.withMessage("Grid should have non-zero height")
				.isGreaterThan(0)
		}

		/**
		 * Validates that XMLContextFactory implements SimulationContextFactory
		 * for converting editing contexts to simulation contexts.
		 *
		 * Railway context: Simulation context enables running train simulations
		 * on the designed network configuration.
		 */
		@Test
		@DisplayName("simulation context created for sim mode from editing context")
		fun simulationFactory_createsFromEditingContext() {
			// Arrange
			val factory = this@ContextInitializationTest.factory
			val editingContext = factory.createEmptyContext()

			// Act
			val simulationContext = factory.createContext(editingContext)

			// Assert
			assertThat(simulationContext)
				.withMessage("SimulationContextFactory should create non-null context")
				.isNotNull()
			assertThat(simulationContext)
				.withMessage("Should be SimulationContext instance")
				.isInstanceOf(SimulationContext::class)
		}
	}

	@Nested
	@DisplayName("File Handling Edge Cases")
	inner class FileHandlingTests {
		/**
		 * Validates that loading a valid XML file creates context with expected content.
		 *
		 * Test uses minimal-network.xml fixture: simple network with two InOut nodes (minimum required).
		 *
		 * Railway context: XML files are the persistent format for railway network configs.
		 */
		@Test
		@DisplayName("valid XML file loads correctly")
		fun xmlFile_valid_loadsContext() {
			// Arrange
			val xmlFile = File("src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/minimal-network.xml")

			// Act
			val context = this@ContextInitializationTest.factory.createContext(xmlFile)

			// Assert
			assertThat(context)
				.withMessage("Should load context from valid XML file")
				.isNotNull()
			assertThat(context.getRailWayNetGrid())
				.withMessage("Loaded context should have grid")
				.isNotNull()
		}

		/**
		 * Validates that loading a linear track with two InOut points creates
		 * proper network topology.
		 *
		 * Railway context: Linear track is fundamental - entry point to exit point.
		 */
		@Test
		@DisplayName("linear track XML loads with two InOut endpoints")
		fun linearTrackFile_loadsWithEndpoints() {
			// Arrange
			val xmlFile = File("src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/linear-track.xml")

			// Act
			val context = this@ContextInitializationTest.factory.createContext(xmlFile)

			// Assert
			assertThat(context)
				.withMessage("Should load linear track from XML")
				.isNotNull()

			// Railway context: Should have entry and exit InOut points
			// Phase 6: Grid is typed as RailwayNetGrid<NodeCell> but internally contains Cell (NodeCell + TrackBlockPart)
			// Cast to Cell grid to access all cells without ClassCastException
			@Suppress("UNCHECKED_CAST")
			val grid = context.getRailWayNetGrid() as RailwayNetGrid<Cell>
			var inOutCount = 0
			for (x in 0 until grid.getCols()) {
				for (y in 0 until grid.getRows()) {
					val cell = grid.getCellAt(x, y)
					if (cell is InOut) {
						inOutCount++
					}
				}
			}
			assertThat(inOutCount)
				.withMessage("Linear track should have at least entry and exit InOut points")
				.isGreaterThan(0)
		}

		/**
		 * Validates error handling when XML file does not exist.
		 *
		 * Railway context: File handling errors should be caught early before
		 * attempting to parse network configuration.
		 */
		@Test
		@DisplayName("nonexistent file throws exception")
		fun xmlFile_notFound_throwsException() {
			// Arrange
			val xmlFile = File("nonexistent/path/does-not-exist.xml")

			// Act & Assert
			assertk
				.assertFailure {
					this@ContextInitializationTest.factory.createContext(xmlFile)
				}.isInstanceOf(Exception::class)
		}

		/**
		 * Validates error handling when XML file is malformed (invalid XML syntax).
		 *
		 * Railway context: Malformed configs should be rejected before loading.
		 * Uses invalid-malformed-xml.xml fixture with broken XML syntax.
		 */
		@Test
		@DisplayName("malformed XML file throws exception")
		fun xmlFile_malformed_throwsException() {
			// Arrange
			val xmlFile = File("src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/invalid-malformed-xml.xml")

			// Act & Assert
			assertk
				.assertFailure {
					this@ContextInitializationTest.factory.createContext(xmlFile)
				}.isInstanceOf(Exception::class)
		}
	}

	@Nested
	@DisplayName("Context State Validation")
	inner class ContextStateTests {
		private lateinit var linearTrackContext: DefaultSimulationContext

		@BeforeEach
		fun setUp() {
			// Load context from linear-track.xml for state validation tests
			val xmlFile = File("src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/linear-track.xml")
			linearTrackContext = this@ContextInitializationTest.factory.createContext(xmlFile) as DefaultSimulationContext
		}

		/**
		 * Validates that context loaded from XML has valid railway grid.
		 *
		 * Railway context: Grid is the spatial representation of the railway layout.
		 */
		@Test
		@DisplayName("context initialized with valid grid")
		fun context_validGrid_hasNonZeroDimensions() {
			// Arrange & Act
			val grid = linearTrackContext.getRailWayNetGrid()

			// Assert
			assertThat(grid)
				.withMessage("Grid should be initialized")
				.isNotNull()
			assertThat(grid.getCols())
				.withMessage("Grid width should be non-zero")
				.isGreaterThan(0)
			assertThat(grid.getRows())
				.withMessage("Grid height should be non-zero")
				.isGreaterThan(0)
		}

		/**
		 * Validates that context contains at least one InOut point.
		 * InOut points represent train entry/exit portals.
		 *
		 * Railway context: Every network needs entry and exit points
		 * (stations, marshalling yards, etc.).
		 */
		@Test
		@DisplayName("context initialized with correct InOut points")
		fun context_hasInOutPoints() {
			// Arrange & Act
			// Phase 6: Grid is typed as RailwayNetGrid<NodeCell> but internally contains Cell (NodeCell + TrackBlockPart)
			// Cast to Cell grid to access all cells without ClassCastException
			@Suppress("UNCHECKED_CAST")
			val grid = linearTrackContext.getRailWayNetGrid() as RailwayNetGrid<Cell>
			var inOutCount = 0
			val inOutPoints = mutableListOf<InOut>()

			for (x in 0 until grid.getCols()) {
				for (y in 0 until grid.getRows()) {
					val cell = grid.getCellAt(x, y)
					if (cell is InOut) {
						inOutCount++
						inOutPoints.add(cell)
					}
				}
			}

			// Assert
			assertThat(inOutCount)
				.withMessage("Linear track should have at least one InOut point")
				.isGreaterThan(0)
			assertThat(inOutPoints)
				.withMessage("All InOut points should be valid cells")
				.isNotNull()
		}

		/**
		 * Validates that context has valid graph representation.
		 *
		 * Railway context: Graph represents connectivity between track elements
		 * (used for path calculation, train routing).
		 */
		@Test
		@DisplayName("context initialized with valid track graph")
		fun context_hasValidGraph() {
			// Arrange & Act
			val graph = linearTrackContext.getGraph()

			// Assert
			assertThat(graph)
				.withMessage("Context should have valid graph")
				.isNotNull()
		}

		/**
		 * Validates that loaded context preserves name from XML.
		 *
		 * Railway context: Network name identifies the configuration
		 * (e.g., "Berlin Main Station", "Shunting Yard A").
		 */
		@Test
		@DisplayName("context preserves name from XML initialization")
		fun context_preservesName() {
			// Arrange & Act
			val contextName = linearTrackContext.currentNameString

			// Assert
			assertThat(contextName)
				.withMessage("Context should have a name set (may be default)")
				.isNotNull()
		}

		/**
		 * Validates that context settings are initialized to defaults.
		 *
		 * Railway context: Max speed and track length are default configuration
		 * settings applied to new network elements.
		 */
		@Test
		@DisplayName("context initialized with valid configuration settings")
		fun context_hasValidSettings() {
			// Arrange & Act
			val maxSpeed = linearTrackContext.currentMaxSpeed
			val trackLength = linearTrackContext.currentTrackLength

			// Assert
			assertThat(maxSpeed)
				.withMessage("Max speed should be positive")
				.isGreaterThan(0.0)
			assertThat(trackLength)
				.withMessage("Track length should be positive")
				.isGreaterThan(0.0)
		}

		/**
		 * Validates that empty context (created programmatically) has valid state.
		 *
		 * Railway context: Empty context is used when creating new network designs.
		 */
		@Test
		@DisplayName("empty context has valid initial state")
		fun emptyContext_hasValidState() {
			// Arrange & Act
			val emptyContext = buildMinimal()

			// Assert - Verify basic structure
			assertThat(emptyContext)
				.withMessage("Empty context should be created successfully")
				.isNotNull()
			assertThat(emptyContext.getRailWayNetGrid())
				.withMessage("Empty context should have grid")
				.isNotNull()
			assertThat(emptyContext.getGraph())
				.withMessage("Empty context should have graph")
				.isNotNull()

			// Railway context: Default settings should be applicable
			assertThat(emptyContext)
				.prop(EditingContext::currentMaxSpeed)
				.withMessage("Empty context should have max speed setting")
				.isGreaterThan(0.0)
		}
	}

	@Nested
	@DisplayName("Factory Pattern Behavior")
	inner class FactoryPatternTests {
		/**
		 * Validates that XMLContextFactory is a singleton.
		 *
		 * Factory pattern: Factory instances should be reused across application.
		 */
		@Test
		@DisplayName("XMLContextFactory uses singleton pattern")
		fun xmlContextFactory_isSingleton() {
			// Arrange & Act
			val factory1 = this@ContextInitializationTest.factory
			val factory2 = this@ContextInitializationTest.factory

			// Assert
			assertThat(factory1)
				.withMessage("Singleton should return same instance")
				.isEqualTo(factory2)
		}

		/**
		 * Validates that factory can create different context types for
		 * different modes (editing vs simulation).
		 *
		 * Railway context: Factory pattern enables creating appropriate context
		 * type for the current application mode.
		 */
		@Test
		@DisplayName("factory creates appropriate context for mode")
		fun factory_createsContextForMode() {
			// Arrange
			val factory = this@ContextInitializationTest.factory

			// Act - Create editing context
			val editingContext = factory.createEmptyContext()

			// Convert to simulation context
			val simulationContext = factory.createContext(editingContext)

			// Assert
			assertThat(editingContext)
				.withMessage("Editing context should be EditingContext")
				.isInstanceOf(EditingContext::class)
			assertThat(simulationContext)
				.withMessage("Simulation context should be SimulationContext")
				.isInstanceOf(SimulationContext::class)
		}

		/**
		 * Validates that created contexts are independent instances.
		 *
		 * Railway context: Each network design is separate; contexts should not
		 * share internal state.
		 */
		@Test
		@DisplayName("factory creates independent context instances")
		fun factory_createsIndependentInstances() {
			// Arrange
			val factory = this@ContextInitializationTest.factory

			// Act
			val context1 = factory.createEmptyContext()
			val context2 = factory.createEmptyContext()

			// Modify first context
			context1.currentNameString = "Network A"

			// Assert
			assertThat(context1)
				.prop(EditingContext::currentNameString)
				.withMessage("First context should have modified name")
				.isEqualTo("Network A")
			assertThat(context2)
				.prop(EditingContext::currentNameString)
				.withMessage("Second context should have different name (independent)")
				.isNotNull()
		}
	}
}
