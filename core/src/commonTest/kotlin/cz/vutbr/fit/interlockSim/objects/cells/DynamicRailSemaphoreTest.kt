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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for DynamicRailSemaphore wrapper class
 */
class DynamicRailSemaphoreTest {
	private lateinit var staticSemaphore1: RailSemaphore
	private lateinit var staticSemaphore2: RailSemaphore
	private lateinit var dynamicSemaphore1: DynamicRailSemaphore
	private lateinit var dynamicSemaphore2: DynamicRailSemaphore

	@BeforeTest
	fun setUp() {
		staticSemaphore1 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		staticSemaphore2 = RailSemaphore(false, Cell.SpatialType.VERTICAL)
		dynamicSemaphore1 = createDynamicInstance(staticSemaphore1)
		dynamicSemaphore2 = createDynamicInstance(staticSemaphore2)
	}

	@Test
	fun `initial signal state is STOP`() {
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)
	}

	@Test
	fun `can change signal state`() {
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.S30)
		dynamicSemaphore1.signal = Signal.FREE
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.FREE)
	}

	@Test
	fun `signal state is independent for different dynamic wrappers`() {
		dynamicSemaphore1.signal = Signal.S30
		dynamicSemaphore2.signal = Signal.S60
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.S30)
		assertThat(dynamicSemaphore2.signal).isEqualTo(Signal.S60)
	}

	@Test
	fun `signal property is mutable`() {
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)
		dynamicSemaphore1.signal = Signal.FREE
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.FREE)
	}

	@Test
	fun `equals is based on static object identity`() {
		val anotherWrapper = createDynamicInstance(staticSemaphore1)
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)
		assertThat(dynamicSemaphore1).isNotEqualTo(dynamicSemaphore2)
	}

	@Test
	fun `hashCode is stable based on static object`() {
		val anotherWrapper = createDynamicInstance(staticSemaphore1)
		assertThat(dynamicSemaphore1.hashCode()).isEqualTo(anotherWrapper.hashCode())
		assertThat(dynamicSemaphore1.hashCode()).isNotEqualTo(dynamicSemaphore2.hashCode())
	}

	@Test
	fun `hashCode is stable across signal state changes`() {
		val initialHash = dynamicSemaphore1.hashCode()
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
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)
		dynamicSemaphore1.signal = Signal.S60
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)
		anotherWrapper.signal = Signal.FREE
		assertThat(dynamicSemaphore1).isEqualTo(anotherWrapper)
	}

	@Test
	fun `can use in hash-based collections`() {
		val set = mutableSetOf<DynamicRailSemaphore>()
		set.add(dynamicSemaphore1)
		assertThat(set).hasSize(1)
		val anotherWrapper1 = createDynamicInstance(staticSemaphore1)
		set.add(anotherWrapper1)
		assertThat(set).hasSize(1)
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
		assertThat(dynamicSemaphore1.getOrientation()).isEqualTo(staticSemaphore1.getOrientation())
		assertThat(dynamicSemaphore1.getSpatialType()).isEqualTo(staticSemaphore1.getSpatialType())
		assertThat(dynamicSemaphore1.name).isEqualTo(staticSemaphore1.getName())
		assertThat(dynamicSemaphore2.getOrientation()).isEqualTo(staticSemaphore2.getOrientation())
		assertThat(dynamicSemaphore2.getSpatialType()).isEqualTo(staticSemaphore2.getSpatialType())
	}

	// ========== PropertyChangeSupport Tests ==========

	@Test
	fun `signal change fires property change event`() {
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		val testListener = ContextPropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents).hasSize(1)
		val signalEvent = capturedEvents[0]
		assertThat(signalEvent.propertyName).isEqualTo("signal")
		assertThat(signalEvent.oldValue).isEqualTo(Signal.STOP)
		assertThat(signalEvent.newValue).isEqualTo(Signal.S30)
	}

	@Test
	fun `multiple signal changes fire multiple events`() {
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		val testListener = ContextPropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		dynamicSemaphore1.signal = Signal.S60
		dynamicSemaphore1.signal = Signal.FREE
		assertThat(capturedEvents).hasSize(3)
		assertThat(capturedEvents[0].oldValue).isEqualTo(Signal.STOP)
		assertThat(capturedEvents[0].newValue).isEqualTo(Signal.S30)
		assertThat(capturedEvents[1].oldValue).isEqualTo(Signal.S30)
		assertThat(capturedEvents[1].newValue).isEqualTo(Signal.S60)
		assertThat(capturedEvents[2].oldValue).isEqualTo(Signal.S60)
		assertThat(capturedEvents[2].newValue).isEqualTo(Signal.FREE)
	}

	@Test
	fun `no event fired when signal set to same value`() {
		dynamicSemaphore1.signal = Signal.S30
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		val testListener = ContextPropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents).isEmpty()
	}

	@Test
	fun `removePropertyChangeListener stops receiving events`() {
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		val testListener = ContextPropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents).hasSize(1)
		capturedEvents.clear()
		dynamicSemaphore1.removePropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S60
		assertThat(capturedEvents).isEmpty()
	}

	@Test
	fun `multiple listeners all receive events`() {
		val capturedEvents1 = mutableListOf<ContextChangeEvent>()
		val capturedEvents2 = mutableListOf<ContextChangeEvent>()
		val testListener1 = ContextPropertyChangeListener { evt -> capturedEvents1.add(evt) }
		val testListener2 = ContextPropertyChangeListener { evt -> capturedEvents2.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener1)
		dynamicSemaphore1.addPropertyChangeListener(testListener2)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents1).hasSize(1)
		assertThat(capturedEvents2).hasSize(1)
		assertThat(capturedEvents1[0].oldValue).isEqualTo(capturedEvents2[0].oldValue)
		assertThat(capturedEvents1[0].newValue).isEqualTo(capturedEvents2[0].newValue)
	}

	@Test
	fun `listener can be added and removed multiple times`() {
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		val testListener = ContextPropertyChangeListener { evt -> capturedEvents.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents).hasSize(1)
		capturedEvents.clear()
		dynamicSemaphore1.removePropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.S60
		assertThat(capturedEvents).isEmpty()
		dynamicSemaphore1.addPropertyChangeListener(testListener)
		dynamicSemaphore1.signal = Signal.FREE
		assertThat(capturedEvents).hasSize(1)
	}

	@Test
	fun `property change events are independent for different semaphores`() {
		val capturedEvents1 = mutableListOf<ContextChangeEvent>()
		val capturedEvents2 = mutableListOf<ContextChangeEvent>()
		val testListener1 = ContextPropertyChangeListener { evt -> capturedEvents1.add(evt) }
		val testListener2 = ContextPropertyChangeListener { evt -> capturedEvents2.add(evt) }
		dynamicSemaphore1.addPropertyChangeListener(testListener1)
		dynamicSemaphore2.addPropertyChangeListener(testListener2)
		dynamicSemaphore1.signal = Signal.S30
		assertThat(capturedEvents1).hasSize(1)
		assertThat(capturedEvents2).isEmpty()
	}

	@Test
	fun `constant semaphore does not fire events`() {
		val constantSemaphore = createConstantInstance(staticSemaphore1, Signal.FREE)
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		val testListener = ContextPropertyChangeListener { evt -> capturedEvents.add(evt) }
		constantSemaphore.addPropertyChangeListener(testListener)
		constantSemaphore.signal = Signal.STOP
		assertThat(constantSemaphore.signal).isEqualTo(Signal.FREE)
		assertThat(capturedEvents).isEmpty()
	}

	// ========== setUpPath / cancelPathSetup / setUpSpeed Tests ==========

	// For staticSemaphore1: orientation=true, HORIZONTAL
	//   direction() = Cell.Segment.A  (HORIZONTAL.segments=[F,A], index 1 for orientation=true)
	//   anti(A) = F
	//
	// A DynamicRailSemaphore sits at a block boundary and clears for traffic entering from
	// EITHER side of that boundary (Issue #566/SP2b.9 domain decision -- see
	// DynamicRailSemaphore.checkPathSegments KDoc). `orientation`/`direction()` remain
	// meaningful for path-finding preference (e.g. PathReservationService.reservePathToAny's
	// same-side/opposite-side sorting), but they no longer gate whether the physical signal
	// can display a proceed aspect: a shunting-loop block signal must be able to authorize a
	// train arriving from either direction across the boundary it protects.
	//   Entry-side pairing: from=F, to=A  → checkPathSegments succeeds, signal fires
	//   Exit-side pairing:  from=A, to=F  → checkPathSegments ALSO succeeds, signal fires

	@Test
	fun `setUpPath fires signal change event for forward direction`() {
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		dynamicSemaphore1.addPropertyChangeListener { capturedEvents.add(it) }

		dynamicSemaphore1.setUpPath(
			Cell.Segment.F,
			Cell.Segment.A,
			20.0,
			stubTrackOccupant()
		)

		// Signal should have changed from STOP to something non-STOP
		assertThat(dynamicSemaphore1.signal).isNotEqualTo(Signal.STOP)
		// Listener should have received the "signal" event
		val signalEvents = capturedEvents.filter { it.propertyName == "signal" }
		assertThat(signalEvents).hasSize(1)
		assertThat(signalEvents[0].oldValue).isEqualTo(Signal.STOP)
	}

	@Test
	fun `cancelPathSetup resets signal to STOP and fires event`() {
		// Given: signal is set to non-STOP
		dynamicSemaphore1.signal = Signal.S60

		val capturedEvents = mutableListOf<ContextChangeEvent>()
		dynamicSemaphore1.addPropertyChangeListener { capturedEvents.add(it) }

		// When: cancelPathSetup is called
		dynamicSemaphore1.cancelPathSetup(Cell.Segment.F, Cell.Segment.A)

		// Then: signal is reset to STOP and listener fired
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)
		val signalEvents = capturedEvents.filter { it.propertyName == "signal" }
		assertThat(signalEvents).hasSize(1)
		assertThat(signalEvents[0].oldValue).isEqualTo(Signal.S60)
		assertThat(signalEvents[0].newValue).isEqualTo(Signal.STOP)
	}

	@Test
	fun `cancelPathSetup with already STOP signal does not fire event`() {
		// Signal is already STOP (initial state)
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)

		val capturedEvents = mutableListOf<ContextChangeEvent>()
		dynamicSemaphore1.addPropertyChangeListener { capturedEvents.add(it) }

		// When: cancelPathSetup is called (signal already STOP)
		dynamicSemaphore1.cancelPathSetup(Cell.Segment.F, Cell.Segment.A)

		// Then: signal unchanged and no event fired (same value)
		assertThat(dynamicSemaphore1.signal).isEqualTo(Signal.STOP)
		assertThat(capturedEvents).isEmpty()
	}

	@Test
	fun `setUpSpeed with the reverse segment pairing also clears the signal`() {
		// Signal initially STOP
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		dynamicSemaphore1.addPropertyChangeListener { capturedEvents.add(it) }

		// Reverse pairing: from=A (direction), to=F (anti-direction) -- a train crossing
		// this semaphore's block boundary from the opposite side. Issue #566/SP2b.9: this
		// must clear the signal too, since the block boundary is traversable in either
		// direction (see checkPathSegments KDoc).
		dynamicSemaphore1.setUpSpeed(Cell.Segment.A, Cell.Segment.F, 20.0)

		assertThat(dynamicSemaphore1.signal).isNotEqualTo(Signal.STOP)
		val signalEvents = capturedEvents.filter { it.propertyName == "signal" }
		assertThat(signalEvents).hasSize(1)
		assertThat(signalEvents[0].oldValue).isEqualTo(Signal.STOP)
	}

	@Test
	fun `setUpPath with the reverse segment pairing also fires a signal event`() {
		val capturedEvents = mutableListOf<ContextChangeEvent>()
		dynamicSemaphore1.addPropertyChangeListener { capturedEvents.add(it) }

		// Reverse pairing for setUpPath -- see setUpSpeed test above for rationale.
		dynamicSemaphore1.setUpPath(
			Cell.Segment.A,
			Cell.Segment.F,
			20.0,
			stubTrackOccupant()
		)

		assertThat(dynamicSemaphore1.signal).isNotEqualTo(Signal.STOP)
		val signalEvents = capturedEvents.filter { it.propertyName == "signal" }
		assertThat(signalEvents).hasSize(1)
	}

	@Test
	fun `cancelPathSetup with invalid segments throws PathSeparatorChangeException`() {
		assertFailure {
			// E and F are not joined by a straight line for HORIZONTAL orientation
			dynamicSemaphore1.cancelPathSetup(Cell.Segment.E, Cell.Segment.F)
		}.isInstanceOf(PathSeparatorChangeException::class)
	}
}

private fun stubTrackOccupant(): TrackOccupant =
	object : TrackOccupant {
		override val name = "StubTrain"

		override fun distanceToSemaphore() = 0.0

		override fun nextSemaphore(): OrientedPathSeparator? = null
	}

/**
 * Equals contract tests extracted from DynamicRailSemaphoreTest @Nested inner class.
 */
class DynamicRailSemaphoreEqualsContractTest {
	private lateinit var staticSemaphore1: RailSemaphore
	private lateinit var staticSemaphore2: RailSemaphore
	private lateinit var dynamicSemaphore1: DynamicRailSemaphore

	@BeforeTest
	fun setUp() {
		staticSemaphore1 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		staticSemaphore2 = RailSemaphore(false, Cell.SpatialType.VERTICAL)
		dynamicSemaphore1 = createDynamicInstance(staticSemaphore1)
	}

	@Test
	fun `equals with null returns false`() {
		@Suppress("KotlinConstantConditions")
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
		assertThat(x).isEqualTo(z)
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

/**
 * Listener thread-safety tests extracted from DynamicRailSemaphoreTest @Nested inner class.
 */
class DynamicRailSemaphoreListenerThreadSafetyTest {
	private lateinit var dynamicSemaphore1: DynamicRailSemaphore

	@BeforeTest
	fun setUp() {
		val staticSemaphore1 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		dynamicSemaphore1 = createDynamicInstance(staticSemaphore1)
	}

	@Test
	fun `listener added during event callback does not cause ConcurrentModificationException`() {
		val secondListenerCallCount = intArrayOf(0)
		val secondListener = ContextPropertyChangeListener { secondListenerCallCount[0]++ }
		val firstListener =
			ContextPropertyChangeListener {
				dynamicSemaphore1.addPropertyChangeListener(secondListener)
			}
		dynamicSemaphore1.addPropertyChangeListener(firstListener)
		dynamicSemaphore1.signal = Signal.FREE
		dynamicSemaphore1.signal = Signal.STOP
		assertThat(secondListenerCallCount[0]).isGreaterThan(0)
	}

	@Test
	fun `listener removed during event callback does not cause ConcurrentModificationException`() {
		var callCount = 0
		lateinit var selfRemovingListener: ContextPropertyChangeListener
		selfRemovingListener =
			ContextPropertyChangeListener {
				callCount++
				dynamicSemaphore1.removePropertyChangeListener(selfRemovingListener)
			}
		dynamicSemaphore1.addPropertyChangeListener(selfRemovingListener)
		dynamicSemaphore1.signal = Signal.FREE
		val countAfterFirstFire = callCount
		dynamicSemaphore1.signal = Signal.STOP
		assertThat(callCount).isEqualTo(countAfterFirstFire)
	}
}
