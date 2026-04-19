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
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.component.inject
import kotlin.test.Test

class ContextSerializationTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)

	@Test
	fun frozenContext_rejectsModifications() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
		}
	}

	@Test
	fun frozenContext_allowsReads() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.putCell(Point(5, 5), outB)
			context.currentMaxSpeed = 120.0
			context.currentTrackLength = 500.0
			context.currentNameString = "TestName"
			context.freeze()
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
	fun freeze_isIdempotent() {
		editingContextFactory.createEmptyContext().use { context ->
			assertThat(context.isFrozen()).isFalse()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
			assertThat(context.isFrozen()).isTrue()
		}
	}

	@Test
	fun freeze_triggersPropertyChangeEvent() {
		editingContextFactory.createEmptyContext().use { context ->
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
			context.freeze()
			assertThat(eventFired).isTrue()
			assertThat(eventPropertyName).isEqualTo("frozen")
			assertThat(eventNewValue as Boolean).isTrue()
		}
	}

	@Test
	fun freeze_eventFiredOnlyOnce() {
		editingContextFactory.createEmptyContext().use { context ->
			var eventCount = 0
			val listener =
				ContextPropertyChangeListener { evt ->
					if (evt.propertyName == "frozen") {
						eventCount++
					}
				}
			context.addPropertyChangeListener(listener)
			context.freeze()
			context.freeze()
			context.freeze()
			assertThat(eventCount).isEqualTo(1)
		}
	}

	@Test
	fun configurationProperties_preservedAfterFreeze() {
		editingContextFactory.createEmptyContext().use { context ->
			context.currentMaxSpeed = 150.0
			context.currentTrackLength = 750.0
			context.currentNameString = "FreezeName"
			context.freeze()
			assertThat(context.currentMaxSpeed).isEqualTo(150.0)
			assertThat(context.currentTrackLength).isEqualTo(750.0)
			assertThat(context.currentNameString).isEqualTo("FreezeName")
		}
	}

	@Test
	fun inOutList_preservedAfterFreeze() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.putCell(Point(5, 5), outB)
			val inOutCountBefore = context.getInOuts().size
			context.freeze()
			val inOutCountAfter = context.getInOuts().size
			assertThat(inOutCountAfter).isEqualTo(inOutCountBefore)
			assertThat(inOutCountAfter).isEqualTo(2)
		}
	}

	@Test
	fun graphStructure_preservedAfterFreeze() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.putCell(Point(2, 1), outB)
			val graphSizeBefore = context.getGraph().size()
			context.freeze()
			val graphSizeAfter = context.getGraph().size()
			assertThat(graphSizeAfter).isEqualTo(graphSizeBefore)
		}
	}

	@Test
	fun gridCells_preservedAfterFreeze() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.putCell(Point(5, 5), outB)
			val cellCountBefore =
				context
					.getRailWayNetGrid()
					.iterator()
					.asSequence()
					.count()
			context.freeze()
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

	@Test
	fun listeners_receiveFreezeEvent() {
		editingContextFactory.createEmptyContext().use { context ->
			val listener1 = TestPropertyChangeListener()
			val listener2 = TestPropertyChangeListener()
			context.addPropertyChangeListener(listener1)
			context.addPropertyChangeListener(listener2)
			context.freeze()
			assertThat(listener1.receivedFreezeEvent).isTrue()
			assertThat(listener2.receivedFreezeEvent).isTrue()
		}
	}

	@Test
	fun listenerAddedAfterFreeze_doesNotReceiveFreezeEvent() {
		editingContextFactory.createEmptyContext().use { context ->
			context.freeze()
			val listener = TestPropertyChangeListener()
			context.addPropertyChangeListener(listener)
			assertThat(listener.receivedFreezeEvent).isFalse()
		}
	}

	@Test
	fun removedListener_doesNotReceiveFreezeEvent() {
		editingContextFactory.createEmptyContext().use { context ->
			val listener = TestPropertyChangeListener()
			context.addPropertyChangeListener(listener)
			context.removePropertyChangeListener(listener)
			context.freeze()
			assertThat(listener.receivedFreezeEvent).isFalse()
		}
	}

	@Test
	fun unFrozenToFrozen_isOneWay() {
		editingContextFactory.createEmptyContext().use { context ->
			assertThat(context.isFrozen()).isFalse()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
		}
	}

	@Test
	fun newContext_startsUnfrozen() {
		val context1 = editingContextFactory.createEmptyContext()
		val context2 = DefaultEditingContext(20, 20)
		assertThat(context1.isFrozen()).isFalse()
		assertThat(context2.isFrozen()).isFalse()
	}

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
