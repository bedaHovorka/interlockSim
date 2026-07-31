/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConflictHintLatch] (SP2c.4, Issue #827 — Goal 9 C7 ruling, option (a)).
 *
 * Verifies:
 * - Hint stored after [ConflictHintLatch.onConflict].
 * - [ConflictHintLatch.updateFromObservation] clears hints for non-HELD trains.
 * - [ConflictHintLatch.updateFromObservation] retains hints for HELD trains.
 * - Deduplication: second hint for same train is ignored.
 * - Thread-safety is validated at the design level (ConcurrentHashMap); functional
 *   concurrency tests are in separate integration tests.
 *
 * @since Issue #827 (SP2c.4 — Goal 10)
 */
@DisplayName("ConflictHintLatch — latch, retain, clear, deduplicate (SP2c.4 #827)")
class ConflictHintLatchTest {
	private lateinit var latch: ConflictHintLatch
	private lateinit var mockBlock: DynamicTrackBlock

	@BeforeEach
	fun setUp() {
		latch = ConflictHintLatch()
		mockBlock = mockk(relaxed = true)
		every { mockBlock.name } returns "kB"
	}

	private fun buildEvent(
		trainId: String,
		conflictingTrainId: String,
		block: DynamicTrackBlock = mockBlock,
		time: Double = 42.0
	): ConflictDetectedEvent =
		ConflictDetectedEvent(
			block = block,
			trainId = trainId,
			conflictingTrainId = conflictingTrainId,
			time = time
		)

	private fun buildObservation(heldTrains: List<String>): DispatcherObservation =
		DispatcherObservation(
			tick = 1L,
			simTime = 50.0,
			trains =
				heldTrains.map { id ->
					TrainView(
						trainId = id,
						phase = TrainPhase.HELD,
						frontSectionName = null,
						velocityMps = 0.0,
						accelerationMps2 = 0.0,
						destinationInOutName = "B",
						signalAheadName = null,
						signalAheadAspect = null,
						distanceToSignalAheadMetres = 0.0,
						waitingSinceSimTime = 40.0,
						waitSeconds = 10.0
					)
				},
			blocks = emptyList(),
			switches = emptyList(),
			signals = emptyList(),
			reservations = emptyList(),
			queued = emptyList(),
			activeCount = heldTrains.size,
			capacity = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
			appliedOutcomes = emptyList()
		)

	// ── Latch stores hint after onConflict ────────────────────────────────────

	@Nested
	@DisplayName("Hint storage: onConflict stores a non-null hint for the blocked train")
	inner class HintStorage {
		@Test
		@DisplayName("getHint returns non-null after onConflict for the blocked train")
		fun getHintReturnNonNullAfterOnConflict() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			assertThat(latch.getHint("T-3")).isNotNull()
		}

		@Test
		@DisplayName("getHint returns null for a train with no recorded conflict")
		fun getHintReturnsNullForUnknownTrain() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			assertThat(latch.getHint("T-999")).isNull()
		}

		@Test
		@DisplayName("Hint text contains the block name and the conflicting train ID")
		fun hintTextContainsBlockNameAndConflictingTrainId() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			val hint = latch.getHint("T-3")!!
			assertThat(hint.contains("kB")).isTrue()
			assertThat(hint.contains("T-4")).isTrue()
		}

		@Test
		@DisplayName("snapshot() reflects all latched hints")
		fun snapshotReflectsAllHints() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			latch.onConflict(buildEvent("T-5", "T-4"))
			assertThat(latch.snapshot()).hasSize(2)
		}

		@Test
		@DisplayName("snapshot() is empty before any conflict event")
		fun snapshotIsEmptyInitially() {
			assertThat(latch.snapshot()).isEmpty()
		}
	}

	// ── updateFromObservation clears non-HELD trains ──────────────────────────

	@Nested
	@DisplayName("Clearing: updateFromObservation removes hints for non-HELD trains")
	inner class ClearingBehavior {
		@Test
		@DisplayName("Hint for a RUNNING train is cleared when observation shows RUNNING")
		fun hintForRunningTrainIsClearedByObservation() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			assertThat(latch.getHint("T-3")).isNotNull()

			// Observation with T-3 as RUNNING (not HELD) → hint should be cleared
			val obs =
				DispatcherObservation.EMPTY.copy(
					trains =
						listOf(
							TrainView(
								trainId = "T-3",
								phase = TrainPhase.RUNNING,
								frontSectionName = "kB",
								velocityMps = 5.0,
								accelerationMps2 = 0.0,
								destinationInOutName = "B",
								signalAheadName = null,
								signalAheadAspect = null,
								distanceToSignalAheadMetres = 0.0,
								waitingSinceSimTime = null,
								waitSeconds = 0.0
							)
						)
				)
			latch.updateFromObservation(obs)
			assertThat(latch.getHint("T-3")).isNull()
		}

		@Test
		@DisplayName("Hint for an EXITED train is cleared when observation shows train absent")
		fun hintForAbsentTrainIsClearedByObservation() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			// Observation with no trains (T-3 has exited)
			latch.updateFromObservation(DispatcherObservation.EMPTY)
			assertThat(latch.getHint("T-3")).isNull()
		}

		@Test
		@DisplayName("Hint for a HELD train is retained after updateFromObservation with that train HELD")
		fun hintForHeldTrainIsRetained() {
			latch.onConflict(buildEvent("T-3", "T-4"))
			latch.updateFromObservation(buildObservation(listOf("T-3")))
			assertThat(latch.getHint("T-3")).isNotNull()
		}

		@Test
		@DisplayName("Selectively clears: only non-HELD train hint is removed, HELD train hint retained")
		fun selectivelyClearsOnlyNonHeldTrains() {
			latch.onConflict(buildEvent("T-3", "T-1")) // T-3 HELD
			latch.onConflict(buildEvent("T-5", "T-1")) // T-5 to be cleared

			// Observation: only T-3 is HELD; T-5 is absent
			latch.updateFromObservation(buildObservation(listOf("T-3")))

			assertThat(latch.getHint("T-3")).isNotNull()
			assertThat(latch.getHint("T-5")).isNull()
		}
	}

	// ── Deduplication: putIfAbsent semantics ──────────────────────────────────

	@Nested
	@DisplayName("Deduplication: second conflict event for same train is ignored")
	inner class Deduplication {
		@Test
		@DisplayName("Second onConflict for same trainId does not overwrite first hint")
		fun secondConflictDoesNotOverwriteFirstHint() {
			val block2 = mockk<DynamicTrackBlock>(relaxed = true)
			every { block2.name } returns "kC"

			latch.onConflict(buildEvent("T-3", "T-4", block = mockBlock))
			latch.onConflict(buildEvent("T-3", "T-7", block = block2))

			val hint = latch.getHint("T-3")!!
			// First hint (kB / T-4) must be retained
			assertThat(hint.contains("kB")).isTrue()
			assertThat(hint.contains("T-4")).isTrue()
			// Second hint (kC / T-7) must NOT be present
			assertThat(hint.contains("T-7")).isFalse()
		}
	}
}
