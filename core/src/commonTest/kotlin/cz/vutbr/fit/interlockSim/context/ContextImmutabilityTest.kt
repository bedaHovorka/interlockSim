/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test suite for context immutability enforcement (Issue #153.8)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.koin.test.inject

class ContextImmutabilityTest : CommonKoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: CommonSimulationContextFactory by inject()

	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	@Test
	fun `editing context starts unfrozen`() {
		editingContextFactory.createEmptyContext().use { context ->
			assertThat(context.isFrozen()).isFalse()
		}
	}

	@Test
	fun `freeze is idempotent`() {
		editingContextFactory.createEmptyContext().use { context ->
			assertThat(context.isFrozen()).isFalse()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
			context.freeze()
			assertThat(context.isFrozen()).isTrue()
		}
	}

	@Test
	fun `putCell throws when frozen`() {
		editingContextFactory.createEmptyContext().use { context ->
			context.freeze()
			val exception =
				assertFailsWith<UnsupportedOperationException> {
					context.putCell(Point(1, 1), inA)
				}
			assertThat(exception).messageContains("Cannot add cell")
			assertThat(exception).messageContains("context is frozen")
			assertThat(exception).messageContains("Use EditingContext")
		}
	}

	@Test
	fun `removeCell throws when frozen`() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.freeze()
			val exception =
				assertFailsWith<UnsupportedOperationException> {
					context.removeCell(Point(1, 1))
				}
			assertThat(exception).messageContains("Cannot remove cell")
			assertThat(exception).messageContains("context is frozen")
			assertThat(exception).messageContains("Use EditingContext")
		}
	}

	@Test
	fun `moveCell throws when frozen`() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.freeze()
			val exception =
				assertFailsWith<UnsupportedOperationException> {
					context.moveCell(Point(1, 1), Point(2, 2))
				}
			assertThat(exception).messageContains("Cannot move cell")
			assertThat(exception).messageContains("context is frozen")
			assertThat(exception).messageContains("Use EditingContext")
		}
	}

	@Test
	fun `joinCells throws when frozen`() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.putCell(Point(5, 5), outB)
			context.freeze()
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			val exception =
				assertFailsWith<UnsupportedOperationException> {
					context.joinCells(Point(1, 1), Point(5, 5), trackBlock)
				}
			assertThat(exception).messageContains("Cannot join cells")
			assertThat(exception).messageContains("context is frozen")
			assertThat(exception).messageContains("Use EditingContext")
		}
	}

	@Test
	fun `removeLine throws when frozen`() {
		editingContextFactory.createEmptyContext().use { context ->
			context.putCell(Point(1, 1), inA)
			context.putCell(Point(2, 1), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			context.joinCells(Point(1, 1), Point(2, 1), trackBlock)
			context.freeze()
			val exception =
				assertFailsWith<UnsupportedOperationException> {
					context.removeLine(trackBlock)
				}
			assertThat(exception).messageContains("Cannot remove track block")
			assertThat(exception).messageContains("context is frozen")
			assertThat(exception).messageContains("Use EditingContext")
		}
	}

	@Test
	fun `simulation context is frozen after fromEditingContext`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
			(simulationContextFactory.createContext(editingContext) as DefaultSimulationContext).use { simulationContext ->
				assertThat(simulationContext.isFrozen()).isTrue()
			}
		}
	}

	@Test
	fun `simulation context is frozen after run initialization`() {
		editingContextFactory.createEmptyContext().use { editingContext ->
			editingContext.putCell(Point(1, 1), inA)
			editingContext.putCell(Point(5, 5), outB)
			val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
			editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
			(simulationContextFactory.createContext(editingContext) as DefaultSimulationContext).use { simulationContext ->
				assertThat(simulationContext.isFrozen()).isTrue()
			}
		}
	}

	@Test
	fun `editing context remains mutable after another context is frozen`() {
		editingContextFactory.createEmptyContext().use { frozenContext ->
			frozenContext.freeze()
			assertThat(frozenContext.isFrozen()).isTrue()
			editingContextFactory.createEmptyContext().use { mutableContext ->
				assertThat(mutableContext.isFrozen()).isFalse()
				mutableContext.putCell(Point(1, 1), inA)
				mutableContext.putCell(Point(5, 5), outB)
				val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
				mutableContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)
				assertThat(mutableContext.getGraph().size()).isGreaterThan(0)
			}
		}
	}

	@Test
	fun `exception message for putCell is clear and actionable`() {
		val context = editingContextFactory.createEmptyContext()
		context.freeze()
		val exception =
			assertFailsWith<UnsupportedOperationException> {
				context.putCell(Point(1, 1), inA)
			}
		assertThat(exception).messageContains("Cannot add cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Network structure is immutable after simulation initialization")
		assertThat(exception).messageContains("Use EditingContext for network modifications")
	}

	@Test
	fun `exception message for removeCell is clear and actionable`() {
		val context = editingContextFactory.createEmptyContext()
		context.putCell(Point(1, 1), inA)
		context.freeze()
		val exception =
			assertFailsWith<UnsupportedOperationException> {
				context.removeCell(Point(1, 1))
			}
		assertThat(exception).messageContains("Cannot remove cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Network structure is immutable after simulation initialization")
		assertThat(exception).messageContains("Use EditingContext for network modifications")
	}

	@Test
	fun `exception message for joinCells is clear and actionable`() {
		val context = editingContextFactory.createEmptyContext()
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		context.freeze()
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		val exception =
			assertFailsWith<UnsupportedOperationException> {
				context.joinCells(Point(1, 1), Point(5, 5), trackBlock)
			}
		assertThat(exception).messageContains("Cannot join cells")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Network structure is immutable after simulation initialization")
		assertThat(exception).messageContains("Use EditingContext for network modifications")
	}

	@Test
	fun `frozen context allows read operations`() {
		val context = editingContextFactory.createEmptyContext()
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		context.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		context.freeze()
		assertThat(context.getRailWayNetGrid().getCellAt(1, 1)!!).isInstanceOf<InOut>()
		assertThat(context.getRailWayNetGrid().getCellAt(5, 5)!!).isInstanceOf<InOut>()
		assertThat(context.getGraph().size()).isGreaterThan(0)
		assertThat(context.isFrozen()).isTrue()
	}

	@Test
	fun `context allows modifications before freeze`() {
		val context = editingContextFactory.createEmptyContext()
		assertThat(context.isFrozen()).isFalse()
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		context.putCell(Point(3, 3), semaphore)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		context.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		context.moveCell(Point(3, 3), Point(4, 4))
		context.removeCell(Point(4, 4))
		context.removeLine(trackBlock)
		assertThat(context.isFrozen()).isFalse()
	}
}
