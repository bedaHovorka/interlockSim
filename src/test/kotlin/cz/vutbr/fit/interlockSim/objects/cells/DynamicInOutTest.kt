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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
		dynamicInSemaphore1 = DynamicRailSemaphore(staticInOut1.getInSemaphore())
		dynamicOutSemaphore1 = DynamicRailSemaphore(staticInOut1.getOutSemaphore())

		// Create dynamic semaphore wrappers for InOut2
		dynamicInSemaphore2 = DynamicRailSemaphore(staticInOut2.getInSemaphore())
		dynamicOutSemaphore2 = DynamicRailSemaphore(staticInOut2.getOutSemaphore())

		// Create dynamic InOut wrappers
		dynamicInOut1 = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)
		dynamicInOut2 = DynamicInOut(staticInOut2, dynamicInSemaphore2, dynamicOutSemaphore2)
	}

	@Test
	fun `can access dynamic input semaphore`() {
		assertThat(dynamicInOut1.getInSemaphore()).isSameAs(dynamicInSemaphore1)
	}

	@Test
	fun `can access dynamic output semaphore`() {
		assertThat(dynamicInOut1.getOutSemaphore()).isSameAs(dynamicOutSemaphore1)
	}

	@Test
	fun `static properties are delegated correctly`() {
		// Verify name delegation
		assertThat(dynamicInOut1.name).isEqualTo(staticInOut1.getName())
		assertThat(dynamicInOut1.name).isEqualTo("Station A")

		// Verify orientation delegation
		assertThat(dynamicInOut1.orientation).isEqualTo(staticInOut1.getOrientation())
		assertThat(dynamicInOut1.orientation).isTrue()

		// Verify spatialType delegation
		assertThat(dynamicInOut1.spatialType).isEqualTo(staticInOut1.getSpatialType())
		assertThat(dynamicInOut1.spatialType).isEqualTo(Cell.SpatialType.HORIZONTAL)

		// Verify for second InOut with different properties
		assertThat(dynamicInOut2.name).isEqualTo("Station B")
		assertThat(dynamicInOut2.orientation).isFalse()
		assertThat(dynamicInOut2.spatialType).isEqualTo(Cell.SpatialType.VERTICAL)
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
		dynamicInSemaphore1.signal = RailSemaphore.Signal.S60
		assertThat(dynamicInOut1.hashCode()).isEqualTo(initialHash)

		dynamicOutSemaphore1.signal = RailSemaphore.Signal.FREE
		assertThat(dynamicInOut1.hashCode()).isEqualTo(initialHash)
	}

	@Test
	fun `equals is stable across semaphore state changes`() {
		val anotherWrapper = DynamicInOut(staticInOut1, dynamicInSemaphore1, dynamicOutSemaphore1)

		// Initially equal
		assertThat(dynamicInOut1).isEqualTo(anotherWrapper)

		// Change one wrapper's semaphore states
		dynamicInSemaphore1.signal = RailSemaphore.Signal.S60

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
		assertThat(dynamicInOut1.static).isSameAs(staticInOut1)
		assertThat(dynamicInOut2.static).isSameAs(staticInOut2)
	}

	@Test
	fun `semaphore wrappers are independent`() {
		// Change state of InOut1's input semaphore
		dynamicInSemaphore1.signal = RailSemaphore.Signal.S60

		// Verify InOut2's semaphores are unaffected
		assertThat(dynamicInSemaphore2.signal).isEqualTo(RailSemaphore.Signal.STOP)

		// Verify we can access and modify InOut1's output semaphore independently
		dynamicOutSemaphore1.signal = RailSemaphore.Signal.FREE
		assertThat(dynamicOutSemaphore1.signal).isEqualTo(RailSemaphore.Signal.FREE)
		assertThat(dynamicInSemaphore1.signal).isEqualTo(RailSemaphore.Signal.S60)
	}

	@Test
	fun `different dynamic wrappers can share same semaphore instances`() {
		// This tests that different DynamicInOut instances can be created
		// with different semaphore wrapper instances
		val alternativeInSemaphore = DynamicRailSemaphore(staticInOut1.getInSemaphore())
		val alternativeOutSemaphore = DynamicRailSemaphore(staticInOut1.getOutSemaphore())
		val alternativeWrapper = DynamicInOut(staticInOut1, alternativeInSemaphore, alternativeOutSemaphore)

		// Should still be equal based on static object
		assertThat(alternativeWrapper).isEqualTo(dynamicInOut1)

		// But semaphore wrappers are different instances
		assertThat(alternativeWrapper.getInSemaphore()).isNotSameAs(dynamicInOut1.getInSemaphore())
	}
}
