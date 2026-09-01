/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: static identity and naming for path separators
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils

/**
 * [a] and [b] are the same **static** separator, whatever dynamic wrappers each is seen
 * through.
 *
 * Two references to one separator are not always `==` — the simulation hands out dynamic
 * wrappers, and a test holding a static cell must compare through
 * [DynamicWrapperUtils.unwrapToStatic] to see the identity. The #788 boundary tests
 * (core `TrainFrontBoundaryStateTest`, desktop `HeadingFlipSampler`) each carried this
 * one-liner privately (Issue #955 pattern, extracted with the #1015 dedup pass).
 */
fun sameStatic(
	a: PathSeparator?,
	b: PathSeparator?
): Boolean = a != null && b != null && DynamicWrapperUtils.unwrapToStatic(a) === DynamicWrapperUtils.unwrapToStatic(b)

/**
 * [separator] as a short name for a diagnostic message: `"null"` when absent, the static
 * [NodeCell] name otherwise, `"?"` when the cell has no name.
 *
 * Heading-flip and boundary-state tests print the separators they flag, and a bare
 * `toString()` of a dynamic wrapper is unreadable in a failure message.
 */
fun separatorLabel(separator: PathSeparator?): String =
	(DynamicWrapperUtils.unwrapToStatic(separator ?: return "null") as? NodeCell)?.getName() ?: "?"
