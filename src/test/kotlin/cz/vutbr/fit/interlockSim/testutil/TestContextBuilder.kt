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

import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
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
	private val factory: XMLContextFactory by getKoin().inject()
	// Use DefaultEditingContext for building the network (supports putCell/joinCells)
	private val editingContext = cz.vutbr.fit.interlockSim.context.DefaultEditingContext(30, 30)

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
	fun build(): DefaultSimulationContext {
		// Convert editing context to simulation context
		val processFactory = getKoin().get<cz.vutbr.fit.interlockSim.context.SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}
}

/**
 * Creates a simple linear track matching the existing ContextTest pattern.
 * InOut "A" at (1,1), Semaphore at (4,2), InOut "B" at (5,5) connected by track.
 *
 * @return configured context with linear track
 */
fun buildLinearTrack(): DefaultSimulationContext {
	val factory = getKoin().get<XMLContextFactory>()
	val editingContext = factory.createEmptyContext()
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
	return factory.createContext(editingContext) as DefaultSimulationContext
}

/**
 * Creates a linear track with a semaphore between two InOut points.
 * InOut "A" at (1,1), Semaphore at (4,2), InOut "B" at (5,5).
 *
 * @return configured context with semaphore
 */
fun buildLinearTrackWithSemaphore(): DefaultSimulationContext {
	val factory = getKoin().get<XMLContextFactory>()
	val editingContext = factory.createEmptyContext()
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
	return factory.createContext(editingContext) as DefaultSimulationContext
}

/**
 * Creates a minimal context with two InOut elements (entry and exit).
 * Updated to comply with strict validation requiring minimum 2 InOut elements.
 *
 * @return context with two InOut elements
 */
fun buildMinimal(): DefaultSimulationContext =
	getKoin()
		.get<TestContextBuilder>()
		.withInOut("A", 1, 1, true) // entry point
		.withInOut("B", 2, 1, false) // exit point
		.build()
