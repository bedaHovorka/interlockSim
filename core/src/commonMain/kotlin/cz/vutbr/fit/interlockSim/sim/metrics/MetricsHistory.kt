/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

/**
 * Bounded time series of [MetricsSnapshot]s recorded over one simulation run.
 *
 * [DefaultMetricsCollectionService] appends every snapshot it publishes, one per handled block
 * event. Two caps keep memory bounded on long runs, and both evict the **oldest** entries first:
 *
 * - **count cap** [maxEntries] — never more than this many snapshots. Default
 *   [DEFAULT_MAX_ENTRIES] (10 000): about 1 MB for the eleven-number snapshot. A
 *   `shuntingLoop` run on `vyhybna.xml` published 409 snapshots in 600 simulated seconds
 *   (measured 2026-10-31, about 41 block events per simulated minute), so the default holds about
 *   four simulated hours of that traffic; a busier station fills it sooner.
 * - **time window** [retentionSeconds] — when set, a snapshot more than this many simulated seconds
 *   older than the last one added is dropped. Default `null`: no time window, only the count cap.
 *
 * Snapshots are expected in non-decreasing [MetricsSnapshot.time] (the simulation clock only moves
 * forward); [between] sorts its answer by time all the same, so the order holds even if one arrives
 * late.
 *
 * ## Threading
 *
 * Not thread-safe. Written on the simulation thread; read it there or after the run has ended. A
 * reader on another thread uses [MetricsCollectionService.latestSnapshot] instead.
 *
 * @param maxEntries Most snapshots kept; must be positive.
 * @param retentionSeconds Simulated-time window kept behind the newest snapshot, or `null` for none;
 *   must be finite and non-negative when set.
 * @since Issue #673 (Goal 6 SP2)
 */
class MetricsHistory(
	val maxEntries: Int = DEFAULT_MAX_ENTRIES,
	val retentionSeconds: Double? = null
) {
	init {
		require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
		require(retentionSeconds == null || (retentionSeconds.isFinite() && retentionSeconds >= 0.0)) {
			"retentionSeconds must be finite and non-negative, was $retentionSeconds"
		}
	}

	private val entries = ArrayDeque<MetricsSnapshot>()

	/**
	 * Number of snapshots currently kept.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	val size: Int
		get() = entries.size

	/**
	 * Append [snapshot], then evict the oldest entries beyond the count cap and outside the time
	 * window measured back from [snapshot].
	 */
	internal fun add(snapshot: MetricsSnapshot) {
		entries.addLast(snapshot)
		while (entries.size > maxEntries) {
			entries.removeFirst()
		}
		val window = retentionSeconds ?: return
		val oldestKept = snapshot.time - window
		while (entries.first().time < oldestKept) {
			entries.removeFirst()
		}
	}

	/**
	 * Every kept snapshot, oldest first, as a copy the history does not change afterwards.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	fun all(): List<MetricsSnapshot> = entries.toList()

	/**
	 * Snapshots with `fromTime <= time <= toTime`, in time order; snapshots of one instant keep the
	 * order they arrived in. Empty when nothing lies in the range or when `fromTime > toTime`.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	fun between(
		fromTime: Double,
		toTime: Double
	): List<MetricsSnapshot> = entries.filter { it.time in fromTime..toTime }.sortedBy { it.time }

	/**
	 * Defaults of [MetricsHistory].
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	companion object {
		/**
		 * Default [maxEntries]: 10 000 snapshots.
		 *
		 * @since Issue #673 (Goal 6 SP2)
		 */
		const val DEFAULT_MAX_ENTRIES: Int = 10_000
	}
}
