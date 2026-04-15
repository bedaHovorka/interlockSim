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
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.deleteFile
import cz.vutbr.fit.interlockSim.util.writeTextFile
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
		XmlContextReader().parse(InlineXmlSnippets.READER_TWO_INOUTS_WITH_TRACK).use { ctx ->
			assertThat(ctx.getRailWayNetGrid().cols).isEqualTo(30)
			assertThat(ctx.getRailWayNetGrid().rows).isEqualTo(30)
			assertThat(ctx.getInOuts().size).isEqualTo(2)
		}
	}

	@Test
	fun parseNetworkWithRailSwitch() {
		XmlContextReader().parse(InlineXmlSnippets.READER_WITH_UNNAMED_SWITCH).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
		}
	}

	@Test
	fun parseNetworkWithRailSemaphore() {
		XmlContextReader().parse(InlineXmlSnippets.READER_WITH_UNNAMED_SEMAPHORE).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
		}
	}

	@Test
	fun parseNetworkWithNamedRailSemaphore() {
		XmlContextReader().parse(InlineXmlSnippets.READER_WITH_NAMED_SEMAPHORE).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
			assertThat((cell as RailSemaphore).getName()).isEqualTo("S1")
		}
	}

	@Test
	fun parseNetworkWithNamedRailSwitch() {
		XmlContextReader().parse(InlineXmlSnippets.READER_WITH_NAMED_SWITCH).use { ctx ->
			val cell = ctx.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
			assertThat((cell as RailSwitch).getName()).isEqualTo("W1")
		}
	}

	@Test
	fun parseSingleInOutNetworkIsValid() {
		XmlContextReader().parse(InlineXmlSnippets.READER_SINGLE_INOUT).use { ctx ->
			assertThat(ctx.getInOuts().size).isEqualTo(1)
		}
	}

	@Test
	fun parseGridDimensions() {
		XmlContextReader().parse(InlineXmlSnippets.READER_NON_SQUARE_GRID).use { ctx ->
			assertThat(ctx.getRailWayNetGrid().cols).isEqualTo(15)
			assertThat(ctx.getRailWayNetGrid().rows).isEqualTo(20)
		}
	}

	@Test
	fun parseInOutOrientation() {
		XmlContextReader().parse(InlineXmlSnippets.READER_TWO_INOUTS).use { ctx ->
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
		XmlContextReader().parse(InlineXmlSnippets.READER_ALL_SPATIAL_TYPES).use { ctx ->
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
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(InlineXmlSnippets.EMPTY_NET_OPEN_CLOSE, skipStructuralValidation = false)
		}
	}

	@Test
	fun acceptEmptyNetWhenStructuralValidationSkipped() {
		XmlContextReader().parse(InlineXmlSnippets.EMPTY_NET_OPEN_CLOSE, skipStructuralValidation = true).use { ctx ->
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
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(InlineXmlSnippets.ROOT_IS_OTHER)
		}
	}

	@Test
	fun rejectMissingGridAttribute() {
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(InlineXmlSnippets.NET_MISSING_Y_ATTR)
		}
	}

	@Test
	fun rejectInvalidSpatialType() {
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(InlineXmlSnippets.INOUT_INVALID_SPATIAL_TYPE)
		}
	}

	@Test
	fun rejectTrackBlockWithSameFromAndTo() {
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(InlineXmlSnippets.TRACK_FROM_EQUALS_TO)
		}
	}

	@Test
	fun rejectTrackBlockReferencingMissingCell() {
		assertFailsWith<IllegalStateException> {
			XmlContextReader().parse(InlineXmlSnippets.TRACK_REFERENCES_MISSING_CELL)
		}
	}

	@Test
	fun rejectNameTooLong() {
		val longName = "a".repeat(51)
		assertFailsWith<IllegalArgumentException> {
			XmlContextReader().parse(InlineXmlSnippets.readerNetWithSemaphoreName(longName))
		}
	}

	@Test
	fun rejectNameWithInvalidCharacters() {
		assertFailsWith<IllegalArgumentException> {
			XmlContextReader().parse(InlineXmlSnippets.readerNetWithSwitchName("bad name!"))
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
			XmlContextReader().parse(InlineXmlSnippets.readerNetWithSwitchType(type, y)).use { ctx ->
				val cell = ctx.getRailWayNetGrid()[Point(3, y)]
				assertThat(cell).isNotNull()
				assertThat(cell!!).isInstanceOf<RailSwitch>()
				assertThat((cell as RailSwitch).type).isEqualTo(RailSwitch.Type.valueOf(type))
			}
		}
	}

	@Test
	fun unknownElementsAreIgnored() {
		XmlContextReader().parse(InlineXmlSnippets.NET_WITH_UNKNOWN_CHILD_INOUT).use { ctx ->
			assertThat(ctx.getInOuts().size).isEqualTo(1)
		}
	}

	@Test
	fun parseFileLoadsContextFromDisk() {
		val path = "interlockSim-reader-test-${currentTimeMillisKMP()}.xml"
		writeTextFile(path, InlineXmlSnippets.READER_PARSE_FILE_NETWORK)
		try {
			XmlContextReader().parseFile(path).use { ctx ->
				assertThat(ctx.getRailWayNetGrid().cols).isEqualTo(20)
				assertThat(ctx.getInOuts().size).isEqualTo(2)
			}
		} finally {
			deleteFile(path)
		}
	}

	// --- Tests for new XML attributes: currentMaxSpeed, currentTrackLength, currentNameString ---

	@Test
	fun parseNetElementWithCurrentMaxSpeed() {
		XmlContextReader().parse(InlineXmlSnippets.NET_CURRENT_MAX_SPEED).use { ctx ->
			assertThat(ctx.currentMaxSpeed).isEqualTo(120.0)
		}
	}

	@Test
	fun parseNetElementWithCurrentTrackLength() {
		XmlContextReader().parse(InlineXmlSnippets.NET_CURRENT_TRACK_LENGTH).use { ctx ->
			assertThat(ctx.currentTrackLength).isEqualTo(400.0)
		}
	}

	@Test
	fun parseNetElementWithCurrentNameString() {
		XmlContextReader().parse(InlineXmlSnippets.NET_CURRENT_NAME_STRING).use { ctx ->
			assertThat(ctx.currentNameString).isEqualTo("My Network")
		}
	}

	@Test
	fun parseNetElementWithAllThreeNewAttributes() {
		XmlContextReader().parse(InlineXmlSnippets.NET_ALL_CURRENT_ATTRS).use { ctx ->
			assertThat(ctx.currentMaxSpeed).isEqualTo(90.5)
			assertThat(ctx.currentTrackLength).isEqualTo(250.0)
			assertThat(ctx.currentNameString).isEqualTo("Full Test")
		}
	}

	@Test
	fun parseNetElementWithoutNewAttributesUsesDefaults() {
		XmlContextReader().parse(InlineXmlSnippets.VALID_MINIMAL_ONE_INOUT).use { ctx ->
			// Defaults must not be changed by absent attributes
			// (exact default values are defined in DefaultEditingContext)
			assertThat(ctx.currentMaxSpeed >= 0.0).isEqualTo(true)
			assertThat(ctx.currentTrackLength >= 0.0).isEqualTo(true)
		}
	}
}
