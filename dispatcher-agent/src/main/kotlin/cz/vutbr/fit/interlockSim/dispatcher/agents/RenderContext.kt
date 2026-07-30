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

import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord

/**
 * All inputs a renderer needs to build one USER-message tick (SP2c.2, #825).
 *
 * Carries the current [observation], the previous tick's observation ([previous] is `null` on
 * tick 0), a bounded ring-buffer history of recent [TickRecord]s, the cross-tick
 * [workingMemory], and the pre-evaluated list of [affordances].
 *
 * ## Determinism
 *
 * Every field is either an immutable data class or a [List] of immutable data classes.
 * Rendering the same [RenderContext] any number of times **must** always produce byte-identical
 * output — this is tested by the 100× determinism test (SP2c.2 acceptance criterion AC3).
 * Renderers must therefore use only deterministic formatting (e.g. `"%.1f".format(d)`) and
 * must not call `Double.toString` or locale-sensitive number formatters (see AC7, #825).
 *
 * @property observation The current (this-tick) full snapshot.
 * @property previous The previous tick's full snapshot; `null` when this is tick 0.
 * @property history Ring-buffer of recent [TickRecord]s (typically N=3); most-recent last.
 * @property workingMemory Cross-tick scratchpad with 4 deterministic fields.
 * @property affordances Pre-evaluated per-train-and-action entries for the WHAT YOU CAN DO NOW
 *   section. The canonical `no_op` affordance ([Affordance.NO_OP]) should be included last.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
data class RenderContext(
	val observation: DispatcherObservation,
	val previous: DispatcherObservation?,
	val history: List<TickRecord>,
	val workingMemory: WorkingMemory,
	val affordances: List<Affordance>
)

/**
 * Contract for every tick-prompt renderer (SP2c.2, #825).
 *
 * A renderer converts a [RenderContext] into a [String] section of the USER message.
 * Implementations **must** be stateless and pure: calling [render] with the same [RenderContext]
 * any number of times must return byte-identical results (AC3 — determinism).
 *
 * This is a SAM / functional interface so a no-arg renderer can be expressed as a lambda or
 * method reference for testing.
 *
 * ## Forbidden patterns
 *
 * - `Double.toString()` — use `"%.1f".format(d)` or `"%d".format(d.toLong())` instead (AC7).
 * - Locale-sensitive formatting — [java.util.Locale.ROOT] must be the effective locale if
 *   `String.format` is used.
 * - Numbered option lines (`^\s*\d+[.)]\s`) — violates constraint C9 (#825, no-menu rule).
 * - The words `option`, `choose one`, `select` in the rendered output — also violates C9.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
fun interface ObservationRenderer {
	/**
	 * Renders [ctx] into a prompt string.
	 *
	 * @param ctx All inputs for this tick.
	 * @return A non-null string; may be empty for renderers that have nothing to contribute on a
	 *   given tick (but every mandatory section must always be present to preserve the
	 *   layout-invariant).
	 */
	fun render(ctx: RenderContext): String
}
