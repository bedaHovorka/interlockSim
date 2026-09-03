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
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

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
		context =
			TestFixtures
				.newShuntingSimulationContext(processFactory = processFactory, initializeDynamicMapping = true)
				.tracked()

		// Get separator position cache from context (performance optimization)
		val cache =
			(context as? cz.vutbr.fit.interlockSim.context.DefaultSimulationContext)
				?.separatorPositionCache ?: emptyMap()

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
				?.separatorPositionCache ?: emptyMap()

		// Cache should contain all PathSeparators from grid (InOuts, semaphores, switches)
		assertThat(cache.size > 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_nullSection() {
		// Null track section - should return null
		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				null,
				50.0
			)

		assertThat(gridLocation).isNull()
	}

	@Test
	fun testCalculateTrainGridLocation_atStart() {
		// Train at start of section (distance = 0)
		val trackSection = getFirstTrackSection()
		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				trackSection,
				0.0
			)

		assertThat(gridLocation).isNotNull()
		// Should be at one of the endpoints
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_atMidpoint() {
		// Train at midpoint of section
		val trackSection = getFirstTrackSection()
		val sectionLength = trackSection?.length() ?: 0.0
		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				trackSection,
				sectionLength / 2.0
			)

		assertThat(gridLocation).isNotNull()
		// Should be between the two endpoints
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_atEnd() {
		// Train at end of section (distance = length)
		val trackSection = getFirstTrackSection()
		val sectionLength = trackSection?.length() ?: 0.0
		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				trackSection,
				sectionLength
			)

		assertThat(gridLocation).isNotNull()
		// Should be at one of the endpoints
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_beyondEnd() {
		// Train beyond end of section (distance > length) - should clamp to 1.0
		val trackSection = getFirstTrackSection()
		val sectionLength = trackSection?.length() ?: 0.0
		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				trackSection,
				sectionLength * 2.0
			)

		assertThat(gridLocation).isNotNull()
		// Should be clamped to end of section
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_beyondEnd_whenEnteringFromEnd1_returnsCorrectExit() {
		// Test correct exit position calculation when train enters from ends[1] using trainEntrySeparator
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()
		
		val ends = trackSection!!.ends()
		assertThat(ends.size).isEqualTo(2)
		
		val end0Dynamic = context.toDynamic(ends[0])
		val end1Dynamic = context.toDynamic(ends[1])
		assertThat(end1Dynamic).isNotNull()

		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.trainEntrySeparator } returns end1Dynamic

		val sectionLength = trackSection.length()
		val gridLocation =
			calculator.calculateTrainGridLocation(
				trainWithEntry,
				trackSection,
				sectionLength * 2.0 // Beyond end
			)

		assertThat(gridLocation).isNotNull()
		
		// The exit separator is determined from the train's entry separator using getSecondEnd.
		// Since we enter from end1Dynamic (ends[1]), the exit should be ends[0].
		val computedExitPos = calculator.getGridPosition(ends[0])
		assertThat(computedExitPos).isNotNull()
		
		// When entering from end[1] and going beyond end, it should clamp to end[0] (the computed exit)
		assertThat(gridLocation!!.x).isEqualTo(computedExitPos!!.x.toFloat())
		assertThat(gridLocation.y).isEqualTo(computedExitPos.y.toFloat())
	}

	@Test
	fun testCalculateTrainGridLocation_beyondEnd_entrySeparatorWithoutGridPosition() {
		// Issue #289 transition window: when the entry end has no grid position,
		// calculateTrainGridLocation returns null. The exit end is a real fixture
		// end with a known grid position, so only the entry check produces the null.
		val realEnd = getFirstTrackSection()!!.ends()[0]
		val unknownSeparator = mockk<PathSeparator>()
		val trackSection = mockk<TrackSection>()
		every { trackSection.ends() } returns arrayOf(unknownSeparator, realEnd)
		every { trackSection.length() } returns 10.0

		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				trackSection,
				20.0 // Beyond end
			)

		assertThat(gridLocation).isNull()
	}

	@Test
	fun testCalculateTrainGridLocation_negativeDistance() {
		// Train before start (negative distance) - should clamp to 0.0
		val trackSection = getFirstTrackSection()
		val gridLocation =
			calculator.calculateTrainGridLocation(
				mockTrain,
				trackSection,
				-10.0
			)

		assertThat(gridLocation).isNotNull()
		// Should be clamped to start of section
		assertThat(gridLocation!!.y >= 0).isEqualTo(true)
	}

	/**
	 * Get the first track section from the railway network graph.
	 */
	private fun getFirstTrackSection(): cz.vutbr.fit.interlockSim.objects.tracks.TrackSection? {
		// All graph edges are DynamicTrackBlock, which implements TrackSection.
		return context.getGraph().values().firstOrNull()
	}

	// ========== Identity-Based Separator Matching Tests (Issue #324) ==========

	@Test
	fun testCalculateTrainGridLocation_withEntrySeparatorMatchingEnd0() {
		// Test identity-based comparison: entrySeparator matches ends[0]
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// Cast to DynamicPathSeparator: in simulation context all separators are dynamic wrappers
		val end0Dynamic = ends[0] as? DynamicPathSeparator
		assertThat(end0Dynamic).isNotNull() // Fails if section end is not a dynamic wrapper
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.trainEntrySeparator } returns end0Dynamic

		// Calculate position at 25% — train is closer to the entry end than to the exit end
		val sectionLength = trackSection.length()
		val gridLocation =
			calculator.calculateTrainGridLocation(
				trainWithEntry,
				trackSection,
				sectionLength * 0.25
			)

		assertThat(gridLocation).isNotNull()
		// Directional check: at 25% the result must be closer to ends[0] (entry) than to ends[1] (exit)
		val end0GridPos = calculator.getGridPosition(ends[0])
		val end1GridPos = calculator.getGridPosition(ends[1])
		assertThat(end0GridPos).isNotNull()
		assertThat(end1GridPos).isNotNull()
		val end0F =
			cz.vutbr.fit.interlockSim.util
				.PointF(end0GridPos!!.x.toFloat(), end0GridPos.y.toFloat())
		val end1F =
			cz.vutbr.fit.interlockSim.util
				.PointF(end1GridPos!!.x.toFloat(), end1GridPos.y.toFloat())
		assertThat(gridLocation!!.distanceTo(end0F) < gridLocation.distanceTo(end1F)).isEqualTo(true)
	}

	@Test
	fun testCalculateTrainGridLocation_withEntrySeparatorMatchingEnd1() {
		// Test identity-based comparison: entrySeparator matches ends[1]
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		// Cast to DynamicPathSeparator: in simulation context all separators are dynamic wrappers
		val end1Dynamic = ends[1] as? DynamicPathSeparator
		assertThat(end1Dynamic).isNotNull() // Fails if section end is not a dynamic wrapper
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.trainEntrySeparator } returns end1Dynamic

		// Calculate position at 25% — train is closer to the entry end than to the exit end
		val sectionLength = trackSection.length()
		val gridLocation =
			calculator.calculateTrainGridLocation(
				trainWithEntry,
				trackSection,
				sectionLength * 0.25
			)

		assertThat(gridLocation).isNotNull()
		// Directional check: at 25% the result must be closer to ends[1] (entry) than to ends[0] (reversed order)
		val end0GridPos = calculator.getGridPosition(ends[0])
		val end1GridPos = calculator.getGridPosition(ends[1])
		assertThat(end0GridPos).isNotNull()
		assertThat(end1GridPos).isNotNull()
		val end0F =
			cz.vutbr.fit.interlockSim.util
				.PointF(end0GridPos!!.x.toFloat(), end0GridPos.y.toFloat())
		val end1F =
			cz.vutbr.fit.interlockSim.util
				.PointF(end1GridPos!!.x.toFloat(), end1GridPos.y.toFloat())
		assertThat(gridLocation!!.distanceTo(end1F) < gridLocation.distanceTo(end0F)).isEqualTo(true)
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
		val allSeparators =
			context
				.getRailWayNetGrid()
				.map { it.value }
				.filterIsInstance<DynamicPathSeparator>()

		val differentSeparator: DynamicPathSeparator? =
			allSeparators.firstOrNull { it !== ends[0] && it !== ends[1] }
		assertThat(differentSeparator).isNotNull()

		val trainWithDifferentEntry = mockk<Train>(relaxed = true)
		every { trainWithDifferentEntry.trainEntrySeparator } returns differentSeparator!!

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

		// ends[0] must be a DynamicPathSeparator in simulation context (all separators are wrapped)
		val end0Dynamic = ends[0] as? DynamicPathSeparator
		assertThat(end0Dynamic).isNotNull()
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.trainEntrySeparator } returns end0Dynamic

		// Test at start (distance = 0)
		val gridLocationStart =
			calculator.calculateTrainGridLocation(
				trainWithEntry,
				trackSection,
				0.0
			)
		assertThat(gridLocationStart).isNotNull()

		// Test at end (distance = length)
		val sectionLength = trackSection.length()
		val gridLocationEnd =
			calculator.calculateTrainGridLocation(
				trainWithEntry,
				trackSection,
				sectionLength
			)
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

		// Create two trains, one entering from each end.
		// Assert casts succeed — all separators are dynamic wrappers in simulation context.
		val end0Dynamic = ends[0] as? DynamicPathSeparator
		val end1Dynamic = ends[1] as? DynamicPathSeparator
		assertThat(end0Dynamic).isNotNull()
		assertThat(end1Dynamic).isNotNull()

		val trainFromEnd0 = mockk<Train>(relaxed = true)
		every { trainFromEnd0.trainEntrySeparator } returns end0Dynamic

		val trainFromEnd1 = mockk<Train>(relaxed = true)
		every { trainFromEnd1.trainEntrySeparator } returns end1Dynamic

		// Use 25% (asymmetric) — at midpoint both trains would land at the same position,
		// so 50% cannot verify that direction selection is actually working
		val sectionLength = trackSection.length()
		val quarterPoint = sectionLength * 0.25

		val positionFromEnd0 =
			calculator.calculateTrainGridLocation(
				trainFromEnd0,
				trackSection,
				quarterPoint
			)
		val positionFromEnd1 =
			calculator.calculateTrainGridLocation(
				trainFromEnd1,
				trackSection,
				quarterPoint
			)

		assertThat(positionFromEnd0).isNotNull()
		assertThat(positionFromEnd1).isNotNull()

		// 25% from end0 = 75% from end1, so the two positions must be different
		// (they're on opposite sides of the midpoint)
		val distance = positionFromEnd0!!.distanceTo(positionFromEnd1!!)
		assertThat(distance > 0.1f).isEqualTo(true)
	}

	// ========== Heading Direction Tests (front/tail swap fix) ==========

	@Test
	fun testCalculateTrainHeadingRadians_nullSection() {
		// Null section - cannot resolve a heading
		val heading = calculator.calculateTrainHeadingRadians(mockTrain, null)
		assertThat(heading).isNull()
	}

	@Test
	fun testCalculateTrainHeadingRadians_matchesEntryToExitDirection() {
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		val end0Dynamic = ends[0] as? DynamicPathSeparator
		assertThat(end0Dynamic).isNotNull()
		val trainWithEntry = mockk<Train>(relaxed = true)
		every { trainWithEntry.trainEntrySeparator } returns end0Dynamic

		val heading = calculator.calculateTrainHeadingRadians(trainWithEntry, trackSection)
		assertThat(heading).isNotNull()

		// Heading must equal atan2(exit - entry): entering from ends[0], exit is ends[1]
		val entryPos = calculator.getGridPosition(ends[0])
		val exitPos = calculator.getGridPosition(ends[1])
		assertThat(entryPos).isNotNull()
		assertThat(exitPos).isNotNull()
		val expected =
			kotlin.math.atan2(
				(exitPos!!.y - entryPos!!.y).toDouble(),
				(exitPos.x - entryPos.x).toDouble()
			)
		assertThat(heading).isEqualTo(expected)
	}

	@Test
	fun testCalculateTrainHeadingRadians_reversesWithEntryEnd() {
		// A train always moves forward along its section: entering from opposite ends must
		// produce opposite (≈180°) headings. This is the invariant that prevents the nose
		// from flipping (front/tail swap) at block boundaries.
		//
		// This still describes the calculator's contract after Issue #788: the direction
		// follows whichever end the train says it entered through. What #788 changed is the
		// input — `Train` no longer publishes the *exit* end of the section the front has just
		// left, so in a running simulation the two headings below can only ever come from two
		// genuinely opposite travel directions.
		val trackSection = getFirstTrackSection()
		assertThat(trackSection).isNotNull()

		val ends = trackSection!!.ends()
		assertThat(ends.size >= 2).isEqualTo(true)

		val end0Dynamic = ends[0] as? DynamicPathSeparator
		val end1Dynamic = ends[1] as? DynamicPathSeparator
		assertThat(end0Dynamic).isNotNull()
		assertThat(end1Dynamic).isNotNull()

		val trainFromEnd0 = mockk<Train>(relaxed = true)
		every { trainFromEnd0.trainEntrySeparator } returns end0Dynamic
		val trainFromEnd1 = mockk<Train>(relaxed = true)
		every { trainFromEnd1.trainEntrySeparator } returns end1Dynamic

		val headingFromEnd0 = calculator.calculateTrainHeadingRadians(trainFromEnd0, trackSection)
		val headingFromEnd1 = calculator.calculateTrainHeadingRadians(trainFromEnd1, trackSection)
		assertThat(headingFromEnd0).isNotNull()
		assertThat(headingFromEnd1).isNotNull()

		// Angles are opposite: normalize the difference to [0, PI] and expect ≈ PI
		var diff = kotlin.math.abs(headingFromEnd0!! - headingFromEnd1!!)
		while (diff > kotlin.math.PI) {
			diff -= 2 * kotlin.math.PI
		}
		assertThat(kotlin.math.abs(kotlin.math.abs(diff) - kotlin.math.PI) < 1e-6).isEqualTo(true)
	}
}
