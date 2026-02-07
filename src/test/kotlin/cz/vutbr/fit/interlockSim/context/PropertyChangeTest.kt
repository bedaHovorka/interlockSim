/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.containsAnyOf
import cz.vutbr.fit.interlockSim.testutil.hasSizeGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener

/**
 * PropertyChange Notification Tests for Java 21 migration.
 * Tests the replacement of deprecated Observable/Observer with PropertyChangeSupport.
 *
 * @since 2026-01 (Java 21 migration)
 */
@DisplayName("PropertyChange Notification Tests")
class PropertyChangeTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private lateinit var context: EditingContext
	private lateinit var listener: TestPropertyChangeListener

	@BeforeEach
	fun setUp() {
		context = editingContextFactory.createEmptyContext()
		testContext = context // Track for cleanup
		listener = TestPropertyChangeListener()
		context.addPropertyChangeListener(listener)
	}

	@Test
	@DisplayName("putCell fires CELL_ADDED event")
	fun putCell_firesPropertyChange() {
		val position = Point(5, 5)
		val inOut = InOut("A", false, SpatialType.HORIZONTAL)

		context.putCell(position, inOut)

		assertThat(listener.events).hasSize(1)
		val event = listener.events[0]
		assertThat(event.propertyName).isEqualTo(ContextChangeListener.CELL_ADDED)
		assertThat(event.newValue).isEqualTo(position)
	}

	@Test
	@DisplayName("removeCell fires CELL_REMOVED event")
	fun removeCell_firesPropertyChange() {
		val position = Point(5, 5)
		val inOut = InOut("A", false, SpatialType.HORIZONTAL)
		context.putCell(position, inOut)
		listener.events.clear()

		context.removeCell(position)

		assertThat(listener.events).hasSize(1)
		val event = listener.events[0]
		assertThat(event.propertyName).isEqualTo(ContextChangeListener.CELL_REMOVED)
		assertThat(event.newValue.toString()).contains("Cell removed at")
	}

	@Test
	@DisplayName("joinCells fires PropertyChange event (success or failure)")
	fun joinCells_firesPropertyChangeEvent() {
		// Setup two cells and attempt to join them
		// The join may succeed or fail depending on railway logic, but should fire an event
		val key1 = Point(5, 5)
		val key2 = Point(10, 5)
		val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
		val cell2 = InOut("B", false, SpatialType.HORIZONTAL)
		context.putCell(key1, cell1)
		context.putCell(key2, cell2)
		listener.events.clear()

		val trackBlock = SimpleTrackBlock(cell1, cell2, 100.0, 80.0)
		context.joinCells(key1, key2, trackBlock)

		// Verify that joinCells operation triggered at least one PropertyChange event
		assertThat(listener.events).hasSizeGreaterThanOrEqualTo(1)
		assertThat(listener.events.map { it.propertyName })
			.containsAnyOf(ContextChangeListener.JOIN_CREATED, ContextChangeListener.JOIN_FAILED)
	}

	@Test
	@DisplayName("joinCells failure fires JOIN_FAILED event")
	fun joinCells_failure_firesPropertyChange() {
		// Setup two cells that cannot be joined (too close)
		val key1 = Point(5, 5)
		val key2 = Point(6, 5)
		val cell1 = InOut("A", false, SpatialType.HORIZONTAL)
		val cell2 = InOut("B", false, SpatialType.HORIZONTAL)
		context.putCell(key1, cell1)
		context.putCell(key2, cell2)
		listener.events.clear()

		val trackBlock = SimpleTrackBlock(cell1, cell2, 100.0, 80.0)
		context.joinCells(key1, key2, trackBlock)

		assertThat(listener.events).hasSizeGreaterThanOrEqualTo(1)
		assertThat(listener.events.map { it.propertyName })
			.contains(ContextChangeListener.JOIN_FAILED)
	}

	@Test
	@DisplayName("multiple listeners all receive events")
	fun multipleListeners_allReceiveEvents() {
		val listener2 = TestPropertyChangeListener()
		context.addPropertyChangeListener(listener2)

		val position = Point(5, 5)
		context.putCell(position, InOut("A", false, SpatialType.HORIZONTAL))

		assertThat(listener.events).hasSize(1)
		assertThat(listener2.events).hasSize(1)
	}

	@Test
	@DisplayName("removePropertyChangeListener stops receiving events")
	fun removeListener_stopsReceivingEvents() {
		context.removePropertyChangeListener(listener)

		val position = Point(5, 5)
		context.putCell(position, InOut("A", false, SpatialType.HORIZONTAL))

		assertThat(listener.events).isEmpty()
	}

	/**
	 * Test PropertyChangeListener implementation that captures all events.
	 */
	private class TestPropertyChangeListener : PropertyChangeListener {
		val events: MutableList<PropertyChangeEvent> = mutableListOf()

		override fun propertyChange(evt: PropertyChangeEvent) {
			events.add(evt)
		}
	}
}
