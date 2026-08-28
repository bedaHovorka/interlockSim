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
 * One "what you can do now" entry for a single train–action pair (SP2c.2, #825).
 *
 * Affordances are pre-computed by the caller (control-loop harness or test fixture) from
 * the current [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation] and
 * passed into [RenderContext]. Renderers format them but do **not** evaluate them — evaluation
 * belongs to the caller, not to the render layer.
 *
 * **Special train ID:** `"no_op"` (constant [NO_OP_TRAIN_ID]) represents the global `no_op`
 * action which is always applicable and requires no train ID. Renderers emit it as a trailing
 * line `no_op: always applicable` regardless of [applicable].
 *
 * @property trainId Train identifier, or [NO_OP_TRAIN_ID] for the global no-op affordance.
 * @property action Action name, e.g. `"approve_train"`, `"request_route"`, `"cancel_route"`,
 *   `"no_op"`.
 * @property applicable `true` when the action can be taken right now; `false` when blocked or
 *   not relevant.
 * @property reason Human-readable explanation. Rendered after `applicable`/`not applicable` or
 *   `blocked` in the WHAT YOU CAN DO NOW section.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
data class Affordance(
	val trainId: String,
	val action: String,
	val applicable: Boolean,
	val reason: String
) {
	companion object {
		/** Sentinel [trainId] for the `no_op` affordance — always the last entry in the list. */
		const val NO_OP_TRAIN_ID = "no_op"

		/** Canonical no-op affordance — always applicable, always listed last. */
		val NO_OP =
			Affordance(
				trainId = NO_OP_TRAIN_ID,
				action = "no_op",
				applicable = true,
				reason = "always applicable"
			)
	}
}
