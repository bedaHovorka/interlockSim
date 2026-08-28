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
 * Renders an **ASCII schematic** of the station topology from a static [StationTopology]
 * (prompt section 3, SP2c.2 layout, #825).
 *
 * The schematic is topology-parameterised: it derives every route line directly from
 * [topology]'s [StationTopology.routes], never from hardcoded `vyhybna.xml` structure
 * (avoiding the SIM-004 mistake). For topologies where the schematic cannot be generated
 * (no routes, or more than [maxRepresentableInOuts] InOuts), the section body is exactly
 * `(schematic unavailable for this topology)` — a non-empty, explicit signal.
 *
 * ## Output format (representable topology)
 *
 * Each route is rendered as a directional arrow line:
 * ```
 * A --> B via kA, kB, kC
 * B --> A via kJ, kI, kH
 * ```
 *
 * ## Static section
 *
 * This section's content depends only on the [topology] constructor argument, **not** on
 * any per-tick [RenderContext] fields. The rendered string is therefore constant across ticks
 * — identical to calling `render(ctx)` with any two different [RenderContext]s — and may be
 * placed in the system prompt (set once at agent construction) for Ollama prefix caching.
 *
 * @param topology The static station topology, typically produced by
 *   [StationTopologySerializer.describe].
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
class SchematicRenderer(
	private val topology: StationTopology
) : ObservationRenderer {
	/**
	 * Maximum number of InOuts for which a schematic is generated. Topologies with more
	 * than this many entry/exit points exceed the simple arrow representation and fall back
	 * to `(schematic unavailable for this topology)`.
	 */
	private val maxRepresentableInOuts = 8

	/**
	 * Pre-rendered schematic string — computed once at construction since the topology is static.
	 */
	private val rendered: String = buildSchematic()

	override fun render(ctx: RenderContext): String = rendered

	private fun buildSchematic(): String {
		val sb = StringBuilder()
		sb.append("=== ASCII SCHEMATIC ===\n")
		if (topology.routes.isEmpty() || topology.inOuts.size > maxRepresentableInOuts) {
			sb.append("(schematic unavailable for this topology)\n")
			return sb.toString()
		}
		for (route in topology.routes) {
			sb.append(route.from)
			sb.append(" --> ")
			sb.append(route.to)
			if (route.blocks.isNotEmpty()) {
				sb.append(" via ")
				sb.append(route.blocks.joinToString(", ") { it.name })
			}
			sb.append('\n')
		}
		return sb.toString()
	}
}
