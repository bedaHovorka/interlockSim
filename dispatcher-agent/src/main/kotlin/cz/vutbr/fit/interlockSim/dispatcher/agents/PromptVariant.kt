/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

/**
 * Which revision of the DISPATCHER system prompt [KoogAgentFactory] assembles (#834, SP2c.11).
 *
 * ## Why a selectable variant instead of an edit
 *
 * The prompt PR #896 landed works: it took journeys per run from 0 to 1-3 and removed the
 * iteration-exhaustion failure mode entirely. A revision of a working prompt cannot be *asserted*
 * to be better — the only honest way to decide is to run both against the same grid and compare
 * measured rates. This enum is that A/B seam: it is a sweep axis
 * ([cz.vutbr.fit.interlockSim.dispatcher.sweep.SweepAxes.promptVariant]), it is recorded in every
 * run JSON ([cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters.promptVariant]), and
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.RunReportAggregator] already splits report rows by
 * it, so two cells differing only in variant never merge into one measured number.
 *
 * Nothing in this codebase claims [REVISED] is an improvement. The sweep decides that.
 *
 * @since Issue #834 (SP2c.11 — prompt rebuild as a swept parameter)
 */
enum class PromptVariant {
	/**
	 * The prompt exactly as PR #896 shipped it, byte for byte.
	 *
	 * This is the control arm, and its stability is a hard contract: `KoogAgentFactoryTest`'s
	 * pinned-phrase assertions all run against this variant unchanged, so any edit to the baseline
	 * text fails a test rather than silently moving the measurement's zero point.
	 */
	BASELINE,

	/**
	 * The #834 revision: same properties, fewer tokens, plus an explicit empty-station idle path.
	 *
	 * Every difference from [BASELINE] is motivated by a recorded measurement — see
	 * [KoogAgentFactory.buildRevisedSystemPrompt]'s KDoc, which lists each change against the run
	 * data that asked for it. No property that was bought with a measured failure was dropped.
	 */
	REVISED;

	companion object {
		/**
		 * The variant used when nothing selects one.
		 *
		 * [BASELINE] on purpose: the default must reproduce the behaviour that already exists, so
		 * that adding this seam changes no run that does not ask to be changed. Flipping this
		 * constant is a measurement decision, not a refactoring one — it belongs in the same commit
		 * as the sweep evidence that justifies it.
		 */
		val DEFAULT: PromptVariant = BASELINE

		/**
		 * Parses a variant name, or returns `null` when [raw] names no known variant.
		 *
		 * Returns `null` rather than throwing or defaulting internally so the caller keeps ownership
		 * of the fallback *and its WARN* — [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig]
		 * routes this through the same `parseOrDefault` helper every other knob uses, which is what
		 * makes a malformed `-DpromptVariant=…` behave like every other malformed `-D`: logged, then
		 * ignored, never an aborted run.
		 *
		 * Matching is case-insensitive and ignores surrounding whitespace: the value travels through
		 * a shell command line and a `.properties` file, and `revised` failing where `REVISED`
		 * succeeds would cost an unattended sweep for no measurement reason.
		 */
		fun parse(raw: String?): PromptVariant? {
			val name = raw?.trim().orEmpty()
			if (name.isEmpty()) return null
			return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
		}
	}
}
