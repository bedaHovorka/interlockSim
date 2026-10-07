/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

/**
 * Formats a dispatcher decision's rationale list for display: one bullet line per entry, or
 * "No rationale recorded." for an empty list.
 *
 * @since Issue #1123 — replaces the near-identical private copies in [Frame] (the "Why this route?"
 *   dialog) and [SemiAutoApprovalDialog], which differed only in their empty-list text
 */
internal fun formatRationale(rationale: List<String>): String =
	if (rationale.isEmpty()) {
		"No rationale recorded."
	} else {
		rationale.joinToString("\n") { "• $it" }
	}
