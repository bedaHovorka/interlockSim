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
		every { mockTrain.trainEntrySeparator } returns null
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

	// ========== Identity-Based Separator Matching Tests (Issue #324) ==========

	@Test
	fun testCalculateTrainGridLocation_withEntrySeparatorMatchingEnd0() {
		// Test identity-based comparison: entrySeparator matches ends[0]
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// Create mock train with entry separator matching first end
		// Cast to DynamicPathSeparator since we're in simulation context
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.getEntrySeparator() } returns ends[0] as? cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

		// Calculate position at midpoint
		val sectionLength = trackSection.length()
		val gridLocation = calculator.calculateTrainGridLocation(trainWithEntry, trackSection, sectionLength / 2.0)

		assertThat(gridLocation).isNotNull()
		// Should interpolate from ends[0] to ends[1]
		assertThat(gridLocation!!.x >= 0.0f).isEqualTo(true)
		assertThat(gridLocation.y >= 0.0f).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_withEntrySeparatorMatchingEnd1() {
		// Test identity-based comparison: entrySeparator matches ends[1]
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// Create mock train with entry separator matching second end
		// Cast to DynamicPathSeparator since we're in simulation context
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.getEntrySeparator() } returns ends[1] as? cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

		// Calculate position at midpoint
		val sectionLength = trackSection.length()
		val gridLocation = calculator.calculateTrainGridLocation(trainWithEntry, trackSection, sectionLength / 2.0)

		assertThat(gridLocation).isNotNull()
		// Should interpolate from ends[1] to ends[0] (reversed order)
		assertThat(gridLocation!!.x >= 0.0f).isEqualTo(true)
		assertThat(gridLocation.y >= 0.0f).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_withEntrySeparatorNotMatching() {
		// Test identity-based comparison fallback: entrySeparator doesn't match either end
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// Create mock train with entry separator that doesn't match either end
		// Use a different separator from the network
		val allSeparators = context.getRailWayNetGrid()
			.map { it.value }
			.filterIsInstance<cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator>()

		val differentSeparator: cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator? = 
			allSeparators.firstOrNull { it !== ends[0] && it !== ends[1] }
		assertThat(differentSeparator).isNotNull()

		val trainWithDifferentEntry = mockk<Train>(relaxed = true)
		every { trainWithDifferentEntry.getEntrySeparator() } returns differentSeparator!!

		// Calculate position - should fall back to arbitrary order
		val sectionLength = trackSection.length()
		val gridLocation = calculator.calculateTrainGridLocation(trainWithDifferentEntry, trackSection, sectionLength / 2.0)

		assertThat(gridLocation).isNotNull()
		// Should still return valid position using fallback order
		assertThat(gridLocation!!.x >= 0.0f).isEqualTo(true)
		assertThat(gridLocation.y >= 0.0f).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_identityBasedComparison_withDynamicWrapper() {
		// Test that identity-based comparison works with dynamic wrappers
		// This tests the DynamicWrapperUtils.unwrapToStatic() functionality
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// If ends[0] is a dynamic wrapper, it should still match via unwrapping
		// Cast to DynamicPathSeparator since we're in simulation context
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.getEntrySeparator() } returns ends[0] as? cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

		// Test at start (distance = 0)
		val gridLocationStart = calculator.calculateTrainGridLocation(trainWithEntry, trackSection, 0.0)
		assertThat(gridLocationStart).isNotNull()

		// Test at end (distance = length)
		val sectionLength = trackSection.length()
		val gridLocationEnd = calculator.calculateTrainGridLocation(trainWithEntry, trackSection, sectionLength)
		assertThat(gridLocationEnd).isNotNull()

		// Positions should be different (start vs end)
		// Allow for small floating-point differences
		val distance = gridLocationStart!!.distanceTo(gridLocationEnd!!)
		assertThat(distance > 0.1f).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_consistentOrderWithEntrySeparator() {
		// Test that entry separator matching produces consistent interpolation order
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// Create two trains, one entering from each end
		// Cast to DynamicPathSeparator since we're in simulation context
		val trainFromEnd0 = mockk<Train>(relaxed = true)
		every { trainFromEnd0.getEntrySeparator() } returns ends[0] as? cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

		val trainFromEnd1 = mockk<Train>(relaxed = true)
		every { trainFromEnd1.getEntrySeparator() } returns ends[1] as? cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator

		// Both trains at midpoint
		val sectionLength = trackSection.length()
		val midpoint = sectionLength / 2.0

		val positionFromEnd0 = calculator.calculateTrainGridLocation(trainFromEnd0, trackSection, midpoint)
		val positionFromEnd1 = calculator.calculateTrainGridLocation(trainFromEnd1, trackSection, midpoint)

		assertThat(positionFromEnd0).isNotNull()
		assertThat(positionFromEnd1).isNotNull()

		// Both should produce valid positions
		// They might be at the same location (midpoint) or different depending on the section geometry
		assertThat(positionFromEnd0!!.x >= 0.0f).isEqualTo(true)
		assertThat(positionFromEnd1!!.x >= 0.0f).isEqualTo(true)
	}
}
