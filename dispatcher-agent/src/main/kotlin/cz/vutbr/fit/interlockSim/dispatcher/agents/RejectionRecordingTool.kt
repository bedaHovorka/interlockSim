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

import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode

/**
 * [DomainTool] decorator that reports every coded in-turn rejection to [onRejection].
 *
 * ## Why the tool boundary (Issue #847 round 4)
 *
 * It is the only place a live `shuntingLoopAI` run rejects anything. The LLM calls the four
 * actuator tools directly and their emissions reach `ActuatorCommandQueue` through `SinkHolder`;
 * `ActionValidator`, the component that would otherwise produce [RejectionCode]s, is reached only
 * from `DispatchTickLoop`, which is never constructed in production. Consequently
 * `DispatcherRunSnapshot.rejectionsByCode` — the field #846's aggregator renders its "Failure
 * Modes" table from — was structurally always empty, and rounds 2 and 3 had to produce their
 * rejected-call figures by grepping the log.
 *
 * ## Invisible to the model
 *
 * The [ToolResult] is passed through **unchanged**. The error message is the channel that teaches
 * the model which argument to send next — round 3 established empirically, in
 * `KoogRealOllamaToolCallingTest`, that a token appearing only in a rejection's text does come back
 * in a later call — so rewriting or suppressing it here would undo the round-2 and round-3 work.
 *
 * ## Only coded errors count
 *
 * A [ToolResult.Error] with `rejection == null` is a port failure or an unavailable sensor, not an
 * argument the model got wrong. Counting it would inflate the very rate this exists to measure.
 *
 * @param delegate The real tool. Its [name], [description] and [parameters] are exposed unchanged,
 *   so the LLM-facing tool schema — and `ActuatorToolSurface.assertExactly`'s four-tool count — are
 *   unaffected.
 * @param onRejection Called with the tool's name and the rejection code, on the agent driver
 *   thread, synchronously inside [execute]. Must be cheap: in production it is a counter increment
 *   on `DispatcherRunRecorder`.
 *
 * ## Why it lives in `agents` and not `agents.tools`
 *
 * `ActuatorToolSurfaceTest` scans the `agents.tools` package and asserts it contains **exactly**
 * the four actuator [DomainTool] classes — the literal SP2c.6 (#829) acceptance criterion. A
 * decorator is not an actuator tool, and placing it there would have quietly widened that surface
 * definition. The guard caught it; the class moved rather than the guard.
 *
 * @since Issue #847 round 4 (PR #891)
 */
class RejectionRecordingTool(
	private val delegate: DomainTool,
	private val onRejection: (toolName: String, code: RejectionCode) -> Unit
) : DomainTool {
	override val name: String get() = delegate.name

	override val description: String get() = delegate.description

	override val parameters: List<DomainToolParameter> get() = delegate.parameters

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val result = delegate.execute(args)
		(result as? ToolResult.Error)?.rejection?.let { code -> onRejection(delegate.name, code) }
		return result
	}
}
