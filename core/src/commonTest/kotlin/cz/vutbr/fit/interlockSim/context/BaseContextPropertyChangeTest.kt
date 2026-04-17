/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Coverage tests for BaseContext property change notification and listener management.
 * Fix 8: targeted coverage for SonarCloud quality gate on new/changed code (PR #375).
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextChangeListener.Companion.CELL_MODIFIED
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.inject
import kotlin.test.Test

/**
 * Tests for BaseContext property change notification infrastructure.
 *
 * Covers:
 * - addPropertyChangeListener / removePropertyChangeListener
 * - firePropertyChange via EditingContext.fireCellModified
 * - freeze() fires "frozen" event to listeners
 * - getTopologyNavigator() is accessible
 *
 * These tests target uncovered code paths in BaseContext and DefaultEditingContext
 * that are part of the new Kotlin-native event system (PR #375).
 */
class BaseContextPropertyChangeTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)

	// --- Listener registration and notification ---

	@Test
	fun `addPropertyChangeListener - listener receives events fired by fireCellModified`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Place a cell so there is a valid position to fire at
			val pos = Point(1, 1)
			context.putCell(pos, inA)

			val received = mutableListOf<ContextChangeEvent>()
			val listener = ContextPropertyChangeListener { received.add(it) }
			context.addPropertyChangeListener(listener)

			// fireCellModified calls firePropertyChange internally
			context.fireCellModified(pos)

			assertThat(received).hasSize(1)
			assertThat(received[0].propertyName).isEqualTo(CELL_MODIFIED)
			assertThat(received[0].newValue).isEqualTo(pos)
		}
	}

	@Test
	fun `removePropertyChangeListener - listener no longer receives events`() {
		editingContextFactory.createEmptyContext().use { context ->
			val pos = Point(2, 2)
			context.putCell(pos, inA)

			val received = mutableListOf<ContextChangeEvent>()
			val listener = ContextPropertyChangeListener { received.add(it) }

			context.addPropertyChangeListener(listener)
			context.fireCellModified(pos)
			assertThat(received).hasSize(1)

			// Remove and verify no more events
			received.clear()
			context.removePropertyChangeListener(listener)
			context.fireCellModified(pos)
			assertThat(received).isEmpty()
		}
	}

	@Test
	fun `multiple listeners all receive the same event`() {
		editingContextFactory.createEmptyContext().use { context ->
			val pos = Point(3, 3)
			context.putCell(pos, inA)

			val received1 = mutableListOf<ContextChangeEvent>()
			val received2 = mutableListOf<ContextChangeEvent>()

			context.addPropertyChangeListener { received1.add(it) }
			context.addPropertyChangeListener { received2.add(it) }

			context.fireCellModified(pos)

			assertThat(received1).hasSize(1)
			assertThat(received2).hasSize(1)
			assertThat(received1[0].propertyName).isEqualTo(received2[0].propertyName)
		}
	}

	// --- freeze() listener notification ---

	@Test
	fun `freeze fires frozen property change event to listeners`() {
		editingContextFactory.createEmptyContext().use { context ->
			val received = mutableListOf<ContextChangeEvent>()
			context.addPropertyChangeListener { received.add(it) }

			context.freeze()

			// freeze() should fire "frozen" event
			val frozenEvents = received.filter { it.propertyName == "frozen" }
			assertThat(frozenEvents).hasSize(1)
			assertThat(frozenEvents[0].oldValue).isEqualTo(false)
			assertThat(frozenEvents[0].newValue).isEqualTo(true)
		}
	}

	@Test
	fun `freeze is idempotent - fires event only once`() {
		editingContextFactory.createEmptyContext().use { context ->
			val received = mutableListOf<ContextChangeEvent>()
			context.addPropertyChangeListener { received.add(it) }

			context.freeze()
			context.freeze() // second call should be no-op
			context.freeze() // third call should be no-op

			val frozenEvents = received.filter { it.propertyName == "frozen" }
			assertThat(frozenEvents).hasSize(1)
		}
	}

	@Test
	fun `listener added after freeze does not receive past freeze event`() {
		editingContextFactory.createEmptyContext().use { context ->
			context.freeze()

			val received = mutableListOf<ContextChangeEvent>()
			context.addPropertyChangeListener { received.add(it) }

			// No events should have been received (freeze already happened)
			assertThat(received).isEmpty()
		}
	}

	// --- Property change events from editing operations ---

	@Test
	fun `putCell fires CELL_ADDED event`() {
		editingContextFactory.createEmptyContext().use { context ->
			val received = mutableListOf<ContextChangeEvent>()
			context.addPropertyChangeListener { received.add(it) }

			context.putCell(Point(1, 1), inA)

			val cellAddedEvents =
				received.filter {
					it.propertyName == ContextChangeListener.CELL_ADDED
				}
			assertThat(cellAddedEvents).hasSize(1)
		}
	}

	@Test
	fun `currentMaxSpeed setter fires property change when value changes`() {
		editingContextFactory.createEmptyContext().use { context ->
			val received = mutableListOf<ContextChangeEvent>()
			context.addPropertyChangeListener { received.add(it) }

			val newSpeed = 120.0
			context.currentMaxSpeed = newSpeed

			val speedEvents = received.filter { it.propertyName == "currentMaxSpeed" }
			assertThat(speedEvents).hasSize(1)
			assertThat(speedEvents[0].newValue).isEqualTo(newSpeed)
		}
	}

	@Test
	fun `currentMaxSpeed setter does not fire event when value unchanged`() {
		editingContextFactory.createEmptyContext().use { context ->
			// Set to same value as current to trigger the "oldValue == newValue" check
			val sameSpeed = context.currentMaxSpeed

			val received = mutableListOf<ContextChangeEvent>()
			context.addPropertyChangeListener { received.add(it) }

			context.currentMaxSpeed = sameSpeed

			// firePropertyChangeAlways skips when old == new
			val speedEvents = received.filter { it.propertyName == "currentMaxSpeed" }
			assertThat(speedEvents).isEmpty()
		}
	}

	// --- getTopologyNavigator ---

	@Test
	fun `getTopologyNavigator returns non-null navigator`() {
		editingContextFactory.createEmptyContext().use { context ->
			val navigator = context.getTopologyNavigator()
			assertThat(navigator).isNotNull()
		}
	}

	@Test
	fun `getTopologyNavigator returns same instance on repeated calls`() {
		editingContextFactory.createEmptyContext().use { context ->
			val nav1 = context.getTopologyNavigator()
			val nav2 = context.getTopologyNavigator()
			assertThat(nav1 === nav2).isTrue()
		}
	}
}
