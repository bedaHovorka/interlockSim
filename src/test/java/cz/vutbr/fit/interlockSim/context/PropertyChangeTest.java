/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory;

import java.awt.Point;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * PropertyChange Notification Tests for Java 21 migration.
 * Tests the replacement of deprecated Observable/Observer with PropertyChangeSupport.
 *
 * @since 2026-01 (Java 21 migration)
 */
@DisplayName("PropertyChange Notification Tests")
public class PropertyChangeTest {

	private DefaultContext context;
	private TestPropertyChangeListener listener;

	@BeforeEach
	void setUp() {
		context = XMLContextFactory.getInstance().createEmptyContext();
		listener = new TestPropertyChangeListener();
		context.addPropertyChangeListener(listener);
	}

	@Test
	@DisplayName("putCell fires CELL_ADDED event")
	void putCell_firesPropertyChange() {
		Point position = new Point(5, 5);
		InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);

		context.putCell(position, inOut);

		assertThat(listener.events).hasSize(1);
		PropertyChangeEvent event = listener.events.get(0);
		assertThat(event.getPropertyName()).isEqualTo(ContextChangeListener.CELL_ADDED);
		assertThat(event.getNewValue()).isEqualTo(position);
	}

	@Test
	@DisplayName("removeCell fires CELL_REMOVED event")
	void removeCell_firesPropertyChange() {
		Point position = new Point(5, 5);
		InOut inOut = new InOut("A", false, SpatialType.HORIZONTAL);
		context.putCell(position, inOut);
		listener.events.clear();

		context.removeCell(position);

		assertThat(listener.events).hasSize(1);
		PropertyChangeEvent event = listener.events.get(0);
		assertThat(event.getPropertyName()).isEqualTo(ContextChangeListener.CELL_REMOVED);
		assertThat(event.getNewValue()).asString().contains("Cell removed at");
	}

	@Test
	@DisplayName("joinCells fires PropertyChange event (success or failure)")
	void joinCells_firesPropertyChangeEvent() {
		// Setup two cells and attempt to join them
		// The join may succeed or fail depending on railway logic, but should fire an event
		Point key1 = new Point(5, 5);
		Point key2 = new Point(10, 5);
		InOut cell1 = new InOut("A", false, SpatialType.HORIZONTAL);
		InOut cell2 = new InOut("B", false, SpatialType.HORIZONTAL);
		context.putCell(key1, cell1);
		context.putCell(key2, cell2);
		listener.events.clear();

		SimpleTrackBlock trackBlock = new SimpleTrackBlock(cell1, cell2, 100, 80);
		context.joinCells(key1, key2, trackBlock);

		// Verify that joinCells operation triggered at least one PropertyChange event
		assertThat(listener.events).hasSizeGreaterThanOrEqualTo(1);
		assertThat(listener.events)
			.extracting(PropertyChangeEvent::getPropertyName)
			.containsAnyOf(ContextChangeListener.JOIN_CREATED, ContextChangeListener.JOIN_FAILED);
	}

	@Test
	@DisplayName("joinCells failure fires JOIN_FAILED event")
	void joinCells_failure_firesPropertyChange() {
		// Setup two cells that cannot be joined (too close)
		Point key1 = new Point(5, 5);
		Point key2 = new Point(6, 5);
		InOut cell1 = new InOut("A", false, SpatialType.HORIZONTAL);
		InOut cell2 = new InOut("B", false, SpatialType.HORIZONTAL);
		context.putCell(key1, cell1);
		context.putCell(key2, cell2);
		listener.events.clear();

		SimpleTrackBlock trackBlock = new SimpleTrackBlock(cell1, cell2, 100, 80);
		context.joinCells(key1, key2, trackBlock);

		assertThat(listener.events).hasSizeGreaterThanOrEqualTo(1);
		assertThat(listener.events)
			.extracting(PropertyChangeEvent::getPropertyName)
			.contains(ContextChangeListener.JOIN_FAILED);
	}

	@Test
	@DisplayName("multiple listeners all receive events")
	void multipleListeners_allReceiveEvents() {
		TestPropertyChangeListener listener2 = new TestPropertyChangeListener();
		context.addPropertyChangeListener(listener2);

		Point position = new Point(5, 5);
		context.putCell(position, new InOut("A", false, SpatialType.HORIZONTAL));

		assertThat(listener.events).hasSize(1);
		assertThat(listener2.events).hasSize(1);
	}

	@Test
	@DisplayName("removePropertyChangeListener stops receiving events")
	void removeListener_stopsReceivingEvents() {
		context.removePropertyChangeListener(listener);

		Point position = new Point(5, 5);
		context.putCell(position, new InOut("A", false, SpatialType.HORIZONTAL));

		assertThat(listener.events).isEmpty();
	}

	/**
	 * Test PropertyChangeListener implementation that captures all events.
	 */
	private static class TestPropertyChangeListener implements PropertyChangeListener {
		List<PropertyChangeEvent> events = new ArrayList<>();

		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			events.add(evt);
		}
	}
}
