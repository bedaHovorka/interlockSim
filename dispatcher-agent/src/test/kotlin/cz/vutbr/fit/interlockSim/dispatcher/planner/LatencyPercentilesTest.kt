/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [nearestRankPercentile] — the pure percentile function backing
 * [DefaultDispatcherRunRecorder]'s p50/p95/max latency fields.
 *
 * @since Issue #834 (SP2c.11 — real per-run inference latency)
 */
@DisplayName("nearestRankPercentile")
class LatencyPercentilesTest {
	@Test
	fun `empty sample set yields zero`() {
		assertThat(nearestRankPercentile(emptyList(), 50.0)).isEqualTo(0L)
		assertThat(nearestRankPercentile(emptyList(), 95.0)).isEqualTo(0L)
		assertThat(nearestRankPercentile(emptyList(), 100.0)).isEqualTo(0L)
	}

	@Test
	fun `single sample is returned for any percentile`() {
		assertThat(nearestRankPercentile(listOf(42L), 50.0)).isEqualTo(42L)
		assertThat(nearestRankPercentile(listOf(42L), 95.0)).isEqualTo(42L)
		assertThat(nearestRankPercentile(listOf(42L), 100.0)).isEqualTo(42L)
	}

	@Test
	fun `p50 of an odd-sized set is the middle element`() {
		// sorted: 5, 15, 25 — nearest-rank median is the middle element
		assertThat(nearestRankPercentile(listOf(25L, 5L, 15L), 50.0)).isEqualTo(15L)
	}

	@Test
	fun `p50 of an even-sized set follows nearest-rank (upper of the two middles)`() {
		// sorted: 10, 20, 30, 40 — nearest-rank(50) = ceil(0.5 * 4) = index 2 (1-based) -> 20
		assertThat(nearestRankPercentile(listOf(40L, 10L, 30L, 20L), 50.0)).isEqualTo(20L)
	}

	@Test
	fun `p95 is not confused with max on a larger sample set`() {
		// sorted: 1..20 — nearest-rank(95) = ceil(0.95 * 20) = 19th element = 19, distinct from max = 20
		val samples = (1..20).map { it.toLong() }.shuffled()

		assertThat(nearestRankPercentile(samples, 95.0)).isEqualTo(19L)
		assertThat(nearestRankPercentile(samples, 100.0)).isEqualTo(20L)
	}

	@Test
	fun `unsorted input is sorted before ranking`() {
		assertThat(nearestRankPercentile(listOf(300L, 100L, 200L), 50.0)).isEqualTo(200L)
	}
}
