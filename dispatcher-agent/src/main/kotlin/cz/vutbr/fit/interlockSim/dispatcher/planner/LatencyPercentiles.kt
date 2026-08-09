/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import kotlin.math.ceil

/**
 * Nearest-rank percentile of [samples], the convention backing
 * [DispatcherRunSnapshot.latencyP50Ms], [DispatcherRunSnapshot.latencyP95Ms] and
 * [DispatcherRunSnapshot.latencyMaxMs].
 *
 * ## Convention
 *
 * Nearest-rank: sort [samples] ascending, then take the element at (1-based) rank
 * `ceil(percentile / 100 * n)`, clamped to `[1, n]`. This is the simplest percentile
 * definition that requires no interpolation and is exact for `percentile = 100.0` (returns the
 * maximum) and `n = 1` (returns the single sample for any percentile).
 *
 * [samples] is empty for a run that recorded no ticks with a meaningful latency (e.g. the
 * rule-based arm, or an LLM run whose every cycle failed before inference started) — that case
 * returns `0L` rather than throwing, preserving the pre-#834 behaviour of reporting `0` when
 * there is nothing to measure.
 *
 * @param samples Latency samples in milliseconds, in any order.
 * @param percentile Percentile to compute, in `[0, 100]`.
 * @return The nearest-rank percentile value, or `0L` if [samples] is empty.
 * @throws IllegalArgumentException if [percentile] is outside `[0, 100]`.
 *
 * @since Issue #834 (SP2c.11 — real per-run inference latency)
 */
internal fun nearestRankPercentile(
	samples: List<Long>,
	percentile: Double
): Long {
	require(percentile in 0.0..100.0) { "percentile must be in [0, 100], was $percentile" }
	if (samples.isEmpty()) return 0L
	val sorted = samples.sorted()
	val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
	return sorted[rank - 1]
}
