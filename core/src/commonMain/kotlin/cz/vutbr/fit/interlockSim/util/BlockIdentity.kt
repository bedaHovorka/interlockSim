/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Derives stable, human-readable identifiers for [DynamicTrackBlock]s.
 *
 * A block's identifier is used to refer to it in perception readings
 * ([cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]) and in the static
 * topology loaded into the dispatcher agent's context (SP2b.8, #695). Both consumers
 * must agree on the same id so the agent can cross-reference the once-loaded topology
 * with per-turn dynamic-state queries; this shared helper guarantees that agreement.
 *
 * @since Issue #695 (SP2b.8 — Goal 10); extracted from
 *   [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort] to be reused by the
 *   dispatcher-agent topology serializer.
 */
object BlockIdentity {
	/**
	 * Derives a stable, human-readable identifier for a [DynamicTrackBlock].
	 *
	 * Prefers the block's explicit name (set in XML or by
	 * [cz.vutbr.fit.interlockSim.sim.ShuntingLoop]). Falls back to a `"sep1-sep2"` label
	 * built from the sorted names of the block's two endpoint separators. Returns
	 * `"unknown"` only when no name is available from any source.
	 */
	fun stableBlockId(block: DynamicTrackBlock): String {
		val explicit = block.name
		if (!explicit.isNullOrBlank()) return explicit
		val endNames =
			runCatching { block.ends() }
				.getOrNull()
				?.mapNotNull { end ->
					(DynamicWrapperUtils.unwrapToStatic(end) as? NodeCell)
						?.getName()
						?.takeIf { it.isNotBlank() }
				}?.sorted()
				.orEmpty()
		return if (endNames.isNotEmpty()) endNames.joinToString("-") else "unknown"
	}
}
