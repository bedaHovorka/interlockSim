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
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

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
		dynamicSemaphore1 = createDynamicInstance(staticSemaphore1)
		dynamicSemaphore2 = createDynamicInstance(staticSemaphore2)
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
		val anotherWrapper = createDynamicInstance(staticSemaphore1)
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)

		// Different static object -> not equal
		assertThat(dynamicSemaphore1).isNotEqualTo(dynamicSemaphore2)
	}

	@Test
	fun `hashCode is stable based on static object`() {
		// Hash code should be same for same static object
		val anotherWrapper = createDynamicInstance(staticSemaphore1)
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
		val anotherWrapper = createDynamicInstance(staticSemaphore1)

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
		val anotherWrapper1 = createDynamicInstance(staticSemaphore1)
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
		assertThat(dynamicSemaphore1.staticRef).isSameInstanceAs(staticSemaphore1)
		assertThat(dynamicSemaphore2.staticRef).isSameInstanceAs(staticSemaphore2)
	}

	@Test
	fun `static properties are delegated correctly`() {
		// Verify delegation works - properties accessible directly
		assertThat(dynamicSemaphore1.getOrientation()).isEqualTo(staticSemaphore1.getOrientation())
		assertThat(dynamicSemaphore1.getSpatialType()).isEqualTo(staticSemaphore1.getSpatialType())
		assertThat(dynamicSemaphore1.name).isEqualTo(staticSemaphore1.getName())

		// Verify for second semaphore with different properties
		assertThat(dynamicSemaphore2.getOrientation()).isEqualTo(staticSemaphore2.getOrientation())
		assertThat(dynamicSemaphore2.getSpatialType()).isEqualTo(staticSemaphore2.getSpatialType())
	}

	// ========== PropertyChangeSupport Tests ==========

	@Test
	fun `signal change fires property change event`() {
		// Given: listener is registered
		val capturedEvents = mutableListOf<PropertyChangeEvent>()
		val testListener = PropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)

		// When: signal changes
		dynamicSemaphore1.signal = Signal.S30

		// Then: one event should be fired
		assertThat(capturedEvents).hasSize(1)

		// Verify "signal" event
		val signalEvent = capturedEvents[0]
		assertThat(signalEvent.propertyName).isEqualTo("signal")
		assertThat(signalEvent.oldValue).isEqualTo(Signal.STOP)
		assertThat(signalEvent.newValue).isEqualTo(Signal.S30)
		assertThat(signalEvent.source).isSameAs(dynamicSemaphore1)
	}

	@Test
	fun `multiple signal changes fire multiple events`() {
		// Given: listener is registered
		val capturedEvents = mutableListOf<PropertyChangeEvent>()
		val testListener = PropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)

		// When: signal changes multiple times
		dynamicSemaphore1.signal = Signal.S30
		dynamicSemaphore1.signal = Signal.S60
		dynamicSemaphore1.signal = Signal.FREE

		// Then: three events should be fired
		assertThat(capturedEvents).hasSize(3)

		// Verify first change: STOP -> S30
		assertThat(capturedEvents[0].oldValue).isEqualTo(Signal.STOP)
		assertThat(capturedEvents[0].newValue).isEqualTo(Signal.S30)

		// Verify second change: S30 -> S60
		assertThat(capturedEvents[1].oldValue).isEqualTo(Signal.S30)
		assertThat(capturedEvents[1].newValue).isEqualTo(Signal.S60)

		// Verify third change: S60 -> FREE
		assertThat(capturedEvents[2].oldValue).isEqualTo(Signal.S60)
		assertThat(capturedEvents[2].newValue).isEqualTo(Signal.FREE)
	}

	@Test
	fun `no event fired when signal set to same value`() {
		// Given: signal is S30
		dynamicSemaphore1.signal = Signal.S30

		// And: listener is registered
		val capturedEvents = mutableListOf<PropertyChangeEvent>()
		val testListener = PropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)

		// When: signal is set to same value
		dynamicSemaphore1.signal = Signal.S30

		// Then: no event should be fired
		assertThat(capturedEvents).isEmpty()
	}

	@Test
	fun `removePropertyChangeListener stops receiving events`() {
		// Given: listener is registered and receives events
		val capturedEvents = mutableListOf<PropertyChangeEvent>()
		val testListener = PropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents).hasSize(1)

		// When: listener is removed
		capturedEvents.clear()
		dynamicSemaphore1.removePropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S60

		// Then: no events should be received
		assertThat(capturedEvents).isEmpty()
	}

	@Test
	fun `multiple listeners all receive events`() {
		// Given: two listeners
		val capturedEvents1 = mutableListOf<PropertyChangeEvent>()
		val capturedEvents2 = mutableListOf<PropertyChangeEvent>()
		val testListener1 = PropertyChangeListener { evt -> capturedEvents1.add(evt) }
		val testListener2 = PropertyChangeListener { evt -> capturedEvents2.add(evt) }

		dynamicSemaphore1.addPropertyChangeListener(testListener1)
		dynamicSemaphore1.addPropertyChangeListener(testListener2)

		// When: signal changes
		dynamicSemaphore1.signal = Signal.S30

		// Then: both listeners receive events
		assertThat(capturedEvents1).hasSize(1)
		assertThat(capturedEvents2).hasSize(1)
		assertThat(capturedEvents1[0].oldValue).isEqualTo(capturedEvents2[0].oldValue)
		assertThat(capturedEvents1[0].newValue).isEqualTo(capturedEvents2[0].newValue)
	}

	@Test
	fun `listener can be added and removed multiple times`() {
		val capturedEvents = mutableListOf<PropertyChangeEvent>()
		val testListener = PropertyChangeListener { evt -> capturedEvents.add(evt) }

		// Add listener
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents).hasSize(1)

		// Remove listener
		capturedEvents.clear()
		dynamicSemaphore1.removePropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S60
		assertThat(capturedEvents).isEmpty()

		// Re-add listener
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.FREE
		assertThat(capturedEvents).hasSize(1)
	}

	@Test
	fun `property change events are independent for different semaphores`() {
		// Given: listeners on both semaphores
		val capturedEvents1 = mutableListOf<PropertyChangeEvent>()
		val capturedEvents2 = mutableListOf<PropertyChangeEvent>()
		val testListener1 = PropertyChangeListener { evt -> capturedEvents1.add(evt) }
		val testListener2 = PropertyChangeListener { evt -> capturedEvents2.add(evt) }

		dynamicSemaphore1.addPropertyChangeListener(testListener1)
		dynamicSemaphore2.addPropertyChangeListener(testListener2)

		// When: only semaphore1 changes
		dynamicSemaphore1.signal = Signal.S30

		// Then: only listener1 receives event
		assertThat(capturedEvents1).hasSize(1)
		assertThat(capturedEvents2).isEmpty()
	}

	@Test
	fun `constant semaphore does not fire events`() {
		// Given: constant semaphore (signal cannot change)
		val constantSemaphore = createConstantInstance(staticSemaphore1, Signal.FREE)

		// And: listener is registered
		val capturedEvents = mutableListOf<PropertyChangeEvent>()
		val testListener = PropertyChangeListener { evt -> capturedEvents.add(evt) }
		constantSemaphore.addPropertyChangeListener(testListener)

		// When: attempt to change signal (should be ignored)
		constantSemaphore.signal = Signal.STOP

		// Then: signal remains FREE and no event is fired
		assertThat(constantSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(capturedEvents).isEmpty()
	}

	@org.junit.jupiter.api.Nested
	@org.junit.jupiter.api.DisplayName("Equals contract tests")
	inner class EqualsContract {
		@Test
		fun `equals with null returns false`() {
			@Suppress("KotlinConstantConditions") // Testing null comparison explicitly
			assertThat(dynamicSemaphore1 == null).isFalse()
		}

		@Test
		fun `equals with self returns true`() {
			assertThat(dynamicSemaphore1).isEqualTo(dynamicSemaphore1)
			assertThat(dynamicSemaphore1.equals(dynamicSemaphore1)).isTrue()
		}

		@Test
		fun `equals with wrong type returns false`() {
			assertThat(dynamicSemaphore1.equals("string")).isFalse()
			assertThat(dynamicSemaphore1.equals(42)).isFalse()
			assertThat(dynamicSemaphore1.equals(listOf("A", "B"))).isFalse()
		}

		@Test
		fun `equals is reflexive`() {
			assertThat(dynamicSemaphore1).isEqualTo(dynamicSemaphore1)
		}

		@Test
		fun `equals is symmetric`() {
			val another = createDynamicInstance(staticSemaphore1)
			assertThat(dynamicSemaphore1).isEqualTo(another)
			assertThat(another).isEqualTo(dynamicSemaphore1)
		}

		@Test
		fun `equals is transitive`() {
			val x = createDynamicInstance(staticSemaphore1)
			val y = createDynamicInstance(staticSemaphore1)
			val z = createDynamicInstance(staticSemaphore1)

			assertThat(x).isEqualTo(y)
			assertThat(y).isEqualTo(z)
			assertThat(x).isEqualTo(z) // Transitivity
		}

		@Test
		fun `equals with static RailSemaphore returns true when same reference`() {
			assertThat(dynamicSemaphore1.equals(staticSemaphore1)).isTrue()
		}

		@Test
		fun `equals with different static RailSemaphore returns false`() {
			assertThat(dynamicSemaphore1.equals(staticSemaphore2)).isFalse()
		}
	}
}
