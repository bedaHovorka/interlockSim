/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import assertk.fail
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.objects.core.conflict
import cz.vutbr.fit.interlockSim.objects.core.segmentFor
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * This class checks cells attributes (mainly topology)
 */
@Suppress("UNCHECKED_CAST")
class CellTest {
	private val testedClasses: Array<Class<out Cell>> = arrayOf(RailSemaphore::class.java, InOut::class.java)

	/**
	 * Tests segment transformations and properties
	 */
	@ParameterizedTest
	@EnumSource(Segment::class)
	fun `segment transformations work correctly`(segment: Segment) {
		// Arrange
		val center = Point(0, 0)
		val dx = segment.dx
		val dy = segment.dy

		// Act & Assert - segmentFor reverses to original
		assertThat(segmentFor(dx, dy))
			.withMessage("segmentFor($dx, $dy) should return $segment")
			.isSameInstanceAs(segment)

		// Act & Assert - double anti() is identity
		assertThat(anti(anti(segment)))
			.withMessage("anti(anti($segment)) should be identity")
			.isSameInstanceAs(segment)

		// Act & Assert - segment conflicts with itself
		assertThat(conflict(segment, segment))
			.withMessage("$segment should conflict with itself")
			.isTrue()

		// Act - transform point
		val transformed = segment.transform(center)

		// Assert - transformation creates new point
		assertThat(transformed)
			.withMessage("transform should create new Point instance")
			.isNotSameInstanceAs(center)

		assertThat(center == transformed)
			.withMessage("transformed point should not equal original")
			.isFalse()
	}

	/**
	 * Tests cell direction properties for all cell types and spatial types
	 */
	@ParameterizedTest
	@EnumSource(SpatialType::class)
	fun `cell direction is opposite for opposite orientations`(spatialType: SpatialType) {
		for (clazz in testedClasses) {
			testDirForCellType(clazz, spatialType)
		}
	}

	private fun testDirForCellType(
		clazz: Class<out Cell>,
		spatialType: SpatialType,
		vararg objects: Any
	) {
		try {
			val cell1 = newCell(clazz, true, spatialType, objects)
			val cell2 = newCell(clazz, false, spatialType, objects)

			assertThat(cell1.direction())
				.withMessage("direction for class ${clazz.simpleName} and $spatialType")
				.isSameInstanceAs(anti(cell2.direction()))
		} catch (e: IllegalArgumentException) {
			// This try-catch is intentional - it handles valid cases where certain
			// switch types are unsupported. This is not an assertion test.
			val message = e.message
			if (message != null && message == UNSUPORTED_SWITCH_TYPES_MESSAGE) {
				return
			}
			throw e
		}
	}

	private fun newCell(
		clazz: Class<out Cell>,
		o: Boolean,
		t: SpatialType,
		vararg objects: Any
	): OrientedNodeCell =
		when {
			clazz == RailSemaphore::class.java -> RailSemaphore(o, t) as OrientedNodeCell
			clazz == InOut::class.java -> InOut("xx", o, t) as OrientedNodeCell
			else -> {
				fail("Unexpected cell class: $clazz, objects: ${objects.contentToString()}")
			}
		}
}
