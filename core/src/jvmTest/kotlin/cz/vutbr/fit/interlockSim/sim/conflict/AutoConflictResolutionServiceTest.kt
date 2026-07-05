/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 → Goal 10 prereq: AutoConflictResolutionService (Issue #568).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultAutoConflictResolutionService] — Goal 9 → Goal 10 prereq (#568).
 *
 * Verifies:
 * 1. Top-ranked candidate is selected from [ConflictResolver.generateResolutions].
 * 2. The selected candidate is recorded in [DispatcherPreferenceStore] with source AUTO.
 * 3. The selected candidate is returned to the caller (for SP2b to enact).
 * 4. Returns `null` (and does not write to the store) when the resolver returns no candidates.
 *
 * All tests use MockK to isolate [DefaultAutoConflictResolutionService] from concrete
 * [ConflictResolver] and [DispatcherPreferenceStore] implementations.
 *
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
@DisplayName("DefaultAutoConflictResolutionService — Goal 9 → Goal 10 prereq (#568)")
class AutoConflictResolutionServiceTest {
	private lateinit var resolver: ConflictResolver
	private lateinit var preferenceStore: DispatcherPreferenceStore
	private lateinit var service: AutoConflictResolutionService
	private lateinit var block: DynamicTrackBlock
	private lateinit var event: ConflictDetectedEvent

	@BeforeEach
	fun setUp() {
		resolver = mockk()
		preferenceStore = mockk(relaxed = true)
		service = DefaultAutoConflictResolutionService(resolver, preferenceStore)
		block = mockk(relaxed = true)
		event = ConflictDetectedEvent(
			block = block,
			trainId = "Blocked",
			conflictingTrainId = "Holding",
			time = 10.0
		)
	}

	// ── Helper builders ───────────────────────────────────────────────────────

	private fun hold(trainId: String, delay: Double = 30.0) = ConflictResolution.HoldTrain(
		trainId = trainId,
		holdDurationSeconds = delay,
		affectedTrains = listOf(trainId),
		estimatedImpact = ConflictResolution.EstimatedImpact(delay, "hold $trainId for ${delay}s")
	)

	private fun speedAdjust(trainId: String, factor: Double = 0.5) = ConflictResolution.SpeedAdjust(
		trainId = trainId,
		speedReductionFactor = factor,
		affectedTrains = listOf(trainId),
		estimatedImpact = ConflictResolution.EstimatedImpact(0.0, "reduce speed of $trainId")
	)

	// ── Tests ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("applyTopRanked — normal path")
	inner class NormalPathTests {
		@Test
		@DisplayName("returns the first (top-ranked) candidate from the resolver")
		fun returnsTopRankedCandidate() {
			val topCandidate = hold("Blocked", delay = 30.0)
			val secondCandidate = speedAdjust("Blocked")
			every { resolver.generateResolutions(event) } returns listOf(topCandidate, secondCandidate)

			val result = service.applyTopRanked(event)

			assertThat(result).isEqualTo(topCandidate)
		}

		@Test
		@DisplayName("records the selected candidate in the preference store")
		fun recordsChoiceInPreferenceStore() {
			val topCandidate = hold("Blocked")
			every { resolver.generateResolutions(event) } returns listOf(topCandidate)

			service.applyTopRanked(event)

			verify(exactly = 1) {
				preferenceStore.record(
					event,
					topCandidate,
					DispatcherPreferenceStore.ApplicationSource.AUTO
				)
			}
		}

		@Test
		@DisplayName("records with ApplicationSource.AUTO (never OPERATOR)")
		fun alwaysRecordsWithAutoSource() {
			val candidate = hold("Blocked")
			every { resolver.generateResolutions(event) } returns listOf(candidate)

			service.applyTopRanked(event)

			verify(exactly = 1) {
				preferenceStore.record(
					any(),
					any(),
					DispatcherPreferenceStore.ApplicationSource.AUTO
				)
			}
		}

		@Test
		@DisplayName("picks only the first candidate — does not apply others")
		fun picksOnlyFirstCandidate() {
			val first = hold("Blocked", delay = 10.0)
			val second = hold("Blocked", delay = 30.0)
			val third = speedAdjust("Blocked")
			every { resolver.generateResolutions(event) } returns listOf(first, second, third)

			val result = service.applyTopRanked(event)

			assertThat(result).isEqualTo(first)
			// Only one record call, not three
			verify(exactly = 1) { preferenceStore.record(any(), any(), any()) }
		}

		@Test
		@DisplayName("returned resolution is a HoldTrain when that is the top candidate")
		fun returnedResolutionTypeMatchesTopCandidate() {
			val candidate = hold("Blocked")
			every { resolver.generateResolutions(event) } returns listOf(candidate)

			val result = service.applyTopRanked(event)

			assertThat(result!!).isInstanceOf<ConflictResolution.HoldTrain>()
		}
	}

	@Nested
	@DisplayName("applyTopRanked — empty-candidates edge case")
	inner class EmptyCandidatesTests {
		@Test
		@DisplayName("returns null when resolver produces no candidates")
		fun returnsNullWhenNoCandidates() {
			every { resolver.generateResolutions(event) } returns emptyList()

			val result = service.applyTopRanked(event)

			assertThat(result).isNull()
		}

		@Test
		@DisplayName("does not record in preference store when no candidates")
		fun doesNotRecordWhenNoCandidates() {
			every { resolver.generateResolutions(event) } returns emptyList()

			service.applyTopRanked(event)

			verify(exactly = 0) { preferenceStore.record(any(), any(), any()) }
		}
	}

	@Nested
	@DisplayName("integration with DefaultDispatcherPreferenceStore")
	inner class IntegrationWithRealStoreTests {
		@Test
		@DisplayName("recorded choice is retrievable via getChoices on real store")
		fun recordedChoiceRetrievableFromRealStore() {
			val realStore = DefaultDispatcherPreferenceStore()
			val svc = DefaultAutoConflictResolutionService(resolver, realStore)
			val candidate = hold("Blocked")
			every { resolver.generateResolutions(event) } returns listOf(candidate)

			svc.applyTopRanked(event)

			val choices = realStore.getChoices()
			assertThat(choices.size == 1).isTrue()
			assertThat(choices[0].applied).isEqualTo(candidate)
			assertThat(choices[0].source).isEqualTo(DispatcherPreferenceStore.ApplicationSource.AUTO)
		}
	}
}
