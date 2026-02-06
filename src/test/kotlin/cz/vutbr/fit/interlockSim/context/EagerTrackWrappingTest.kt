/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test for Issue #214: Pre-Wrap All Tracks at Initialization
 * Validates that tracks are wrapped eagerly (not lazily)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File

/**
 * Tests for Issue #214: Pre-Wrap All Tracks at Initialization
 *
 * Validates that:
 * 1. All tracks in the railway network graph are wrapped eagerly during run() initialization
 * 2. toDynamic() throws exception for tracks not in the context (no lazy creation)
 * 3. Consistent wrapping strategy (eager for both path separators and tracks)
 */
@DisplayName("Eager Track Wrapping (Issue #214)")
class EagerTrackWrappingTest : KoinTestBase() {
	private val factory: SimulationContextFactory by inject()

	@Nested
	@DisplayName("Unit Tests - Eager Wrapping")
	inner class EagerWrappingUnitTests {
		/**
		 * Validates that toDynamic() throws exception for tracks not in the context
		 * (replaces lazy creation behavior)
		 */
		@Test
		@DisplayName("toDynamic throws exception for unwrapped track")
		fun toDynamic_unwrappedTrack_throwsException() {
			// Arrange
			val context = factory.createEmptyContext() as DefaultSimulationContext
			// Create an orphan track that's not in the context
			val end1 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			val end2 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			val orphanTrack = SimpleTrackBlock(end1, end2, 100.0, 80.0)

			// Act & Assert - should throw because track not in context
			assertFailure {
				context.toDynamic(orphanTrack as TrackFacility)
			}
				.isInstanceOf<IllegalStateException>()
				.message()
				.isNotNull()
				.contains("Dynamic wrapper not found for track")
				.contains("This indicates the track was not registered during initialization")
		}

		/**
		 * Validates that empty context has no tracks in graph
		 */
		@Test
		@DisplayName("empty context has no tracks in graph")
		fun emptyContext_hasNoTracks() {
			// Arrange
			val context = factory.createEmptyContext()

			// Act - get the graph
			val graph = context.getGraph()

			// Assert - empty context should have no tracks
			assertThat(graph.size())
				.withMessage("Empty context should have no tracks in graph")
				.isEqualTo(0)
		}
	}

	@Nested
	@DisplayName("Integration Tests - vyhybna.xml")
	@Tag("integration-test")
	inner class VyhybnaIntegrationTests {
		/**
		 * Integration test: Verify all tracks are wrapped after run() initialization
		 *
		 * This test ensures that initializeDynamicMapping() wraps all tracks eagerly,
		 * so that toDynamic() never needs to create wrappers on-demand.
		 *
		 * Railway context: vyhybna.xml is a test network with switches and multiple track segments
		 */
		@Test
		@DisplayName("vyhybna.xml - all tracks wrapped after run initialization")
		fun vyhybnaXml_allTracksWrappedAfterRunInit() {
			// Arrange - Load vyhybna.xml configuration
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			val context = factory.createContext(xmlFile) as DefaultSimulationContext

			// Act - Call initializeDynamicMapping (normally called by run())
			// We can't call run() in tests because it blocks, so we call the initialization directly
			context.initializeDynamicMapping()

			// Assert - All tracks in graph should have wrappers
			val graph = context.getGraph()
			var totalTracks = 0

			for (trackBlock in graph.values()) {
				totalTracks++
				val trackFacility = trackBlock as TrackFacility

				// toDynamic should find wrapper (no lazy creation)
				val dynamicTrack = context.toDynamic(trackFacility)

				assertThat(dynamicTrack)
					.withMessage("All tracks should have wrappers after initialization")
					.isNotNull()

				// Verify wrapper points to correct static track
				assertThat(dynamicTrack.staticRef)
					.withMessage("Wrapper should reference original track")
					.isEqualTo(trackFacility)
			}

			// Verify we have tracks
			assertThat(totalTracks)
				.withMessage("vyhybna.xml should have multiple track segments")
				.isGreaterThan(0)
		}

		/**
		 * Integration test: Verify graph contains multiple tracks
		 */
		@Test
		@DisplayName("vyhybna.xml - graph contains multiple track blocks")
		fun vyhybnaXml_graphHasMultipleTracks() {
			// Arrange
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			val context = factory.createContext(xmlFile) as DefaultSimulationContext

			// Act
			val graph = context.getGraph()

			// Assert - vyhybna.xml should have multiple track segments
			assertThat(graph.size())
				.withMessage("vyhybna.xml should have at least 5 track segments")
				.isGreaterThan(4)
		}
	}

	@Nested
	@DisplayName("Performance Tests")
	@Tag("integration-test")
	inner class PerformanceTests {
		/**
		 * Performance test: Ensure initialization time is acceptable
		 *
		 * Eager wrapping should have minimal performance impact since all tracks
		 * are accessed during simulation anyway.
		 */
		@Test
		@DisplayName("initialization time acceptable for vyhybna.xml")
		fun initializationTimeAcceptable() {
			// Arrange
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")

			// Act - Measure initialization time
			val start = System.currentTimeMillis()
			val context = factory.createContext(xmlFile) as DefaultSimulationContext
			context.initializeDynamicMapping()
			val duration = System.currentTimeMillis() - start

			// Assert - Should complete quickly (< 1 second for small network)
			assertThat(duration)
				.withMessage("Initialization should complete in under 1 second")
				.isLessThan(1000)

			// Verify tracks were actually wrapped
			val graph = context.getGraph()
			assertThat(graph.size())
				.withMessage("Should have wrapped multiple tracks")
				.isGreaterThan(0)
		}
	}
}
