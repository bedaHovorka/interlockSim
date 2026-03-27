package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class XmlContextWriterTest {

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	@Test
	fun generateProducesXmlDeclarationAndDoctype() {
		createMinimalContext().use { ctx ->
			val xml = XmlContextWriter().generate(ctx)
			assertThat(xml).startsWith("<?xml version=\"1.0\"?>")
			assertThat(xml).contains("<!DOCTYPE net>")
		}
	}

	@Test
	fun generateProducesRootElementWithDimensions() {
		val ctx = DefaultEditingContext(20, 25)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("<net X=\"20\" Y=\"25\"")
			assertThat(xml).contains("</net>")
		}
	}

	@Test
	fun generateContainsInOutElements() {
		createMinimalContext().use { ctx ->
			val xml = XmlContextWriter().generate(ctx)
			assertThat(xml).contains("<InOut ")
			assertThat(xml).contains("name=\"A\"")
			assertThat(xml).contains("name=\"B\"")
			assertThat(xml).contains("SpatialType=\"HORIZONTAL\"")
		}
	}

	@Test
	fun generateContainsOrientationForInOut() {
		createMinimalContext().use { ctx ->
			val xml = XmlContextWriter().generate(ctx)
			assertThat(xml).contains("orientation=\"false\"")
			assertThat(xml).contains("orientation=\"true\"")
		}
	}

	@Test
	fun generateContainsRailSemaphore() {
		val ctx = DefaultEditingContext(30, 30)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.putCell(Point(3, 3), RailSemaphore("S1", true, Cell.SpatialType.VERTICAL))
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("<RailSemaphore ")
			assertThat(xml).contains("SpatialType=\"VERTICAL\"")
			assertThat(xml).contains("orientation=\"true\"")
			assertThat(xml).contains("name=\"S1\"")
		}
	}

	@Test
	fun generateContainsRailSwitch() {
		val ctx = DefaultEditingContext(30, 30)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.putCell(Point(5, 5), RailSwitch("W1", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_FALSE))
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("<RailSwitch ")
			assertThat(xml).contains("Type=\"SIMPLE_LEFT_FALSE\"")
			assertThat(xml).contains("name=\"W1\"")
		}
	}

	@Test
	fun generateContainsTrackBlockWhenCellsAreJoined() {
		createContextWithTrack().use { ctx ->
			val xml = XmlContextWriter().generate(ctx)
			assertThat(xml).contains("<SimpleTrackBlock ")
			assertThat(xml).contains("fromX=")
			assertThat(xml).contains("toX=")
			assertThat(xml).contains("length=")
			assertThat(xml).contains("maxSpeedfrom=")
			assertThat(xml).contains("maxSpeedto=")
		}
	}

	@Test
	fun generateOmitsNameForUnnamedSemaphore() {
		val ctx = DefaultEditingContext(30, 30)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.putCell(Point(3, 3), RailSemaphore(true, Cell.SpatialType.HORIZONTAL))
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("<RailSemaphore ")
		}
	}

	@Test
	fun generateProducesTabIndentedElements() {
		createMinimalContext().use { ctx ->
			val xml = XmlContextWriter().generate(ctx)
			val inOutLines = xml.lines().filter { it.contains("<InOut ") }
			assertThat(inOutLines).isNotEmpty()
			for (line in inOutLines) {
				assertThat(line.startsWith("\t")).isTrue()
			}
		}
	}

	@Test
	fun generateIncludesCurrentMaxSpeedInNetElement() {
		val ctx = DefaultEditingContext(20, 20)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.currentMaxSpeed = 120.0
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("currentMaxSpeed=\"120.0\"")
		}
	}

	@Test
	fun generateIncludesCurrentTrackLengthInNetElement() {
		val ctx = DefaultEditingContext(20, 20)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.currentTrackLength = 250.5
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("currentTrackLength=\"250.5\"")
		}
	}

	@Test
	fun generateIncludesCurrentNameStringWhenNonEmpty() {
		val ctx = DefaultEditingContext(20, 20)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.currentNameString = "Test Network"
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml).contains("currentNameString=\"Test Network\"")
		}
	}

	@Test
	fun generateOmitsCurrentNameStringWhenEmpty() {
		val ctx = DefaultEditingContext(20, 20)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		// currentNameString is empty by default
		ctx.use {
			val xml = XmlContextWriter().generate(it)
			assertThat(xml.contains("currentNameString")).isFalse()
		}
	}

	// --- Helper methods ---

	private fun createMinimalContext(): DefaultEditingContext {
		val ctx = DefaultEditingContext(30, 30)
		ctx.putCell(Point(1, 1), InOut("A", false, Cell.SpatialType.HORIZONTAL))
		ctx.putCell(Point(5, 5), InOut("B", true, Cell.SpatialType.HORIZONTAL))
		return ctx
	}

	private fun createContextWithTrack(): DefaultEditingContext {
		val ctx = DefaultEditingContext(30, 30)
		val inOutA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
		val inOutB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
		ctx.putCell(Point(1, 1), inOutA)
		ctx.putCell(Point(5, 1), inOutB)
		val trackBlock = SimpleTrackBlock(inOutA, inOutB, 100.0, 80.0, 80.0)
		ctx.hardJoin(
			Cell.Segment.F, Cell.Segment.A,
			Point(1, 1), Point(5, 1), trackBlock
		)
		return ctx
	}
}
