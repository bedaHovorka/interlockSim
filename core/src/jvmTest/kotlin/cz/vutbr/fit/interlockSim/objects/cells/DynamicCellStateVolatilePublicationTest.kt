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
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Pins safe cross-thread publication (Issue #1128) of the dynamic state that the simulation thread
 * writes and other threads (Swing EDT renderer, off-thread readers) read.
 *
 * Kotlin `@Volatile` compiles to the JVM `volatile` modifier on the backing field, so a reflection
 * check on the field is an exact pin.
 */
class DynamicCellStateVolatilePublicationTest {
	private fun assertVolatile(
		owner: Class<*>,
		field: String
	) {
		val declared = owner.getDeclaredField(field)
		assertThat(Modifier.isVolatile(declared.modifiers)).isTrue()
	}

	@Test
	fun `DynamicRailSwitch conf is volatile`() = assertVolatile(DynamicRailSwitch::class.java, "conf")

	@Test
	fun `DynamicRailSwitch locked is volatile`() = assertVolatile(DynamicRailSwitch::class.java, "locked")

	@Test
	fun `DynamicRailSemaphore signal is volatile`() = assertVolatile(DynamicRailSemaphore::class.java, "signal")

	@Test
	fun `DynamicInOut occupied is volatile`() = assertVolatile(DynamicInOut::class.java, "occupied")
}
