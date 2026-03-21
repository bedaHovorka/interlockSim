package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
		XmlContextReader().parse(xml).use { ctx ->
			assertThat(ctx.getRailWayNetGrid().cols).isEqualTo(30)
			assertThat(ctx.getRailWayNetGrid().rows).isEqualTo(30)
			assertThat(ctx.getInOuts().size).isEqualTo(2)
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
		XmlContextReader().parse(xml).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
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
		XmlContextReader().parse(xml).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
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
		XmlContextReader().parse(xml).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
			assertThat((cell as RailSemaphore).getName()).isEqualTo("S1")
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
		XmlContextReader().parse(xml).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
			assertThat((cell as RailSwitch).getName()).isEqualTo("W1")
		}
	}

	@Test
	fun parseSingleInOutNetworkIsValid() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		XmlContextReader().parse(xml).use { ctx ->
			assertThat(ctx.getInOuts().size).isEqualTo(1)
		}
	}

	@Test
	fun parseGridDimensions() {
		val xml = """<?xml version="1.0"?>
			<net X="15" Y="20">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		XmlContextReader().parse(xml).use { ctx ->
			assertThat(ctx.getRailWayNetGrid().cols).isEqualTo(15)
			assertThat(ctx.getRailWayNetGrid().rows).isEqualTo(20)
		}
	}

	@Test
	fun parseInOutOrientation() {
		val xml = """<?xml version="1.0"?>
			<net X="30" Y="30">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<InOut X="5" Y="5" SpatialType="HORIZONTAL" orientation="true" name="B"/>
			</net>"""
		XmlContextReader().parse(xml).use { ctx ->
			val cellA = ctx.getRailWayNetGrid()[Point(1, 1)]
			assertThat(cellA).isNotNull().isInstanceOf<InOut>()
			assertThat((cellA as InOut).getName()).isEqualTo("A")

			val cellB = ctx.getRailWayNetGrid()[Point(5, 5)]
			assertThat(cellB).isNotNull().isInstanceOf<InOut>()
			assertThat((cellB as InOut).getName()).isEqualTo("B")
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
		XmlContextReader().parse(xml).use { ctx ->
			val horiz = ctx.getRailWayNetGrid()[Point(1, 1)]
			assertThat(horiz).isNotNull()
			assertThat(horiz!!.getSpatialType()).isEqualTo(Cell.SpatialType.HORIZONTAL)

			val vert = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(vert).isNotNull()
			assertThat(vert!!.getSpatialType()).isEqualTo(Cell.SpatialType.VERTICAL)

			val diag1 = ctx.getRailWayNetGrid()[Point(5, 5)]
			assertThat(diag1).isNotNull()
			assertThat(diag1!!.getSpatialType()).isEqualTo(Cell.SpatialType.DIAGONAL1)

			val diag2 = ctx.getRailWayNetGrid()[Point(7, 7)]
			assertThat(diag2).isNotNull()
			assertThat(diag2!!.getSpatialType()).isEqualTo(Cell.SpatialType.DIAGONAL2)
		}
	}

	// --- Validation / error tests ---

	@Test
	fun rejectEmptyNetWhenStructuralValidationEnabled() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
			</net>"""
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(xml, skipStructuralValidation = false)
		}
	}

	@Test
	fun acceptEmptyNetWhenStructuralValidationSkipped() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
			</net>"""
		XmlContextReader().parse(xml, skipStructuralValidation = true).use { ctx ->
			assertThat(ctx.getInOuts().size).isEqualTo(0)
		}
	}

	@Test
	fun rejectMalformedXml() {
		assertFailsWith<Exception> {
			XmlContextReader().parse("not xml at all")
		}
	}

	@Test
	fun rejectMissingNetElement() {
		val xml = """<?xml version="1.0"?>
			<other X="10" Y="10"/>"""
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(xml)
		}
	}

	@Test
	fun rejectMissingGridAttribute() {
		val xml = """<?xml version="1.0"?>
			<net X="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
			</net>"""
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(xml)
		}
	}

	@Test
	fun rejectInvalidSpatialType() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="INVALID" orientation="false" name="A"/>
			</net>"""
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(xml)
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
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(xml)
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
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(xml)
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
		assertFailsWith<IllegalArgumentException> {
			XmlContextReader().parse(xml)
		}
	}

	@Test
	fun rejectNameWithInvalidCharacters() {
		val xml = """<?xml version="1.0"?>
			<net X="10" Y="10">
				<InOut X="1" Y="1" SpatialType="HORIZONTAL" orientation="false" name="A"/>
				<RailSwitch X="3" Y="3" SpatialType="HORIZONTAL" Type="SIMPLE_LEFT_FALSE" name="bad name!"/>
			</net>"""
		assertFailsWith<IllegalArgumentException> {
			XmlContextReader().parse(xml)
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
			XmlContextReader().parse(xml).use { ctx ->
				val cell = ctx.getRailWayNetGrid()[Point(3, y)]
				assertThat(cell).isNotNull()
				assertThat(cell!!).isInstanceOf<RailSwitch>()
				assertThat((cell as RailSwitch).type).isEqualTo(RailSwitch.Type.valueOf(type))
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
		XmlContextReader().parse(xml).use { ctx ->
			assertThat(ctx.getInOuts().size).isEqualTo(1)
		}
	}
}
