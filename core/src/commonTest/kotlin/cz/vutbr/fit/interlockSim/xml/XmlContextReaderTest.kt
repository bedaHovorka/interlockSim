package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class XmlContextReaderTest {

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	// --- Happy path tests ---

	@Test
	fun parseMinimalNetworkWithTwoInOutsAndTrack() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="5" toY="5"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			assertEquals(30, ctx.getRailWayNetGrid().cols)
			assertEquals(30, ctx.getRailWayNetGrid().rows)
			assertEquals(2, ctx.getInOuts().size)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseNetworkWithRailSwitch() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(cell)
			assertIs<RailSwitch>(cell)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseNetworkWithRailSemaphore() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="VERTICAL" orientation="true"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(cell)
			assertIs<RailSemaphore>(cell)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseNetworkWithNamedRailSemaphore() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="HORIZONTAL" orientation="true" name="S1"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(cell)
			assertIs<RailSemaphore>(cell)
			assertEquals("S1", cell.getName())
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseNetworkWithNamedRailSwitch() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE" name="W1"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(cell)
			assertIs<RailSwitch>(cell)
			assertEquals("W1", cell.getName())
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseSingleInOutNetworkIsValid() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			assertEquals(1, ctx.getInOuts().size)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseGridDimensions() {
		val xml = """<?xml version="1.0"?>
			<net X="15" Y="20">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			assertEquals(15, ctx.getRailWayNetGrid().cols)
			assertEquals(20, ctx.getRailWayNetGrid().rows)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseInOutOrientation() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			val cellA = ctx.getRailWayNetGrid()[Point(1, 1)]
			assertNotNull(cellA)
			assertIs<InOut>(cellA)
			assertEquals("A", cellA.getName())

			val cellB = ctx.getRailWayNetGrid()[Point(5, 5)]
			assertNotNull(cellB)
			assertIs<InOut>(cellB)
			assertEquals("B", cellB.getName())
		} finally {
			ctx.close()
		}
	}

	@Test
	fun parseAllSpatialTypes() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="VERTICAL" orientation="true"/>
				<RailSemaphore X="5" Y="5" SpatialType="DIAGONAL1" orientation="false"/>
				<RailSemaphore X="7" Y="7" SpatialType="DIAGONAL2" orientation="true"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			val horiz = ctx.getRailWayNetGrid()[Point(1, 1)]
			assertNotNull(horiz)
			assertEquals(Cell.SpatialType.HORIZONTAL, horiz.getSpatialType())

			val vert = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertNotNull(vert)
			assertEquals(Cell.SpatialType.VERTICAL, vert.getSpatialType())

			val diag1 = ctx.getRailWayNetGrid()[Point(5, 5)]
			assertNotNull(diag1)
			assertEquals(Cell.SpatialType.DIAGONAL1, diag1.getSpatialType())

			val diag2 = ctx.getRailWayNetGrid()[Point(7, 7)]
			assertNotNull(diag2)
			assertEquals(Cell.SpatialType.DIAGONAL2, diag2.getSpatialType())
		} finally {
			ctx.close()
		}
	}

	// --- Validation / error tests ---

	@Test
	fun rejectEmptyNetWhenStructuralValidationEnabled() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalStateException> {
			reader.parse(xml, skipStructuralValidation = false)
		}
	}

	@Test
	fun acceptEmptyNetWhenStructuralValidationSkipped() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml, skipStructuralValidation = true)
		try {
			assertEquals(0, ctx.getInOuts().size)
		} finally {
			ctx.close()
		}
	}

	@Test
	fun rejectMalformedXml() {
		val xml = "not xml at all"
		val reader = XmlContextReader()
		assertFailsWith<Exception> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectMissingNetElement() {
		val xml = """<?xml version="1.0"?>
			<other X="10" Y="10"/>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalStateException> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectMissingGridAttribute() {
		val xml = """<?xml version="1.0"?>
			<net X="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalStateException> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectInvalidSpatialType() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="INVALID" orientation="false" name="A"/>
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalStateException> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectTrackBlockWithSameFromAndTo() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="1" toY="1"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalStateException> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectTrackBlockReferencingMissingCell() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<SimpleTrackBlock fromX="1" fromY="1" toX="9" toY="9"
					fromSegment="F" toSegment="A"
					length="100.0" maxSpeedfrom="80.0" maxSpeedto="80.0"/>
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalStateException> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectNameTooLong() {
		val longName = "a".repeat(51)
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSemaphore X="3" Y="3" SpatialType="HORIZONTAL" orientation="true" name="$longName"/>
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalArgumentException> {
			reader.parse(xml)
		}
	}

	@Test
	fun rejectNameWithInvalidCharacters() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE" name="bad name!"/>
			</net>"""
		val reader = XmlContextReader()
		assertFailsWith<IllegalArgumentException> {
			reader.parse(xml)
		}
	}

	@Test
	fun parseAllSwitchTypes() {
		val types = listOf(
			"SIMPLE_LEFT_FALSE",
			"SIMPLE_LEFT_TRUE",
			"SIMPLE_RIGHT_FALSE",
			"SIMPLE_RIGHT_TRUE"
		)
		for ((index, type) in types.withIndex()) {
			val y = 1 + index * 2
			val xml = """<?xml version="1.0"?>
				<net X="30" Y="30">
					<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
					<RailSwitch X="3" Y="$y" SpatialType="HORIZONTAL" Type="$type"/>
				</net>"""
			val reader = XmlContextReader()
			val ctx = reader.parse(xml)
			try {
				val cell = ctx.getRailWayNetGrid()[Point(3, y)]
				assertNotNull(cell, "Cell for switch type $type should exist")
				assertIs<RailSwitch>(cell)
				assertEquals(
					RailSwitch.Type.valueOf(type),
					cell.type,
					"Switch type should be $type"
				)
			} finally {
				ctx.close()
			}
		}
	}

	@Test
	fun unknownElementsAreIgnored() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<SomeUnknownElement foo="bar"/>
			</net>"""
		val reader = XmlContextReader()
		val ctx = reader.parse(xml)
		try {
			assertEquals(1, ctx.getInOuts().size)
		} finally {
			ctx.close()
		}
	}
}
