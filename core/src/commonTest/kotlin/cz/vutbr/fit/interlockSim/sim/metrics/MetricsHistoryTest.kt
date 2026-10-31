/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test

/**
 * Unit tests for [MetricsHistory]: the count cap, the simulation-time retention window, and the
 * inclusive, time-ordered range query.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
class MetricsHistoryTest {
	private fun snapshotAt(
		time: Double,
		completed: Int = 0
	) = MetricsSnapshot(
		time = time,
		conflictCount = 0,
		completedTrains = completed,
		throughput = 0.0,
		totalWaitSeconds = 0.0,
		averageWaitSeconds = 0.0,
		occupiedBlocks = 0,
		totalBlocks = 0,
		utilization = 0.0
	)

	private fun MetricsHistory.times(): List<Double> = all().map { it.time }

	@Test
	fun `a new history is empty`() {
		val history = MetricsHistory()

		assertThat(history.size).isEqualTo(0)
		assertThat(history.all()).isEmpty()
		assertThat(history.between(0.0, 100.0)).isEmpty()
	}

	@Test
	fun `the defaults bound memory by count and keep no time window`() {
		val history = MetricsHistory()

		assertThat(history.maxEntries).isEqualTo(MetricsHistory.DEFAULT_MAX_ENTRIES)
		assertThat(history.retentionSeconds).isEqualTo(null)
	}

	@Test
	fun `entries are kept in insertion order`() {
		val history = MetricsHistory()
		listOf(1.0, 2.0, 3.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.times()).containsExactly(1.0, 2.0, 3.0)
		assertThat(history.size).isEqualTo(3)
	}

	@Test
	fun `the count cap evicts the oldest entries first`() {
		val history = MetricsHistory(maxEntries = 3)
		listOf(1.0, 2.0, 3.0, 4.0, 5.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.times()).containsExactly(3.0, 4.0, 5.0)
		assertThat(history.size).isEqualTo(3)
	}

	@Test
	fun `the retention window evicts entries older than the newest minus the window`() {
		val history = MetricsHistory(retentionSeconds = 10.0)
		listOf(0.0, 4.0, 9.0, 15.0).forEach { history.add(snapshotAt(it)) }

		// Newest is 15.0, so the window keeps [5.0, 15.0]: 0.0 and 4.0 go, oldest first.
		assertThat(history.times()).containsExactly(9.0, 15.0)
	}

	@Test
	fun `an entry exactly at the window edge is kept`() {
		val history = MetricsHistory(retentionSeconds = 10.0)
		listOf(5.0, 15.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.times()).containsExactly(5.0, 15.0)
	}

	@Test
	fun `count cap and retention window apply together`() {
		val history = MetricsHistory(maxEntries = 2, retentionSeconds = 100.0)
		listOf(1.0, 2.0, 3.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.times()).containsExactly(2.0, 3.0)
	}

	@Test
	fun `between returns the inclusive range in time order`() {
		val history = MetricsHistory()
		listOf(1.0, 2.0, 3.0, 4.0, 5.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.between(2.0, 4.0).map { it.time }).containsExactly(2.0, 3.0, 4.0)
	}

	@Test
	fun `between keeps every snapshot of one instant in arrival order`() {
		val history = MetricsHistory()
		history.add(snapshotAt(1.0, completed = 0))
		history.add(snapshotAt(2.0, completed = 1))
		history.add(snapshotAt(2.0, completed = 2))
		history.add(snapshotAt(3.0, completed = 3))

		assertThat(history.between(2.0, 2.0).map { it.completedTrains }).containsExactly(1, 2)
	}

	@Test
	fun `between returns time order even when snapshots arrived out of order`() {
		val history = MetricsHistory()
		listOf(3.0, 1.0, 2.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.between(0.0, 10.0).map { it.time }).containsExactly(1.0, 2.0, 3.0)
	}

	@Test
	fun `between with a range outside the history is empty`() {
		val history = MetricsHistory()
		listOf(1.0, 2.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.between(5.0, 9.0)).isEmpty()
	}

	@Test
	fun `between with the bounds reversed is empty`() {
		val history = MetricsHistory()
		listOf(1.0, 2.0, 3.0).forEach { history.add(snapshotAt(it)) }

		assertThat(history.between(3.0, 1.0)).isEmpty()
	}

	@Test
	fun `all returns a copy the history does not change afterwards`() {
		val history = MetricsHistory(maxEntries = 2)
		history.add(snapshotAt(1.0))
		val before = history.all()

		history.add(snapshotAt(2.0))
		history.add(snapshotAt(3.0))

		assertThat(before.map { it.time }).containsExactly(1.0)
	}

	@Test
	fun `a non-positive count cap is rejected`() {
		assertFailure { MetricsHistory(maxEntries = 0) }.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	fun `a negative or non-finite retention window is rejected`() {
		assertFailure { MetricsHistory(retentionSeconds = -1.0) }.isInstanceOf<IllegalArgumentException>()
		assertFailure { MetricsHistory(retentionSeconds = Double.NaN) }.isInstanceOf<IllegalArgumentException>()
		assertFailure {
			MetricsHistory(retentionSeconds = Double.POSITIVE_INFINITY)
		}.isInstanceOf<IllegalArgumentException>()
	}
}
