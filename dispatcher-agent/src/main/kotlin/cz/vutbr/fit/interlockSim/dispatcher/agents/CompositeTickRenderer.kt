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
 * Combines multiple [ObservationRenderer]s by concatenating their outputs in [parts] order
 * (SP2c.2, #825).
 *
 * Each part's [ObservationRenderer.render] is called in declaration order; the results are
 * concatenated with no separator (each part is responsible for its own trailing newline).
 * An empty [parts] list produces an empty string.
 *
 * ## Intended usage
 *
 * ```kotlin
 * val userMessageRenderer = CompositeTickRenderer(
 *     listOf(
 *         CompactTextRenderer(),   // sections 4–9 (canonical, always on)
 *     )
 * )
 * ```
 *
 * Or for custom assembly:
 * ```kotlin
 * CompositeTickRenderer(listOf(DeltaRenderer(), CompactTextRenderer()))
 * ```
 *
 * ## Determinism
 *
 * Determinism follows from the determinism of each constituent part.
 *
 * @param parts Ordered list of renderers whose outputs will be concatenated.
 *
 * @since Issue #825 (SP2c.2 — Goal 10 renderers)
 */
class CompositeTickRenderer(private val parts: List<ObservationRenderer>) : ObservationRenderer {
	override fun render(ctx: RenderContext): String {
		if (parts.isEmpty()) return ""
		val sb = StringBuilder()
		for (part in parts) {
			sb.append(part.render(ctx))
		}
		return sb.toString()
	}
}
