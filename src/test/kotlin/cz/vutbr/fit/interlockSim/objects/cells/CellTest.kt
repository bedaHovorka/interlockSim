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
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.Test
import java.util.EnumMap

/**
 * This class checks cells attributes (mainly topology)
 */
@Suppress("UNCHECKED_CAST")
class CellTest {
	private val testedClasses: Array<Class<out Cell>> = arrayOf(RailSemaphore::class.java, InOut::class.java)

	/**
	 * Tests segment transformations and properties
	 */
	@Test
	fun testSegments() {
		val center = Point(0, 0)
		val points = EnumMap<Segment, Point>(Segment::class.java)

		for (s in Segment.values()) { // TODO parametrized test
			val dx = s.dx
			val dy = s.dy

			assertThat(Segment.segmentFor(dx, dy)).isSameInstanceAs(s)

			assertThat(Segment.anti(Segment.anti(s))).isSameInstanceAs(s)
			assertThat(Segment.conflict(s, s)).isTrue()

			val tr = s.transform(center)
			assertThat(tr).isNotSameInstanceAs(center) // Must not return same object
			assertThat(center == tr).withMessage("transformed point is equal").isFalse()
			assertThat(points.values.contains(tr)).withMessage("transformed point is generated twice").isFalse()
			points[s] = tr
		}
	}

	/**
	 * Tests cell direction properties
	 */
	@Test
	fun testDirection() {
		for (clazz in testedClasses) {
			testDir(clazz)
		}
	}

	private fun testDir(
		clazz: Class<out Cell>,
		vararg objects: Any
	) {
		for (t in SpatialType.values()) {
			try {
				val sem1 = newCell(clazz, true, t, objects)
				val sem2 = newCell(clazz, false, t, objects)

				assertThat(sem1.direction())
					.withMessage("direction for class ${clazz.simpleName} and $t")
					.isSameInstanceAs(Segment.anti(sem2.direction()))
			} catch (e: IllegalArgumentException) {
				// This try-catch is intentional - it handles valid cases where certain
				// switch types are unsupported. This is not an assertion test.
				val message = e.message
				if (message != null && message == RailSwitch.UNSUPORTED_SWITCH_TYPES_MESSAGE) {
					continue
				}
				throw e
			}
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
