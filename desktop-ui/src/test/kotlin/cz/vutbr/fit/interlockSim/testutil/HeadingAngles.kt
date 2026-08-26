/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: heading angle formatting and comparison
 */
package cz.vutbr.fit.interlockSim.testutil

import kotlin.math.PI

/** [rad] rendered as whole degrees, for a failure message a human has to read. */
fun deg(rad: Double): String = "${Math.toDegrees(rad).toInt()}°"

/**
 * [difference] folded into `(-π, π]`.
 *
 * A heading-flip test asks "did the heading turn by more than a right angle between two samples?".
 * Raw subtraction answers that wrongly whenever the two headings sit on opposite sides of the
 * ±π wrap, so the difference has to be normalised first. Both heading-flip regression tests
 * carried this loop privately (Issue #955, cluster U5).
 */
fun normalizeAngleDiff(difference: Double): Double {
	var normalized = difference
	while (normalized > PI) {
		normalized -= 2.0 * PI
	}
	while (normalized < -PI) {
		normalized += 2.0 * PI
	}
	return normalized
}
