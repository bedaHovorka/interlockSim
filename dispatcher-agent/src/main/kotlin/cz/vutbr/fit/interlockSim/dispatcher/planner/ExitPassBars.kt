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

import cz.vutbr.fit.interlockSim.ExampleNames

/**
 * Where one exit bar of [ExitPassBars] comes from: a measured control-arm campaign.
 *
 * @property horizonSeconds Simulated run length the bar was measured at. Recorded with the bar,
 *   deliberately not a key of the table: a key would move the #895 verdicts (Issue #1315 ruling).
 * @property provenance What the control arm ran on: a commit, or, for the #895 entry whose run files
 *   are gone, a prose pointer to the record that survived.
 * @property runCount Runs of the campaign behind the bar.
 * @property rawFigures The measured per-run figures and the derivation, as read from the run JSONs.
 *
 * @since Issue #1315 (Goal 6)
 */
internal data class ExitBarSource(
	val horizonSeconds: Int,
	val provenance: String,
	val runCount: Int,
	val rawFigures: String
)

/**
 * One exit bar: the minimum count of trains that left by their **timetabled InOut** a run of the
 * example must reach to pass [RunReportAggregator.runPassed], and the [source] it was measured from.
 *
 * @since Issue #1315 (Goal 6)
 */
internal data class ExitPassBar(
	val bar: Long,
	val source: ExitBarSource
)

/**
 * The exit bar of each sweepable example (Issue #1315), keyed by the [ExampleNames] constants.
 *
 * A bar counts **exits by the timetabled InOut**: [RailwayOutcome.trainsExited] minus
 * [RailwayOutcome.wrongExits]. Each bar is derived from the deterministic arm of its example the way
 * #895 derived the vyhybna bar: half of the fewest own-InOut exits over the campaign's runs, rounded
 * up — a dispatcher that moves less than half of what the deterministic dispatcher moves is not doing
 * the job. Like [RunReportAggregator.MIN_TRAINS_EXITED] before it, every bar is a **tripwire**: it
 * exists so a throughput regression cannot pass a gate that only reads decision records.
 *
 * Rules of the table:
 * - `shuntingLoop`, `shuntingLoopAI` and a run file without an example (a GUI run, a file before
 *   schema version 13) keep the #895 bar [RunReportAggregator.MIN_TRAINS_EXITED] = 6, so the #895
 *   verdicts do not move — although the vyhybna control arm now exits 15 trains, not 11.
 * - An LLM example has no control arm of its own: it uses its rule-based twin's bar ([LLM_TWINS]).
 * - Any other name (`vyhybna.xml` from a GUI run of an XML file, Issue #1328, or an example nobody
 *   measured) has **no** bar: its runs get no exit check, and the report says so on their verdict.
 *
 * @since Issue #1315 (Goal 6)
 */
internal object ExitPassBars {
	/** Simulated horizon of every control-arm campaign in this table. */
	const val CONTROL_ARM_HORIZON_SECONDS = 600

	/** Commit of the Issue #1315 control-arm campaign (`origin/goal-6` on 2026-11-22). */
	private const val MEASURE_1315_COMMIT = "bc5c4b068b1e80fc9eda461d4726483c61edd505"

	/** Runs per example of the Issue #1315 campaign (all ten byte-identical per example). */
	private const val MEASURE_1315_RUNS = 10

	private const val BAR_SHUNTING_LOOP_SYNC = 7L
	private const val BAR_CERVENY_UJEZD = 10L
	private const val BAR_CERVENY_UJEZD_SYNC = 8L
	private const val BAR_PRAHA_HLAVNI_NADRAZI = 4L
	private const val BAR_THREE_TRAIN_FIXTURES = 2L

	/** The #895 runs of the rule-based vyhybna control arm (two campaigns of ten). */
	private const val ISSUE_895_CONTROL_RUNS = 20

	private val ISSUE_895_SOURCE =
		ExitBarSource(
			horizonSeconds = CONTROL_ARM_HORIZON_SECONDS,
			provenance = "Issue #895 campaign (run files deleted 2026-08-24; GOAL_10_SP2C14 report, section 12.2)",
			runCount = ISSUE_895_CONTROL_RUNS,
			rawFigures =
				"vyhybna.xml rule-based: 11 exits on every run, zero variance; bar = ceil(11 / 2) = 6. " +
					"Issue #1315 re-measured 15/15/0 entered/exited/unfinished, 0 wrong exits (would give 8); " +
					"kept at 6 by ruling so the #895 verdicts do not move"
		)

	private fun measured1315(rawFigures: String) =
		ExitBarSource(CONTROL_ARM_HORIZON_SECONDS, MEASURE_1315_COMMIT, MEASURE_1315_RUNS, rawFigures)

	/** The measured bars of the examples that have a control arm of their own. */
	private val BARS: Map<String, ExitPassBar> =
		mapOf(
			ExampleNames.SHUNTING_LOOP to ExitPassBar(RunReportAggregator.MIN_TRAINS_EXITED, ISSUE_895_SOURCE),
			ExampleNames.SHUNTING_LOOP_SYNC to
				ExitPassBar(
					BAR_SHUNTING_LOOP_SYNC,
					measured1315("rule_based arm: 15/14/1 entered/exited/unfinished, 0 wrong exits; ceil(14 / 2) = 7")
				),
			ExampleNames.CERVENY_UJEZD to
				ExitPassBar(
					BAR_CERVENY_UJEZD,
					measured1315("rule_based arm: 19/19/0 entered/exited/unfinished, 0 wrong exits; ceil(19 / 2) = 10")
				),
			ExampleNames.CERVENY_UJEZD_SYNC to
				ExitPassBar(
					BAR_CERVENY_UJEZD_SYNC,
					measured1315("rule_based arm: 19/16/3 entered/exited/unfinished, 0 wrong exits; ceil(16 / 2) = 8")
				),
			ExampleNames.PRAHA_HLAVNI_NADRAZI to
				ExitPassBar(
					BAR_PRAHA_HLAVNI_NADRAZI,
					measured1315("rule_based arm: 8/8/0 entered/exited/unfinished, 0 wrong exits; ceil(8 / 2) = 4")
				),
			ExampleNames.MULTI_TRAIN_LOOP to
				ExitPassBar(
					BAR_THREE_TRAIN_FIXTURES,
					measured1315("no_dispatcher arm: 3/3/0 entered/exited/unfinished, 0 wrong exits; ceil(3 / 2) = 2")
				),
			ExampleNames.THREE_TRAIN_LOOP to
				ExitPassBar(
					BAR_THREE_TRAIN_FIXTURES,
					measured1315("no_dispatcher arm: 3/3/0 entered/exited/unfinished, 0 wrong exits; ceil(3 / 2) = 2")
				)
		)

	/** Each LLM example and the rule-based twin whose bar it uses. */
	val LLM_TWINS: Map<String, String> =
		mapOf(
			ExampleNames.SHUNTING_LOOP_AI to ExampleNames.SHUNTING_LOOP,
			ExampleNames.CERVENY_UJEZD_AI to ExampleNames.CERVENY_UJEZD,
			ExampleNames.PRAHA_HLAVNI_NADRAZI_AI to ExampleNames.PRAHA_HLAVNI_NADRAZI
		)

	/**
	 * The bar of [example] with its source; `null` when [example] has no measured bar. A run without
	 * an example (`null`, or a blank name, which the sweep cell key also reads as unnamed) reads as
	 * `shuntingLoop`, the #895 network, **whatever network it actually ran**: a file before schema
	 * version 13 carries no example, so an SP5-era `cervenyUjezd` file is judged against the bar of 6,
	 * with its wrong exits subtracted.
	 */
	fun entryFor(example: String?): ExitPassBar? {
		val name = example?.takeIf { it.isNotBlank() } ?: ExampleNames.SHUNTING_LOOP
		return BARS[LLM_TWINS[name] ?: name]
	}

	/** The bar of [example]; `null` when it has none. See [entryFor]. */
	fun barFor(example: String?): Long? = entryFor(example)?.bar
}
