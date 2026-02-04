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

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Unit tests for [PointF].
 *
 * Tests floating-point coordinate handling and conversion methods.
 */
class PointFTest {

	@Test
	fun testConstruction() {
		val point = PointF(1.5f, 2.7f)
		assertThat(point.x).isEqualTo(1.5f)
		assertThat(point.y).isEqualTo(2.7f)
	}

	@Test
	fun testToPoint_rounding() {
		// Test rounding to nearest integer (Kotlin uses round half to even)
		val point1 = PointF(1.4f, 2.6f)
		val intPoint1 = point1.toPoint()
		assertThat(intPoint1.x).isEqualTo(1) // 1.4 rounds to 1
		assertThat(intPoint1.y).isEqualTo(3) // 2.6 rounds to 3

		val point2 = PointF(1.5f, 2.5f)
		val intPoint2 = point2.toPoint()
		assertThat(intPoint2.x).isEqualTo(2) // 1.5 rounds to 2 (even)
		assertThat(intPoint2.y).isEqualTo(3) // 2.5 rounds to 3 (Kotlin roundToInt uses Math.round which rounds up at 0.5)
	}

	@Test
	fun testToPointFloor_truncation() {
		// Test flooring (truncation toward zero)
		val point = PointF(1.9f, 2.1f)
		val intPoint = point.toPointFloor()
		assertThat(intPoint.x).isEqualTo(1) // 1.9 floors to 1
		assertThat(intPoint.y).isEqualTo(2) // 2.1 floors to 2
	}

	@Test
	fun testToPointFloor_negative() {
		// Test flooring with negative coordinates
		val point = PointF(-1.5f, -2.9f)
		val intPoint = point.toPointFloor()
		assertThat(intPoint.x).isEqualTo(-1) // -1.5 truncates to -1
		assertThat(intPoint.y).isEqualTo(-2) // -2.9 truncates to -2
	}

	@Test
	fun testEquality() {
		val point1 = PointF(1.5f, 2.5f)
		val point2 = PointF(1.5f, 2.5f)
		val point3 = PointF(1.5f, 2.6f)

		assertThat(point1).isEqualTo(point2)
		assertThat(point1).isNotEqualTo(point3)
	}

	@Test
	fun testDataClassCopy() {
		val point = PointF(1.5f, 2.5f)
		val copy = point.copy(x = 3.5f)

		assertThat(copy.x).isEqualTo(3.5f)
		assertThat(copy.y).isEqualTo(2.5f)
		assertThat(point.x).isEqualTo(1.5f) // Original unchanged
	}

	@Test
	fun testHashCode() {
		val point1 = PointF(1.5f, 2.5f)
		val point2 = PointF(1.5f, 2.5f)
		val point3 = PointF(1.5f, 2.6f)

		assertThat(point1.hashCode()).isEqualTo(point2.hashCode())
		assertThat(point1.hashCode()).isNotEqualTo(point3.hashCode())
	}

	@Test
	fun testToString() {
		val point = PointF(1.5f, 2.5f)
		val string = point.toString()

		// Data class toString includes property names and values
		assertThat(string).isEqualTo("PointF(x=1.5, y=2.5)")
	}

	@Test
	fun testSubPixelPrecision() {
		// Verify that fractional coordinates are preserved
		val point = PointF(22.3f, 8.7f)

		assertThat(point.x).isEqualTo(22.3f)
		assertThat(point.y).isEqualTo(8.7f)

		// Should NOT be rounded to integers
		assertThat(point.x).isNotEqualTo(22.0f)
		assertThat(point.y).isNotEqualTo(9.0f)
	}

	@Test
	fun testZeroCoordinates() {
		val point = PointF(0.0f, 0.0f)
		assertThat(point.x).isEqualTo(0.0f)
		assertThat(point.y).isEqualTo(0.0f)

		val intPoint = point.toPoint()
		assertThat(intPoint.x).isEqualTo(0)
		assertThat(intPoint.y).isEqualTo(0)
	}

	@Test
	fun testNegativeCoordinates() {
		val point = PointF(-1.5f, -2.5f)
		assertThat(point.x).isEqualTo(-1.5f)
		assertThat(point.y).isEqualTo(-2.5f)

		val intPoint = point.toPoint()
		assertThat(intPoint.x).isEqualTo(-1) // -1.5 rounds to -1 (Math.round behavior)
		assertThat(intPoint.y).isEqualTo(-2) // -2.5 rounds to -2 (Math.round behavior)
	}

	@Test
	fun testLargeCoordinates() {
		val point = PointF(9999.99f, 10000.01f)
		assertThat(point.x).isEqualTo(9999.99f)
		assertThat(point.y).isEqualTo(10000.01f)

		val intPoint = point.toPoint()
		assertThat(intPoint.x).isEqualTo(10000)
		assertThat(intPoint.y).isEqualTo(10000)
	}

	// ========== Distance Calculation Tests (Issue #289) ==========

	@Test
	fun testDistanceTo_zeroDistance() {
		// Distance from point to itself should be zero
		val point = PointF(5.0f, 10.0f)
		val distance = point.distanceTo(point)
		assertThat(distance).isCloseTo(0.0f, 0.0001f)
	}

	@Test
	fun testDistanceTo_horizontal() {
		// Distance along X-axis only
		val point1 = PointF(0.0f, 5.0f)
		val point2 = PointF(3.0f, 5.0f)
		val distance = point1.distanceTo(point2)
		assertThat(distance).isCloseTo(3.0f, 0.0001f)
	}

	@Test
	fun testDistanceTo_vertical() {
		// Distance along Y-axis only
		val point1 = PointF(5.0f, 0.0f)
		val point2 = PointF(5.0f, 4.0f)
		val distance = point1.distanceTo(point2)
		assertThat(distance).isCloseTo(4.0f, 0.0001f)
	}

	@Test
	fun testDistanceTo_diagonal() {
		// 3-4-5 right triangle
		val point1 = PointF(0.0f, 0.0f)
		val point2 = PointF(3.0f, 4.0f)
		val distance = point1.distanceTo(point2)
		assertThat(distance).isCloseTo(5.0f, 0.0001f)
	}

	@Test
	fun testDistanceTo_symmetric() {
		// Distance should be symmetric: d(A,B) = d(B,A)
		val point1 = PointF(1.0f, 2.0f)
		val point2 = PointF(4.0f, 6.0f)
		val distance1 = point1.distanceTo(point2)
		val distance2 = point2.distanceTo(point1)
		assertThat(distance1).isCloseTo(distance2, 0.0001f)
	}

	@Test
	fun testDistanceTo_fractional() {
		// Test with fractional coordinates (common in animation)
		val point1 = PointF(1.5f, 2.3f)
		val point2 = PointF(4.2f, 5.7f)
		val expectedDistance = sqrt((4.2f - 1.5f) * (4.2f - 1.5f) + (5.7f - 2.3f) * (5.7f - 2.3f))
		val distance = point1.distanceTo(point2)
		assertThat(distance).isCloseTo(expectedDistance, 0.0001f)
	}

	@Test
	fun testDistanceTo_thresholdComparison() {
		// Simulate continuity check threshold (1.5 grid cells)
		val point1 = PointF(10.0f, 5.0f)

		// Small movement (0.3 cells) - should be below threshold
		val point2 = PointF(10.3f, 5.0f)
		val smallDistance = point1.distanceTo(point2)
		assertThat(smallDistance).isCloseTo(0.3f, 0.0001f)

		// Diagonal movement (√2 ≈ 1.41 cells) - should be below threshold
		val point3 = PointF(11.0f, 6.0f)
		val diagonalDistance = point1.distanceTo(point3)
		assertThat(diagonalDistance).isCloseTo(sqrt(2.0f), 0.0001f)

		// Large jump (4.0 cells) - should exceed threshold
		val point4 = PointF(14.0f, 5.0f)
		val largeDistance = point1.distanceTo(point4)
		assertThat(largeDistance).isCloseTo(4.0f, 0.0001f)
	}

	@Test
	fun testDistanceTo_negativeCoordinates() {
		// Test with negative coordinates
		val point1 = PointF(-2.0f, -3.0f)
		val point2 = PointF(1.0f, 1.0f)
		val expectedDistance = sqrt((1.0f - (-2.0f)) * (1.0f - (-2.0f)) + (1.0f - (-3.0f)) * (1.0f - (-3.0f)))
		val distance = point1.distanceTo(point2)
		assertThat(distance).isCloseTo(expectedDistance, 0.0001f)
		assertThat(distance).isCloseTo(5.0f, 0.0001f) // 3-4-5 triangle
	}
}
