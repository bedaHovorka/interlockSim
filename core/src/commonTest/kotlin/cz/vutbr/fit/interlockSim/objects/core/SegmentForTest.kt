/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for the segmentFor direction-to-segment mapping
 */
package cz.vutbr.fit.interlockSim.objects.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.Test

/**
 * Tests for [segmentFor]: the mapping from a displacement's sign pair to a [Cell.Segment].
 *
 * The mapping depends only on the *sign* of dx and dy, never on their magnitude,
 * because callers pass grid displacements that are sign-normalized upstream.
 */
class SegmentForTest {
	@Test
	fun `segmentFor maps all nine sign combinations`() {
		val expected =
			mapOf(
				-1 to -1 to Segment.B,
				-1 to 0 to Segment.A,
				-1 to 1 to Segment.D,
				0 to -1 to Segment.C,
				0 to 0 to null,
				0 to 1 to Segment.H,
				1 to -1 to Segment.E,
				1 to 0 to Segment.F,
				1 to 1 to Segment.G
			)
		for (dx in -1..1) {
			for (dy in -1..1) {
				val actual = segmentFor(dx, dy)
				assertThat(actual)
					.withMessage("segmentFor($dx, $dy) should be ${expected[dx to dy]}")
					.isEqualTo(expected[dx to dy])
			}
		}
	}

	@Test
	fun `segmentFor depends only on the sign of its inputs`() {
		val boundaryCases =
			mapOf(
				segmentFor(-3, 7) to segmentFor(-1, 1),
				segmentFor(5, -9) to segmentFor(1, -1),
				segmentFor(0, 12) to segmentFor(0, 1),
				segmentFor(-2, 0) to segmentFor(-1, 0)
			)
		for ((actual, expected) in boundaryCases) {
			assertThat(actual)
				.withMessage("Magnitudes beyond -1/0/1 must map like their sign")
				.isEqualTo(expected)
		}
	}

	// Deliberately kept alongside the jvmTest round-trip in CellTest: this commonTest
	// copy also exercises the mapping on linuxX64, where jvmTest never runs.
	@Test
	fun `segmentFor maps each segment's own direction back to itself`() {
		for (segment in Segment.entries) {
			assertThat(segmentFor(segment.dx, segment.dy))
				.withMessage("segmentFor must map segment $segment's direction back to itself")
				.isEqualTo(segment)
		}
	}
}
