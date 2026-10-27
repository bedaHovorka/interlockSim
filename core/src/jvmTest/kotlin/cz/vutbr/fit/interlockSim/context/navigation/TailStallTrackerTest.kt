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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.single
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The stall rule of reservation expiry phase 2 (Issue #1220, ruling D1–D3) in [TailStallTracker]
 * alone: no kDisco, no release, a hand-moved clock and a registry stand-in whose
 * [PathReservationRegistry.occupyingHoldings] the test sets. The release side of the same rule is
 * pinned in [ReservationExpiryOccupyingTrainTest].
 */
class TailStallTrackerTest {
	/** A train whose progress reading the test moves by hand. */
	private class Occupant(
		override val name: String,
		var reading: Double = 0.0
	) : TrackOccupant {
		override fun distanceToSemaphore(): Double = reading

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

	/** A block stand-in whose occupant the test sets. */
	private class Spot {
		var standing: TrackOccupant? = null
		val block: DynamicTrackBlock = mockk(relaxed = true) { every { occupant } answers { standing } }
	}

	private val train = Occupant(TRAIN)
	private val head = Spot().also { it.standing = train }
	private val tail = listOf(Spot().block, Spot().block)
	private var holdings = listOf(OccupyingHolding(TRAIN, listOf(head.block), tail))
	private var now = 0.0
	private val registry = mockk<PathReservationRegistry> { every { occupyingHoldings() } answers { holdings } }
	private val tracker = TailStallTracker(registry, TTL) { now }

	/** [TailStallTracker.stalled] at simulation time [time]. */
	private fun at(
		time: Double,
		exceptTrainId: String? = null
	): List<TailStallTracker.StalledTail> {
		now = time
		return tracker.stalled(exceptTrainId)
	}

	/** Replace [TRAIN]'s holding by one standing on [occupied] with [reserved] as its tail. */
	private fun holding(
		occupied: List<DynamicTrackBlock> = listOf(head.block),
		reserved: List<DynamicTrackBlock> = tail
	) {
		holdings = listOf(OccupyingHolding(TRAIN, occupied, reserved))
	}

	/** [TRAIN] is reported stalled at [time], with its clock started at [since]. */
	private fun assertStalledAt(
		time: Double,
		since: Double
	) {
		val stalled = assertThat(at(time)).single()
		stalled.transform { it.holding.trainId }.isEqualTo(TRAIN)
		stalled.transform { it.snapshot.sinceSimTime }.isEqualTo(since)
		stalled.transform { it.stalledForSimSeconds }.isEqualTo(time - since)
	}

	@Test
	fun `a train seen for the first time starts a fresh clock`() {
		assertThat(at(FIRST_SEEN)).isEmpty()
		assertThat(at(FIRST_SEEN + TTL - 1.0)).isEmpty()
		assertStalledAt(FIRST_SEEN + TTL, since = FIRST_SEEN)
	}

	@Test
	fun `a stalled train hands over the holding the registry reported`() {
		at(0.0)
		assertThat(at(TTL)).single().transform { it.holding }.isSameInstanceAs(holdings.single())
	}

	@Test
	fun `creep below the threshold within the TTL is still stalled`() {
		at(0.0)
		train.reading -= CREEP
		at(TTL / 3)
		train.reading -= CREEP
		at(2 * TTL / 3)
		train.reading -= CREEP
		assertStalledAt(TTL, since = 0.0)
	}

	@Test
	fun `creep that adds up past the threshold restarts the clock`() {
		// Each step is under the threshold; measured from the clock's own reading, the third passes it.
		at(0.0)
		for (step in 1..3) {
			train.reading -= SLOW_STEP
			assertThat(at(step * STEP_GAP)).isEmpty()
		}
		assertThat(at(TTL)).isEmpty()
		assertStalledAt(3 * STEP_GAP + TTL, since = 3 * STEP_GAP)
	}

	@Test
	fun `a move of exactly the threshold is not progress`() {
		at(0.0)
		train.reading = -TailStallTracker.PROGRESS_THRESHOLD_METERS
		assertStalledAt(TTL, since = 0.0)
	}

	@Test
	fun `a move just past the threshold is progress`() {
		at(0.0)
		train.reading = -2 * TailStallTracker.PROGRESS_THRESHOLD_METERS
		assertThat(at(STEP_GAP)).isEmpty()
		assertThat(at(TTL)).isEmpty()
		assertStalledAt(STEP_GAP + TTL, since = STEP_GAP)
	}

	@Test
	fun `a change of the occupied set restarts the clock`() {
		at(0.0)
		val second = Spot().also { it.standing = train }
		holding(occupied = listOf(head.block, second.block))
		assertThat(at(STEP_GAP)).isEmpty()
		assertThat(at(TTL)).isEmpty()
		assertStalledAt(STEP_GAP + TTL, since = STEP_GAP)
	}

	@Test
	fun `a change of the reserved set restarts the clock`() {
		at(0.0)
		holding(reserved = tail + Spot().block)
		assertThat(at(STEP_GAP)).isEmpty()
		assertThat(at(TTL)).isEmpty()
		assertStalledAt(STEP_GAP + TTL, since = STEP_GAP)
	}

	@Test
	fun `an unreadable stretch drops the clock, so the train is judged a TTL after it is readable again`() {
		at(0.0)
		// Nobody on the block, then somebody else on it: no reading of the train's own progress.
		head.standing = null
		assertThat(at(TTL)).isEmpty()
		head.standing = Occupant("intruder")
		assertThat(at(3 * TTL)).isEmpty()
		// Readable again with the very sets and reading of t=0: the clock starts now, not at t=0.
		head.standing = train
		assertThat(at(READABLE_AGAIN)).isEmpty()
		assertThat(at(READABLE_AGAIN + TTL - 1.0)).isEmpty()
		assertStalledAt(READABLE_AGAIN + TTL, since = READABLE_AGAIN)
	}

	@Test
	fun `the exempt train is neither judged nor snapshotted`() {
		// Exempt on its first sighting: no clock taken, so the next call's sighting starts it.
		assertThat(at(0.0, exceptTrainId = TRAIN)).isEmpty()
		assertThat(at(STEP_GAP)).isEmpty()
		assertThat(at(TTL)).isEmpty()
		// Stalled, but exempt on this call; the next call judges it with the clock it had.
		assertThat(at(STEP_GAP + TTL, exceptTrainId = TRAIN)).isEmpty()
		assertStalledAt(STEP_GAP + TTL + 1.0, since = STEP_GAP)
	}

	@Test
	fun `only the exempt train is spared`() {
		val other = Occupant(OTHER)
		val otherHead = Spot().also { it.standing = other }
		holdings = holdings + OccupyingHolding(OTHER, listOf(otherHead.block), listOf(Spot().block))
		at(0.0)
		assertThat(at(TTL, exceptTrainId = TRAIN)).single().transform { it.holding.trainId }.isEqualTo(OTHER)
	}

	@Test
	fun `a train with no tail keeps no clock`() {
		at(0.0)
		holding(reserved = emptyList())
		assertThat(at(STEP_GAP)).isEmpty()
		// The same tail back: a fresh clock from this call, not the one of t=0.
		holding()
		assertThat(at(2 * STEP_GAP)).isEmpty()
		assertThat(at(TTL)).isEmpty()
		assertStalledAt(2 * STEP_GAP + TTL, since = 2 * STEP_GAP)
	}

	@Test
	fun `a train that leaves the occupying holdings starts a fresh clock when it is back`() {
		at(0.0)
		holdings = emptyList()
		assertThat(at(STEP_GAP)).isEmpty()
		holding()
		assertThat(at(2 * STEP_GAP)).isEmpty()
		assertThat(at(TTL)).isEmpty()
		assertStalledAt(2 * STEP_GAP + TTL, since = 2 * STEP_GAP)
	}

	@Test
	fun `keepClock keeps the clock with the remaining tail`() {
		at(0.0)
		val stalled = at(TTL).single()
		// The expiry's own release freed part of the tail: that is not progress.
		holding(reserved = tail.drop(1))
		tracker.keepClock(stalled, tail.drop(1).toSet())
		assertStalledAt(TTL + STEP_GAP, since = 0.0)
	}

	@Test
	fun `without keepClock the shorter tail restarts the clock`() {
		at(0.0)
		at(TTL).single()
		holding(reserved = tail.drop(1))
		assertThat(at(TTL + STEP_GAP)).isEmpty()
	}

	@Test
	fun `forget drops the clock`() {
		at(0.0)
		at(TTL).single()
		tracker.forget(TRAIN)
		assertThat(at(TTL + STEP_GAP)).isEmpty()
		assertThat(at(2 * TTL)).isEmpty()
		assertStalledAt(2 * TTL + STEP_GAP, since = TTL + STEP_GAP)
	}

	private companion object {
		const val TRAIN = "train1"
		const val OTHER = "train2"
		const val TTL = 60.0
		const val FIRST_SEEN = 50.0
		const val STEP_GAP = 10.0
		const val READABLE_AGAIN = 3 * TTL + 2.0

		/** 2 mm a step: 6 mm over the TTL, under the 1 cm threshold. */
		const val CREEP = 0.002

		/** 4 mm a step: under 1 cm each, over it after three. */
		const val SLOW_STEP = 0.004
	}
}
