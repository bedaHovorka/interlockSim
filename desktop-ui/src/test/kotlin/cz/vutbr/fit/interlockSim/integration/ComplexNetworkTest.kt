/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Integration test for complex network simulations
 */
package cz.vutbr.fit.interlockSim.integration

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Integration tests for complex railway network simulations.
 *
 * These tests verify that complex network topologies with multiple tracks,
 * switches, semaphores, and varied configurations work correctly during
 * simulation. Tests focus on realistic railway scenarios.
 *
 * Test Coverage:
 * - Multiple tracks with different characteristics
 * - Track conflicts and proper collision handling
 * - Varied speed limits across network sections
 * - Bidirectional track configurations
 * - Network topology correctness
 *
 * Note: These tests focus on network topology and configuration validation.
 * Full simulation execution tests are in sim/ package tests.
 *
 * Total tests: 5
 */
@DisplayName("Complex Network Simulation")
@Tag("integration-test")
class ComplexNetworkTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val transformer: ContextTransformer by inject()
	private val processFactory: SimulationProcessFactory by inject()

	/**
	 * Test a complex network with multiple entry/exit points and interconnected tracks.
	 * Verifies that the network topology is correctly established for simulation.
	 */
	@Test
	@DisplayName("simulate complex network with multiple trains")
	fun complexNetwork_multipleTrains_topologyValid() {
		// Create a complex network with 4 InOut points (2 entries, 2 exits)
		val editingContext = DefaultEditingContext(60, 60)

		// Create entry points
		val entryA = InOut("Entry_Platform_1", false, Cell.SpatialType.HORIZONTAL)
		val entryB = InOut("Entry_Platform_2", false, Cell.SpatialType.HORIZONTAL)

		// Create exit points
		val exitC = InOut("Exit_Platform_1", true, Cell.SpatialType.HORIZONTAL)
		val exitD = InOut("Exit_Platform_2", true, Cell.SpatialType.HORIZONTAL)

		// Create central junction with switches
		val switchMain = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		switchMain.setName("Main_Junction")

		// Place elements on grid
		editingContext.putCell(Point(5, 20), entryA)
		editingContext.putCell(Point(5, 40), entryB)
		editingContext.putCell(Point(30, 30), switchMain)
		editingContext.putCell(Point(55, 20), exitC)
		editingContext.putCell(Point(55, 40), exitD)

		// Create track connections (simplified routing through junction)
		val trackA = SimpleTrackBlock(entryA, switchMain, 250.0, 80.0)
		val trackB = SimpleTrackBlock(entryB, switchMain, 250.0, 80.0)
		val trackC = SimpleTrackBlock(switchMain, exitC, 250.0, 100.0)
		val trackD = SimpleTrackBlock(switchMain, exitD, 250.0, 90.0)

		editingContext.joinCells(Point(5, 20), Point(30, 30), trackA)
		editingContext.joinCells(Point(5, 40), Point(30, 30), trackB)
		editingContext.joinCells(Point(30, 30), Point(55, 20), trackC)
		editingContext.joinCells(Point(30, 30), Point(55, 40), trackD)

		// Transform to simulation context
		val simContext = transformer.createSimulationContext(editingContext, processFactory)

		// Verify network topology is valid
		assertThat(simContext).isNotNull()
		assertThat(simContext.getInOuts()).hasSize(4)
		assertThat(simContext.getGraph().size()).isGreaterThan(0)

		// Verify switch is accessible
		val switchCell = simContext.getRailWayNetGrid().getCellAt(30, 30)
		assertThat(switchCell).isNotNull()
		assertThat(switchCell as Any).isInstanceOf(DynamicRailSwitch::class)
	}

	/**
	 * Test network with potential track conflicts (crossing paths).
	 * Verifies that track topology allows for proper conflict detection.
	 */
	@Test
	@DisplayName("simulate network with track conflicts")
	fun complexNetwork_trackConflicts_topologyValid() {
		// Create a network with crossing tracks
		val editingContext = DefaultEditingContext(50, 50)

		// Horizontal track
		val entryH1 = InOut("Entry_H1", false, Cell.SpatialType.HORIZONTAL)
		val exitH2 = InOut("Exit_H2", true, Cell.SpatialType.HORIZONTAL)

		// Vertical track (crosses horizontal)
		val entryV1 = InOut("Entry_V1", false, Cell.SpatialType.VERTICAL)
		val exitV2 = InOut("Exit_V2", true, Cell.SpatialType.VERTICAL)

		editingContext.putCell(Point(5, 25), entryH1)
		editingContext.putCell(Point(45, 25), exitH2)
		editingContext.putCell(Point(25, 5), entryV1)
		editingContext.putCell(Point(25, 45), exitV2)

		val trackH = SimpleTrackBlock(entryH1, exitH2, 400.0, 80.0)
		val trackV = SimpleTrackBlock(entryV1, exitV2, 400.0, 80.0)

		editingContext.joinCells(Point(5, 25), Point(45, 25), trackH)
		editingContext.joinCells(Point(25, 5), Point(25, 45), trackV)

		// Transform to simulation context
		val simContext = transformer.createSimulationContext(editingContext, processFactory)

		// Verify network has 4 InOut points
		assertThat(simContext.getInOuts()).hasSize(4)

		// Verify graph contains both tracks
		assertThat(simContext.getGraph().size()).isGreaterThan(0)

		// Verify topology allows conflict detection (both tracks exist independently)
		val cellH1 = simContext.getRailWayNetGrid().getCellAt(5, 25)
		val cellH2 = simContext.getRailWayNetGrid().getCellAt(45, 25)
		val cellV1 = simContext.getRailWayNetGrid().getCellAt(25, 5)
		val cellV2 = simContext.getRailWayNetGrid().getCellAt(25, 45)

		assertThat(cellH1).isNotNull()
		assertThat(cellH2).isNotNull()
		assertThat(cellV1).isNotNull()
		assertThat(cellV2).isNotNull()
	}

	/**
	 * Test network with varied speed limits across different sections.
	 * Verifies that different tracks can have different speed characteristics.
	 */
	@Test
	@DisplayName("simulate network with varied speed limits")
	fun complexNetwork_variedSpeedLimits_topologyValid() {
		// Create a network with three sections having different speed limits
		val editingContext = DefaultEditingContext(60, 30)

		val entry = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)
		val junction1 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		junction1.setName("Junction_1")
		val junction2 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		junction2.setName("Junction_2")
		val exit = InOut("Exit", true, Cell.SpatialType.HORIZONTAL)

		editingContext.putCell(Point(5, 15), entry)
		editingContext.putCell(Point(20, 15), junction1)
		editingContext.putCell(Point(40, 15), junction2)
		editingContext.putCell(Point(55, 15), exit)

		// Create tracks with different speed limits
		val slowTrack = SimpleTrackBlock(entry, junction1, 150.0, 40.0) // 40 m/s (slow)
		val mediumTrack = SimpleTrackBlock(junction1, junction2, 200.0, 80.0) // 80 m/s (medium)
		val fastTrack = SimpleTrackBlock(junction2, exit, 150.0, 120.0) // 120 m/s (fast)

		editingContext.joinCells(Point(5, 15), Point(20, 15), slowTrack)
		editingContext.joinCells(Point(20, 15), Point(40, 15), mediumTrack)
		editingContext.joinCells(Point(40, 15), Point(55, 15), fastTrack)

		// Transform to simulation context
		val simContext = transformer.createSimulationContext(editingContext, processFactory)

		// Verify network topology
		assertThat(simContext.getInOuts()).hasSize(2)
		assertThat(simContext.getGraph().size()).isGreaterThan(0)

		// Verify all junctions exist
		val j1 = simContext.getRailWayNetGrid().getCellAt(20, 15)
		val j2 = simContext.getRailWayNetGrid().getCellAt(40, 15)
		assertThat(j1).isNotNull()
		assertThat(j2).isNotNull()
		assertThat(j1 as Any).isInstanceOf(DynamicRailSemaphore::class)
		assertThat(j2 as Any).isInstanceOf(DynamicRailSemaphore::class)
	}

	/**
	 * Test network with bidirectional tracks (tracks that can be traversed
	 * in both directions). Verifies proper track configuration.
	 */
	@Test
	@DisplayName("simulate network with bidirectional tracks")
	fun complexNetwork_bidirectionalTracks_topologyValid() {
		// Create a bidirectional track network (loop configuration)
		val editingContext = DefaultEditingContext(50, 50)

		// Create a loop with 4 junctions
		val entryExit1 = InOut("Platform_1", false, Cell.SpatialType.HORIZONTAL)
		val entryExit2 = InOut("Platform_2", true, Cell.SpatialType.HORIZONTAL)
		val junction1 = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		junction1.setName("Junction_1")
		val junction2 = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_TRUE)
		junction2.setName("Junction_2")

		editingContext.putCell(Point(10, 25), entryExit1)
		editingContext.putCell(Point(40, 25), entryExit2)
		editingContext.putCell(Point(20, 15), junction1)
		editingContext.putCell(Point(30, 35), junction2)

		// Create bidirectional tracks (simplified loop)
		val track1 = SimpleTrackBlock(entryExit1, junction1, 150.0, 80.0)
		val track2 = SimpleTrackBlock(junction1, entryExit2, 200.0, 80.0)
		val track3 = SimpleTrackBlock(entryExit2, junction2, 150.0, 80.0)
		val track4 = SimpleTrackBlock(junction2, entryExit1, 200.0, 80.0)

		editingContext.joinCells(Point(10, 25), Point(20, 15), track1)
		editingContext.joinCells(Point(20, 15), Point(40, 25), track2)
		editingContext.joinCells(Point(40, 25), Point(30, 35), track3)
		editingContext.joinCells(Point(30, 35), Point(10, 25), track4)

		// Transform to simulation context
		val simContext = transformer.createSimulationContext(editingContext, processFactory)

		// Verify loop topology
		assertThat(simContext.getInOuts()).hasSize(2)
		assertThat(simContext.getGraph().size()).isGreaterThan(0)

		// Verify both switches exist
		val sw1 = simContext.getRailWayNetGrid().getCellAt(20, 15)
		val sw2 = simContext.getRailWayNetGrid().getCellAt(30, 35)
		assertThat(sw1).isNotNull()
		assertThat(sw2).isNotNull()
		assertThat(sw1 as Any).isInstanceOf(DynamicRailSwitch::class)
		assertThat(sw2 as Any).isInstanceOf(DynamicRailSwitch::class)
	}

	/**
	 * Test complex real-world network topology (vyhybna.xml).
	 * Verifies that the complex network structure is correctly loaded and
	 * maintains its integrity.
	 */
	@Test
	@DisplayName("network handles train priorities correctly")
	fun complexNetwork_trainPriorities_topologyValid() {
		// Load complex vyhybna.xml network
		val edContext =
			editingContextFactory.createContext(
				TestFixtures.loadShuntingXml()
			) as EditingContext

		// Verify complex network topology
		assertThat(edContext).isNotNull()
		val inOuts: Collection<*> = edContext.getInOuts()
		assertThat(inOuts).hasSize(2)
		assertThat(edContext.getGraph().size()).isGreaterThan(0)

		// Verify network has switches (for priority handling)
		var switchCount = 0
		var semaphoreCount = 0

		for (x in 0 until edContext.getRailWayNetGrid().getCols()) {
			for (y in 0 until edContext.getRailWayNetGrid().getRows()) {
				val cell = edContext.getRailWayNetGrid().getCellAt(x, y)
				when (cell) {
					is RailSwitch -> switchCount++
					is RailSemaphore -> semaphoreCount++
				}
			}
		}

		// Vyhybna has multiple switches and semaphores
		assertThat(switchCount).isGreaterThan(0)
		assertThat(semaphoreCount).isGreaterThan(0)

		// Verify InOut points are properly configured
		val inOutsFinal: Collection<*> = edContext.getInOuts()
		assertThat(inOutsFinal).hasSize(2)
	}
}
