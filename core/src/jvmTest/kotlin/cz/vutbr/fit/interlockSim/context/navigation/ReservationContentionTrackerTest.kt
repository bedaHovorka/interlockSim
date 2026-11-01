/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Unit tests for [ReservationContentionTracker] (Issue #1167, extracted from [DefaultPathReservationService]). */
class ReservationContentionTrackerTest {
	private val owners = mutableMapOf<DynamicTrackBlock, String>()
	private val registry =
		mockk<PathReservationRegistry> {
			every { getOwner(any()) } answers { owners[firstArg()] }
		}
	private val emitted = mutableListOf<ConflictDetectedEvent>()
	private val tracker = ReservationContentionTracker(registry) { emitted += it }

	private fun block(
		state: TrackFacility.State,
		trainName: String? = null
	): DynamicTrackBlock =
		mockk {
			every { getState() } returns state
			every { this@mockk.trainName } returns trainName
		}

	private val busyA = block(TrackFacility.State.RESERVED, "owner")
	private val busyB = block(TrackFacility.State.OCCUPIED, "owner")

	@Nested
	inner class FindFirstBlockedConflict {
		@Test
		fun `returns the first busy block of another train paired with its registry owner`() {
			val free = block(TrackFacility.State.FREE)
			owners[busyA] = "registered"

			assertThat(tracker.findFirstBlockedConflict(listOf(free, busyA, busyB), "me"))
				.isEqualTo(busyA to "registered")
		}

		@Test
		fun `falls back to the block's own train name when the registry knows no owner`() {
			assertThat(tracker.findFirstBlockedConflict(listOf(busyB), "me")).isEqualTo(busyB to "owner")
		}

		@Test
		fun `skips blocks the requesting train holds itself`() {
			val mine = block(TrackFacility.State.RESERVED, "me")

			assertThat(tracker.findFirstBlockedConflict(listOf(mine, busyB), "me")).isEqualTo(busyB to "owner")
		}

		@Test
		fun `returns null when every block is free or already held by the train`() {
			val free = block(TrackFacility.State.FREE)
			val mine = block(TrackFacility.State.OCCUPIED, "me")

			assertThat(tracker.findFirstBlockedConflict(listOf(free, mine), "me")).isNull()
		}

		@Test
		fun `returns null when the first busy block has no owner at all`() {
			val ownerless = block(TrackFacility.State.RESERVED, null)

			assertThat(tracker.findFirstBlockedConflict(listOf(ownerless, busyB), "me")).isNull()
		}
	}

	@Nested
	inner class RecordContentionAndEmitIfNew {
		@Test
		fun `emits one conflict event per new contention with the trains named`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")

			assertThat(emitted).hasSize(1)
			val event = emitted.single()
			assertThat(event.block).isEqualTo(busyA)
			assertThat(event.trainId).isEqualTo("me")
			assertThat(event.conflictingTrainId).isEqualTo("owner")
			// Outside a running simulation currentSimulationTime() falls back to 0.0.
			assertThat(event.time).isEqualTo(0.0)
		}

		@Test
		fun `the conflict event time equals the recorded contention start`() {
			// One clock read: a second read would stamp the event with 9.0, not the stored 5.0 (the
			// "since" of the open-contention snapshot, Issue #1305).
			mockkObject(Process.Companion)
			try {
				every { Process.time() } returnsMany listOf(5.0, 9.0)

				tracker.recordContentionAndEmitIfNew("me", busyA, "owner")

				assertThat(emitted.single().time).isEqualTo(5.0)
				assertThat(tracker.openContentions { null }.single().since).isEqualTo(5.0)
				verify(exactly = 1) { Process.time() }
			} finally {
				unmockkObject(Process.Companion)
			}
		}

		@Test
		fun `a repeated contention on the same block emits nothing more`() {
			repeat(3) { tracker.recordContentionAndEmitIfNew("me", busyA, "owner") }

			assertThat(emitted).hasSize(1)
		}

		@Test
		fun `another block or another train is a new contention`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			tracker.recordContentionAndEmitIfNew("me", busyB, "owner")
			tracker.recordContentionAndEmitIfNew("other", busyA, "owner")

			assertThat(emitted.map { it.trainId to it.block })
				.containsExactly("me" to busyA, "me" to busyB, "other" to busyA)
		}

		@Test
		fun `a cleared contention emits again when it recurs`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			tracker.clearBlockedTracking("me")
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")

			assertThat(emitted).hasSize(2)
		}
	}

	@Nested
	inner class FlushUnresolvedConflicts {
		@Test
		fun `returns nothing when no contention is tracked`() {
			assertThat(tracker.flushUnresolvedConflicts(10.0)).isEmpty()
		}

		@Test
		fun `reports each unresolved contention in record order with the current owner and the end time`() {
			owners[busyA] = "registered"
			tracker.recordContentionAndEmitIfNew("me", busyA, "stale")
			tracker.recordContentionAndEmitIfNew("other", busyB, "owner")

			assertThat(tracker.flushUnresolvedConflicts(10.0)).containsExactly(
				BlockEvent.ReservationConflictDetected(busyA, "me", "registered", 10.0, atRunEnd = true),
				BlockEvent.ReservationConflictDetected(busyB, "other", "owner", 10.0, atRunEnd = true)
			)
		}

		@Test
		fun `drops a contention whose block nobody owns any more`() {
			val freed = block(TrackFacility.State.FREE, null)
			tracker.recordContentionAndEmitIfNew("me", freed, "gone")

			assertThat(tracker.flushUnresolvedConflicts(10.0)).isEmpty()
		}

		@Test
		fun `forgets everything it reported`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			tracker.flushUnresolvedConflicts(10.0)

			assertThat(tracker.flushUnresolvedConflicts(11.0)).isEmpty()
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			assertThat(emitted).hasSize(2)
		}
	}

	@Nested
	inner class ClearBlockedTracking {
		@Test
		fun `forgets only the given train's contention`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			tracker.recordContentionAndEmitIfNew("me", busyB, "owner")
			tracker.recordContentionAndEmitIfNew("other", busyA, "owner")

			tracker.clearBlockedTracking("me")

			assertThat(tracker.flushUnresolvedConflicts(1.0).map { it.trainId }).containsExactly("other")
		}

		@Test
		fun `is a no-op for a train with no contention`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")

			tracker.clearBlockedTracking("nobody")

			assertThat(tracker.flushUnresolvedConflicts(1.0)).hasSize(1)
		}
	}

	@Nested
	inner class BlockedTrainIds {
		@Test
		fun `projects the multi-block contention of one train as a single deduplicated id`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			tracker.recordContentionAndEmitIfNew("me", busyB, "owner")
			tracker.recordContentionAndEmitIfNew("other", busyA, "owner")

			assertThat(tracker.blockedTrainIds()).containsExactlyInAnyOrder("me", "other")
		}

		@Test
		fun `snapshot is independent of later tracking changes`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			tracker.recordContentionAndEmitIfNew("other", busyB, "owner")
			val snapshot = tracker.blockedTrainIds()

			tracker.clearBlockedTracking("me")

			assertThat(snapshot).containsExactlyInAnyOrder("me", "other")
			assertThat(tracker.blockedTrainIds()).containsExactlyInAnyOrder("other")
		}
	}

	/** Runs [body] with the simulation clock answering [times] in turn (Issue #1305). */
	private fun withClock(
		vararg times: Double,
		body: () -> Unit
	) {
		mockkObject(Process.Companion)
		try {
			every { Process.time() } returnsMany times.toList()
			body()
		} finally {
			unmockkObject(Process.Companion)
		}
	}

	@Nested
	inner class OpenContentions {
		@Test
		fun `lists every open contention in record order with the owner named now and its start`() {
			withClock(3.0, 7.0) {
				tracker.recordContentionAndEmitIfNew("me", busyA, "stale")
				tracker.recordContentionAndEmitIfNew("other", busyB, "owner")
			}

			assertThat(tracker.openContentions { if (it == busyA) "now-owner" else null }).containsExactly(
				OpenContention("me", busyA, "now-owner", 3.0),
				OpenContention("other", busyB, null, 7.0)
			)
		}

		@Test
		fun `is empty with no contention and after the train is cleared`() {
			assertThat(tracker.openContentions { null }).isEmpty()
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")

			tracker.clearBlockedTracking("me")

			assertThat(tracker.openContentions { null }).isEmpty()
		}

		@Test
		fun `is a copy that later tracking changes leave alone`() {
			tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			val snapshot = tracker.openContentions { null }

			tracker.clearBlockedTracking("me")

			assertThat(snapshot).hasSize(1)
		}
	}

	@Nested
	inner class BlockedSeconds {
		@Test
		fun `a cleared contention counts the time from its start to the clear`() {
			withClock(10.0, 25.0) {
				tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
				tracker.clearBlockedTracking("me")
			}

			assertThat(tracker.blockedSeconds(now = 99.0)).isEqualTo(mapOf("me" to 15.0))
		}

		@Test
		fun `overlapping contentions of one train count once, from the earliest start`() {
			withClock(10.0, 14.0, 20.0) {
				tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
				tracker.recordContentionAndEmitIfNew("me", busyB, "owner")
				tracker.clearBlockedTracking("me")
			}

			assertThat(tracker.blockedSeconds(now = 99.0)).isEqualTo(mapOf("me" to 10.0))
		}

		@Test
		fun `episodes of one train add up and an open one counts up to now`() {
			withClock(1.0, 3.0, 10.0) {
				tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
				tracker.clearBlockedTracking("me")
				tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
			}

			assertThat(tracker.blockedSeconds(now = 14.0)).isEqualTo(mapOf("me" to 6.0))
		}

		@Test
		fun `the end-of-run flush closes every open contention at the end time`() {
			withClock(4.0, 6.0) {
				tracker.recordContentionAndEmitIfNew("me", busyA, "owner")
				tracker.recordContentionAndEmitIfNew("other", block(TrackFacility.State.FREE, null), "gone")
			}

			tracker.flushUnresolvedConflicts(10.0)

			// The ownerless one is dropped from the flushed events, but the train still waited until the end.
			assertThat(tracker.blockedSeconds(now = 99.0)).isEqualTo(mapOf("me" to 6.0, "other" to 4.0))
		}

		@Test
		fun `clearing a train with no contention reads no clock and records nothing`() {
			mockkObject(Process.Companion)
			try {
				tracker.clearBlockedTracking("nobody")

				verify(exactly = 0) { Process.time() }
			} finally {
				unmockkObject(Process.Companion)
			}
			assertThat(tracker.blockedSeconds(now = 1.0)).isEmpty()
		}
	}
}
