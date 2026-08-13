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

import kotlinx.serialization.Serializable

/**
 * What the railway actually achieved during one dispatcher run.
 *
 * Every other figure in [DispatcherRunSnapshot] describes *decision hygiene* — how many ticks the
 * planner completed, how many of its actions were rejected, whether the rule fallback engaged.
 * None of it says whether a single train moved. Issue #895 states the consequence plainly: *"The
 * gate scores decision hygiene, not outcomes. … If #893's fix works, these move; if only the gate
 * moves, it did not."* #834's sweep ranks parameter cells, and it cannot rank them on whether the
 * railway worked unless the run JSON carries these numbers.
 *
 * ## Absent is not zero
 *
 * Every field is nullable, and `null` means **not measured** — never "measured as none". The
 * distinction is load-bearing, not stylistic:
 *
 * - `trainsEntered = 0` is a finding: the dispatcher admitted nothing for the whole run.
 * - `trainsEntered = null` is the absence of a finding: nothing in this run was in a position to
 *   count admissions, because the example's main process keeps no such counter.
 *
 * Collapsing the two would reproduce exactly the failure mode
 * `docs/GOAL_10_SP2C24_SWEEP_REPORT.md` records under *"Structurally empty columns"*, where a
 * column of hardcoded zeroes was read for several rounds as a measured result. Serialising these
 * as JSON `null` keeps the two apart on disk as well as in memory.
 *
 * Absence is expressed **per field**, never by omitting the whole object, because the two sources
 * differ in availability: `MetricsCollectionService` is bound for every simulation context, while
 * `ShuntingLoop`'s counters exist only when a `ShuntingLoop` is the run's main process. A run may
 * therefore legitimately have the first pair of figures and not the rest.
 *
 * ## Where the values come from
 *
 * Sourced at run end by `DispatcherRunSummaries.railwayOutcomeFrom` (in `:desktop-ui`, the only
 * place holding the Koin scope with both the simulation context and the loop) and handed to
 * [DispatcherRunRecorder.recordRailwayOutcome] before [DispatcherRunRecorder.finish] freezes the
 * snapshot. Both accessors are plain reads on existing `:core` APIs.
 *
 * @property journeysCompleted Trains that released every block they held, i.e. finished their
 *   journey and left the network (`MetricsSnapshot.completedTrains`).
 * @property trainsEntered Trains admitted into the network (`ShuntingLoop.getTrainsEntered`).
 * @property trainsExited Trains that left through an exit point (`ShuntingLoop.getTrainsExited`).
 * @property maxConcurrentTrains Peak number of simultaneously admitted trains
 *   (`ShuntingLoop.getMaxConcurrentTrains`).
 * @property blockTransitions Total train movement events across all trains — every block-to-block
 *   step (sum over `ShuntingLoop.getAllBlockTransitions`). This is the figure that separated the
 *   rule-based arm (173 per 600 s run) from the LLM arm (2) in the #847 sweep.
 * @property conflicts Reservation conflicts detected since simulation start
 *   (`MetricsSnapshot.conflictCount`).
 * @property failedReservations Dispatcher reservation attempts that were refused
 *   (`ShuntingLoop.getFailedReservations`).
 *
 * @see DispatcherRunSnapshot.railwayOutcome
 * @since Issue #834 (SP2c.11 — record what the railway achieved, not just decision hygiene)
 */
@Serializable
data class RailwayOutcome(
	val journeysCompleted: Long? = null,
	val trainsEntered: Long? = null,
	val trainsExited: Long? = null,
	val maxConcurrentTrains: Long? = null,
	val blockTransitions: Long? = null,
	val conflicts: Long? = null,
	val failedReservations: Long? = null
) {
	companion object {
		/**
		 * The all-absent outcome: nothing about this run's railway was measured.
		 *
		 * This is the default for [DispatcherRunSnapshot.railwayOutcome], which is what keeps
		 * schema-version-1 run files readable — see that property's KDoc.
		 */
		val UNMEASURED: RailwayOutcome = RailwayOutcome()
	}
}
