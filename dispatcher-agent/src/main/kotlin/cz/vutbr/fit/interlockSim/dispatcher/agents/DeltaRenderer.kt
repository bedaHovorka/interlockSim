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

import cz.vutbr.fit.interlockSim.dispatcher.observation.BlockView
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.SignalView
import cz.vutbr.fit.interlockSim.dispatcher.observation.SwitchView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility

/**
 * Renders **only** the `=== CHANGED SINCE LAST TICK ===` section (prompt section 7,
 * SP2c.2 layout, #825).
 *
 * Compares [RenderContext.observation] against [RenderContext.previous] field-by-field and
 * lists only those trains, blocks, switches, and signals whose state changed. When
 * [RenderContext.previous] is `null` (tick 0), the section body is exactly `(first tick)`.
 *
 * ## Token savings
 *
 * On a typical `vyhybna.xml` tick where only one train moves, this section produces a handful
 * of lines instead of repeating the full state, cutting ~200–400 tokens compared to repeating
 * the full CURRENT STATE.
 *
 * ## Determinism
 *
 * All output is built with explicit fixed-precision formatting (`"%.1f".format(d)`) — never
 * `Double.toString` — satisfying AC7 (#825). Rendering the same [RenderContext] any number of
 * times produces byte-identical output.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
class DeltaRenderer : ObservationRenderer {
	override fun render(ctx: RenderContext): String {
		val sb = StringBuilder()
		sb.append("=== CHANGED SINCE LAST TICK ===\n")
		val prev = ctx.previous
		if (prev == null) {
			sb.append("(first tick)\n")
		} else {
			renderDelta(sb, ctx.observation, prev)
		}
		return sb.toString()
	}

	private fun renderDelta(
		sb: StringBuilder,
		curr: DispatcherObservation,
		prev: DispatcherObservation
	) {
		val trainDeltas = trainDeltas(curr.trains, prev.trains)
		val blockDeltas = blockDeltas(curr.blocks, prev.blocks)
		val switchDeltas = switchDeltas(curr.switches, prev.switches)
		val signalDeltas = signalDeltas(curr.signals, prev.signals)

		if (trainDeltas.isEmpty() && blockDeltas.isEmpty() && switchDeltas.isEmpty() && signalDeltas.isEmpty()) {
			sb.append("(no changes)\n")
			return
		}

		if (trainDeltas.isNotEmpty()) {
			sb.append("trains:")
			for (delta in trainDeltas) sb.append(" $delta")
			sb.append('\n')
		}
		if (blockDeltas.isNotEmpty()) {
			sb.append("blocks:")
			for (delta in blockDeltas) sb.append(" $delta")
			sb.append('\n')
		}
		if (switchDeltas.isNotEmpty()) {
			sb.append("switches:")
			for (delta in switchDeltas) sb.append(" $delta")
			sb.append('\n')
		}
		if (signalDeltas.isNotEmpty()) {
			sb.append("signals:")
			for (delta in signalDeltas) sb.append(" $delta")
			sb.append('\n')
		}
	}

	private fun trainDeltas(
		curr: List<TrainView>,
		prev: List<TrainView>
	): List<String> {
		val prevById = prev.associateBy { it.trainId }
		val result = mutableListOf<String>()
		for (t in curr) {
			val p = prevById[t.trainId]
			if (p == null) {
				// new train this tick
				result.add("${t.trainId}(new/${t.phase})")
			} else if (t.phase != p.phase) {
				result.add("${t.trainId} ${t.phase}(was ${p.phase})")
			}
		}
		// trains that disappeared
		val currIds = curr.map { it.trainId }.toSet()
		for (p in prev) {
			if (p.trainId !in currIds) {
				result.add("${p.trainId}(exited)")
			}
		}
		return result
	}

	private fun blockDeltas(
		curr: List<BlockView>,
		prev: List<BlockView>
	): List<String> {
		val prevById = prev.associateBy { it.blockId }
		val result = mutableListOf<String>()
		for (b in curr) {
			val p = prevById[b.blockId] ?: continue
			if (b.state != p.state || b.occupantTrainId != p.occupantTrainId) {
				val from = blockStateLabel(p)
				val to = blockStateLabel(b)
				result.add("${b.blockId} $from->$to")
			}
		}
		return result
	}

	private fun blockStateLabel(bv: BlockView): String =
		when (bv.state) {
			TrackFacility.State.FREE -> "FREE"
			TrackFacility.State.RESERVED -> "RESERVED(${bv.occupantTrainId ?: "?"})"
			TrackFacility.State.OCCUPIED -> "OCCUPIED(${bv.occupantTrainId ?: "?"})"
		}

	private fun switchDeltas(
		curr: List<SwitchView>,
		prev: List<SwitchView>
	): List<String> {
		val prevByName = prev.associateBy { it.switchName }
		val result = mutableListOf<String>()
		for (s in curr) {
			val p = prevByName[s.switchName] ?: continue
			if (s.position != p.position || s.lockedByTrainId != p.lockedByTrainId) {
				val from = switchLabel(p)
				val to = switchLabel(s)
				result.add("${s.switchName} $from->$to")
			}
		}
		return result
	}

	private fun switchLabel(sv: SwitchView): String {
		val lock = if (sv.lockedByTrainId != null) "(locked by ${sv.lockedByTrainId})" else "(free)"
		return "${sv.position}$lock"
	}

	private fun signalDeltas(
		curr: List<SignalView>,
		prev: List<SignalView>
	): List<String> {
		val prevByName = prev.associateBy { it.name }
		val result = mutableListOf<String>()
		for (s in curr) {
			val p = prevByName[s.name] ?: continue
			if (s.aspect != p.aspect) {
				result.add("${s.name} ${p.aspect}->${s.aspect}")
			}
		}
		return result
	}
}
