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

import cz.vutbr.fit.interlockSim.context.DefaultContext
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.mp.KoinPlatform.getKoin

/**
 * Test utility for building {@link DefaultContext} instances with fluent API.
 *
 * <p>This is a scaffold implementation created in Phase 1.
 * Full implementation will be provided in Phase 2 by Developer 2.</p>
 *
 * <p>Example usage (future API):
 * <pre>{@code
 * Context context = TestContextBuilder()
 *     .withSimpleTrack("T1", 100.0)
 *     .withSimpleTrack("T2", 150.0)
 *     .withConnection("T1", "T2")
 *     .withSemaphore("SEM1", "T1", Signal.GREEN)
 *     .build();
 * }</pre>
 *
 * @see DefaultContext
 */
class TestContextBuilder {
	private val context: DefaultContext

	constructor(factory: XMLContextFactory) {
		// DefaultContext is abstract, use factory to create concrete instance
		this.context = factory.createEmptyContext()
	}

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
				cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL
			)
		context.putCell(position, inOut)
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
				cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL
			)
		context.putCell(position, semaphore)
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

		val fromCell = context.getRailWayNetGrid().getCellAt(fromX, fromY)
		val toCell = context.getRailWayNetGrid().getCellAt(toX, toY)

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

		context.joinCells(fromPoint, toPoint, trackBlock)
		return this
	}

	/**
	 * Builds and returns the configured context.
	 *
	 * @return configured DefaultContext instance
	 */
	fun build(): DefaultContext = context

	// Static factory methods for common test scenarios

	companion object {
		/**
		 * Creates a simple linear track matching the existing ContextTest pattern.
		 * InOut "A" at (1,1), Semaphore at (4,2), InOut "B" at (5,5) connected by track.
		 *
		 * @return configured context with linear track
		 */
		fun buildLinearTrack(): DefaultContext {
			val context = getKoin().get<XMLContextFactory>().createEmptyContext()
			val inA =
				cz.vutbr.fit.interlockSim.objects.cells.InOut(
					"A",
					false,
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL
				)
			val outB =
				cz.vutbr.fit.interlockSim.objects.cells.InOut(
					"B",
					true,
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL
				)
			val trackBlock =
				cz.vutbr.fit.interlockSim.objects.tracks
					.SimpleTrackBlock(inA, outB, 1000.0, 80.0)

			val pA = Point(1, 1)
			val pB = Point(5, 5)
			context.putCell(pA, inA)
			context.putCell(pB, outB)
			context.joinCells(pA, pB, trackBlock)
			return context
		}

		/**
		 * Creates a linear track with a semaphore between two InOut points.
		 * InOut "A" at (1,1), Semaphore at (4,2), InOut "B" at (5,5).
		 *
		 * @return configured context with semaphore
		 */
		fun buildLinearTrackWithSemaphore(): DefaultContext {
			val context = getKoin().get<XMLContextFactory>().createEmptyContext()
			val inA =
				cz.vutbr.fit.interlockSim.objects.cells.InOut(
					"A",
					false,
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL
				)
			val rs1 =
				cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore(
					false,
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.DIAGONAL1
				)
			val outB =
				cz.vutbr.fit.interlockSim.objects.cells.InOut(
					"B",
					true,
					cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType.HORIZONTAL
				)
			val trackBlock =
				cz.vutbr.fit.interlockSim.objects.tracks
					.SimpleTrackBlock(inA, outB, 1000.0, 80.0)

			val pA = Point(1, 1)
			val r1 = Point(4, 2)
			val pB = Point(5, 5)
			context.putCell(pA, inA)
			context.putCell(r1, rs1)
			context.putCell(pB, outB)
			context.joinCells(r1, pB, trackBlock)
			context.joinCells(pA, r1, trackBlock)
			return context
		}

		/**
		 * Creates an empty context with just one InOut for minimal testing.
		 *
		 * @return context with single InOut
		 */
		fun buildMinimal(): DefaultContext {
			val factory = getKoin().get<XMLContextFactory>()
			return TestContextBuilder(factory)
				.withInOut("A", 1, 1, false)
				.build()
		}
	}
}
