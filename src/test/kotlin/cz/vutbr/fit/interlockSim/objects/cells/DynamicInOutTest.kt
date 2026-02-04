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
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Factory functions for creating dynamic semaphores

/**
 * Tests for DynamicInOut wrapper class
 *
 * Verifies:
 * - Access to dynamic semaphores
 * - Stable identity (equals/hashCode based on static object)
 * - Delegation of static properties
 */
class DynamicInOutTest {
	private lateinit var staticInOut1: InOut
	private lateinit var staticInOut2: InOut
	private lateinit var dynamicInSemaphore1: DynamicRailSemaphore
	private lateinit var dynamicOutSemaphore1: DynamicRailSemaphore
	private lateinit var dynamicInSemaphore2: DynamicRailSemaphore
	private lateinit var dynamicOutSemaphore2: DynamicRailSemaphore
	private lateinit var dynamicInOut1: DynamicInOut
	private lateinit var dynamicInOut2: DynamicInOut

	@BeforeEach
	fun setUp() {
		// Create static InOut objects (these represent editing-time configuration)
		staticInOut1 = InOut("Station A", true, Cell.SpatialType.HORIZONTAL)
		staticInOut2 = InOut("Station B", false, Cell.SpatialType.VERTICAL)

		// Create dynamic semaphore wrappers for InOut1
		dynamicInSemaphore1 = createDynamicInstance(staticInOut1.getInSemaphore())
		dynamicOutSemaphore1 = createConstantInstance(staticInOut1.getOutSemaphore(), Signal.FREE)

		// Create dynamic semaphore wrappers for InOut2
		dynamicInSemaphore2 = createDynamicInstance(staticInOut2.getInSemaphore())
		dynamicOutSemaphore2 = createConstantInstance(staticInOut2.getOutSemaphore(), Signal.FREE)

		// Create dynamic InOut wrappers
		dynamicInOut1 = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
		dynamicInOut2 = DynamicInOut(staticInOut2, dynamicInSemaphore2, dynamicOutSemaphore2)
	}

	@Test
	fun `can access dynamic input semaphore`() {
		assertThat(dynamicInOut1.inSemaphore).isSameAs(dynamicInSemaphore1)
	}

	@Test
	fun `can access dynamic output semaphore`() {
		assertThat(dynamicInOut1.outSemaphore).isSameAs(dynamicOutSemaphore1)
	}

	@Test
	fun `static properties are delegated correctly`() {
		// Verify name delegation
		assertThat(dynamicInOut1.name).isEqualTo(staticInOut1.getName())
		assertThat(dynamicInOut1.name).isEqualTo("Station A")

		// Verify orientation delegation
		assertThat(dynamicInOut1.getOrientation()).isEqualTo(staticInOut1.getOrientation())
		assertThat(dynamicInOut1.getOrientation()).isTrue()

		// Verify spatialType delegation
		assertThat(dynamicInOut1.staticRef.getSpatialType()).isEqualTo(staticInOut1.getSpatialType())
		assertThat(dynamicInOut1.staticRef.getSpatialType()).isEqualTo(Cell.SpatialType.HORIZONTAL)

		// Verify for second InOut with different properties
		assertThat(dynamicInOut2.name).isEqualTo("Station B")
		assertThat(dynamicInOut2.getOrientation()).isFalse()
		assertThat(dynamicInOut2.staticRef.getSpatialType()).isEqualTo(Cell.SpatialType.VERTICAL)
	}

	@Test
	fun `equals is based on static object identity`() {
		// Same static object -> equal
		val anotherWrapper = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
		assertThat(dynamicInOut1).isEqualTo(anotherWrapper)

		// Different static object -> not equal
		assertThat(dynamicInOut1).isNotEqualTo(dynamicInOut2)
	}

	@Test
	fun `hashCode is stable based on static object`() {
		// Hash code should be same for same static object
		val anotherWrapper = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
		assertThat(dynamicInOut1.hashCode()).isEqualTo(anotherWrapper.hashCode())

		// Hash code should differ for different static objects
		assertThat(dynamicInOut1.hashCode()).isNotEqualTo(dynamicInOut2.hashCode())
	}

	@Test
	fun `hashCode is stable across semaphore state changes`() {
		// Record initial hash
		val initialHash = dynamicInOut1.hashCode()

		// Change semaphore states
		dynamicInSemaphore1.signal = Signal.S60
		assertThat(dynamicInOut1.hashCode()).isEqualTo(initialHash)

		dynamicOutSemaphore1.signal = Signal.FREE
		assertThat(dynamicInOut1.hashCode()).isEqualTo(initialHash)
	}

	@Test
	fun `equals is stable across semaphore state changes`() {
		val anotherWrapper = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)

		// Initially equal
		assertThat(dynamicInOut1).isEqualTo(anotherWrapper)

		// Change one wrapper's semaphore states
		dynamicInSemaphore1.signal = Signal.S60

		// Still equal (equality based on static object, not semaphore states)
		assertThat(dynamicInOut1).isEqualTo(anotherWrapper)
	}

	@Test
	fun `can use in hash-based collections`() {
		val set = mutableSetOf<DynamicInOut>()

		// Add first wrapper
		set.add(dynamicInOut1)
		assertThat(set).hasSize(1)

		// Add wrapper for same static object -> should not increase size
		val anotherWrapper1 = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
		set.add(anotherWrapper1)
		assertThat(set).hasSize(1)

		// Add wrapper for different static object -> should increase size
		set.add(dynamicInOut2)
		assertThat(set).hasSize(2)
	}

	@Test
	fun `toString includes name`() {
		val str = dynamicInOut1.toString()

		assertThat(str).contains("Dynamic")
		assertThat(str).contains("Station A")
	}

	@Test
	fun `static object is accessible`() {
		assertThat(dynamicInOut1.staticRef).isSameAs(staticInOut1)
		assertThat(dynamicInOut2.staticRef).isSameAs(staticInOut2)
	}

	@Test
	fun `semaphore wrappers are independent`() {
		// Change state of InOut1's input semaphore
		dynamicInSemaphore1.signal = Signal.S60

		// Verify InOut2's semaphores are unaffected
		assertThat(dynamicInSemaphore2.signal).isEqualTo(Signal.STOP)

		// Verify we can access and modify InOut1's output semaphore independently
		dynamicOutSemaphore1.signal = Signal.FREE
		assertThat(dynamicOutSemaphore1.signal).isEqualTo(Signal.FREE)
		assertThat(dynamicInSemaphore1.signal).isEqualTo(Signal.S60)
	}

	@Test
	fun `different dynamic wrappers can share same semaphore instances`() {
		// This tests that different DynamicInOut instances can be created
		// with different semaphore wrapper instances
		val alternativeInSemaphore = createDynamicInstance(staticInOut1.getInSemaphore())
		val alternativeOutSemaphore = createConstantInstance(staticInOut1.getOutSemaphore(), Signal.FREE)
		val alternativeWrapper = DynamicInOut(staticInOut1, alternativeInSemaphore, alternativeOutSemaphore)

		// Should still be equal based on static object
		assertThat(alternativeWrapper).isEqualTo(dynamicInOut1)

		// But semaphore wrappers are different instances
		assertThat(alternativeWrapper.inSemaphore).isNotSameAs(dynamicInOut1.inSemaphore)
	}

	@org.junit.jupiter.api.Nested
	@org.junit.jupiter.api.DisplayName("Equals contract tests")
	inner class EqualsContract {
		@Test
		fun `equals with null returns false`() {
			@Suppress("KotlinConstantConditions") // Testing null comparison explicitly
			assertThat(dynamicInOut1 == null).isFalse()
		}

		@Test
		fun `equals with self returns true`() {
			assertThat(dynamicInOut1).isEqualTo(dynamicInOut1)
			assertThat(dynamicInOut1.equals(dynamicInOut1)).isTrue()
		}

		@Test
		fun `equals with wrong type returns false`() {
			assertThat(dynamicInOut1.equals("string")).isFalse()
			assertThat(dynamicInOut1.equals(42)).isFalse()
			assertThat(dynamicInOut1.equals(listOf("A", "B"))).isFalse()
		}

		@Test
		fun `equals is reflexive`() {
			assertThat(dynamicInOut1).isEqualTo(dynamicInOut1)
		}

		@Test
		fun `equals is symmetric`() {
			val another = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
			assertThat(dynamicInOut1).isEqualTo(another)
			assertThat(another).isEqualTo(dynamicInOut1)
		}

		@Test
		fun `equals is transitive`() {
			val x = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
			val y = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
			val z = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)

			assertThat(x).isEqualTo(y)
			assertThat(y).isEqualTo(z)
			assertThat(x).isEqualTo(z) // Transitivity
		}

		@Test
		fun `equals with static InOut returns true when same reference`() {
			assertThat(dynamicInOut1.equals(staticInOut1)).isTrue()
		}

		@Test
		fun `equals with different static InOut returns false`() {
			assertThat(dynamicInOut1.equals(staticInOut2)).isFalse()
		}
	}
}
