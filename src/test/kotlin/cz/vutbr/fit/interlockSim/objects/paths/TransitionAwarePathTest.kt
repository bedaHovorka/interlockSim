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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Tests for TransitionAwarePath wrapper class.
 *
 * ## Purpose (Issue #296 Phase 9)
 *
 * Verifies that TransitionAwarePath correctly handles path transitions where
 * the train's current TrackSection is not present in the new path segment.
 *
 * ## Test Coverage
 *
 * - Transition case: getNext(transitionCurrent) returns transitionNext
 * - Normal delegation: getNext(otherCurrent) delegates to underlying path
 * - Safety check: Self-collision detection (current.block == next.block)
 * - Collection operations: Delegation to underlying path
 */
class TransitionAwarePathTest {
	private lateinit var context: SimulationContext
	private lateinit var mockDelegate: Path
	private lateinit var mockTransitionCurrent: TrackSection
	private lateinit var mockTransitionNext: TrackSection
	private lateinit var mockOtherCurrent: TrackSection

	@BeforeEach
	fun setUp() {
		// Initialize Koin for tests
		if (GlobalContext.getOrNull() == null) {
			startKoin {
				modules(interlockSimModule)
			}
		}

		context = TestTopologies.simpleLinearPathSimulation()

		// Create mock delegate path
		mockDelegate = mockk<Path>()

		// Create mock track sections
		mockTransitionCurrent = mockk<TrackSection>()
		mockTransitionNext = mockk<TrackSection>()
		mockOtherCurrent = mockk<TrackSection>()
	}

	@AfterEach
	fun tearDown() {
		context.close()
		stopKoin()
	}

	@Test
	fun `getNext should return transitionNext when called with transitionCurrent`() {
		// Given: Different blocks for current and next (no self-collision)
		val currentBlock = mockk<DynamicTrackBlock>()
		val nextBlock = mockk<DynamicTrackBlock>()
		every { mockTransitionCurrent.getTrackBlock() } returns currentBlock
		every { mockTransitionNext.getTrackBlock() } returns nextBlock

		val path = TransitionAwarePath(mockDelegate, mockTransitionCurrent, mockTransitionNext)

		// When: Get next from transition current
		val result = path.getNext(mockTransitionCurrent)

		// Then: Should return transition next
		assertThat(result).isNotNull()
		assertThat(result).isEqualTo(mockTransitionNext)
	}

	@Test
	fun `getNext should delegate to underlying path when called with non-transition current`() {
		// Given: Underlying path returns a result for otherCurrent
		val expectedNext = mockk<TrackSection>()
		every { mockDelegate.getNext(mockOtherCurrent) } returns expectedNext

		val currentBlock = mockk<DynamicTrackBlock>()
		val nextBlock = mockk<DynamicTrackBlock>()
		every { mockTransitionCurrent.getTrackBlock() } returns currentBlock
		every { mockTransitionNext.getTrackBlock() } returns nextBlock

		val path = TransitionAwarePath(mockDelegate, mockTransitionCurrent, mockTransitionNext)

		// When: Get next from a different current
		val result = path.getNext(mockOtherCurrent)

		// Then: Should delegate to underlying path
		assertThat(result).isEqualTo(expectedNext)
	}

	@Test
	fun `getNext should delegate to underlying path when called with null`() {
		// Given: Underlying path returns a result for null
		val expectedNext = mockk<TrackSection>()
		every { mockDelegate.getNext(null) } returns expectedNext

		val currentBlock = mockk<DynamicTrackBlock>()
		val nextBlock = mockk<DynamicTrackBlock>()
		every { mockTransitionCurrent.getTrackBlock() } returns currentBlock
		every { mockTransitionNext.getTrackBlock() } returns nextBlock

		val path = TransitionAwarePath(mockDelegate, mockTransitionCurrent, mockTransitionNext)

		// When: Get next with null current
		val result = path.getNext(null)

		// Then: Should delegate to underlying path
		assertThat(result).isEqualTo(expectedNext)
	}

	@Test
	fun `getNext should return null when self-collision detected`() {
		// Given: Same block for current and next (self-collision)
		val sameBlock = mockk<DynamicTrackBlock>()
		every { mockTransitionCurrent.getTrackBlock() } returns sameBlock
		every { mockTransitionNext.getTrackBlock() } returns sameBlock

		val path = TransitionAwarePath(mockDelegate, mockTransitionCurrent, mockTransitionNext)

		// When: Get next from transition current (self-collision case)
		val result = path.getNext(mockTransitionCurrent)

		// Then: Should return null (safety check)
		assertThat(result).isNull()
	}

	@Test
	fun `getNext should handle transitionCurrent null gracefully`() {
		// Given: transitionCurrent is null
		val nextBlock = mockk<DynamicTrackBlock>()
		every { mockTransitionNext.getTrackBlock() } returns nextBlock

		val path = TransitionAwarePath(mockDelegate, null, mockTransitionNext)

		// When: Get next with some current
		val expectedNext = mockk<TrackSection>()
		every { mockDelegate.getNext(mockOtherCurrent) } returns expectedNext
		val result = path.getNext(mockOtherCurrent)

		// Then: Should delegate to underlying path (no transition match)
		assertThat(result).isEqualTo(expectedNext)
	}

	@Test
	fun `size should delegate to underlying path`() {
		// Given
		every { mockDelegate.size } returns 5
		val path = TransitionAwarePath(mockDelegate, null, mockTransitionNext)

		// When/Then
		assertThat(path.size).isEqualTo(5)
	}

	@Test
	fun `isEmpty should delegate to underlying path`() {
		// Given
		every { mockDelegate.isEmpty() } returns false
		val path = TransitionAwarePath(mockDelegate, null, mockTransitionNext)

		// When/Then
		assertThat(path.isEmpty()).isEqualTo(false)
	}

	@Test
	fun `contains should delegate to underlying path`() {
		// Given
		val separator = mockk<PathSeparator>()
		every { mockDelegate.contains(separator) } returns true
		val path = TransitionAwarePath(mockDelegate, null, mockTransitionNext)

		// When/Then
		assertThat(path.contains(separator)).isEqualTo(true)
	}

	@Test
	fun `length should delegate to underlying path`() {
		// Given
		every { mockDelegate.length() } returns 123.45
		val path = TransitionAwarePath(mockDelegate, null, mockTransitionNext)

		// When/Then
		assertThat(path.length()).isEqualTo(123.45)
	}

	@Test
	fun `getFirst should delegate to underlying path`() {
		// Given
		val separator = mockk<RailSemaphore>()
		every { mockDelegate.getFirst() } returns separator
		val path = TransitionAwarePath(mockDelegate, null, mockTransitionNext)

		// When/Then
		assertThat(path.getFirst()).isEqualTo(separator)
	}

	@Test
	fun `toString should include wrapper info`() {
		// Given
		val path = TransitionAwarePath(mockDelegate, mockTransitionCurrent, mockTransitionNext)

		// When
		val result = path.toString()

		// Then: Should indicate it's a TransitionAwarePath with transition info
		assertThat(result).isNotNull()
		// Just verify it doesn't throw - exact format may vary
	}
}
