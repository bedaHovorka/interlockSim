/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.mp.KoinPlatformTools

/**
 * Common network topology fixtures for testing (core module version).
 *
 * Programmatically builds EditingContext / SimulationContext from well-known topologies.
 * XML-loading helpers live in [TestFixtures].
 */
object TestTopologies {
	fun simpleLinearPath(): EditingContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildEditingContext()

	fun linearPathWithSemaphore(semaphoreAllowing: Boolean = false): EditingContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withSemaphore(3, 3, semaphoreAllowing)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 3, 3, 100.0, 80.0)
			.withConnection(3, 3, 5, 5, 100.0, 80.0)
			.buildEditingContext()

	fun yJunctionWithSwitch(): EditingContext {
		val context = DefaultEditingContext(60, 50)

		val entry = InOut("Entry", false, Cell.SpatialType.HORIZONTAL)
		val exitMain = InOut("ExitMain", true, Cell.SpatialType.HORIZONTAL)
		val exitBranch = InOut("ExitBranch", true, Cell.SpatialType.HORIZONTAL)
		val switch = RailSwitch("Junction", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)

		context.putCell(Point(5, 30), entry)
		context.putCell(Point(30, 30), switch)
		context.putCell(Point(55, 30), exitMain)
		context.putCell(Point(50, 40), exitBranch)

		val trackEntry = SimpleTrackBlock(entry, switch, 250.0, 80.0)
		val trackMain = SimpleTrackBlock(switch, exitMain, 250.0, 100.0)
		val trackBranch = SimpleTrackBlock(switch, exitBranch, 180.0, 90.0)

		context.joinCells(Point(5, 30), Point(30, 30), trackEntry)
		context.joinCells(Point(30, 30), Point(55, 30), trackMain)
		context.joinCells(Point(30, 30), Point(50, 40), trackBranch)

		return context
	}

	/**
	 * A chain of [stageCount] binary-switch bypass stages creating exactly 2^[stageCount]
	 * switch-constrained paths from entry "A" to exit "B".
	 *
	 * **Layout per stage i (0-indexed):**
	 * - `SW{i}` — [RailSwitch] split point (SIMPLE_RIGHT_FALSE, HORIZONTAL).
	 *   From the incoming (west/A) direction it fans out to:
	 *   - east (Segment.F → main leg, directly to the merge switch), and
	 *   - southeast (Segment.G → bypass leg, through the bypass semaphore).
	 * - `BrSem{i}` — [RailSemaphore] bypass intermediate node (no path constraints).
	 * - `MrgSem{i}` — [RailSwitch] merge point (SIMPLE_LEFT_TRUE, HORIZONTAL). A plain
	 *   [RailSemaphore] cannot serve as this merge node: it exposes only 2 connection
	 *   segments (HORIZONTAL joinsOnLine = {F, A}), but the merge point needs 3 physical
	 *   connections (main leg in via A, bypass leg in via D, outgoing via F).
	 *   SIMPLE_LEFT_TRUE exposes exactly {A, D, F} for HORIZONTAL, and its confs graph
	 *   (F merging, D branch, A main) permits both A→F and D→F, so either incoming leg
	 *   can reach the outgoing connection.
	 *
	 * Each stage doubles the number of active paths, so after [stageCount] stages the
	 * total path count from "A" to "B" is 2^[stageCount].
	 *
	 * **Intended use:** performance testing of [findAllSwitchConstrainedPaths] enumeration
	 * on branchy networks.  With [stageCount] = 15 this gives 32 768 paths, which is
	 * enough to make the combinatorial enumeration cost observable while staying well
	 * below the 2^20 threshold that would make the test itself prohibitively slow.
	 *
	 * @param stageCount number of binary-switch bypass stages (1..20); default 15
	 * @return [EditingContext] with the branchy network and exactly 2 [InOut] separators
	 *   named "A" (entry) and "B" (exit)
	 */
	fun branchySwitchChain(stageCount: Int = 15): EditingContext {
		require(stageCount in 1..20) { "stageCount must be in 1..20, got $stageCount" }

		// Grid sizing: entry at x=1; each stage advances x by 10; exit adds 4 more.
		// Max x = 1 + stageCount*10 + 4; height = 20 comfortably fits y=5 and y=10 nodes.
		val gridWidth = stageCount * 10 + 20
		val gridHeight = 20
		val context = DefaultEditingContext(gridWidth, gridHeight)

		// InOut.direction() = spatialType.segments[if (orientation) 1 else 0]; for HORIZONTAL,
		// segments = [F (east), A (west)]. "A" sits at the west end of the network and must
		// point east (F) to join the first switch, so orientation = false here.
		val entry = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(1, 5), entry)

		var prevNode: NodeCell = entry
		var prevX = 1

		for (i in 0 until stageCount) {
			// Split switch: 4 units east of previous merge/entry node.
			val swX = prevX + 4
			// Bypass semaphore: 3 east, 5 south of the split switch (Segment.G = southeast).
			val brX = swX + 3
			val brY = 10
			// Merge semaphore: 6 east of the split switch (Segment.F = east/main leg).
			val mrgX = swX + 6

			val sw = RailSwitch("SW$i", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			val brSem = RailSemaphore("BrSem$i", false, Cell.SpatialType.HORIZONTAL)
			val mrgSem = RailSwitch("MrgSem$i", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_TRUE)

			context.putCell(Point(swX, 5), sw)
			context.putCell(Point(brX, brY), brSem)
			context.putCell(Point(mrgX, 5), mrgSem)

			// prevNode → sw  (incoming track from left; arriving at Segment.A on the switch)
			context.joinCells(Point(prevX, 5), Point(swX, 5), SimpleTrackBlock(prevNode, sw, 100.0, 80.0))
			// sw → mrgSem    (main leg; Segment.F east on sw, Segment.A west on mrgSem)
			context.joinCells(Point(swX, 5), Point(mrgX, 5), SimpleTrackBlock(sw, mrgSem, 100.0, 80.0))
			// sw → brSem     (bypass leg; Segment.G southeast direction)
			context.joinCells(Point(swX, 5), Point(brX, brY), SimpleTrackBlock(sw, brSem, 100.0, 80.0))
			// brSem → mrgSem (bypass completion; Segment.D southwest on mrgSem)
			context.joinCells(Point(brX, brY), Point(mrgX, 5), SimpleTrackBlock(brSem, mrgSem, 100.0, 80.0))

			prevNode = mrgSem
			prevX = mrgX
		}

		val exitX = prevX + 4
		// "B" sits at the east end of the network and must point west (A) to join the last
		// merge switch, so orientation = true here (mirrors the entry's reasoning above).
		val exit = InOut("B", true, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(exitX, 5), exit)
		context.joinCells(Point(prevX, 5), Point(exitX, 5), SimpleTrackBlock(prevNode, exit, 100.0, 80.0))

		return context
	}

	fun linearPathWithSemaphoreSequence(
		semaphoreCount: Int = 3,
		semaphoresAllowing: Boolean = false
	): EditingContext {
		require(semaphoreCount >= 1) { "semaphoreCount must be at least 1, got $semaphoreCount" }

		val gridSize = (semaphoreCount + 2) * 5 + 10
		val context = DefaultEditingContext(gridSize, gridSize)

		val inA = InOut("A", true, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(1, 5), inA)

		var previousX = 1
		var previousElement: NodeCell = inA

		for (i in 1..semaphoreCount) {
			val semaphore = RailSemaphore("Sem$i", semaphoresAllowing, Cell.SpatialType.HORIZONTAL)

			val currentX = 1 + (i * 5)
			context.putCell(Point(currentX, 5), semaphore)

			val track = SimpleTrackBlock(previousElement, semaphore, 100.0, 80.0)
			context.joinCells(Point(previousX, 5), Point(currentX, 5), track)

			previousX = currentX
			previousElement = semaphore
		}

		val exitX = 1 + ((semaphoreCount + 1) * 5)
		val outB = InOut("B", false, Cell.SpatialType.HORIZONTAL)
		context.putCell(Point(exitX, 5), outB)

		val finalTrack = SimpleTrackBlock(previousElement, outB, 100.0, 80.0)
		context.joinCells(Point(previousX, 5), Point(exitX, 5), finalTrack)

		return context
	}

	/**
	 * Linear A → Sem → B network with direction-dependent speed limits.
	 *
	 * Block1 (A→Sem): length=600m, maxSpeed from A = 60 m/s, maxSpeed from Sem = 120 m/s
	 * Block2 (Sem→B): length=900m, maxSpeed from Sem = 90 m/s, maxSpeed from B = 180 m/s
	 *
	 * Expected BY_TRAVEL_TIME cost from A to B:
	 *   Block1 = 600 / 60  = 10.0 s
	 *   Block2 = 900 / 90  = 10.0 s
	 *   Total  = 20.0 s
	 *
	 * Used to verify that [cz.vutbr.fit.interlockSim.pathfinding.DefaultRouteFinder.buildBreakdown]
	 * passes the correct directional separator to the cost function for every segment.
	 */
	fun linearPathWithAsymmetricSpeeds(): EditingContext {
		val context = DefaultEditingContext(100, 100)

		val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val sem = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)

		context.putCell(Point(5, 5), inA)
		context.putCell(Point(30, 5), sem)
		context.putCell(Point(55, 5), inB)

		// Asymmetric: different cost depending on which end the train enters from.
		// maxSpeed1 applies when the train enters from end1; maxSpeed2 from end2.
		val block1 = SimpleTrackBlock(inA, sem, 600.0, 60.0, 120.0)
		val block2 = SimpleTrackBlock(sem, inB, 900.0, 90.0, 180.0)

		context.joinCells(Point(5, 5), Point(30, 5), block1)
		context.joinCells(Point(30, 5), Point(55, 5), block2)

		return context
	}

	fun deadEndSingleInOut(): EditingContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.buildEditingContext()

	fun simpleLinearPathSimulation(): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

	fun linearPathWithSemaphoreSimulation(semaphoreAllowing: Boolean = false): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.withSemaphore(3, 3, semaphoreAllowing)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 3, 3, 100.0, 80.0)
			.withConnection(3, 3, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

	fun yJunctionWithSwitchSimulation(): SimulationContext {
		val editingContext = yJunctionWithSwitch()
		val processFactory = KoinPlatformTools.defaultContext().get().get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	fun linearPathWithSemaphoreSequenceSimulation(
		semaphoreCount: Int = 3,
		semaphoresAllowing: Boolean = false
	): SimulationContext {
		val editingContext = linearPathWithSemaphoreSequence(semaphoreCount, semaphoresAllowing)
		val processFactory = KoinPlatformTools.defaultContext().get().get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	fun deadEndSingleInOutSimulation(): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.buildSimulationContext()
}
