/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Test Utility: Context Builder (core module version)
    Fluent API for building test contexts

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Test utility for building DefaultSimulationContext instances with fluent API.
 */
class TestContextBuilder {
	private val factory: SimulationContextFactory by getKoin().inject()

	// Use DefaultEditingContext for building the network (supports putCell/joinCells)
	private val editingContext =
		cz.vutbr.fit.interlockSim.context
			.DefaultEditingContext(30, 30)

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

	fun buildSimulationContext(): DefaultSimulationContext {
		val processFactory = getKoin().get<cz.vutbr.fit.interlockSim.context.SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	fun buildEditingContext(): DefaultEditingContext = editingContext
}

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

fun buildConnectedInOut(
	inOutName: String = "TEST_INOUT",
	isEntry: Boolean = false
): DefaultSimulationContext {
	val builder = getKoin().get<TestContextBuilder>()

	return if (isEntry) {
		builder
			.withInOut(inOutName, 1, 1, isEntry = true)
			.withSemaphore(2, 1, false)
			.withConnection(1, 1, 2, 1, 100.0, 80.0)
			.buildSimulationContext()
	} else {
		builder
			.withInOut(inOutName, 2, 1, isEntry = false)
			.withSemaphore(1, 1, false)
			.withConnection(2, 1, 1, 1, 100.0, 80.0)
			.buildSimulationContext()
	}
}
