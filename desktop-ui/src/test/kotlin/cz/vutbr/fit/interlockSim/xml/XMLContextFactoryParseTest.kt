/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: XML Parsing & Configuration Tests
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.INOUT_KEY
import cz.vutbr.fit.interlockSim.testutil.RAIL_SEMAPHORE_KEY
import cz.vutbr.fit.interlockSim.testutil.RAIL_SWITCH_KEY
import cz.vutbr.fit.interlockSim.testutil.assertRudyUjezdStationInOuts
import cz.vutbr.fit.interlockSim.testutil.countCellTypes
import cz.vutbr.fit.interlockSim.testutil.existPath
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Parse tests for [XMLContextFactory]: factory basics and empty-context creation,
 * fixture loading, grid size, cell presence, and invalid-input handling.
 *
 * Test Fixtures (src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/):
 * - minimal-network.xml - Minimal valid network (2 InOut nodes, no tracks)
 * - linear-track.xml - Two InOut nodes connected by SimpleTrackBlock
 * - switch-basic.xml - RailSwitch with two output tracks
 * - semaphore-basic.xml - RailSemaphore between two InOut nodes
 * - two-tracks-parallel.xml - Two parallel independent tracks
 * - empty-grid.xml - Grid with minimal elements (2 InOut nodes, no tracks)
 * - rudyUjezd.xml - Full station topology (Rudy Ujezd)
 * - invalid-*.xml - Various malformed/invalid XML files
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryParseTest : XMLContextFactoryTestBase() {
	@Test
	fun factoryInjection_always_returnsSameInstance() {
		// Access factory multiple times through Koin injection
		val instance1 = editingContextFactory
		val instance2 = editingContextFactory

		assertThat(instance1)
			.isNotNull()
			.withMessage("Factory instance should not be null")
			.isSameInstanceAs(instance2)
	}

	@Test
	fun createEmptyContext_always_returns100x100Grid() {
		editingContextFactory.createEmptyContext().use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(100)
			assertThat(grid.rows).isEqualTo(100)
		}
	}

	@Test
	fun createEmptyContext_always_hasEmptyGraph() {
		editingContextFactory.createEmptyContext().use { context ->
			assertThat(context.getGraph().nodeSet())
				.withMessage("Empty context should have empty graph")
				.isEmpty()
		}
	}

	@Test
	fun parseXML_minimalNetwork_createsTwoInOuts() {
		val xml = getFixtureStream("minimal-network.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			val cellA = grid.getCellAt(10, 10)
			val cellB = grid.getCellAt(20, 10)
			assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
			assertThat((cellA as InOut).getName()).isEqualTo("A")
			assertThat((cellB as InOut).getName()).isEqualTo("B")
		}
	}

	@Test
	fun parseXML_linearTrack_createsTwoConnectedInOuts() {
		val xml = getFixtureStream("linear-track.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val cellA = context.getRailWayNetGrid().getCellAt(10, 10)
			val cellB = context.getRailWayNetGrid().getCellAt(20, 10)
			assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
			assertThat((cellA as InOut).getName()).isEqualTo("A")
			assertThat((cellB as InOut).getName()).isEqualTo("B")
		}
	}

	@Test
	fun parseXML_switchBasic_createsRailSwitch() {
		val xml = getFixtureStream("switch-basic.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val switchCell = context.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
			val railSwitch = switchCell as RailSwitch
			assertThat(railSwitch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		}
	}

	@Test
	fun parseXML_switchBasic_createsThreeInOuts() {
		val xml = getFixtureStream("switch-basic.xml")

		editingContextFactory.createContext(xml).use { context ->
			val cellIN = context.getRailWayNetGrid().getCellAt(10, 10)
			val cellOutPlus = context.getRailWayNetGrid().getCellAt(20, 10)
			val cellOutMinus = context.getRailWayNetGrid().getCellAt(20, 11)
			assertThat(cellIN).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellOutPlus).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellOutMinus).isNotNull().isInstanceOf(InOut::class)
			assertThat((cellIN as InOut).getName()).isEqualTo("IN")
			assertThat((cellOutPlus as InOut).getName()).isEqualTo("OUT_PLUS")
			assertThat((cellOutMinus as InOut).getName()).isEqualTo("OUT_MINUS")
		}
	}

	@Test
	fun parseXML_semaphoreBasic_createsRailSemaphore() {
		val xml = getFixtureStream("semaphore-basic.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val semaphoreCell = context.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(semaphoreCell).isNotNull().isInstanceOf(RailSemaphore::class)
			val semaphore = semaphoreCell as RailSemaphore
			assertThat(semaphore.getOrientation()).isTrue()
		}
	}

	@Test
	fun parseXML_emptyGrid_createsContextWithMinimalElements() {
		val xml = getFixtureStream("empty-grid.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(50)
			assertThat(grid.rows).isEqualTo(50)
			// Should have 2 InOut elements (minimum required)
			assertThat(context.getInOuts()::size).isEqualTo(2)
		}
	}

	@Test
	fun parseXML_emptyGrid_hasNoTracks() {
		val xml = getFixtureStream("empty-grid.xml")

		editingContextFactory.createContext(xml).use { context ->
			// Grid has InOut nodes but no track connections
			assertThat(context.getGraph().entrySet().size)
				.withMessage("Empty grid should have no track connections")
				.isEqualTo(0)
		}
	}

	@Test
	fun parseXML_twoTracksParallel_createsFourInOuts() {
		val xml = getFixtureStream("two-tracks-parallel.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val cellA1 = context.getRailWayNetGrid().getCellAt(10, 10)
			val cellB1 = context.getRailWayNetGrid().getCellAt(20, 10)
			val cellA2 = context.getRailWayNetGrid().getCellAt(10, 12)
			val cellB2 = context.getRailWayNetGrid().getCellAt(20, 12)
			assertThat(cellA1).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB1).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellA2).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB2).isNotNull().isInstanceOf(InOut::class)
		}
	}

	@Test
	fun parseXML_rudyUjezd_createsValidContext() {
		val xml = getFixtureStream("rudyUjezd.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			// Check grid size (from rudyUjezd.xml: X=100, Y=100)
			assertThat(grid.cols).isEqualTo(100)
			assertThat(grid.rows).isEqualTo(100)
			// in-outs on the first end (f1, f2) and the second end (s1, s2) of the station
			val (f1, f2, s1, s2) = assertRudyUjezdStationInOuts(context)

			// from each end, there are switches and semaphores leading into the station area and must exist path to each InOut on the other side
			assertThat(existPath(f1, s1, context)).isTrue()
			assertThat(existPath(f1, s2, context)).isTrue()
			assertThat(existPath(f2, s1, context)).isTrue()
			assertThat(existPath(f2, s2, context)).isTrue()
			// and back
			assertThat(existPath(s1, f1, context)).isTrue()
			assertThat(existPath(s1, f2, context)).isTrue()
			assertThat(existPath(s2, f1, context)).isTrue()
			assertThat(existPath(s2, f2, context)).isTrue()

			assertGridContainsAllCellTypes(grid)
		}
	}

	/** Check the presence of at least one InOut, RailSwitch, and RailSemaphore in [grid]. */
	private fun assertGridContainsAllCellTypes(grid: RailwayNetGrid<Cell>) {
		val counts = countCellTypes(grid)

		assertThat(counts.getValue(INOUT_KEY))
			.withMessage("Should contain at least one InOut")
			.isGreaterThan(0)
		assertThat(counts.getValue(RAIL_SWITCH_KEY))
			.withMessage("Should contain at least one RailSwitch")
			.isGreaterThan(0)
		assertThat(counts.getValue(RAIL_SEMAPHORE_KEY))
			.withMessage("Should contain at least one RailSemaphore")
			.isGreaterThan(0)
	}

	// Every test below asserts that creation THROWS — the block throws before a
	// context exists, so there is no scope to close and no `.use {}` wrapping (Issue #1035).

	@Test
	fun parseXML_missingGridSize_throwsException() {
		val xml = getFixtureStream("invalid-missing-grid-size.xml")

		assertThatBlock { editingContextFactory.createContext(xml) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	fun parseXML_missingSpatialType_throwsException() {
		val xml = getFixtureStream("invalid-missing-spatial-type.xml")

		assertThatBlock { editingContextFactory.createContext(xml) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	fun parseXML_wrongRootElement_throwsException() {
		val xml = getFixtureStream("invalid-wrong-root-element.xml")

		assertThatBlock { editingContextFactory.createContext(xml) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	fun parseXML_malformedXML_throwsException() {
		val xml = getFixtureStream("invalid-malformed-xml.xml")

		assertThatBlock { editingContextFactory.createContext(xml) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	fun createContext_nonExistentFile_throwsException() {
		val nonExistentFile = File("non-existent-file.xml")

		assertThatBlock { editingContextFactory.createContext(nonExistentFile) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	fun createContext_nullInputStream_throwsException() {
		val nullStream: InputStream? = null
		assertThatBlock { editingContextFactory.createContext(nullStream!!) }
			.isFailure()
			.isInstanceOf(Exception::class)
	}

	@Test
	fun createContext_emptyInputStream_throwsException() {
		val emptyStream = ByteArrayInputStream(ByteArray(0))

		assertThatBlock { editingContextFactory.createContext(emptyStream) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}

	@Test
	fun createContext_invalidXMLContent_throwsException() {
		val invalidXML = "This is not XML at all!"
		val stream = ByteArrayInputStream(invalidXML.toByteArray())

		assertThatBlock { editingContextFactory.createContext(stream) }
			.isFailure()
			.isInstanceOf(ContextCreationException::class)
	}
}
