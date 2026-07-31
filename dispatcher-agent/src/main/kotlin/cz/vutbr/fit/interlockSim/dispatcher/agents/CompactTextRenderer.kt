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
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/**
 * Canonical USER-message renderer — always on (SP2c.2, #825).
 *
 * Renders all six user-message prompt sections (4–9) in the SP2c.2 layout order:
 *
 * ```
 * 4. RECENT TICKS (ring buffer, N≤3)
 * 5. WORKING MEMORY (4 deterministic fields)
 * 6. APPLIED OUTCOMES (results of prior actions)
 * 7. CHANGED SINCE LAST TICK (delta) — delegated to [DeltaRenderer]
 * 8. CURRENT STATE (full snapshot)
 * 9. WHAT YOU CAN DO NOW (affordance-annotated facts)
 * ```
 *
 * ## Determinism (AC3 / AC7)
 *
 * All doubles are formatted with `"%.1f".format(d)` (never `Double.toString`). Rendering the
 * same [RenderContext] any number of times produces byte-identical output.
 *
 * ## No-menu invariant (C9)
 *
 * The rendered text contains no numbered-option lines (`^\s*\d+[.)]\s`) and no tokens
 * `option`, `choose one`, or `select`.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
class CompactTextRenderer : ObservationRenderer {
	private val deltaRenderer = DeltaRenderer()

	override fun render(ctx: RenderContext): String {
		val sb = StringBuilder()
		renderRecentTicks(sb, ctx)
		renderWorkingMemory(sb, ctx.workingMemory)
		renderAppliedOutcomes(sb, ctx.observation)
		sb.append(deltaRenderer.render(ctx))
		renderCurrentState(sb, ctx.observation)
		renderAffordances(sb, ctx.affordances)
		return sb.toString()
	}

	// ── Section 4: RECENT TICKS ────────────────────────────────────────────────────────────

	private fun renderRecentTicks(
		sb: StringBuilder,
		ctx: RenderContext
	) {
		sb.append("=== RECENT TICKS ===\n")
		if (ctx.history.isEmpty()) {
			sb.append("(none yet)\n")
		} else {
			for (record in ctx.history.takeLast(3)) {
				sb.append("  tick simTime=")
				sb.append("%.1f".format(record.simTime))
				sb.append(" outcome=")
				sb.append(record.outcome.name)
				if (record.timeoutNoOpCause != null) {
					sb.append(" cause=")
					sb.append(record.timeoutNoOpCause.name)
				}
				sb.append('\n')
			}
		}
	}

	// ── Section 5: WORKING MEMORY ─────────────────────────────────────────────────────────

	private fun renderWorkingMemory(
		sb: StringBuilder,
		wm: WorkingMemory
	) {
		sb.append("=== WORKING MEMORY ===\n")
		sb.append("consecutive_no_op_ticks: ")
		sb.append(wm.consecutiveNoOpTicks)
		sb.append('\n')
		sb.append("longest_queued_wait_secs: ")
		sb.append("%.1f".format(wm.longestQueuedWaitSecs))
		sb.append('\n')
		sb.append("blocked_train_count: ")
		sb.append(wm.blockedTrainCount)
		sb.append('\n')
		sb.append("last_tick_outcome: ")
		sb.append(wm.lastTickOutcome ?: "none")
		sb.append('\n')
	}

	// ── Section 6: APPLIED OUTCOMES ───────────────────────────────────────────────────────

	private fun renderAppliedOutcomes(
		sb: StringBuilder,
		observation: DispatcherObservation
	) {
		sb.append("=== APPLIED OUTCOMES ===\n")
		if (observation.appliedOutcomes.isEmpty()) {
			sb.append("(none)\n")
		} else {
			for (outcome in observation.appliedOutcomes) {
				sb.append("  tick=")
				sb.append(outcome.tick)
				sb.append(" train=")
				sb.append(outcome.trainId)
				sb.append(": ")
				sb.append(outcome.description)
				sb.append('\n')
			}
		}
	}

	// ── Section 8: CURRENT STATE ──────────────────────────────────────────────────────────

	private fun renderCurrentState(
		sb: StringBuilder,
		obs: DispatcherObservation
	) {
		sb.append("=== CURRENT STATE (tick ")
		sb.append(obs.tick)
		sb.append(", simTime ")
		sb.append("%.1f".format(obs.simTime))
		sb.append(") ===\n")

		sb.append("active ")
		sb.append(obs.activeCount)
		sb.append('/')
		sb.append(obs.capacity)
		sb.append(" ; queued ")
		sb.append(obs.queued.size)
		sb.append('\n')

		renderTrains(sb, obs)
		renderBlocks(sb, obs)
		renderSwitches(sb, obs)
		renderSignals(sb, obs)
		renderReservations(sb, obs)
	}

	private fun renderTrains(
		sb: StringBuilder,
		obs: DispatcherObservation
	) {
		if (obs.trains.isEmpty()) return
		sb.append("trains:\n")
		for (train in obs.trains) {
			sb.append("  ")
			renderTrain(sb, train)
			sb.append('\n')
		}
	}

	private fun renderTrain(
		sb: StringBuilder,
		t: TrainView
	) {
		val phase = t.phase.name
		sb.append(t.trainId)
		sb.append(' ')
		sb.append(phase)
		when (t.phase) {
			TrainPhase.RUNNING, TrainPhase.HELD, TrainPhase.DWELLING -> {
				if (t.frontSectionName != null) {
					sb.append(" at ")
					sb.append(t.frontSectionName)
				}
				sb.append(" -> ")
				sb.append(t.destinationInOutName)
				sb.append(" | v=")
				sb.append("%.1f".format(t.velocityMps))
				sb.append(" a=")
				sb.append("%.1f".format(t.accelerationMps2))
				if (t.signalAheadName != null) {
					sb.append(" | ahead ")
					sb.append(t.signalAheadName)
					sb.append('=')
					sb.append(signalDisplayName(t.signalAheadAspect))
					sb.append(' ')
					sb.append("%.0f".format(t.distanceToSignalAheadMetres))
					sb.append('m')
				}
				sb.append(" | wait ")
				sb.append("%.0f".format(t.waitSeconds))
				sb.append('s')
			}
			TrainPhase.QUEUED -> {
				sb.append(" -> ")
				sb.append(t.destinationInOutName)
				if (t.waitingSinceSimTime != null) {
					sb.append(" | queued since ")
					sb.append("%.1f".format(t.waitingSinceSimTime))
				}
				sb.append(" | wait ")
				sb.append("%.1f".format(t.waitSeconds))
				sb.append('s')
			}
			TrainPhase.EXITED -> {
				// Just show the destination — minimal info for an exited train
				sb.append(" -> ")
				sb.append(t.destinationInOutName)
			}
		}
	}

	private fun signalDisplayName(signal: Signal?): String =
		when (signal) {
			null -> "?"
			Signal.FREE -> "GO"
			else -> signal.name
		}

	private fun renderBlocks(
		sb: StringBuilder,
		obs: DispatcherObservation
	) {
		if (obs.blocks.isEmpty()) return
		sb.append("blocks:")
		val lineWidth = 80
		var lineLen = 7 // "blocks:".length
		for (block in obs.blocks) {
			val entry = blockEntry(block.blockId, block.state, block.occupantTrainId)
			if (lineLen + 1 + entry.length > lineWidth && lineLen > 7) {
				sb.append('\n')
				sb.append("       ") // align with "blocks:"
				lineLen = 7
			}
			sb.append(' ')
			sb.append(entry)
			lineLen += 1 + entry.length
		}
		sb.append('\n')
	}

	private fun blockEntry(
		blockId: String,
		state: TrackFacility.State,
		occupantId: String?
	): String =
		when (state) {
			TrackFacility.State.FREE -> "$blockId=FREE"
			TrackFacility.State.RESERVED -> "$blockId=RESERVED(${occupantId ?: "?"})"
			TrackFacility.State.OCCUPIED -> "$blockId=${occupantId ?: "?"}"
		}

	private fun renderSwitches(
		sb: StringBuilder,
		obs: DispatcherObservation
	) {
		if (obs.switches.isEmpty()) return
		sb.append("switches:")
		for (sw in obs.switches) {
			sb.append(' ')
			sb.append(sw.switchName)
			sb.append('=')
			sb.append(sw.position.name)
			val lock = if (sw.lockedByTrainId != null) "(locked by ${sw.lockedByTrainId})" else "(free)"
			sb.append(lock)
		}
		sb.append('\n')
	}

	private fun renderSignals(
		sb: StringBuilder,
		obs: DispatcherObservation
	) {
		if (obs.signals.isEmpty()) return
		sb.append("signals:")
		for (sig in obs.signals) {
			sb.append(' ')
			sb.append(sig.name)
			sb.append('=')
			sb.append(signalDisplayName(sig.aspect))
		}
		sb.append('\n')
	}

	private fun renderReservations(
		sb: StringBuilder,
		obs: DispatcherObservation
	) {
		if (obs.reservations.isEmpty()) return
		sb.append("reservations:")
		for (res in obs.reservations) {
			sb.append(' ')
			sb.append(res.trainId)
			sb.append(" -> ")
			sb.append(res.targetName)
			sb.append(" via ")
			sb.append(res.blockIds.joinToString(","))
		}
		sb.append('\n')
	}

	// ── Section 9: WHAT YOU CAN DO NOW ────────────────────────────────────────────────────

	private fun renderAffordances(
		sb: StringBuilder,
		affordances: List<Affordance>
	) {
		sb.append("=== WHAT YOU CAN DO NOW ===\n")
		if (affordances.isEmpty()) {
			sb.append("no_op: always applicable\n")
			return
		}

		// Group by trainId, preserve insertion order; no_op is always last
		val grouped = linkedMapOf<String, MutableList<Affordance>>()
		for (aff in affordances) {
			if (aff.trainId != Affordance.NO_OP_TRAIN_ID) {
				grouped.getOrPut(aff.trainId) { mutableListOf() }.add(aff)
			}
		}

		// Per-train blocks
		for ((trainId, entries) in grouped) {
			for ((index, aff) in entries.withIndex()) {
				if (index == 0) {
					sb.append(trainId)
					sb.append(": ")
				} else {
					sb.append(" ".repeat(trainId.length + 2))
				}
				sb.append(aff.action)
				sb.append("  ")
				sb.append(if (aff.applicable) "applicable" else "not applicable")
				if (aff.reason.isNotBlank()) {
					sb.append(" - ")
					sb.append(aff.reason)
				}
				sb.append('\n')
			}
		}

		// no_op is always the last line
		sb.append("no_op: always applicable\n")
	}
}
