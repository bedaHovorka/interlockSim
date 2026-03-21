package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class XmlRoundTripTest {

	private val reader = XmlContextReader()
	private val writer = XmlContextWriter()

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	@Test
	fun roundTripPreservesGridDimensions() {
		val originalXml = minimalNetworkXml()

		val ctx1 = reader.parse(originalXml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		val ctx2 = reader.parse(generatedXml)
		try {
			assertEquals(30, ctx2.getRailWayNetGrid().cols)
			assertEquals(30, ctx2.getRailWayNetGrid().rows)
		} finally {
			ctx2.close()
		}
	}

	@Test
	fun roundTripPreservesInOutCount() {
		val originalXml = minimalNetworkXml()

		val ctx1 = reader.parse(originalXml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		val ctx2 = reader.parse(generatedXml)
		try {
			assertEquals(2, ctx2.getInOuts().size)
		} finally {
			ctx2.close()
		}
	}

	@Test
	fun roundTripPreservesInOutPositionsAndNames() {
		val originalXml = minimalNetworkXml()

		val ctx1 = reader.parse(originalXml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		val ctx2 = reader.parse(generatedXml)
		try {
			val grid = ctx2.getRailWayNetGrid()
			val cellA = grid[Point(1, 1)]
			assertNotNull(cellA, "Cell at (1,1) should exist")
			assertIs<InOut>(cellA)
			assertEquals("A", cellA.getName())

			val cellB = grid[Point(5, 5)]
			assertNotNull(cellB, "Cell at (5,5) should exist")
			assertIs<InOut>(cellB)
			assertEquals("B", cellB.getName())
		} finally {
			ctx2.close()
		}
	}

	@Test
	fun roundTripPreservesRailSemaphore() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="VERTICAL" orientation="true" name="S1"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

		val ctx1 = reader.parse(xml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		val ctx2 = reader.parse(generatedXml)
		try {
			val cell = ctx2.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(cell)
			assertIs<RailSemaphore>(cell)
			assertEquals(Cell.SpatialType.VERTICAL, cell.getSpatialType())
			assertIs<OrientedPathSeparator>(cell)
			assertEquals(true, (cell as OrientedPathSeparator).getOrientation())
			assertEquals("S1", cell.getName())
		} finally {
			ctx2.close()
		}
	}

	@Test
	fun roundTripPreservesRailSwitch() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE" name="W1"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""

		val ctx1 = reader.parse(xml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		val ctx2 = reader.parse(generatedXml)
		try {
			val cell = ctx2.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(cell)
			assertIs<RailSwitch>(cell)
			assertEquals(RailSwitch.Type.SIMPLE_LEFT_FALSE, cell.type)
			assertEquals("W1", cell.getName())
		} finally {
			ctx2.close()
		}
	}

	@Test
	fun roundTripPreservesTrackBlock() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="5" toY="5"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""

		val ctx1 = reader.parse(xml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		val ctx2 = reader.parse(generatedXml)
		try {
			val graph = ctx2.getGraph()
			val edges = graph.entrySet()
			assertEquals(1, edges.size, "Should have exactly one track block")

			val entry = edges.first()
			val trackBlock = entry.value
			assertEquals(100.0, trackBlock.length())
		} finally {
			ctx2.close()
		}
	}

	@Test
	fun doubleRoundTripProducesStableXml() {
		val originalXml = minimalNetworkXml()

		// First round trip
		val ctx1 = reader.parse(originalXml)
		val xml1 = writer.generate(ctx1)
		ctx1.close()

		// Second round trip
		val ctx2 = reader.parse(xml1)
		val xml2 = writer.generate(ctx2)
		ctx2.close()

		// The XML from second round trip should be identical to first
		assertEquals(xml1, xml2, "Double round-trip should produce stable XML")
	}

	// --- Helper methods ---

	private fun minimalNetworkXml(): String = """<?xml version="1.0"?>
		<net X="30" Y="30">
			<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
		</net>"""
}
