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
 * A group of metrics that can be switched on or off as a unit, to keep collection overhead off
 * large runs.
 *
 * @since Issue #674 (Goal 6 SP4)
 */
enum class MetricGroup {
	/**
	 * The counters the run record needs (delay, conflicts, throughput, utilization and the
	 * unresolved-at-run-end count). Always on: [MetricsConfig] cannot disable it.
	 */
	CORE,

	/** The [MetricsHistory] of published snapshots (Goal 6 SP2). */
	HISTORY,

	/** The per-journey [TrainJourneyRecord]s (Goal 6 SP2). */
	JOURNEYS,

	/** Per-train wait time (Goal 6 SP7); no consumer yet. */
	TRAIN_WAIT,

	/** The performance row of a run (Goal 6 SP8); no consumer yet. */
	PERFORMANCE
}

/**
 * Selection of the active [MetricGroup]s for a run.
 *
 * [MetricGroup.CORE] is always part of [enabled]: it is added when the caller omits it, so a
 * config can never switch off the counters the run record depends on. (A plain class rather than
 * a data class, because a generated `copy` would bypass that normalisation; equality is by
 * [enabled].)
 *
 * [DefaultMetricsCollectionService] reads the config once, at construction, so it is fixed for the
 * run: set it (for example through the Koin `MetricsConfig` binding) before `run()`.
 *
 * @param requested The groups the caller wants; [MetricGroup.CORE] is added if missing.
 * @since Issue #674 (Goal 6 SP4)
 */
class MetricsConfig(
	requested: Set<MetricGroup>
) {
	/** The active groups, always including [MetricGroup.CORE]. */
	val enabled: Set<MetricGroup> = requested + MetricGroup.CORE

	/** True if [group] is active; always true for [MetricGroup.CORE]. */
	fun isEnabled(group: MetricGroup): Boolean = group in enabled

	override fun equals(other: Any?): Boolean = other is MetricsConfig && other.enabled == enabled

	override fun hashCode(): Int = enabled.hashCode()

	override fun toString(): String = "MetricsConfig(enabled=$enabled)"

	companion object {
		/** Every group on; the default. */
		val ALL: MetricsConfig = MetricsConfig(MetricGroup.entries.toSet())

		/** Only [MetricGroup.CORE]: the lowest-overhead configuration. */
		fun coreOnly(): MetricsConfig = MetricsConfig(emptySet())
	}
}
