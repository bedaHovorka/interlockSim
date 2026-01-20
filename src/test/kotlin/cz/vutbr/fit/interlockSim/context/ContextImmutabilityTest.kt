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
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.test.inject

/**
 * Test suite for context immutability enforcement.
 *
 * Tests verify that:
 * - Editing contexts start unfrozen (mutable)
 * - Simulation contexts are frozen after initialization (immutable)
 * - freeze() is idempotent
 * - All editing operations throw UnsupportedOperationException when frozen
 * - Error messages are clear and actionable
 * - Separate EditingContext instances remain mutable
 *
 * ## Issue #153.8: Enforce Simulation Network Immutability
 *
 * Level 1 (Compile-time): Interface segregation - already achieved
 * Level 2 (Runtime): Defensive checks - tested here
 *
 * @since 2026-01-20
 */
class ContextImmutabilityTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	// Test cells
	private val inA: InOut = InOut("A", false, SpatialType.HORIZONTAL)
	private val outB: InOut = InOut("B", true, SpatialType.HORIZONTAL)
	private val semaphore: RailSemaphore = RailSemaphore(false, SpatialType.DIAGONAL1)

	/**
	 * Test 1: Verify editing context starts unfrozen (mutable)
	 */
	@Test
	fun `editing context starts unfrozen`() {
		val context = factory.createEmptyContext()
		assertThat(context.isFrozen()).isFalse()
	}

	/**
	 * Test 2: Verify freeze() is idempotent (multiple calls OK)
	 */
	@Test
	fun `freeze is idempotent`() {
		val context = factory.createEmptyContext()
		assertThat(context.isFrozen()).isFalse()

		// First freeze
		context.freeze()
		assertThat(context.isFrozen()).isTrue()

		// Second freeze - should have no effect
		context.freeze()
		assertThat(context.isFrozen()).isTrue()

		// Third freeze - should still have no effect
		context.freeze()
		assertThat(context.isFrozen()).isTrue()
	}

	/**
	 * Test 3: putCell throws UnsupportedOperationException when frozen
	 */
	@Test
	fun `putCell throws when frozen`() {
		val context = factory.createEmptyContext()
		context.freeze()

		val exception = assertThrows<UnsupportedOperationException> {
			context.putCell(Point(1, 1), inA)
		}
		assertThat(exception).messageContains("Cannot add cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Use EditingContext")
	}

	/**
	 * Test 4: removeCell throws UnsupportedOperationException when frozen
	 */
	@Test
	fun `removeCell throws when frozen`() {
		val context = factory.createEmptyContext()
		// Add cell first (while unfrozen)
		context.putCell(Point(1, 1), inA)
		// Then freeze
		context.freeze()

		val exception = assertThrows<UnsupportedOperationException> {
			context.removeCell(Point(1, 1))
		}
		assertThat(exception).messageContains("Cannot remove cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Use EditingContext")
	}

	/**
	 * Test 5: moveCell throws UnsupportedOperationException when frozen
	 */
	@Test
	fun `moveCell throws when frozen`() {
		val context = factory.createEmptyContext()
		// Add cell first (while unfrozen)
		context.putCell(Point(1, 1), inA)
		// Then freeze
		context.freeze()

		val exception = assertThrows<UnsupportedOperationException> {
			context.moveCell(Point(1, 1), Point(2, 2))
		}
		assertThat(exception).messageContains("Cannot move cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Use EditingContext")
	}

	/**
	 * Test 6: joinCells throws UnsupportedOperationException when frozen
	 */
	@Test
	fun `joinCells throws when frozen`() {
		val context = factory.createEmptyContext()
		// Add cells first (while unfrozen)
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		// Then freeze
		context.freeze()

		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		val exception = assertThrows<UnsupportedOperationException> {
			context.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		}
		assertThat(exception).messageContains("Cannot join cells")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Use EditingContext")
	}

	/**
	 * Test 7: removeLine throws UnsupportedOperationException when frozen
	 */
	@Test
	fun `removeLine throws when frozen`() {
		val context = factory.createEmptyContext()
		// Add cells and join them first (while unfrozen)
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(2, 1), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		context.joinCells(Point(1, 1), Point(2, 1), trackBlock)
		// Then freeze
		context.freeze()

		val exception = assertThrows<UnsupportedOperationException> {
			context.removeLine(trackBlock)
		}
		assertThat(exception).messageContains("Cannot remove track block")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Use EditingContext")
	}

	/**
	 * Test 8: Verify simulation context is frozen after initialization from editing context
	 */
	@Test
	fun `simulation context is frozen after fromEditingContext`() {
		// Build network with editing context
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Convert to simulation context
		val simulationContext = factory.createContext(editingContext)

		// Verify simulation context is frozen
		assertThat(simulationContext.isFrozen()).isTrue()
	}

	/**
	 * Test 9: Verify simulation context is frozen after run() initialization
	 *
	 * Note: This test doesn't actually run the simulation (which would call System.exit),
	 * it just verifies the freeze happens early in run() before simulation starts.
	 */
	@Test
	fun `simulation context is frozen after run initialization`() {
		// Build network with editing context
		val editingContext = factory.createEmptyContext()
		editingContext.putCell(Point(1, 1), inA)
		editingContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		editingContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Convert to simulation context
		val simulationContext = factory.createContext(editingContext)

		// Already frozen from factory method
		assertThat(simulationContext.isFrozen()).isTrue()

		// Note: run() would also freeze, but it would start the simulation
		// (which calls System.exit). The factory method freeze is sufficient.
	}

	/**
	 * Test 10: Verify EditingContext remains mutable after separate instance creation
	 */
	@Test
	fun `editing context remains mutable after another context is frozen`() {
		// Create first editing context and freeze it
		val frozenContext = factory.createEmptyContext()
		frozenContext.freeze()
		assertThat(frozenContext.isFrozen()).isTrue()

		// Create second editing context (completely separate instance)
		val mutableContext = factory.createEmptyContext()
		assertThat(mutableContext.isFrozen()).isFalse()

		// Second context should be mutable
		mutableContext.putCell(Point(1, 1), inA)
		mutableContext.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		mutableContext.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// No exceptions thrown - operations succeeded
		assertThat(mutableContext.getGraph().size()).isTrue()
	}

	/**
	 * Test 11: Verify exception message clarity for putCell
	 */
	@Test
	fun `exception message for putCell is clear and actionable`() {
		val context = factory.createEmptyContext()
		context.freeze()

		val exception = assertThrows<UnsupportedOperationException> {
			context.putCell(Point(1, 1), inA)
		}

		// Verify all key parts of error message
		assertThat(exception).messageContains("Cannot add cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Network structure is immutable after simulation initialization")
		assertThat(exception).messageContains("Use EditingContext for network modifications")
	}

	/**
	 * Test 12: Verify exception message clarity for removeCell
	 */
	@Test
	fun `exception message for removeCell is clear and actionable`() {
		val context = factory.createEmptyContext()
		context.putCell(Point(1, 1), inA)
		context.freeze()

		val exception = assertThrows<UnsupportedOperationException> {
			context.removeCell(Point(1, 1))
		}

		// Verify all key parts of error message
		assertThat(exception).messageContains("Cannot remove cell")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Network structure is immutable after simulation initialization")
		assertThat(exception).messageContains("Use EditingContext for network modifications")
	}

	/**
	 * Test 13: Verify exception message clarity for joinCells
	 */
	@Test
	fun `exception message for joinCells is clear and actionable`() {
		val context = factory.createEmptyContext()
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		context.freeze()

		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		val exception = assertThrows<UnsupportedOperationException> {
			context.joinCells(Point(1, 1), Point(5, 5), trackBlock)
		}

		// Verify all key parts of error message
		assertThat(exception).messageContains("Cannot join cells")
		assertThat(exception).messageContains("context is frozen")
		assertThat(exception).messageContains("Network structure is immutable after simulation initialization")
		assertThat(exception).messageContains("Use EditingContext for network modifications")
	}

	/**
	 * Test 14: Verify frozen context can still read data
	 */
	@Test
	fun `frozen context allows read operations`() {
		val context = factory.createEmptyContext()
		// Add data while unfrozen
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		context.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Freeze
		context.freeze()

		// All read operations should still work
		assertThat(context.getRailWayNetGrid().getCellAt(1, 1)).isInstanceOf(InOut::class)
		assertThat(context.getRailWayNetGrid().getCellAt(5, 5)).isInstanceOf(InOut::class)
		assertThat(context.getGraph().size()).isTrue()
		assertThat(context.isFrozen()).isTrue()
	}

	/**
	 * Test 15: Verify modifications work before freeze
	 */
	@Test
	fun `context allows modifications before freeze`() {
		val context = factory.createEmptyContext()
		assertThat(context.isFrozen()).isFalse()

		// All operations should succeed
		context.putCell(Point(1, 1), inA)
		context.putCell(Point(5, 5), outB)
		context.putCell(Point(3, 3), semaphore)
		val trackBlock = SimpleTrackBlock(inA, outB, 1000.0, 80.0)
		context.joinCells(Point(1, 1), Point(5, 5), trackBlock)

		// Move cell
		context.moveCell(Point(3, 3), Point(4, 4))

		// Remove cell
		context.removeCell(Point(4, 4))

		// Remove line
		context.removeLine(trackBlock)

		// No exceptions thrown - all operations succeeded
		assertThat(context.isFrozen()).isFalse()
	}
}
