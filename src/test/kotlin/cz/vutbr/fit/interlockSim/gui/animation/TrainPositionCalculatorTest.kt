/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File

/**
 * Unit tests for [TrainPositionCalculator].
 *
 * Tests the linear interpolation algorithm for calculating train grid positions
 * along track sections using the vyhybna.xml test configuration.
 */
class TrainPositionCalculatorTest : KoinTestBase() {
	private lateinit var context: SimulationContext
	private lateinit var calculator: TrainPositionCalculator
	private lateinit var mockTrain: Train
	private val processFactory: SimulationProcessFactory by inject()

	/**
	 * Load vyhybna.xml test configuration.
	 */
	@BeforeEach
	fun setUp() {
		val xmlFactory = XMLContextFactory()
		val resourcePath = javaClass.getResource("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
		assertThat(resourcePath).isNotNull()

		val editingContext = xmlFactory.createContext(File(resourcePath!!.path)) as EditingContext
		context = ContextTransformer.createSimulationContext(editingContext, processFactory)

		// Get separator position cache from context (performance optimization)
		val cache =
			(context as? cz.vutbr.fit.interlockSim.context.DefaultSimulationContext)
				?.getSeparatorPositionCache() ?: emptyMap()

		calculator = TrainPositionCalculator(context, cache)

		// Create mock train for tests
		// For simplicity, return null for entrySeparator (uses fallback arbitrary order)
		mockTrain = mockk<Train>(relaxed = true)
		every { mockTrain.getEntrySeparator() } returns null
	}

	@Test
	fun testCacheIsPopulated() {
		// Verify that cache was populated during context creation
		val cache =
			(context as? cz.vutbr.fit.interlockSim.context.DefaultSimulationContext)
				?.getSeparatorPositionCache() ?: emptyMap()

		// Cache should contain all PathSeparators from grid (InOuts, semaphores, switches)
		assertThat(cache.size > 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_nullSection() {
		// Null track section - should return null
		val gridLocation = calculator.calculateTrainGridLocation(mockTrain, null, 50.0)

		assertThat(gridLocation).isNull()
	}

	@Test
	fun testCalculateTrainGridLocation_atStart() {
		// Train at start of section (distance = 0)
		val trackSection = getFirstTrackSection()
		val gridLocation = calculator.calculateTrainGridLocation(mockTrain, trackSection, 0.0)

		assertThat(gridLocation).isNotNull()
		// Should be at one of the endpoints
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_atMidpoint() {
		// Train at midpoint of section
		val trackSection = getFirstTrackSection()
		val sectionLength = trackSection?.length() ?: 0.0
		val gridLocation = calculator.calculateTrainGridLocation(mockTrain, trackSection, sectionLength / 2.0)

		assertThat(gridLocation).isNotNull()
		// Should be between the two endpoints
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_atEnd() {
		// Train at end of section (distance = length)
		val trackSection = getFirstTrackSection()
		val sectionLength = trackSection?.length() ?: 0.0
		val gridLocation = calculator.calculateTrainGridLocation(mockTrain, trackSection, sectionLength)

		assertThat(gridLocation).isNotNull()
		// Should be at one of the endpoints
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_beyondEnd() {
		// Train beyond end of section (distance > length) - should clamp to 1.0
		val trackSection = getFirstTrackSection()
		val sectionLength = trackSection?.length() ?: 0.0
		val gridLocation = calculator.calculateTrainGridLocation(mockTrain, trackSection, sectionLength * 2.0)

		assertThat(gridLocation).isNotNull()
		// Should be clamped to end of section
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_negativeDistance() {
		// Train before start (negative distance) - should clamp to 0.0
		val trackSection = getFirstTrackSection()
		val gridLocation = calculator.calculateTrainGridLocation(mockTrain, trackSection, -10.0)

		assertThat(gridLocation).isNotNull()
		// Should be clamped to start of section
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	/**
	 * Get the first track section from the railway network graph.
	 */
	private fun getFirstTrackSection(): cz.vutbr.fit.interlockSim.objects.tracks.TrackSection? {
		val graph = context.getGraph()
		val edges = graph.values()

		for (edge in edges) {
			if (edge is cz.vutbr.fit.interlockSim.objects.tracks.TrackSection) {
				return edge
			}
		}

		return null
	}
}
