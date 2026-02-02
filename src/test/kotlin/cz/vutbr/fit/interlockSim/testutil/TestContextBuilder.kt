/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Test Utility: Context Builder
    Fluent API for building test contexts

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Test utility for building {@link DefaultSimulationContext} instances with fluent API.
 *
 * <p>This builder uses a two-phase approach:
 * 1. Build the network structure using DefaultEditingContext (supports editing operations)
 * 2. Convert to DefaultSimulationContext for simulation (immutable structure)
 * </p>
 *
 * <p>Example usage:
 * <pre>{@code
 * DefaultSimulationContext context = get<TestContextBuilder>()
 *     .withInOut("A", 1, 1, true)
 *     .withInOut("B", 10, 10, false)
 *     .withConnection(1, 1, 10, 10, 100.0, 80.0)
 *     .build();
 * }</pre>
 *
 * @see DefaultSimulationContext
 * @see cz.vutbr.fit.interlockSim.context.DefaultEditingContext
 */
class TestContextBuilder {
	private val factory: SimulationContextFactory by getKoin().inject()

	// Use DefaultEditingContext for building the network (supports putCell/joinCells)
	private val editingContext =
		cz.vutbr.fit.interlockSim.context
			.DefaultEditingContext(30, 30)

	/**
	 * Adds an InOut (entry/exit point) to the context at specified grid position.
	 *
	 * @param name name of the InOut point
	 * @param x grid x coordinate
	 * @param y grid y coordinate
	 * @param isEntry true for entry point, false for exit point
	 * @return this builder for chaining
	 */
	fun withInOut(
		name: String,
		x: Int,
		y: Int,
		isEntry: Boolean
	): TestContextBuilder {
		val position = Point(x, y)
		val inOut =
			cz.vutbr.fit.interlockSim.objects.cells.InOut(
				name,
				!isEntry, // orientation is inverted for exit points
				cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL
			)
		editingContext.putCell(position, inOut)
		return this
	}

	/**
	 * Adds a RailSemaphore to the context at specified grid position.
	 *
	 * @param x grid x coordinate
	 * @param y grid y coordinate
	 * @param isAllowing true for allowing signal (green), false for stop (red)
	 * @return this builder for chaining
	 */
	fun withSemaphore(
		x: Int,
		y: Int,
		isAllowing: Boolean
	): TestContextBuilder {
		val position = Point(x, y)
		val semaphore =
			cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore(
				isAllowing,
				cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL
			)
		editingContext.putCell(position, semaphore)
		return this
	}

	/**
	 * Connects two cells with a SimpleTrackBlock.
	 *
	 * @param fromX source cell x coordinate
	 * @param fromY source cell y coordinate
	 * @param toX destination cell x coordinate
	 * @param toY destination cell y coordinate
	 * @param length track length in meters
	 * @param maxSpeed maximum speed in m/s
	 * @return this builder for chaining
	 */
	fun withConnection(
		fromX: Int,
		fromY: Int,
		toX: Int,
		toY: Int,
		length: Double,
		maxSpeed: Double
	): TestContextBuilder {
		val fromPoint = Point(fromX, fromY)
		val toPoint = Point(toX, toY)

		val fromCell = editingContext.getRailWayNetGrid().getCellAt(fromX, fromY)
		val toCell = editingContext.getRailWayNetGrid().getCellAt(toX, toY)

		if (fromCell == null || toCell == null) {
			throw IllegalArgumentException("Both cells must exist before connecting them")
		}

		if (fromCell !is cz.vutbr.fit.interlockSim.objects.cells.NodeCell) {
			throw IllegalArgumentException("From cell must be a NodeCell")
		}
		if (toCell !is cz.vutbr.fit.interlockSim.objects.cells.NodeCell) {
			throw IllegalArgumentException("To cell must be a NodeCell")
		}

		val trackBlock =
			cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock(
				fromCell as cz.vutbr.fit.interlockSim.objects.cells.NodeCell,
				toCell as cz.vutbr.fit.interlockSim.objects.cells.NodeCell,
				length,
				maxSpeed
			)

		editingContext.joinCells(fromPoint, toPoint, trackBlock)
		return this
	}

	/**
	 * Builds and returns the configured simulation context.
	 * Converts the editing context to a simulation context using the factory.
	 *
	 * @return configured DefaultSimulationContext instance (immutable network structure)
	 */
	fun buildSimulationContext(): DefaultSimulationContext {
		// Convert editing context to simulation context
		val processFactory = getKoin().get<cz.vutbr.fit.interlockSim.context.SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	/**
	 * Builds and returns the configured editing context.
	 * Converts the editing context to a editing context using the factory.
	 *
	 * @return configured DefaultSimulationContext instance (immutable network structure)
	 */
	fun buildEditingContext(): DefaultEditingContext {
		return editingContext
	}
}

/**
 * Creates a simple linear track matching the existing ContextTest pattern.
 * InOut "A" at (1,1), Semaphore at (4,2), InOut "B" at (5,5) connected by track.
 *
 * @return configured context with linear track
 */
fun buildLinearTrack(): DefaultSimulationContext {
	val editingFactory = getKoin().get<EditingContextFactory>()
	val simulationFactory = getKoin().get<SimulationContextFactory>()
	val editingContext = editingFactory.createEmptyContext()
	val inA =
		cz.vutbr.fit.interlockSim.objects.cells.InOut(
			"A",
			false,
			cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL
		)
	val outB =
		cz.vutbr.fit.interlockSim.objects.cells.InOut(
			"B",
			true,
			cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL
		)
	val trackBlock =
		cz.vutbr.fit.interlockSim.objects.tracks
			.SimpleTrackBlock(inA, outB, 1000.0, 80.0)

	val pA = Point(1, 1)
	val pB = Point(5, 5)
	editingContext.putCell(pA, inA)
	editingContext.putCell(pB, outB)
	editingContext.joinCells(pA, pB, trackBlock)

	// Convert to simulation context
	return simulationFactory.createContext(editingContext) as DefaultSimulationContext
}

/**
 * Creates a linear track with a semaphore between two InOut points.
 * InOut "A" at (1,1), Semaphore at (4,2), InOut "B" at (5,5).
 *
 * @return configured context with semaphore
 */
fun buildLinearTrackWithSemaphore(): DefaultSimulationContext {
	val editingFactory = getKoin().get<EditingContextFactory>()
	val simulationFactory = getKoin().get<SimulationContextFactory>()
	val editingContext = editingFactory.createEmptyContext()
	val inA =
		cz.vutbr.fit.interlockSim.objects.cells.InOut(
			"A",
			false,
			cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL
		)
	val rs1 =
		cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore(
			false,
			cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.DIAGONAL1
		)
	val outB =
		cz.vutbr.fit.interlockSim.objects.cells.InOut(
			"B",
			true,
			cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL
		)
	val trackBlock =
		cz.vutbr.fit.interlockSim.objects.tracks
			.SimpleTrackBlock(inA, outB, 1000.0, 80.0)

	val pA = Point(1, 1)
	val r1 = Point(4, 2)
	val pB = Point(5, 5)
	editingContext.putCell(pA, inA)
	editingContext.putCell(r1, rs1)
	editingContext.putCell(pB, outB)
	editingContext.joinCells(r1, pB, trackBlock)
	editingContext.joinCells(pA, r1, trackBlock)

	// Convert to simulation context
	return simulationFactory.createContext(editingContext) as DefaultSimulationContext
}

/**
 * Creates a minimal context with two InOut elements (entry and exit).
 * Updated to comply with strict validation requiring minimum 2 InOut elements.
 *
 * @return context with two InOut elements
 */
fun buildMinimalSimulation(): DefaultSimulationContext =
	getKoin()
		.get<TestContextBuilder>()
		.withInOut("A", 1, 1, true) // entry point
		.withInOut("B", 2, 1, false) // exit point
		.buildSimulationContext()

fun buildMinimalEditing(): DefaultEditingContext =
	getKoin()
		.get<TestContextBuilder>()
		.withInOut("A", 1, 1, true) // entry point
		.withInOut("B", 2, 1, false) // exit point
		.buildEditingContext()

/**
 * Creates a minimal context with a single connected InOut for testing InOutWorker.
 *
 * This creates different topologies based on whether it's an entry or exit:
 * - **Entry InOut (isEntry=true)**: Semaphore → InOut (track INTO the network)
 * - **Exit InOut (isEntry=false)**: InOut → Semaphore (track OUT OF the network)
 *
 * This ensures the InOut has a valid outgoing track section, which is required
 * by InOutWorker validation. Both entry and exit InOuts need an outgoing connection
 * from the TopologyNavigator's perspective.
 *
 * @param inOutName name for the InOut element
 * @param isEntry true for entry point, false for exit point
 * @return context with connected InOut
 */
fun buildConnectedInOut(
	inOutName: String = "TEST_INOUT",
	isEntry: Boolean = false
): DefaultSimulationContext {
	val builder = getKoin().get<TestContextBuilder>()

	// For InOutWorker to function, InOuts need a track in their movement direction.
	//
	// Entry InOut (isEntry=true, orientation=false):
	//   - direction() points INTO network (towards far end)
	//   - getNextTrackSection finds track FROM (1,1) TO (2,1)
	//   - Track connection: entry (1,1) ---> semaphore (2,1)
	//
	// Exit InOut (isEntry=false, orientation=true):
	//   - direction() points OUT OF network (away from network center)
	//   - getNextTrackSection looks for track in direction OUT
	//   - But our connection goes (1,1) ---> (2,1) which is TO the right
	//   - This might not align with the exit direction!
	//
	// SOLUTION: For exit InOut, reverse the connection OR use different topology.
	// Following vyhybna.xml pattern: exit InOut B (30,8) has track FROM it to (27,8).

	return if (isEntry) {
		// Entry point: Track goes from InOut into network
		builder
			.withInOut(inOutName, 1, 1, isEntry = true)
			.withSemaphore(2, 1, false)
			.withConnection(1, 1, 2, 1, 100.0, 80.0)
			.buildSimulationContext()
	} else {
		// Exit point: Track goes from InOut outward
		// Reverse the connection to match exit direction
		builder
			.withInOut(inOutName, 2, 1, isEntry = false)
			.withSemaphore(1, 1, false)
			.withConnection(2, 1, 1, 1, 100.0, 80.0)
			.buildSimulationContext()
	}
}

