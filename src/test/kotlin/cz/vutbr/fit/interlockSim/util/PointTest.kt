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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotSameAs
import cz.vutbr.fit.interlockSim.testutil.isEqualTo
import cz.vutbr.fit.interlockSim.testutil.isNotEqualTo
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Test class for Point
 */
class PointTest {
	@Test
	fun testDefaultConstructor() {
		val point = Point()
		assertThat(point::x).isEqualTo(0)
		assertThat(point::y).isEqualTo(0)
	}

	@Test
	fun testParameterizedConstructor() {
		val point = Point(10, 20)
		assertThat(point::x).isEqualTo(10)
		assertThat(point::y).isEqualTo(20)
	}

	@Test
	fun testNegativeCoordinates() {
		val point = Point(-5, -10)
		assertThat(point::x).isEqualTo(-5)
		assertThat(point::y).isEqualTo(-10)
	}

	@Test
	fun testEquals() {
		val point1 = Point(10, 20)
		val point2 = Point(10, 20)
		val point3 = Point(10, 21)
		val point4 = Point(11, 20)
		
		assertThat(point1).isEqualTo(point2)
		assertThat(point1).isNotEqualTo(point3)
		assertThat(point1).isNotEqualTo(point4)
	}

	@Test
	fun testHashCode() {
		val point1 = Point(10, 20)
		val point2 = Point(10, 20)
		val point3 = Point(10, 21)
		
		assertThat(point1.hashCode()).isEqualTo(point2.hashCode())
		assertThat(point1.hashCode()).isNotEqualTo(point3.hashCode())
	}

	@Test
	fun testToString() {
		val point = Point(10, 20)
		val str = point.toString()
		
		assertThat(str).contains("10")
		assertThat(str).contains("20")
		assertThat(str).contains("Point")
	}

	@Test
	fun testDistanceToSelf() {
		val point = Point(10, 20)
		val distance = point.distance(point)
		
		assertThat(distance).isEqualTo(0.0)
	}

	@Test
	fun testDistanceHorizontal() {
		val point1 = Point(0, 0)
		val point2 = Point(3, 0)
		val distance = point1.distance(point2)
		
		assertThat(distance).isEqualTo(3.0)
	}

	@Test
	fun testDistanceVertical() {
		val point1 = Point(0, 0)
		val point2 = Point(0, 4)
		val distance = point1.distance(point2)
		
		assertThat(distance).isEqualTo(4.0)
	}

	@Test
	fun testDistanceDiagonal() {
		val point1 = Point(0, 0)
		val point2 = Point(3, 4)
		val distance = point1.distance(point2)
		
		assertThat(distance).isEqualTo(5.0)
	}

	@Test
	fun testDistanceSymmetric() {
		val point1 = Point(10, 20)
		val point2 = Point(15, 25)
		
		val distance1 = point1.distance(point2)
		val distance2 = point2.distance(point1)
		
		assertThat(distance1).isEqualTo(distance2)
	}

	@Test
	fun testDistanceWithNegativeCoordinates() {
		val point1 = Point(-3, -4)
		val point2 = Point(0, 0)
		val distance = point1.distance(point2)
		
		assertThat(distance).isEqualTo(5.0)
	}

	@Test
	fun testDistanceTriangleInequality() {
		val point1 = Point(0, 0)
		val point2 = Point(3, 0)
		val point3 = Point(3, 4)
		
		val d12 = point1.distance(point2)
		val d23 = point2.distance(point3)
		val d13 = point1.distance(point3)
		
		// Triangle inequality: d13 <= d12 + d23
		assertThat(d13).isLessThanOrEqualTo(d12 + d23)
	}

	@Test
	fun testDistanceLargeCoordinates() {
		val point1 = Point(1000, 2000)
		val point2 = Point(1000, 2001)
		val distance = point1.distance(point2)
		
		assertThat(distance).isEqualTo(1.0)
	}

	@Test
	fun testImmutability() {
		val point = Point(10, 20)
		
		// Verify that x and y are val (immutable)
		// This is a compile-time check, but we verify the values remain unchanged
		assertThat(point::x).isEqualTo(10)
		assertThat(point::y).isEqualTo(20)
		
		// Create a new point to verify immutability pattern
		val point2 = Point(point.x + 5, point.y + 5)
		assertThat(point::x).isEqualTo(10) // Original unchanged
		assertThat(point::y).isEqualTo(20) // Original unchanged
		assertThat(point2::x).isEqualTo(15)
		assertThat(point2::y).isEqualTo(25)
	}

	@Test
	fun testCopy() {
		val original = Point(10, 20)
		val copy = original.copy()
		
		assertThat(copy).isEqualTo(original)
		assertThat(copy).isNotSameAs(original)
	}

	@Test
	fun testCopyWithModifiedX() {
		val original = Point(10, 20)
		val modified = original.copy(x = 15)
		
		assertThat(modified::x).isEqualTo(15)
		assertThat(modified::y).isEqualTo(20)
		assertThat(original::x).isEqualTo(10) // Original unchanged
	}

	@Test
	fun testCopyWithModifiedY() {
		val original = Point(10, 20)
		val modified = original.copy(y = 25)
		
		assertThat(modified::x).isEqualTo(10)
		assertThat(modified::y).isEqualTo(25)
		assertThat(original::y).isEqualTo(20) // Original unchanged
	}

	@Test
	fun testCopyWithBothModified() {
		val original = Point(10, 20)
		val modified = original.copy(x = 15, y = 25)
		
		assertThat(modified::x).isEqualTo(15)
		assertThat(modified::y).isEqualTo(25)
		assertThat(original::x).isEqualTo(10) // Original unchanged
		assertThat(original::y).isEqualTo(20) // Original unchanged
	}

	@Test
	fun testOriginPoint() {
		val origin = Point(0, 0)
		
		assertThat(origin::x).isEqualTo(0)
		assertThat(origin::y).isEqualTo(0)
	}

	@Test
	fun testDistanceFormula() {
		// Test the mathematical correctness of distance formula
		val point1 = Point(1, 2)
		val point2 = Point(4, 6)
		
		// Expected: sqrt((4-1)^2 + (6-2)^2) = sqrt(9 + 16) = sqrt(25) = 5
		val expected = sqrt(9.0 + 16.0)
		val actual = point1.distance(point2)
		
		assertThat(actual).isEqualTo(expected)
	}

	@Test
	fun testDataClassComponentAccess() {
		val point = Point(10, 20)
		val (x, y) = point
		
		assertThat(x).isEqualTo(10)
		assertThat(y).isEqualTo(20)
	}
}
