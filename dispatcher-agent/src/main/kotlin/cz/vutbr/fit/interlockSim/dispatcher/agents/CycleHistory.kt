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

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome

/**
 * Bounded, deterministically rendered history of the last N agent cycles, injected into the
 * per-cycle prompt on the **live** dispatcher path (SP2c.24, Issue #847).
 *
 * ## Why this is not [TickRingBuffer]
 *
 * SP2c.7 (#830) already built a bounded history — [TickRingBuffer] over
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.TickRecord]. It cannot be reused here, and the
 * reason is worth stating rather than rediscovering: that record is built from a
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation] (for its `digest`),
 * `AttributedAction`s carrying a `CommandId`, per-action `ValidationVerdict`s and correlated
 * `AppliedOutcome`s. All four are produced by
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop], which production never constructs — the
 * live path is [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] →
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter], and it sees a
 * [cz.vutbr.fit.interlockSim.sim.DispatchObservation] instead. Populating a [TickRecord] here
 * would mean either putting the SP2c.5 loop into production (a different task, #829/#833) or
 * filling half its fields with placeholders, which would make the history *look* richer than the
 * data behind it. This type records exactly what the live path actually knows.
 *
 * When [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop] does reach production, this class
 * should be replaced by [TickRingBuffer] rather than kept alongside it.
 *
 * ## Why a history at all (C5)
 *
 * #822 C5 asks for "a bounded ring buffer of the last N (snapshot digest, actions, outcome)
 * triples, **rendered deterministically from recorded state**, never accumulated as free-form
 * chat". Each Koog cycle is otherwise entirely stateless — the model cannot tell that it approved
 * a train two cycles ago — which is #822's RC1 in miniature. Everything rendered here is a pure
 * function of recorded values, so P8's "same recorded sequence ⇒ byte-identical prompt" holds.
 *
 * ## capacity == 0 disables it
 *
 * [TickRingBuffer] requires `capacity >= 1`. Zero is allowed here on purpose: `historyN = 0` is
 * the control arm of the #847 parameter grid, and it must render *nothing at all* rather than an
 * empty header, so the two arms differ by the presence of the block and not by a stray line.
 *
 * ## Threading
 *
 * Both the writer ([record], from `KoogAgentPlanAdapter.plan`) and the reader
 * ([renderPromptBlock], from `KoogDispatchAgentImpl.buildUserPrompt` inside the same `plan` call)
 * run on the `dispatcher-agent-driver` thread. The internal deque is nevertheless guarded, for the
 * same defensive-publication reason [SinkHolder] uses atomics: the planner is reachable from more
 * than one wrapper and the cost here is negligible.
 *
 * @param capacity Number of cycles retained; `0` disables the history block entirely.
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid)
 */
class CycleHistory(
	val capacity: Int = DispatcherRunConfig.DEFAULT_HISTORY_N
) {
	init {
		require(capacity >= 0) { "capacity must be >= 0, was $capacity" }
	}

	/**
	 * One recorded cycle.
	 *
	 * @property simTime Simulation time at which the cycle ran.
	 * @property outcome Planner-level classification of the cycle, as reported to
	 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerTickListener].
	 * @property actions Actions the agent emitted during the cycle, in emission order.
	 */
	data class Entry(
		val simTime: Double,
		val outcome: TickOutcome,
		val actions: List<DispatchAction>
	)

	private val entries: ArrayDeque<Entry> = ArrayDeque()

	/** Appends one cycle, dropping the oldest when [capacity] is reached. No-op when disabled. */
	fun record(
		simTime: Double,
		outcome: TickOutcome,
		actions: List<DispatchAction>
	) {
		if (capacity == 0) return
		synchronized(entries) {
			while (entries.size >= capacity) {
				entries.removeFirst()
			}
			entries.addLast(Entry(simTime, outcome, actions.toList()))
		}
	}

	/** Immutable snapshot in oldest-first order. */
	fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

	/** Drops every recorded cycle. */
	fun clear() {
		synchronized(entries) { entries.clear() }
	}

	/**
	 * Renders the history as a prompt block, or the empty string when disabled or still empty.
	 *
	 * The caller places this **before** the current snapshot, per #822 §5.4: the lost-in-the-middle
	 * result puts history in the middle of the message and the state the model must act on last,
	 * closest to generation.
	 */
	fun renderPromptBlock(): String {
		val recorded = snapshot()
		if (recorded.isEmpty()) return ""
		return buildString {
			appendLine("=== YOUR LAST ${recorded.size} CYCLE(S), OLDEST FIRST ===")
			recorded.forEach { entry ->
				appendLine("t=${entry.simTime} ${entry.outcome.name}: ${renderActions(entry.actions)}")
			}
			appendLine(
				"This is what you already did. Do not repeat an action that is still in effect; " +
					"a train you approved stays approved."
			)
		}
	}

	private fun renderActions(actions: List<DispatchAction>): String {
		if (actions.isEmpty()) return "no action emitted"
		return actions.joinToString("; ") { action ->
			when (action) {
				is DispatchAction.ApproveTrain -> "${action.kind}(${action.trainId})"
				is DispatchAction.RequestRoute ->
					"${action.kind}(${action.trainId}, ${action.fromEndpointName} -> ${action.toEndpointName})"

				is DispatchAction.CancelRoute -> "${action.kind}(${action.trainId})"
				DispatchAction.NoOp -> action.kind
			}
		}
	}
}
