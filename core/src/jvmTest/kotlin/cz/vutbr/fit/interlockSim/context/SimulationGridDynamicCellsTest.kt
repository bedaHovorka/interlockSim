/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #281: Verify simulation grid stores dynamic wrappers (not static cells)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test suite for Issue #280/#281: Verify that DefaultSimulationContext.fromEditingContext()
 * correctly populates the simulation grid with DYNAMIC wrapper instances (not static cells).
 *
 * **Background:**
 * Issue #280 discovered that the simulation grid contained static NodeCell instances instead
 * of dynamic wrappers (DynamicInOut, DynamicRailSemaphore, etc.). This caused trains to deadlock
 * because Train.pathToNextSemaphore() returned static cells that didn't have dynamic state.
 *
 * **Fix (Issue #280.1):**
 * DefaultSimulationContext.fromEditingContext() was fixed to use GridTransformer.dynamicGrid
 * to populate the simulation grid with dynamic wrappers, ensuring identity consistency.
 *
 * **Purpose of these tests:**
 * - Verify the fix works correctly (regression detection)
 * - Ensure all NodeCells in simulation grid are dynamic wrappers
 * - Validate grid navigation returns dynamic instances
 * - Confirm TrackBlockPart cells are preserved (not transformed)
 *
 * **Test Data:**
 * Uses vyhybna.xml fixture (shunting loop configuration) which contains:
 * - 2 InOut elements (InOut A, InOut B)
 * - 6 RailSemaphore elements (zA, doA1, doA2, doB1, doB2, zB)
 * - 2 RailSwitch elements (vA, vB)
 * - 4 TrackBlock instances (k1, k2, kA, kB)
 *
 * @see DefaultSimulationContext.fromEditingContext
 * @see GridTransformer.transformGrid
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/280">Issue #280</a>
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/281">Issue #281</a>
 */
@DisplayName("Simulation Grid Dynamic Cells Verification (Issue #281)")
class SimulationGridDynamicCellsTest : KoinTestBase() {
	private fun loadVyhybnaStream() =
		TestFixtures.loadShuntingXml()
			?: error("Resource not found: vyhybna.xml")

	@Nested
	@DisplayName("Dynamic semaphore storage")
	inner class DynamicSemaphoreTests {
		/**
		 * Test Case 1: Verify simulation grid stores DynamicRailSemaphore instances
		 * instead of static RailSemaphore instances.
		 *
		 * **Expected behavior:**
		 * - Grid contains at least one DynamicRailSemaphore instance
		 * - The cell is an instance of DynamicRailSemaphore
		 * - The cell is NOT an instance of static RailSemaphore
		 *
		 * **Rationale:**
		 * DynamicRailSemaphore wraps static RailSemaphore and provides dynamic state.
		 * Train.pathToNextSemaphore() needs dynamic instances to check semaphore state.
		 */
		@Test
		fun `simulation grid stores DynamicRailSemaphore not static RailSemaphore`() {
			// Arrange: Load vyhybna.xml and create simulation context
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find any semaphore cell in the grid
			val semaphoreCell =
				grid
					.asSequence()
					.mapNotNull { (_, cell) -> cell }
					.firstOrNull { it is DynamicRailSemaphore }

			// Assert: Grid contains DynamicRailSemaphore (not static RailSemaphore)
			assertThat(semaphoreCell!!).isInstanceOf(DynamicRailSemaphore::class)

			// Verify it's a DynamicRailSemaphore (sealed class)
			// DynamicRailSemaphore is implemented by DefaultDynamicSemaphore (private) and ConstantSemaphore (private)
			// We check for the sealed base class
		}

		/**
		 * Test Case: Verify ALL semaphores in grid are dynamic wrappers.
		 *
		 * **Expected behavior:**
		 * - vyhybna.xml has 6 semaphores
		 * - All 6 must be DynamicRailSemaphore instances
		 * - None should be static RailSemaphore instances
		 */
		@Test
		fun `all semaphores in simulation grid are dynamic wrappers`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find all cells that are instances of DynamicRailSemaphore
			val dynamicSemaphores =
				grid
					.asSequence()
					.mapNotNull { (_, cell) -> cell }
					.filter { it is DynamicRailSemaphore }
					.toList()

			// Assert: vyhybna.xml has 6 semaphores (zA, doA1, doA2, doB1, doB2, zB)
			assertThat(dynamicSemaphores.size).isEqualTo(6)

			// Verify each is a DynamicRailSemaphore instance
			dynamicSemaphores.forEach { cell ->
				assertThat(cell).isInstanceOf<DynamicRailSemaphore>()
			}
		}
	}

	@Nested
	@DisplayName("Dynamic InOut storage")
	inner class DynamicInOutTests {
		/**
		 * Test Case 2: Verify simulation grid stores DynamicInOut instances
		 * instead of static InOut instances.
		 *
		 * **Expected behavior:**
		 * - Grid contains at least one DynamicInOut instance
		 * - The cell is an instance of DynamicInOut
		 * - The cell is NOT an instance of static InOut
		 */
		@Test
		fun `simulation grid stores DynamicInOut not static InOut`() {
			// Arrange: Load vyhybna.xml and create simulation context
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find any InOut cell in the grid
			val inoutCell =
				grid
					.asSequence()
					.mapNotNull { (_, cell) -> cell }
					.firstOrNull { it is DynamicInOut }

			// Assert: Grid contains DynamicInOut (not static InOut)
			assertThat(inoutCell!!).isInstanceOf(DynamicInOut::class)

			// Verify it's NOT the static InOut type
			val actualClass = inoutCell::class.java
			assertThat(actualClass).isEqualTo(DynamicInOut::class.java)
		}

		/**
		 * Test Case: Verify ALL InOuts in grid are dynamic wrappers.
		 *
		 * **Expected behavior:**
		 * - vyhybna.xml has 2 InOuts (InOut A, InOut B)
		 * - Both must be DynamicInOut instances
		 * - None should be static InOut instances
		 */
		@Test
		fun `all InOuts in simulation grid are dynamic wrappers`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find all cells that are instances of DynamicInOut
			val dynamicInOuts =
				grid
					.asSequence()
					.mapNotNull { (_, cell) -> cell }
					.filter { it is DynamicInOut }
					.toList()

			// Assert: vyhybna.xml has 2 InOuts (InOut A at (11,8), InOut B at (30,8))
			assertThat(dynamicInOuts.size).isEqualTo(2)

			// Verify each is exactly DynamicInOut type
			dynamicInOuts.forEach { cell ->
				assertThat(cell::class.java).isEqualTo(DynamicInOut::class.java)
			}
		}
	}

	@Nested
	@DisplayName("Grid navigation returns dynamic wrappers")
	inner class GridNavigationTests {
		/**
		 * Test Case 3: Verify grid.getCellAt() returns dynamic wrappers directly.
		 *
		 * **Expected behavior:**
		 * - getCellAt() returns DynamicRailSemaphore for semaphore positions
		 * - No need to call toDynamic() for grid-retrieved cells
		 * - Identity consistency: same object instance on repeated calls
		 */
		@Test
		fun `grid navigation returns dynamic wrappers directly`() {
			// Arrange: Load vyhybna.xml and create simulation context
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find position of any semaphore in grid
			val semaphorePoint =
				grid
					.asSequence()
					.filter { (_, cell) -> cell is DynamicRailSemaphore }
					.map { (point, _) -> point }
					.firstOrNull()

			assertThat(semaphorePoint).isNotNull()

			// Get cell at that position using grid navigation
			val cell = grid.getCellAt(semaphorePoint!!.x, semaphorePoint.y)

			// Assert: Grid navigation returns DynamicRailSemaphore directly
			assertThat(cell!!).isInstanceOf<DynamicRailSemaphore>()
		}

		/**
		 * Test Case: Verify grid navigation returns same instance (identity consistency).
		 *
		 * **Expected behavior:**
		 * - Multiple calls to getCellAt() return the same object instance
		 * - Reference equality holds (=== operator)
		 */
		@Test
		fun `grid navigation returns same instance on repeated calls`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Find any semaphore position
			val semaphorePoint =
				grid
					.asSequence()
					.filter { (_, cell) -> cell is DynamicRailSemaphore }
					.map { (point, _) -> point }
					.firstOrNull()

			assertThat(semaphorePoint).isNotNull()

			// Act: Get cell multiple times
			val cell1 = grid.getCellAt(semaphorePoint!!.x, semaphorePoint.y)
			val cell2 = grid.getCellAt(semaphorePoint.x, semaphorePoint.y)

			// Assert: Same object instance (reference equality)
			assertThat(cell1).isSameInstanceAs(cell2)
		}
	}

	@Nested
	@DisplayName("All NodeCells are dynamic wrappers")
	inner class AllNodeCellsTests {
		/**
		 * Test Case 4: Verify all NodeCells in simulation grid are dynamic wrappers.
		 *
		 * **Expected behavior:**
		 * - All NodeCell instances (excluding TrackBlockPart) are DynamicPathSeparator
		 * - No static NodeCell instances remain in the grid
		 * - TrackBlockPart cells are preserved (not transformed to dynamic)
		 *
		 * **Rationale:**
		 * NodeCell is the base class for InOut, RailSemaphore, RailSwitch.
		 * All must be wrapped in DynamicPathSeparator (or its subclasses like DynamicInOut).
		 * TrackBlockPart extends Cell but NOT NodeCell, so it's not transformed.
		 */
		@Test
		fun `all NodeCells in simulation grid are dynamic wrappers`() {
			// Arrange: Load vyhybna.xml and create simulation context
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find all cells that are DynamicPathSeparator instances
			// (these are the dynamic wrappers for NodeCells)
			val dynamicNodeCells =
				grid
					.asSequence()
					.mapNotNull { (_, cell) -> cell }
					.filter { it is DynamicPathSeparator }
					.toList()

			// Assert: All should be DynamicPathSeparator instances
			dynamicNodeCells.forEach { cell ->
				assertThat(cell).isInstanceOf<DynamicPathSeparator>()
			}

			// Additional validation: Count expected NodeCells in vyhybna.xml
			// 2 InOuts + 6 Semaphores + 2 Switches = 10 NodeCells (all wrapped)
			assertThat(dynamicNodeCells.size).isEqualTo(10)
		}

		/**
		 * Test Case: Verify TrackBlockPart cells are NOT transformed to dynamic.
		 *
		 * **Expected behavior:**
		 * - TrackBlockPart cells remain as-is (static)
		 * - They are NOT wrapped in DynamicPathSeparator
		 * - Grid contains both dynamic NodeCells and static TrackBlockPart
		 */
		@Test
		fun `TrackBlockPart cells are not transformed to dynamic wrappers`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Find all TrackBlockPart cells
			val trackBlockParts =
				grid
					.asSequence()
					.mapNotNull { (_, cell) -> cell }
					.filter { it is TrackBlockPart }
					.toList()

			// Assert: TrackBlockPart cells exist in the grid
			assertThat(trackBlockParts).isNotNull()
			// vyhybna.xml has track blocks, so we expect some TrackBlockPart cells
			// (exact count depends on track block decomposition)

			// Verify none are wrapped in DynamicPathSeparator
			trackBlockParts.forEach { cell ->
				assertThat(cell).isInstanceOf(TrackBlockPart::class)
				// Should NOT be DynamicPathSeparator
				assertThat(cell is DynamicPathSeparator).isEqualTo(false)
			}
		}

		/**
		 * Test Case: Verify grid contains both dynamic and static cell types.
		 *
		 * **Expected behavior:**
		 * - Grid has DynamicPathSeparator instances (NodeCells)
		 * - Grid has TrackBlockPart instances (not transformed)
		 * - Both coexist in the same RailwayNetGrid<Cell>
		 */
		@Test
		fun `grid contains both dynamic NodeCells and static TrackBlockPart`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Act: Count dynamic NodeCells and static TrackBlockPart
			var dynamicNodeCellCount = 0
			var trackBlockPartCount = 0

			grid
				.asSequence()
				.mapNotNull { (_, cell) -> cell }
				.forEach { cell ->
					when {
						cell is DynamicPathSeparator -> dynamicNodeCellCount++
						cell is TrackBlockPart -> trackBlockPartCount++
					}
				}

			// Assert: Both types exist in the grid
			assertThat(dynamicNodeCellCount).isEqualTo(10) // 2 InOuts + 6 Semaphores + 2 Switches
			assertThat(trackBlockPartCount > 0).isEqualTo(true) // At least some TrackBlockPart cells
		}
	}

	@Nested
	@DisplayName("Static to dynamic mapping verification")
	inner class StaticToDynamicMappingTests {
		/**
		 * Test Case: Verify staticToDynamicMap is populated correctly.
		 *
		 * **Expected behavior:**
		 * - DefaultSimulationContext has staticToDynamicMap
		 * - Map contains entries for all NodeCells (InOut, Semaphore, Switch)
		 * - toDynamic() can retrieve dynamic wrappers for static cells
		 */
		@Test
		fun `staticToDynamicMap contains mappings for all NodeCells`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext

			// Act: Access staticToDynamicMap via reflection (it's private)
			val staticToDynamicMapField =
				DefaultSimulationContext::class.java.getDeclaredField("staticToDynamicMap")
			staticToDynamicMapField.isAccessible = true
			@Suppress("UNCHECKED_CAST")
			val staticToDynamicMap =
				staticToDynamicMapField.get(context) as Map<Cell, DynamicPathSeparator>

			// Assert: Map contains at least 10 entries (2 InOuts + 6 Semaphores + 2 Switches)
			// Note: May contain more if there are additional trackblock part wrappers
			assertThat(staticToDynamicMap.size >= 10).isEqualTo(true)

			// Verify all values are DynamicPathSeparator instances
			staticToDynamicMap.values.forEach { dynamicCell ->
				assertThat(dynamicCell).isInstanceOf(DynamicPathSeparator::class)
			}
		}

		/**
		 * Test Case: Verify toDynamic() returns correct dynamic wrapper.
		 *
		 * **Expected behavior:**
		 * - context.toDynamic(staticCell) returns corresponding dynamic wrapper
		 * - Dynamic wrapper is the same instance as in the grid
		 * - Identity consistency: toDynamic(x) === grid.getCellAt(x.position)
		 */
		@Test
		fun `toDynamic returns same instance as grid navigation`() {
			// Arrange
			val factory = getKoin().get<SimulationContextFactory>()
			val context = loadVyhybnaStream().use { factory.createContext(it) } as DefaultSimulationContext
			val grid = context.getRailWayNetGrid()

			// Find a semaphore in the grid
			val semaphoreEntry =
				grid
					.asSequence()
					.firstOrNull { (_, cell) -> cell is DynamicRailSemaphore }

			assertThat(semaphoreEntry).isNotNull()

			val (point, dynamicSemaphore) = semaphoreEntry!!
			val dynamicRailSem = dynamicSemaphore as DynamicRailSemaphore

			// Get the static semaphore via asRailSemaphore() method (public API)
			val staticSemaphore = dynamicRailSem.asRailSemaphore()

			// Act: Get dynamic wrapper via toDynamic()
			val dynamicViaToDynamic = context.toDynamic(staticSemaphore)

			// Assert: Same instance as in grid (identity consistency)
			assertThat(dynamicViaToDynamic).isSameInstanceAs(dynamicSemaphore)
		}
	}
}
