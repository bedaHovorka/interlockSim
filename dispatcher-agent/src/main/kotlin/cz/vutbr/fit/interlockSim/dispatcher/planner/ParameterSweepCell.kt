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

/**
 * The key of one Parameter Sweep row of `report.md` (Issue #1333): the run parameters **and** the
 * example the run ran. Within one arm's [ArmReport], runs with an equal key form one cell.
 *
 * Before #1333 the key was [RunParameters] alone, which has no example field, so two networks run
 * with equal parameters (`shuntingLoop` and `cervenyUjezd` on the rule-based arm, say) pooled into
 * one row of the Decision Hygiene and Railway Outcomes tables. Arm Comparison
 * ([RunReportAggregator.aggregateByArm]) still pools every example of an arm; only the sweep cells
 * are split.
 *
 * @property example [DispatcherRunSnapshot.example]; `null` for a run that named none (a GUI run, a
 *   file before schema version 13), rendered as [UNNAMED]. Only [of] normalizes a blank name to
 *   `null`; the constructor itself trusts the caller.
 *
 * @since Issue #1333 (Goal 6)
 */
internal data class ParameterSweepCellKey(
	val params: RunParameters,
	val example: String?
) {
	/** The Example cell of the row. */
	val exampleLabel: String get() = example ?: UNNAMED

	companion object {
		/** The Example cell of a run that named no example (a GUI run, a file before schema version 13). */
		const val UNNAMED = "(unnamed)"

		/** The key of the cell [snapshot] belongs to; a blank example counts as none, so it reads as [UNNAMED]. */
		fun of(snapshot: DispatcherRunSnapshot): ParameterSweepCellKey =
			ParameterSweepCellKey(snapshot.params, snapshot.example?.takeIf { it.isNotBlank() })
	}
}

/**
 * One ranked Parameter Sweep row: its [key], the [report] aggregated over the cell's runs alone (its
 * `runCount`, `passingRuns` and gate are the cell's own), and the cell's summed [outcome].
 *
 * @since Issue #1333 (Goal 6)
 */
internal data class ParameterSweepCell(
	val key: ParameterSweepCellKey,
	val report: ArmReport,
	val outcome: RailwayOutcome
)
