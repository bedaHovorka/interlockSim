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
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore.Signal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for DynamicRailSemaphore wrapper class
 *
 * Verifies:
 * - Dynamic state management (signal changes)
 * - Stable identity (equals/hashCode based on static object)
 * - Independence from static object state
 */
class DynamicRailSemaphoreTest {
	private lateinit var staticSemaphore1: RailSemaphore
	private lateinit var staticSemaphore2: RailSemaphore
	private lateinit var dynamicSemaphore1: DynamicRailSemaphore
	private lateinit var dynamicSemaphore2: DynamicRailSemaphore

	@BeforeEach
	fun setUp() {
		// Create static semaphores (these represent editing-time configuration)
		staticSemaphore1 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		staticSemaphore2 = RailSemaphore(false, Cell.SpatialType.VERTICAL)

		// Create dynamic wrappers
		dynamicSemaphore1 = DynamicRailSemaphore(staticSemaphore1)
		dynamicSemaphore2 = DynamicRailSemaphore(staticSemaphore2)
	}

	@Test
	fun `initial signal state is STOP`() {
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `can change signal state`() {
		// Initially STOP
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)

		// Change to S30
		dynamicSemaphore1.signal = Signal.S30
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.S30)

		// Change to FREE
		dynamicSemaphore1.signal = Signal.FREE
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.FREE)
	}

	@Test
	fun `signal state is independent for different dynamic wrappers`() {
		// Set different states
		dynamicSemaphore1.signal = Signal.S30
		dynamicSemaphore2.signal = Signal.S60

		// Verify independence
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.S30)
		assertThat(dynamicSemaphore2.signal).isEqualTo(Signal.S60)
	}

	@Test
	fun `signal property is mutable`() {
		// Access via property
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)

		// Change via setter
		dynamicSemaphore1.signal = Signal.FREE

		// Verify via property
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.FREE)
	}

	@Test
	fun `equals is based on static object identity`() {
		// Same static object -> equal
		val anotherWrapper = DynamicRailSemaphore(staticSemaphore1)
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)

		// Different static object -> not equal
		assertThat(dynamicSemaphore1).isNotEqualTo(dynamicSemaphore2)
	}

	@Test
	fun `hashCode is stable based on static object`() {
		// Hash code should be same for same static object
		val anotherWrapper = DynamicRailSemaphore(staticSemaphore1)
		assertThat(dynamicSemaphore1.hashCode()).isEqualTo(anotherWrapper.hashCode())

		// Hash code should differ for different static objects
		assertThat(dynamicSemaphore1.hashCode()).isNotEqualTo(dynamicSemaphore2.hashCode())
	}

	@Test
	fun `hashCode is stable across signal state changes`() {
		// Record initial hash
		val initialHash = dynamicSemaphore1.hashCode()

		// Change signal state multiple times
		dynamicSemaphore1.signal = Signal.S30
		assertThat(dynamicSemaphore1.hashCode()).isEqualTo(initialHash)

		dynamicSemaphore1.signal = Signal.S60
		assertThat(dynamicSemaphore1.hashCode()).isEqualTo(initialHash)

		dynamicSemaphore1.signal = Signal.FREE
		assertThat(dynamicSemaphore1.hashCode()).isEqualTo(initialHash)
	}

	@Test
	fun `equals is stable across signal state changes`() {
		val anotherWrapper = DynamicRailSemaphore(staticSemaphore1)

		// Initially equal
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)

		// Change one wrapper's state
		dynamicSemaphore1.signal = Signal.S60

		// Still equal (equality based on static object, not signal state)
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)

		// Change other wrapper's state differently
		anotherWrapper.signal = Signal.FREE

		// Still equal
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)
	}

	@Test
	fun `can use in hash-based collections`() {
		val set = mutableSetOf<DynamicRailSemaphore>()

		// Add first wrapper
		set.add(dynamicSemaphore1)
		assertThat(set).hasSize(1)

		// Add wrapper for same static object -> should not increase size
		val anotherWrapper1 = DynamicRailSemaphore(staticSemaphore1)
		set.add(anotherWrapper1)
		assertThat(set).hasSize(1)

		// Add wrapper for different static object -> should increase size
		set.add(dynamicSemaphore2)
		assertThat(set).hasSize(2)
	}

	@Test
	fun `toString includes signal state`() {
		dynamicSemaphore1.signal = Signal.S30
		val str = dynamicSemaphore1.toString()

		assertThat(str).contains("Dynamic")
		assertThat(str).contains("S30")
	}

	@Test
	fun `static object is accessible`() {
		assertThat(dynamicSemaphore1.static).isSameAs(staticSemaphore1)
		assertThat(dynamicSemaphore2.static).isSameAs(staticSemaphore2)
	}

	@Test
	fun `static properties are delegated correctly`() {
		// Verify delegation works - properties accessible directly
		assertThat(dynamicSemaphore1.orientation).isEqualTo(staticSemaphore1.getOrientation())
		assertThat(dynamicSemaphore1.spatialType).isEqualTo(staticSemaphore1.getSpatialType())
		assertThat(dynamicSemaphore1.name).isEqualTo(staticSemaphore1.getName())

		// Verify for second semaphore with different properties
		assertThat(dynamicSemaphore2.orientation).isEqualTo(staticSemaphore2.getOrientation())
		assertThat(dynamicSemaphore2.spatialType).isEqualTo(staticSemaphore2.getSpatialType())
	}
}
