/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test: DynamicInOut (Static/Dynamic Separation)
	Issue #79: Enhance test coverage for InOut with dynamic wrapper

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test coverage: 2026-02-05
*/

package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.coreTestModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Test suite for DynamicInOut wrapper (static/dynamic separation pattern).
 *
 * **Static/Dynamic Separation Pattern:**
 * - InOut (static) - Immutable configuration: name, position, entry/exit flag
 * - DynamicInOut (dynamic) - Mutable state: semaphore signals (via embedded semaphores)
 *
 * **Identity Contract:**
 * - equals() based on wrapped static InOut object (reference equality)
 * - hashCode() uses System.identityHashCode(static) for stable hash
 * - Same static InOut always returns same dynamic wrapper (IdentityHashMap in context)
 *
 * **Usage Pattern:**
 * ```kotlin
 * val staticInOut: InOut = editingContext.getInOutsList()[0]
 * val dynamicInOut: DynamicInOut = simulationContext.toDynamic(staticInOut) as DynamicInOut
 *
 * // Dynamic wrapper delegates configuration to static
 * assert(dynamicInOut.name == staticInOut.getName())
 *
 * // Dynamic wrapper manages simulation state (via semaphores)
 * dynamicInOut.inSemaphore.setUpPath(...)  // Mutable operation
 * ```
 *
 * @see DynamicInOut
 * @see cz.vutbr.fit.interlockSim.objects.cells.InOut
 * @see cz.vutbr.fit.interlockSim.context.SimulationContext
 */
class DynamicInOutTest {
	private lateinit var context: DefaultSimulationContext

	@BeforeEach
	fun setUp() {
		startKoin {
			modules(coreTestModule)
		}
		// Use simple linear path with 2 InOuts (Entry, Exit)
		context = TestTopologies.simpleLinearPathSimulation()
	}

	@AfterEach
	fun tearDown() {
		context.close()
		stopKoin()
	}

	/**
	 * Test: DynamicInOut wraps static InOut correctly.
	 *
	 * Expected: Dynamic wrapper delegates name and configuration to static.
	 */
	@Test
	fun `DynamicInOut wraps static InOut correctly`() {
		val staticInOuts = context.getInOutsList()
		assertThat(staticInOuts.size).isEqualTo(2)

		val staticInOut = staticInOuts[0]
		val dynamicInOut = context.toDynamic(staticInOut) as DynamicInOut

		// Dynamic delegates name to static
		assertThat(dynamicInOut.name).isEqualTo(staticInOut.getName())

		// Dynamic references static object
		assertThat(dynamicInOut.staticRef).isSameAs(staticInOut)
	}

	/**
	 * Test: DynamicInOut identity based on wrapped static object.
	 *
	 * Expected: Two dynamics wrapping same static are equal.
	 */
	@Test
	fun `DynamicInOut identity based on wrapped static object`() {
		val staticInOuts = context.getInOutsList()
		val staticInOut = staticInOuts[0]

		val dynamic1 = context.toDynamic(staticInOut) as DynamicInOut
		val dynamic2 = context.toDynamic(staticInOut) as DynamicInOut

		// Same static → same dynamic wrapper (IdentityHashMap)
		assertThat(dynamic1).isSameAs(dynamic2)

		// Equality based on wrapped static
		assertThat(dynamic1 == dynamic2).isTrue()

		// equals() also works with static InOut directly
		assertThat(dynamic1.equals(staticInOut)).isTrue()
	}

	/**
	 * Test: DynamicInOut hashCode stable (based on static identity).
	 *
	 * Expected: hashCode uses System.identityHashCode(static) for stability.
	 */
	@Test
	fun `DynamicInOut hashCode stable based on static identity`() {
		val staticInOuts = context.getInOutsList()
		val staticInOut = staticInOuts[0]

		val dynamicInOut = context.toDynamic(staticInOut) as DynamicInOut

		// Hash code is identity hash of static object
		val expectedHash = System.identityHashCode(staticInOut)
		assertThat(dynamicInOut.hashCode()).isEqualTo(expectedHash)
	}

	/**
	 * Test: Different static InOuts produce different dynamic wrappers.
	 *
	 * Expected: Dynamics wrapping different statics are NOT equal.
	 */
	@Test
	fun `Different static InOuts produce different dynamic wrappers`() {
		val staticInOuts = context.getInOutsList()
		assertThat(staticInOuts.size).isEqualTo(2)

		val static1 = staticInOuts[0]
		val static2 = staticInOuts[1]

		val dynamic1 = context.toDynamic(static1) as DynamicInOut
		val dynamic2 = context.toDynamic(static2) as DynamicInOut

		// Different statics → different dynamics
		assertThat(dynamic1 == dynamic2).isFalse()
		assertThat(dynamic1).isNotEqualTo(dynamic2)

		// Different hash codes
		assertThat(dynamic1.hashCode()).isNotEqualTo(dynamic2.hashCode())
	}

	/**
	 * Test: DynamicInOut provides access to dynamic semaphores.
	 *
	 * Expected: inSemaphore and outSemaphore are dynamic semaphore wrappers.
	 */
	@Test
	fun `DynamicInOut provides access to dynamic semaphores`() {
		val staticInOuts = context.getInOutsList()
		val staticInOut = staticInOuts[0]

		val dynamicInOut = context.toDynamic(staticInOut) as DynamicInOut

		// Dynamic provides semaphore wrappers (non-null)
		assertThat(dynamicInOut.inSemaphore).isNotEqualTo(null)
		assertThat(dynamicInOut.outSemaphore).isNotEqualTo(null)

		// Verify semaphores are dynamic wrappers (have class names)
		val inSemaphoreName = dynamicInOut.inSemaphore::class.java.simpleName
		val outSemaphoreName = dynamicInOut.outSemaphore::class.java.simpleName

		assertThat(inSemaphoreName).isNotEqualTo("")
		assertThat(outSemaphoreName).isNotEqualTo("")
	}

	/**
	 * Test: toDynamic() returns same wrapper for same static (IdentityHashMap).
	 *
	 * Expected: Multiple calls to toDynamic(static) return identical wrapper instance.
	 */
	@Test
	fun `toDynamic returns same wrapper for same static InOut`() {
		val staticInOuts = context.getInOutsList()
		val staticInOut = staticInOuts[0]

		val dynamic1 = context.toDynamic(staticInOut) as DynamicInOut
		val dynamic2 = context.toDynamic(staticInOut) as DynamicInOut
		val dynamic3 = context.toDynamic(staticInOut) as DynamicInOut

		// All three references point to same object
		assertThat(dynamic1).isSameAs(dynamic2)
		assertThat(dynamic2).isSameAs(dynamic3)
	}

	/**
	 * Test: DynamicInOut delegates spatial type to static.
	 *
	 * Expected: Spatial type configuration comes from static InOut.
	 */
	@Test
	fun `DynamicInOut delegates spatial type to static InOut`() {
		val staticInOuts = context.getInOutsList()
		val staticInOut = staticInOuts[0]

		val dynamicInOut = context.toDynamic(staticInOut) as DynamicInOut

		// spatialType delegates to static (both should be HORIZONTAL for linear path)
		val staticSpatialType = staticInOut.getSpatialType()
		val dynamicSpatialType = dynamicInOut.getSpatialType()

		assertThat(dynamicSpatialType).isEqualTo(staticSpatialType)
	}

	/**
	 * Test: DynamicInOut toString() provides useful debug output.
	 *
	 * Expected: toString includes "Dynamic" prefix and InOut name.
	 */
	@Test
	fun `DynamicInOut toString provides useful debug output`() {
		val staticInOuts = context.getInOutsList()
		val staticInOut = staticInOuts[0]

		val dynamicInOut = context.toDynamic(staticInOut) as DynamicInOut

		val debugString = dynamicInOut.toString()

		// Format: "Dynamic[name]"
		assertThat(debugString).isEqualTo("Dynamic[${staticInOut.getName()}]")
	}
}
