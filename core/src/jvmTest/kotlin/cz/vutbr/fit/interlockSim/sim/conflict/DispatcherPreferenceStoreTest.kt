/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 → Goal 10 prereq: DispatcherPreferenceStore (Issue #568).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultDispatcherPreferenceStore] — Goal 9 → Goal 10 prereq (#568).
 *
 * Verifies:
 * 1. [DispatcherPreferenceStore.record] appends choices.
 * 2. [DispatcherPreferenceStore.getChoices] returns an immutable snapshot.
 * 3. [DispatcherPreferenceStore.getChoicesForTrain] filters by trainId or conflictingTrainId.
 * 4. The [DispatcherPreferenceStore.ApplicationSource] is preserved in the stored choice.
 *
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
@DisplayName("DefaultDispatcherPreferenceStore — Goal 9 → Goal 10 prereq (#568)")
class DispatcherPreferenceStoreTest {
	private lateinit var store: DispatcherPreferenceStore
	private lateinit var block: DynamicTrackBlock

	private fun makeEvent(
		trainId: String,
		conflictingTrainId: String,
		time: Double = 0.0
	) = ConflictDetectedEvent(
		block = block,
		trainId = trainId,
		conflictingTrainId = conflictingTrainId,
		time = time
	)

	private fun makeHoldResolution(trainId: String) = ConflictResolution.HoldTrain(
		trainId = trainId,
		holdDurationSeconds = 30.0,
		affectedTrains = listOf(trainId),
		estimatedImpact = ConflictResolution.EstimatedImpact(30.0, "hold $trainId")
	)

	@BeforeEach
	fun setUp() {
		store = DefaultDispatcherPreferenceStore()
		block = mockk(relaxed = true)
	}

	@Nested
	@DisplayName("record")
	inner class RecordTests {
		@Test
		@DisplayName("empty store returns empty choices list")
		fun emptyStoreReturnsEmptyList() {
			assertThat(store.getChoices()).isEmpty()
		}

		@Test
		@DisplayName("single record is retrievable via getChoices")
		fun singleRecordIsRetrievable() {
			val event = makeEvent("T1", "T2")
			val resolution = makeHoldResolution("T1")

			store.record(event, resolution, DispatcherPreferenceStore.ApplicationSource.AUTO)

			val choices = store.getChoices()
			assertThat(choices).hasSize(1)
			assertThat(choices[0].event).isEqualTo(event)
			assertThat(choices[0].applied).isEqualTo(resolution)
			assertThat(choices[0].source).isEqualTo(DispatcherPreferenceStore.ApplicationSource.AUTO)
		}

		@Test
		@DisplayName("multiple records are stored in insertion order")
		fun multipleRecordsStoredInOrder() {
			val event1 = makeEvent("T1", "T2", time = 0.0)
			val event2 = makeEvent("T3", "T4", time = 5.0)
			val resolution1 = makeHoldResolution("T1")
			val resolution2 = makeHoldResolution("T3")

			store.record(event1, resolution1, DispatcherPreferenceStore.ApplicationSource.AUTO)
			store.record(event2, resolution2, DispatcherPreferenceStore.ApplicationSource.OPERATOR)

			val choices = store.getChoices()
			assertThat(choices).hasSize(2)
			assertThat(choices[0].event).isEqualTo(event1)
			assertThat(choices[1].event).isEqualTo(event2)
		}

		@Test
		@DisplayName("OPERATOR source is preserved when recording")
		fun operatorSourceIsPreserved() {
			val event = makeEvent("T1", "T2")
			val resolution = makeHoldResolution("T1")

			store.record(event, resolution, DispatcherPreferenceStore.ApplicationSource.OPERATOR)

			val choice = store.getChoices().first()
			assertThat(choice.source).isEqualTo(DispatcherPreferenceStore.ApplicationSource.OPERATOR)
		}
	}

	@Nested
	@DisplayName("getChoices snapshot immutability")
	inner class SnapshotTests {
		@Test
		@DisplayName("getChoices returns a snapshot — subsequent records do not mutate it")
		fun getChoicesReturnsSnapshot() {
			val event1 = makeEvent("T1", "T2")
			store.record(event1, makeHoldResolution("T1"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			val snapshotBeforeSecondRecord = store.getChoices()
			assertThat(snapshotBeforeSecondRecord).hasSize(1)

			// Add a second record after the snapshot was taken.
			store.record(makeEvent("T3", "T4"), makeHoldResolution("T3"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			// The snapshot must still have only the original choice.
			assertThat(snapshotBeforeSecondRecord).hasSize(1)
			// The store itself now has two.
			assertThat(store.getChoices()).hasSize(2)
		}
	}

	@Nested
	@DisplayName("getChoicesForTrain")
	inner class GetChoicesForTrainTests {
		@Test
		@DisplayName("returns empty list when no choices recorded for given train")
		fun returnsEmptyForUnknownTrain() {
			val event = makeEvent("T1", "T2")
			store.record(event, makeHoldResolution("T1"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			assertThat(store.getChoicesForTrain("T99")).isEmpty()
		}

		@Test
		@DisplayName("matches on trainId (the blocked train)")
		fun matchesOnTrainId() {
			val event = makeEvent(trainId = "Blocked", conflictingTrainId = "Holding")
			store.record(event, makeHoldResolution("Blocked"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			val choices = store.getChoicesForTrain("Blocked")
			assertThat(choices).hasSize(1)
			assertThat(choices[0].event.trainId).isEqualTo("Blocked")
		}

		@Test
		@DisplayName("matches on conflictingTrainId (the holding train)")
		fun matchesOnConflictingTrainId() {
			val event = makeEvent(trainId = "Blocked", conflictingTrainId = "Holding")
			store.record(event, makeHoldResolution("Blocked"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			val choices = store.getChoicesForTrain("Holding")
			assertThat(choices).hasSize(1)
			assertThat(choices[0].event.conflictingTrainId).isEqualTo("Holding")
		}

		@Test
		@DisplayName("filters out choices not involving the given train")
		fun filtersUnrelatedChoices() {
			store.record(
				makeEvent("T1", "T2"),
				makeHoldResolution("T1"),
				DispatcherPreferenceStore.ApplicationSource.AUTO
			)
			store.record(
				makeEvent("T3", "T4"),
				makeHoldResolution("T3"),
				DispatcherPreferenceStore.ApplicationSource.OPERATOR
			)
			store.record(
				makeEvent("T1", "T5"),
				makeHoldResolution("T1"),
				DispatcherPreferenceStore.ApplicationSource.AUTO
			)

			val choices = store.getChoicesForTrain("T1")
			assertThat(choices).hasSize(2)
			choices.forEach { choice ->
				assertThat(choice.event.trainId == "T1" || choice.event.conflictingTrainId == "T1").isEqualTo(true)
			}
		}

		@Test
		@DisplayName("getChoicesForTrain returns snapshot — subsequent records do not mutate it")
		fun getChoicesForTrainReturnsSnapshot() {
			val event1 = makeEvent("T1", "T2")
			store.record(event1, makeHoldResolution("T1"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			val snapshot = store.getChoicesForTrain("T1")
			assertThat(snapshot).hasSize(1)

			store.record(makeEvent("T1", "T3"), makeHoldResolution("T1"), DispatcherPreferenceStore.ApplicationSource.AUTO)

			assertThat(snapshot).hasSize(1)
			assertThat(store.getChoicesForTrain("T1")).hasSize(2)
		}
	}
}
