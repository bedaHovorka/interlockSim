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
import org.koin.java.KoinJavaComponent.getKoin
import java.io.InputStream

/**
 * Centralized test fixture repository (core module version).
 *
 * Provides consistent access to XML configuration files and common network topologies.
 */
object TestFixtures {
	fun loadShuntingXml(): InputStream = loadMainResource("vyhybna.xml")

	fun loadLinearTrackXml(): InputStream = loadTestFixture("linear-track.xml")

	fun loadMinimalNetworkXml(): InputStream = loadTestFixture("minimal-network.xml")

	fun loadSwitchBasicXml(): InputStream = loadTestFixture("switch-basic.xml")

	fun loadSemaphoreBasicXml(): InputStream = loadTestFixture("semaphore-basic.xml")

	fun loadTwoTracksParallelXml(): InputStream = loadTestFixture("two-tracks-parallel.xml")

	fun loadEmptyGridXml(): InputStream = loadTestFixture("empty-grid.xml")

	fun loadPrahaHlavniNadraziXml(): InputStream = loadTestFixture("praha-hlavni-nadrazi.xml")

	fun loadRudyUjezdXml(): InputStream = loadTestFixture("rudyUjezd.xml")

	fun loadLegacyNetworkNoNamesXml(): InputStream = loadTestFixture("legacy-network-no-names.xml")

	fun loadValidSpecialCharsNamesXml(): InputStream = loadTestFixture("valid-special-chars-names.xml")

	fun loadInvalidMalformedXml(): InputStream = loadTestFixture("invalid-malformed-xml.xml")

	fun loadInvalidMissingGridSizeXml(): InputStream = loadTestFixture("invalid-missing-grid-size.xml")

	fun loadInvalidMissingSpatialTypeXml(): InputStream = loadTestFixture("invalid-missing-spatial-type.xml")

	fun loadInvalidWrongRootElementXml(): InputStream = loadTestFixture("invalid-wrong-root-element.xml")

	fun loadInvalidNameSpecialCharsXml(): InputStream = loadTestFixture("invalid-name-special-chars.xml")

	fun loadInvalidNameTooLongXml(): InputStream = loadTestFixture("invalid-name-too-long.xml")

	fun loadSwitchBetweenSemaphoresXml(): InputStream = loadTestFixture("switch-between-semaphores.xml")

	fun loadInvalidInOutXml(fixtureName: String): InputStream = loadTestFixture(fixtureName)

	private fun loadMainResource(resourceName: String): InputStream =
		javaClass.getResourceAsStream(
			"/cz/vutbr/fit/interlockSim/resource/$resourceName"
		) ?: error("Resource not found in main resources: $resourceName")

	private fun loadTestFixture(fixtureFilename: String): InputStream =
		javaClass.getResourceAsStream(
			"/cz/vutbr/fit/interlockSim/xml/fixtures/$fixtureFilename"
		) ?: error("Test fixture not found: $fixtureFilename")
}

/**
 * Common network topology fixtures for testing (core module version).
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
		val switch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		switch.setName("Junction")

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
			val semaphore = RailSemaphore(semaphoresAllowing, Cell.SpatialType.HORIZONTAL)
			semaphore.setName("Sem$i")

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
		val processFactory = getKoin().get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	fun linearPathWithSemaphoreSequenceSimulation(
		semaphoreCount: Int = 3,
		semaphoresAllowing: Boolean = false
	): SimulationContext {
		val editingContext = linearPathWithSemaphoreSequence(semaphoreCount, semaphoresAllowing)
		val processFactory = getKoin().get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}

	fun deadEndSingleInOutSimulation(): SimulationContext =
		TestContextBuilder()
			.withInOut("A", 1, 1, true)
			.buildSimulationContext()
}
