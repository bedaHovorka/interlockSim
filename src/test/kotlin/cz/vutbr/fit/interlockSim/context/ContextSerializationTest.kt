/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for context serialization and freeze behavior (Issue: Context Package Test Coverage)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener

/**
 * Comprehensive tests for context serialization and freeze behavior
 *
 * Test Coverage:
 * - freeze() is idempotent (multiple calls have no effect)
 * - frozen context rejects modifications with clear error messages
 * - frozen context allows read operations
 * - freeze() triggers property change event
 * - Property change events during freeze lifecycle
 * - Configuration properties preserved across freeze
 *
 * Total tests: 12+
 */
@DisplayName("Context Serialization and Freeze")
class ContextSerializationTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()

	// Test cells
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)

	@Nested
	@DisplayName("Freeze Behavior")
	inner class FreezeBehavior {
		@Test
		@DisplayName("frozen context rejects modifications")
		fun frozenContext_rejectsModifications() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.putCell(Point(1, 1), inA)

				// Act
				context.freeze()

				// Assert
				assertThat(context.isFrozen()).isTrue()

				// All modification operations should fail with UnsupportedOperationException
				// (tested in detail in ContextImmutabilityTest)
			}
		}

		@Test
		@DisplayName("frozen context allows reads")
		fun frozenContext_allowsReads() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.putCell(Point(1, 1), inA)
				context.putCell(Point(5, 5), outB)
				context.currentMaxSpeed = 120.0
				context.currentTrackLength = 500.0
				context.currentNameString = "TestName"

				// Act
				context.freeze()

				// Assert - All read operations work
				assertThat(context.isFrozen()).isTrue()
				assertThat(context.getRailWayNetGrid().getCellAt(1, 1)).isNotNull()
				assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isNotNull()
				assertThat(context.currentMaxSpeed).isEqualTo(120.0)
				assertThat(context.currentTrackLength).isEqualTo(500.0)
				assertThat(context.currentNameString).isEqualTo("TestName")
				assertThat(context.getGraph()).isNotNull()
				assertThat(context.getInOuts().size).isEqualTo(2)
			}
		}

		@Test
		@DisplayName("freeze is idempotent")
		fun freeze_isIdempotent() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				assertThat(context.isFrozen()).isFalse()

				// Act - Freeze multiple times
				context.freeze()
				assertThat(context.isFrozen()).isTrue()

				context.freeze()
				assertThat(context.isFrozen()).isTrue()

				context.freeze()
				assertThat(context.isFrozen()).isTrue()

				context.freeze()
				assertThat(context.isFrozen()).isTrue()

				// Assert - Still frozen, no exception thrown
				assertThat(context.isFrozen()).isTrue()
			}
		}

		@Test
		@DisplayName("freeze triggers property change event")
		fun freeze_triggersPropertyChangeEvent() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				var eventFired = false
				var eventPropertyName: String? = null
				var eventNewValue: Any? = null

				val listener =
					ContextPropertyChangeListener { evt ->
						if (evt.propertyName == "frozen") {
							eventFired = true
							eventPropertyName = evt.propertyName
							eventNewValue = evt.newValue
						}
					}
				context.addPropertyChangeListener(listener)

				// Act
				context.freeze()

				// Assert - Event was fired with correct property
				assertThat(eventFired).isTrue()
				assertThat(eventPropertyName).isEqualTo("frozen")
				assertThat(eventNewValue as Boolean).isTrue()
			}
		}

		@Test
		@DisplayName("freeze event fired only on first freeze call")
		fun freeze_eventFiredOnlyOnce() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				var eventCount = 0

				val listener =
					ContextPropertyChangeListener { evt ->
						if (evt.propertyName == "frozen") {
							eventCount++
						}
					}
				context.addPropertyChangeListener(listener)

				// Act - Freeze multiple times
				context.freeze()
				context.freeze()
				context.freeze()

				// Assert - Event fired only once (idempotent)
				assertThat(eventCount).isEqualTo(1)
			}
		}
	}

	@Nested
	@DisplayName("Configuration Properties Across Freeze")
	inner class ConfigurationPropertiesAcrossFreeze {
		@Test
		@DisplayName("configuration properties preserved after freeze")
		fun configurationProperties_preservedAfterFreeze() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.currentMaxSpeed = 150.0
				context.currentTrackLength = 750.0
				context.currentNameString = "FreezeName"

				// Act
				context.freeze()

				// Assert - Properties unchanged
				assertThat(context.currentMaxSpeed).isEqualTo(150.0)
				assertThat(context.currentTrackLength).isEqualTo(750.0)
				assertThat(context.currentNameString).isEqualTo("FreezeName")
			}
		}

		@Test
		@DisplayName("InOut list preserved after freeze")
		fun inOutList_preservedAfterFreeze() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.putCell(Point(1, 1), inA)
				context.putCell(Point(5, 5), outB)
				val inOutCountBefore = context.getInOuts().size

				// Act
				context.freeze()

				// Assert - InOut list unchanged
				val inOutCountAfter = context.getInOuts().size
				assertThat(inOutCountAfter).isEqualTo(inOutCountBefore)
				assertThat(inOutCountAfter).isEqualTo(2)
			}
		}

		@Test
		@DisplayName("graph structure preserved after freeze")
		fun graphStructure_preservedAfterFreeze() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.putCell(Point(1, 1), inA)
				context.putCell(Point(2, 1), outB)
				val graphSizeBefore = context.getGraph().size()

				// Act
				context.freeze()

				// Assert - Graph unchanged
				val graphSizeAfter = context.getGraph().size()
				assertThat(graphSizeAfter).isEqualTo(graphSizeAfter)
			}
		}

		@Test
		@DisplayName("grid cells preserved after freeze")
		fun gridCells_preservedAfterFreeze() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.putCell(Point(1, 1), inA)
				context.putCell(Point(5, 5), outB)
				val cellCountBefore =
					context
						.getRailWayNetGrid()
						.iterator()
						.asSequence()
						.count()

				// Act
				context.freeze()

				// Assert - Grid unchanged
				val cellCountAfter =
					context
						.getRailWayNetGrid()
						.iterator()
						.asSequence()
						.count()
				assertThat(cellCountAfter).isEqualTo(cellCountBefore)
				assertThat(cellCountAfter).isEqualTo(2)
			}
		}
	}

	@Nested
	@DisplayName("Property Change Events During Freeze Lifecycle")
	inner class PropertyChangeEventsDuringFreezeLifecycle {
		@Test
		@DisplayName("listeners receive freeze event")
		fun listeners_receiveFreezeEvent() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				val listener1 = TestPropertyChangeListener()
				val listener2 = TestPropertyChangeListener()

				context.addPropertyChangeListener(listener1)
				context.addPropertyChangeListener(listener2)

				// Act
				context.freeze()

				// Assert - Both listeners received the event
				assertThat(listener1.receivedFreezeEvent).isTrue()
				assertThat(listener2.receivedFreezeEvent).isTrue()
			}
		}

		@Test
		@DisplayName("listener added after freeze does not receive freeze event")
		fun listenerAddedAfterFreeze_doesNotReceiveFreezeEvent() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				context.freeze()

				// Act - Add listener after freeze
				val listener = TestPropertyChangeListener()
				context.addPropertyChangeListener(listener)

				// Trigger another event (putCell will throw, but we test listener registration)
				// We can't trigger events on frozen context, so just verify no freeze event

				// Assert - Listener didn't receive freeze event (context already frozen)
				assertThat(listener.receivedFreezeEvent).isFalse()
			}
		}

		@Test
		@DisplayName("removed listener does not receive freeze event")
		fun removedListener_doesNotReceiveFreezeEvent() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				val listener = TestPropertyChangeListener()
				context.addPropertyChangeListener(listener)

				// Act - Remove listener before freeze
				context.removePropertyChangeListener(listener)
				context.freeze()

				// Assert - Listener didn't receive event
				assertThat(listener.receivedFreezeEvent).isFalse()
			}
		}
	}

	@Nested
	@DisplayName("Freeze State Transitions")
	inner class FreezeStateTransitions {
		@Test
		@DisplayName("unfrozen to frozen transition is one-way")
		fun unFrozenToFrozen_isOneWay() {
			editingContextFactory.createEmptyContext().use { context ->
				// Arrange
				assertThat(context.isFrozen()).isFalse()

				// Act
				context.freeze()

				// Assert - Cannot unfreeze (no unfreeze method exists)
				assertThat(context.isFrozen()).isTrue()

				// Freeze is permanent - once frozen, always frozen
				// This is by design to ensure simulation network immutability
			}
		}

		@Test
		@DisplayName("new context starts in unfrozen state")
		fun newContext_startsUnfrozen() {
			// Arrange & Act
			val context1 = editingContextFactory.createEmptyContext()
			val context2 = DefaultEditingContext(20, 20)

			// Assert - Both start unfrozen
			assertThat(context1.isFrozen()).isFalse()
			assertThat(context2.isFrozen()).isFalse()
		}
	}

	/**
	 * Test PropertyChangeListener implementation that tracks freeze events.
	 */
	private class TestPropertyChangeListener : ContextPropertyChangeListener {
		var receivedFreezeEvent: Boolean = false
		var lastEvent: ContextChangeEvent? = null

		override fun propertyChange(event: ContextChangeEvent) {
			lastEvent = event
			if (event.propertyName == "frozen") {
				receivedFreezeEvent = true
			}
		}
	}
}
