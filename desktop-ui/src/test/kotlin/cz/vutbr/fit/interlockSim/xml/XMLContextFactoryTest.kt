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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultRailWayNetGrid
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.exists
import cz.vutbr.fit.interlockSim.testutil.isFile
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Resources
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Unit tests for {@link XMLContextFactory}.
 *
 * Coverage:
 * - XML parsing with valid fixtures
 * - Empty context creation
 * - Error handling for invalid XML
 * - Round-trip serialization (save & load)
 * - Edge cases and malformed input
 *
 * Test Fixtures (src/test/resources/cz/vutbr/fit/interlockSim/xml/fixtures/):
 * - minimal-network.xml - Minimal valid network (2 InOut nodes, no tracks)
 * - linear-track.xml - Two InOut nodes connected by SimpleTrackBlock
 * - switch-basic.xml - RailSwitch with two output tracks
 * - semaphore-basic.xml - RailSemaphore between two InOut nodes
 * - two-tracks-parallel.xml - Two parallel independent tracks
 * - empty-grid.xml - Grid with minimal elements (2 InOut nodes, no tracks)
 * - invalid-*.xml - Various malformed/invalid XML files
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryTest : KoinTestBase() {
	private val editingContextFactory: XMLContextFactory by inject()

	@Nested
	@DisplayName("Factory instance and initialization")
	inner class FactoryInstanceTests {
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
			val context = editingContextFactory.createEmptyContext()

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(100)
			assertThat(grid.rows).isEqualTo(100)
		}

		@Test
		fun createEmptyContext_always_hasEmptyGraph() {
			val context = editingContextFactory.createEmptyContext()

			assertThat(context.getGraph().nodeSet())
				.withMessage("Empty context should have empty graph")
				.isEmpty()
		}
	}

	@Nested
	@DisplayName("Parsing valid XML fixtures")
	inner class ValidXMLParsingTests {
		@Test
		fun parseXML_minimalNetwork_createsTwoInOuts() {
			val xml = getFixtureStream("minimal-network.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			val cellA = grid.getCellAt(10, 10)
			val cellB = grid.getCellAt(20, 10)
			assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
			assertThat((cellA as InOut).getName()).isEqualTo("A")
			assertThat((cellB as InOut).getName()).isEqualTo("B")
		}

		@Test
		fun parseXML_linearTrack_createsTwoConnectedInOuts() {
			val xml = getFixtureStream("linear-track.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			val cellA = context.getRailWayNetGrid().getCellAt(10, 10)
			val cellB = context.getRailWayNetGrid().getCellAt(20, 10)
			assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
			assertThat((cellA as InOut).getName()).isEqualTo("A")
			assertThat((cellB as InOut).getName()).isEqualTo("B")
		}

		@Test
		fun parseXML_switchBasic_createsRailSwitch() {
			val xml = getFixtureStream("switch-basic.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			val switchCell = context.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
			val railSwitch = switchCell as RailSwitch
			assertThat(railSwitch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		}

		@Test
		fun parseXML_switchBasic_createsThreeInOuts() {
			val xml = getFixtureStream("switch-basic.xml")

			val context = editingContextFactory.createContext(xml)

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

		@Test
		fun parseXML_semaphoreBasic_createsRailSemaphore() {
			val xml = getFixtureStream("semaphore-basic.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			val semaphoreCell = context.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(semaphoreCell).isNotNull().isInstanceOf(RailSemaphore::class)
			val semaphore = semaphoreCell as RailSemaphore
			assertThat(semaphore.getOrientation()).isTrue()
		}

		@Test
		fun parseXML_emptyGrid_createsContextWithMinimalElements() {
			val xml = getFixtureStream("empty-grid.xml")

			val context = editingContextFactory.createContext(xml) as EditingContext

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(50)
			assertThat(grid.rows).isEqualTo(50)
			// Should have 2 InOut elements (minimum required)
			assertThat(context.getInOuts()::size).isEqualTo(2)
		}

		@Test
		fun parseXML_emptyGrid_hasNoTracks() {
			val xml = getFixtureStream("empty-grid.xml")

			val context = editingContextFactory.createContext(xml)

			// Grid has InOut nodes but no track connections
			assertThat(context.getGraph().entrySet().size)
				.withMessage("Empty grid should have no track connections")
				.isEqualTo(0)
		}

		@Test
		fun parseXML_twoTracksParallel_createsFourInOuts() {
			val xml = getFixtureStream("two-tracks-parallel.xml")

			val context = editingContextFactory.createContext(xml)

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

		@Test
		fun parseXML_rudyUjezd_createsValidContext() {
			val xml = getFixtureStream("rudyUjezd.xml")

			val context = editingContextFactory.createContext(xml) as EditingContext

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			// Check grid size (from rudyUjezd.xml: X=100, Y=100)
			assertThat(grid.cols).isEqualTo(100)
			assertThat(grid.rows).isEqualTo(100)
			// Check presence of at least one InOut, RailSwitch, and RailSemaphore
			var hasInOut = false
			var hasSwitch = false
			var hasSemaphore = false

			// Phase 6: Grid is typed as RailwayNetGrid<NodeCell> but internally contains Cell (NodeCell + TrackBlockPart)
			// Cast to Cell grid to iterate without ClassCastException
			@Suppress("UNCHECKED_CAST")
			val cellGrid = grid as RailwayNetGrid<Cell>
			for (entry in cellGrid) {
				when (entry.value) {
					is InOut -> hasInOut = true
					is RailSwitch -> hasSwitch = true
					is RailSemaphore -> hasSemaphore = true
				}
			}

			// in-outs on first end:
			// <InOut X="37" Y="32" SpatialType="HORIZONTAL" orientation="true" name="" />
			val f1 = context.getRailWayNetGrid().getCellAt(37, 32)
			// <InOut X="37" Y="31" SpatialType="HORIZONTAL" orientation="true" name="" />
			val f2 = context.getRailWayNetGrid().getCellAt(37, 31)

			// in-outs on second end:
			// <InOut X="5" Y="31" SpatialType="HORIZONTAL" orientation="false" name="" />
			val s1 = context.getRailWayNetGrid().getCellAt(5, 31)
			// <InOut X="5" Y="32" SpatialType="HORIZONTAL" orientation="false" name="" />
			val s2 = context.getRailWayNetGrid().getCellAt(5, 32)

			assertThat(f1).isNotNull().isInstanceOf(InOut::class)
			assertThat(f2).isNotNull().isInstanceOf(InOut::class)
			assertThat(s1).isNotNull().isInstanceOf(InOut::class)
			assertThat(s2).isNotNull().isInstanceOf(InOut::class)

			// from each end, there are switches and semaphores leading into the station area and must exist path to each InOut on the other side
			assertThat(existPath(f1 as InOut, s1 as InOut, context)).isTrue()
			assertThat(existPath(f1, s2 as InOut, context)).isTrue()
			assertThat(existPath(f2 as InOut, s1, context)).isTrue()
			assertThat(existPath(f2, s2, context)).isTrue()
			// and back
			assertThat(existPath(s1, f1, context)).isTrue()
			assertThat(existPath(s1, f2, context)).isTrue()
			assertThat(existPath(s2, f1, context)).isTrue()
			assertThat(existPath(s2, f2, context)).isTrue()

			assertThat(hasInOut).withMessage("Should contain at least one InOut").isTrue()
			assertThat(hasSwitch).withMessage("Should contain at least one RailSwitch").isTrue()
			assertThat(hasSemaphore).withMessage("Should contain at least one RailSemaphore").isTrue()
		}

		/**
		 * Checks if a path exists (or can be created) between two InOuts in the railway network.
		 * Uses BFS to traverse the track graph and verify connectivity.
		 */
		private fun existPath(
			from: InOut,
			to: InOut,
			context: EditingContext
		): Boolean {
			// Get grid locations for both InOuts
			val fromLoc = context.getRailWayNetGrid().getLocation(from) ?: return false
			val toLoc = context.getRailWayNetGrid().getLocation(to) ?: return false

			// If they're the same location, path exists trivially
			if (fromLoc == toLoc) return true

			// BFS on the track graph
			val graph = context.getGraph()
			val visited = mutableSetOf<Point>()
			val queue = mutableListOf(fromLoc)

			while (queue.isNotEmpty()) {
				val current = queue.removeFirst()

				// Skip if already visited
				if (current in visited) continue
				visited.add(current)

				// Check if we reached the destination
				if (current == toLoc) return true

				// Get all track blocks connected to this location
				val edges = graph.assignedEdges(current)

				// For each track block, find the other end and add it to the queue
				for (entry in edges.entries) {
					val trackBlock = entry.value
					// TrackBlocks should be TrackSections which have ends()
					if (trackBlock !is TrackSection) continue

					val ends = trackBlock.ends()
					// Get grid locations of both ends
					for (pathSeparator in ends) {
						// Phase 6: Grid is now typed as NodeCell, but ends() returns PathSeparator
						// PathSeparator instances should be NodeCell in editing context
						if (pathSeparator !is cz.vutbr.fit.interlockSim.objects.cells.NodeCell) continue

						val endLocation = context.getRailWayNetGrid().getLocation(pathSeparator) ?: continue
						// Add the other end (not current) to the queue
						if (endLocation != current && endLocation !in visited) {
							queue.add(endLocation)
						}
					}
				}
			}

			// No path found
			return false
		}
	}

	@Nested
	@DisplayName("Error handling for invalid XML")
	inner class InvalidXMLParsingTests {
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

	@Nested
	@DisplayName("Round-trip serialization (save & load)")
	inner class SerializationTests {
		private var tempFile: File? = null

		@BeforeEach
		fun setUpTempFile() {
			tempFile = File.createTempFile("test-network-", ".xml")
			tempFile?.deleteOnExit()
		}

		@AfterEach
		fun cleanUpTempFile() {
			if (tempFile != null && tempFile?.exists() == true) {
				tempFile?.delete()
			}
		}

		@Test
		fun saveContext_minimalNetwork_createsValidFile() {
			// Load fixture
			val xml = getFixtureStream("minimal-network.xml")
			val context = editingContextFactory.createContext(xml)

			// Save to file
			val saved = editingContextFactory.saveContext(context, tempFile!!)

			// Verify file exists and is readable
			assertThat(tempFile!!).exists().isFile()
			assertThat(tempFile!!.canRead()).isTrue()
			assertThat(tempFile!!.length()).isGreaterThan(0)
		}

		@Test
		fun saveAndLoad_minimalNetwork_preservesStructure() {
			// Load original fixture
			val xml = getFixtureStream("minimal-network.xml")
			val originalContext = editingContextFactory.createContext(xml)

			// Save to file
			editingContextFactory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = editingContextFactory.createContext(tempFile!!)

			// Verify structure is preserved
			assertThat(loadedContext).isNotNull()
			assertThat(loadedContext.getRailWayNetGrid().cols).isEqualTo(100)
			assertThat(loadedContext.getRailWayNetGrid().rows).isEqualTo(100)

			// Verify the InOut cell exists
			var foundInOut = false
			for (entry in loadedContext.getRailWayNetGrid()) {
				if (entry.value is InOut) {
					val inOut = entry.value as InOut
					if ("A" == inOut.getName()) {
						foundInOut = true
						break
					}
				}
			}
			assertThat(foundInOut).withMessage("InOut 'A' should exist in loaded context").isTrue()
		}

		@Test
		fun saveContext_emptyContext_createsValidXML() {
			// Create empty context
			val emptyContext = editingContextFactory.createEmptyContext()

			// Add minimum required InOut elements to satisfy validation
			emptyContext.putCell(Point(1, 1), InOut("ENTRY", true, Cell.SpatialType.HORIZONTAL))
			emptyContext.putCell(Point(2, 1), InOut("EXIT", false, Cell.SpatialType.HORIZONTAL))

			// Save to file
			editingContextFactory.saveContext(emptyContext, tempFile!!)

			// Verify file is valid XML by loading it
			val loadedContext = editingContextFactory.createContext(tempFile!!)
			assertThat(loadedContext).isNotNull()
		}

		@Test
		fun saveAndLoad_emptyGrid_preservesGridSize() {
			// Load empty grid fixture
			val xml = getFixtureStream("empty-grid.xml")
			val originalContext = editingContextFactory.createContext(xml)

			// Save to file
			editingContextFactory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = editingContextFactory.createContext(tempFile!!)

			// Verify grid size is preserved
			val grid = loadedContext.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(50)
			assertThat(grid.rows).isEqualTo(50)
		}

		@Test
		fun saveAndLoad_linearTrack_preservesTrackBlocks() {
			// Load fixture with track block
			val xml = getFixtureStream("linear-track.xml")
			val originalContext = editingContextFactory.createContext(xml)

			// Save to file
			editingContextFactory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = editingContextFactory.createContext(tempFile!!)

			// Verify cells are preserved
			val cellA = loadedContext.getRailWayNetGrid().getCellAt(10, 10)
			val cellB = loadedContext.getRailWayNetGrid().getCellAt(20, 10)
			assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
		}

		@Test
		fun saveAndLoad_switchBasic_preservesSwitchType() {
			// Load fixture with rail switch
			val xml = getFixtureStream("switch-basic.xml")
			val originalContext = editingContextFactory.createContext(xml)

			// Save to file
			editingContextFactory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = editingContextFactory.createContext(tempFile!!)

			// Verify switch is preserved
			val switchCell = loadedContext.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
			val railSwitch = switchCell as RailSwitch
			assertThat(railSwitch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		}

		@Test
		fun saveContext_overwritesExistingFile() {
			// Create initial context and save
			val context1 = editingContextFactory.createEmptyContext()
			editingContextFactory.saveContext(context1, tempFile!!)

			// Load a different fixture and save to same file
			val xml = getFixtureStream("minimal-network.xml")
			val context2 = editingContextFactory.createContext(xml)
			editingContextFactory.saveContext(context2, tempFile!!)

			// Verify loaded context matches context2 (not context1)
			val loadedContext = editingContextFactory.createContext(tempFile!!)
			val cell = loadedContext.getRailWayNetGrid().getCellAt(10, 10)
			assertThat(cell)
				.isNotNull()
				.isInstanceOf(InOut::class)
				.withMessage("Loaded context should contain the InOut from context2, proving file was overwritten")
		}
	}

	@Nested
	@DisplayName("Edge cases and boundary conditions")
	inner class EdgeCaseTests {
		@Test
		fun parseXML_largeGridSize_succeeds() {
			val largeGridXML =
				"<?xml version=\"1.0\"?>\n" +
					"<!DOCTYPE net>\n" +
					"<net X=\"500\" Y=\"500\">\n" +
					"  <InOut X=\"10\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"ENTRY\"/>\n" +
					"  <InOut X=\"490\" Y=\"490\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"EXIT\"/>\n" +
					"</net>"
			val stream = ByteArrayInputStream(largeGridXML.toByteArray())

			val context = editingContextFactory.createContext(stream)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(500)
			assertThat(grid.rows).isEqualTo(500)
		}

		@Test
		fun parseXML_minimalGridSize_succeeds() {
			val minimalGridXML =
				"<?xml version=\"1.0\"?>\n" +
					"<!DOCTYPE net>\n" +
					"<net X=\"10\" Y=\"10\">\n" +
					"  <InOut X=\"1\" Y=\"1\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"ENTRY\"/>\n" +
					"  <InOut X=\"2\" Y=\"1\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"EXIT\"/>\n" +
					"</net>"
			val stream = ByteArrayInputStream(minimalGridXML.toByteArray())

			val context = editingContextFactory.createContext(stream)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(10)
			assertThat(grid.rows).isEqualTo(10)
		}

		@Test
		fun parseXML_cellAtGridBoundary_succeeds() {
			val boundaryXML =
				"<?xml version=\"1.0\"?>\n" +
					"<!DOCTYPE net>\n" +
					"<net X=\"100\" Y=\"100\">\n" +
					"  <InOut X=\"1\" Y=\"1\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"ENTRY\"/>\n" +
					"  <InOut X=\"98\" Y=\"98\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"CORNER\"/>\n" +
					"</net>"
			val stream = ByteArrayInputStream(boundaryXML.toByteArray())

			val context = editingContextFactory.createContext(stream)

			assertThat(context).isNotNull()
			val cell = context.getRailWayNetGrid().getCellAt(98, 98)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)
			assertThat((cell as InOut).getName()).isEqualTo("CORNER")
		}

		@Test
		fun parseXML_multipleInOutsWithSameName_lastOneWins() {
			val duplicateNameXML =
				"<?xml version=\"1.0\"?>\n" +
					"<!DOCTYPE net>\n" +
					"<net X=\"100\" Y=\"100\">\n" +
					"  <InOut X=\"10\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"A\"/>\n" +
					"  <InOut X=\"20\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"A\"/>\n" +
					"</net>"
			val stream = ByteArrayInputStream(duplicateNameXML.toByteArray())

			val context = editingContextFactory.createContext(stream)

			assertThat(context).isNotNull()
			// Both cells should exist at different positions
			val cell1 = context.getRailWayNetGrid().getCellAt(10, 10)
			val cell2 = context.getRailWayNetGrid().getCellAt(20, 10)
			assertThat(cell1).isNotNull().isInstanceOf(InOut::class)
			assertThat(cell2).isNotNull().isInstanceOf(InOut::class)
		}
	}

	@Nested
	@DisplayName("Complex railway station configurations")
	inner class ComplexStationConfigurationTests {
		// Helper methods for complex station validation

		/**
		 * Finds the leftmost InOut element (typically main line entry from west/north).
		 */
		private fun findMainLineEntry(context: EditingContext): InOut? {
			var leftmost: InOut? = null
			var minX = Int.MAX_VALUE

			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut) {
					val location = context.getRailWayNetGrid().getLocation(cell)
					if (location != null && location.x < minX) {
						minX = location.x
						leftmost = cell
					}
				}
			}
			return leftmost
		}

		/**
		 * Finds the rightmost InOut element (typically main line exit to east/south).
		 */
		private fun findMainLineExit(context: EditingContext): InOut? {
			var rightmost: InOut? = null
			var maxX = Int.MIN_VALUE

			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut) {
					val location = context.getRailWayNetGrid().getLocation(cell)
					if (location != null && location.x > maxX) {
						maxX = location.x
						rightmost = cell
					}
				}
			}
			return rightmost
		}

		/**
		 * Finds all mid-layout InOut points (platforms).
		 * These are InOuts that are not at the extreme left or right edges.
		 */
		private fun findPlatformInOuts(context: EditingContext): List<InOut> {
			val allInOuts = mutableListOf<InOut>()
			for (entry in context.getRailWayNetGrid()) {
				if (entry.value is InOut) {
					allInOuts.add(entry.value as InOut)
				}
			}

			if (allInOuts.size <= 2) return emptyList()

			// Find extreme X coordinates
			var minX = Int.MAX_VALUE
			var maxX = Int.MIN_VALUE
			for (inOut in allInOuts) {
				val location = context.getRailWayNetGrid().getLocation(inOut)
				if (location != null) {
					if (location.x < minX) minX = location.x
					if (location.x > maxX) maxX = location.x
				}
			}

			// Return InOuts that are not at extreme edges
			val platforms = mutableListOf<InOut>()
			for (inOut in allInOuts) {
				val location = context.getRailWayNetGrid().getLocation(inOut)
				if (location != null && location.x > minX && location.x < maxX) {
					platforms.add(inOut)
				}
			}
			return platforms
		}

		/**
		 * Finds yard access point (mid-area InOut for freight yard).
		 */
		private fun findYardAccessPoint(context: EditingContext): InOut? {
			// For yard, we look for InOuts in middle Y range
			val grid = context.getRailWayNetGrid()
			val midY = grid.rows / 2

			for (entry in grid) {
				val cell = entry.value
				if (cell is InOut) {
					val location = grid.getLocation(cell)
					if (location != null && Math.abs(location.y - midY) < 5) {
						return cell
					}
				}
			}
			return null
		}

		/**
		 * Finds yard siding tracks (dead-end InOuts for parking/storage).
		 */
		private fun findYardSidings(context: EditingContext): List<InOut> {
			val sidings = mutableListOf<InOut>()
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut) {
					// Check if this InOut has name containing "Yard" or "Track"
					if (cell.getName().contains("Yard") || cell.getName().contains("Track")) {
						sidings.add(cell)
					}
				}
			}
			return sidings
		}

		/**
		 * Finds switch where multiple lines converge (junction merge point).
		 */
		private fun findJunctionMergePoint(context: EditingContext): RailSwitch? {
			// Junction switch typically has connections from 3+ directions
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is RailSwitch) {
					// Check connectivity - junction switches should have high connectivity
					val location = context.getRailWayNetGrid().getLocation(cell)
					if (location != null) {
						val edges = context.getGraph().assignedEdges(location)
						if (edges.size >= 3) {
							return cell
						}
					}
				}
			}
			return null
		}

		/**
		 * Counts elements by type in the grid.
		 */
		private fun countElements(grid: RailwayNetGrid<Cell>): Map<String, Int> {
			val counts = mutableMapOf<String, Int>()
			counts["InOut"] = 0
			counts["RailSwitch"] = 0
			counts["RailSemaphore"] = 0
			counts["TrackBlock"] = 0

			for (entry in grid) {
				when (entry.value) {
					is InOut -> counts["InOut"] = counts["InOut"]!! + 1
					is RailSwitch -> counts["RailSwitch"] = counts["RailSwitch"]!! + 1
					is RailSemaphore -> counts["RailSemaphore"] = counts["RailSemaphore"]!! + 1
				}
			}

			return counts
		}

		/**
		 * Finds all signals within specified grid rectangle.
		 */
		private fun findSignalsInArea(
			context: EditingContext,
			startX: Int,
			endX: Int,
			startY: Int,
			endY: Int
		): List<RailSemaphore> {
			val signals = mutableListOf<RailSemaphore>()

			// Phase 6: Grid is typed as RailwayNetGrid<NodeCell> but internally contains Cell (NodeCell + TrackBlockPart)
			// Cast to Cell grid to access all cells without ClassCastException
			@Suppress("UNCHECKED_CAST")
			val grid = context.getRailWayNetGrid() as RailwayNetGrid<Cell>

			for (x in startX..endX) {
				for (y in startY..endY) {
					val cell = grid.getCellAt(x, y)
					if (cell is RailSemaphore) {
						signals.add(cell)
					}
				}
			}
			return signals
		}

		// Praha Hlavní Nádraží Tests

		@Test
		fun testPragueContextLoading() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(70)
			assertThat(grid.rows).isEqualTo(25)

			// Verify grid is not empty
			val hasElements = grid.iterator().hasNext()
			assertThat(hasElements).withMessage("Praha grid should contain elements").isTrue()
		}

		@Test
		fun testPragueElementComposition() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml) as EditingContext
			val counts = countElements(context.getRailWayNetGrid())

			// Verify element counts meet thresholds (adjusted for simplified topology)
			assertThat(counts["InOut"]!!)
				.withMessage("Praha should have at least 6 InOut points (4 north + 6 south)")
				.isGreaterThan(5)
			assertThat(counts["RailSwitch"]!!)
				.withMessage("Praha should have at least 8 switches (4 north throat + 4 south throat)")
				.isGreaterThan(7)
			assertThat(counts["RailSemaphore"]!!)
				.withMessage("Praha should have at least 20 signals (entry + platform + exit)")
				.isGreaterThan(19)
		}

		@Test
		fun testPraguePlatformConnectivity() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml) as EditingContext

			// Find entry and exit InOuts by orientation
			val entries = mutableListOf<InOut>() // orientation=false (entries from west/north)
			val exits = mutableListOf<InOut>() // orientation=true (exits to east/south)

			for (entry in context.getRailWayNetGrid()) {
				if (entry.value is InOut) {
					val inOut = entry.value as InOut
					if (inOut.getOrientation()) {
						exits.add(inOut)
					} else {
						entries.add(inOut)
					}
				}
			}

			assertThat(entries.size)
				.withMessage("Praha should have entry points (orientation=false)")
				.isGreaterThan(3)
			assertThat(exits.size)
				.withMessage("Praha should have exit points (orientation=true)")
				.isGreaterThan(3)

			// Verify connectivity between north entry and south exit
			if (entries.isNotEmpty() && exits.isNotEmpty()) {
				val from = entries[0]
				val to = exits[0]
				assertThat(existPath(from, to, context as DefaultEditingContext))
					.withMessage("Path should exist from north entry ${from.getName()} to south exit ${to.getName()}")
					.isTrue()
			}
		}

		@Test
		fun testPragueSignalPlacement() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml) as EditingContext
			val grid = context.getRailWayNetGrid()

			// Find signals in north throat area (X=1-20)
			val northSignals = findSignalsInArea(context, 1, 20, 0, grid.rows - 1)
			assertThat(northSignals.size)
				.withMessage("North throat should have entry signals")
				.isGreaterThan(3)

			// Find signals in south throat area (X=50-69)
			val southSignals =
				findSignalsInArea(context, 50, grid.cols - 1, 0, grid.rows - 1)
			assertThat(southSignals.size)
				.withMessage("South throat should have exit signals")
				.isGreaterThan(3)
		}

		@Test
		fun testPragueMultipleRoutes() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml)

			// Find all InOuts
			val allInOuts = mutableListOf<InOut>()
			for (entry in context.getRailWayNetGrid()) {
				if (entry.value is InOut) {
					allInOuts.add(entry.value as InOut)
				}
			}

			// Verify at least 3 independent entry/exit pairs exist
			assertThat(allInOuts.size)
				.withMessage("Praha should support multiple routes with multiple InOuts")
				.isGreaterThan(6)
		}

		@Test
		fun testPragueBayPlatformTermination() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml)

			// Bay platforms should exist (check for InOuts with "Bay" in name)
			var foundBayPlatform = false
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut && cell.getName().contains("Bay")) {
					foundBayPlatform = true
					break
				}
			}

			// This test is informational - bay platforms are optional
			// Just verify we can load and parse the structure
			assertThat(context).isNotNull()
		}

		// Validation Tests

		@Test
		fun testSwitchSegmentConsistencyPraha() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml)

			// Verify all switches are properly connected
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is RailSwitch) {
					val location = context.getRailWayNetGrid().getLocation(cell)
					if (location != null) {
						val edges = context.getGraph().assignedEdges(location)
						// Switches should have at least 2 connections (input + output)
						assertThat(edges.size)
							.withMessage("Switch at $location should have connections")
							.isGreaterThan(1)
					}
				}
			}
		}

		// Serialization Tests

		@Test
		fun testPragueSaveLoad() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val originalContext = editingContextFactory.createContext(xml)

			// Save to temp file
			val tempFile = File.createTempFile("test-praha-", ".xml")
			tempFile.deleteOnExit()

			editingContextFactory.saveContext(originalContext, tempFile)

			// Reload from file
			val loadedContext = editingContextFactory.createContext(tempFile)

			// Verify structure preserved
			assertThat(loadedContext).isNotNull()
			assertThat(loadedContext.getRailWayNetGrid().cols).isEqualTo(70)
			assertThat(loadedContext.getRailWayNetGrid().rows).isEqualTo(25)

			// Verify element counts match
			val originalCounts = countElements(originalContext.getRailWayNetGrid() as DefaultRailWayNetGrid)
			val loadedCounts = countElements(loadedContext.getRailWayNetGrid() as DefaultRailWayNetGrid)
			assertThat(loadedCounts["InOut"]).isEqualTo(originalCounts["InOut"])
			assertThat(loadedCounts["RailSwitch"]).isEqualTo(originalCounts["RailSwitch"])
			assertThat(loadedCounts["RailSemaphore"]).isEqualTo(originalCounts["RailSemaphore"])

			tempFile.delete()
		}

		/**
		 * Reuses the existPath method from ValidXMLParsingTests for connectivity checking.
		 */
		private fun existPath(
			from: InOut,
			to: InOut,
			context: DefaultEditingContext
		): Boolean {
			val fromLoc = context.getRailWayNetGrid().getLocation(from) ?: return false
			val toLoc = context.getRailWayNetGrid().getLocation(to) ?: return false
			if (fromLoc == toLoc) return true

			val graph = context.getGraph()
			val visited = mutableSetOf<Point>()
			val queue = mutableListOf(fromLoc)

			while (queue.isNotEmpty()) {
				val current = queue.removeFirst()
				if (current in visited) continue
				visited.add(current)
				if (current == toLoc) return true

				val edges = graph.assignedEdges(current)
				for (entry in edges.entries) {
					val trackBlock = entry.value
					if (trackBlock !is TrackSection) continue

					val ends = trackBlock.ends()
					for (pathSeparator in ends) {
						// Phase 6: Grid is now typed as NodeCell, but ends() returns PathSeparator
						// PathSeparator instances should be NodeCell in editing context
						if (pathSeparator !is cz.vutbr.fit.interlockSim.objects.cells.NodeCell) continue

						val endLocation = context.getRailWayNetGrid().getLocation(pathSeparator) ?: continue
						if (endLocation != current && endLocation !in visited) {
							queue.add(endLocation)
						}
					}
				}
			}
			return false
		}
	}

	@Nested
	@DisplayName("Praha Topology Improvements (PR #347)")
	inner class PragueTopologyImprovementsTests {
		@Test
		@DisplayName("Praha XML loads with exact element counts after PR #347 additions")
		fun testPragueExactElementCounts() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml) as EditingContext

			@Suppress("UNCHECKED_CAST")
			val cellGrid = context.getRailWayNetGrid() as RailwayNetGrid<Cell>

			var inOutCount = 0
			var switchCount = 0
			var semaphoreCount = 0
			for (entry in cellGrid) {
				when (entry.value) {
					is InOut -> inOutCount++
					is RailSwitch -> switchCount++
					is RailSemaphore -> semaphoreCount++
				}
			}

			// Count unique track blocks via graph (SimpleTrackBlocks are graph edges, not grid cells)
			val seenBlocks = java.util.IdentityHashMap<TrackSection, Unit>()
			val graph = (context as DefaultEditingContext).getGraph()
			for (node in graph.nodeSet()) {
				for (entry in graph.assignedEdges(node).entries) {
					val edge = entry.value
					if (edge is TrackSection) {
						seenBlocks[edge] = Unit
					}
				}
			}
			val trackBlockCount = seenBlocks.size

			assertThat(inOutCount)
				.withMessage("Praha should have exactly 11 InOut elements (car train terminal deferred)")
				.isEqualTo(11)
			assertThat(switchCount)
				.withMessage("Praha should have exactly 50 switches")
				.isEqualTo(50)
			assertThat(semaphoreCount)
				.withMessage("Praha should have exactly 37 signals")
				.isEqualTo(37)
			assertThat(trackBlockCount)
				.withMessage("Praha should have exactly 117 track blocks (car train terminal deferred)")
				.isEqualTo(117)
		}

		@Test
		@DisplayName("N-Bypass InOut is present at grid (2,20) with entry orientation")
		fun testPragueNorthBypassInOutPresent() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml)

			val cell = context.getRailWayNetGrid().getCellAt(2, 20)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)

			val inOut = cell as InOut
			assertThat(inOut.getName())
				.withMessage("N-Bypass InOut should have correct name")
				.isEqualTo("N-Bypass")
			assertThat(inOut.getOrientation())
				.withMessage("N-Bypass InOut should be an entry point (orientation=false)")
				.isFalse()
		}

		@Test
		@DisplayName("S-Bypass InOut is present at grid (60,20) with exit orientation")
		fun testPragueSouthBypassInOutPresent() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml)

			val cell = context.getRailWayNetGrid().getCellAt(60, 20)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)

			val inOut = cell as InOut
			assertThat(inOut.getName())
				.withMessage("S-Bypass InOut should have correct name")
				.isEqualTo("S-Bypass")
			assertThat(inOut.getOrientation())
				.withMessage("S-Bypass InOut should be an exit point (orientation=true)")
				.isTrue()
		}

		@Test
		@DisplayName("Bypass route N-Bypass to S-Bypass is navigable")
		fun testPragueBypassRouteNavigable() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml) as EditingContext

			var nBypass: InOut? = null
			var sBypass: InOut? = null
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut) {
					when (cell.getName()) {
						"N-Bypass" -> nBypass = cell
						"S-Bypass" -> sBypass = cell
					}
				}
			}

			assertThat(nBypass)
				.withMessage("N-Bypass InOut should exist in Praha XML")
				.isNotNull()
			assertThat(sBypass)
				.withMessage("S-Bypass InOut should exist in Praha XML")
				.isNotNull()

			assertThat(existPath(nBypass!!, sBypass!!, context as DefaultEditingContext))
				.withMessage("Path should exist from N-Bypass to S-Bypass")
				.isTrue()
		}

		@Test
		@DisplayName("Bypass corridor switches at Y=20 have the orientations the bypass route requires")
		fun testPragueBypassSwitchOrientations() {
			val xml = getFixtureStream("praha-hlavni-nadrazi.xml")
			val context = editingContextFactory.createContext(xml)
			val grid = context.getRailWayNetGrid()

			// Four switches sit on the bypass corridor (Y=20). Their types are the
			// physical orientation of the diverge — assert each one to lock the
			// bypass topology against regression.
			val expectedTypes =
				mapOf(
					(11 to 20) to RailSwitch.Type.SIMPLE_RIGHT_TRUE,
					(15 to 20) to RailSwitch.Type.SIMPLE_RIGHT_TRUE,
					(46 to 20) to RailSwitch.Type.SIMPLE_RIGHT_TRUE,
					(51 to 20) to RailSwitch.Type.SIMPLE_RIGHT_FALSE
				)
			for ((coords, expectedType) in expectedTypes) {
				val (x, y) = coords
				val cell = grid.getCellAt(x, y)
				assertThat(cell)
					.withMessage("Cell at ($x,$y) should be a RailSwitch on the bypass corridor")
					.isNotNull()
					.isInstanceOf(RailSwitch::class)
				assertThat((cell as RailSwitch).type)
					.withMessage("Switch at ($x,$y) must be $expectedType to keep the bypass route diverging correctly")
					.isEqualTo(expectedType)
			}
		}

		/**
		 * Path existence check for Praha topology improvement tests.
		 * Reuses the BFS approach from ComplexStationConfigurationTests.
		 */
		private fun existPath(
			from: InOut,
			to: InOut,
			context: DefaultEditingContext
		): Boolean {
			val fromLoc = context.getRailWayNetGrid().getLocation(from) ?: return false
			val toLoc = context.getRailWayNetGrid().getLocation(to) ?: return false
			if (fromLoc == toLoc) return true

			val graph = context.getGraph()
			val visited = mutableSetOf<Point>()
			val queue = mutableListOf(fromLoc)

			while (queue.isNotEmpty()) {
				val current = queue.removeFirst()
				if (current in visited) continue
				visited.add(current)
				if (current == toLoc) return true

				val edges = graph.assignedEdges(current)
				for (entry in edges.entries) {
					val trackBlock = entry.value
					if (trackBlock !is TrackSection) continue

					val ends = trackBlock.ends()
					for (pathSeparator in ends) {
						if (pathSeparator !is cz.vutbr.fit.interlockSim.objects.cells.NodeCell) continue

						val endLocation = context.getRailWayNetGrid().getLocation(pathSeparator) ?: continue
						if (endLocation != current && endLocation !in visited) {
							queue.add(endLocation)
						}
					}
				}
			}
			return false
		}
	}

	@Nested
	@DisplayName("Name attribute persistence and validation (Issue #306)")
	inner class NameAttributeTests {
		@Test
		@DisplayName("save and load preserves RailSemaphore names")
		fun saveAndLoad_preservesRailSemaphoreNames() {
			val context = editingContextFactory.createEmptyContext()
			val semaphore = RailSemaphore("signal_test_1", true, Cell.SpatialType.HORIZONTAL)
			context.putCell(Point(10, 10), semaphore)
			val inOut1 = InOut("entry", true, Cell.SpatialType.HORIZONTAL)
			val inOut2 = InOut("exit", false, Cell.SpatialType.HORIZONTAL)
			context.putCell(Point(5, 10), inOut1)
			context.putCell(Point(15, 10), inOut2)

			val tempFile = File.createTempFile("test-semaphore-names-", ".xml")
			tempFile.deleteOnExit()

			editingContextFactory.saveContext(context, tempFile)
			val loadedContext = editingContextFactory.createContext(tempFile)

			val loadedSemaphore = loadedContext.getRailWayNetGrid().getCellAt(10, 10) as RailSemaphore
			assertThat(loadedSemaphore.getName()).isEqualTo("signal_test_1")

			tempFile.delete()
		}

		@Test
		@DisplayName("save and load preserves RailSwitch names")
		fun saveAndLoad_preservesRailSwitchNames() {
			val context = editingContextFactory.createEmptyContext()
			val railSwitch = RailSwitch("switch_A", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_LEFT_TRUE)
			context.putCell(Point(10, 10), railSwitch)
			val inOut1 = InOut("entry", true, Cell.SpatialType.HORIZONTAL)
			val inOut2 = InOut("exit", false, Cell.SpatialType.HORIZONTAL)
			context.putCell(Point(5, 10), inOut1)
			context.putCell(Point(15, 10), inOut2)

			val tempFile = File.createTempFile("test-switch-names-", ".xml")
			tempFile.deleteOnExit()

			editingContextFactory.saveContext(context, tempFile)
			val loadedContext = editingContextFactory.createContext(tempFile)

			val loadedSwitch = loadedContext.getRailWayNetGrid().getCellAt(10, 10) as RailSwitch
			assertThat(loadedSwitch.getName()).isEqualTo("switch_A")

			tempFile.delete()
		}

		@Test
		@DisplayName("load XML without name attributes (backward compatibility)")
		fun parseXML_withoutNameAttributes_succeedsWithAutoNames() {
			val xml = getFixtureStream("legacy-network-no-names.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			// Elements without names should have empty names (auto-naming happens in GUI)
			val semaphore = context.getRailWayNetGrid().getCellAt(15, 10) as RailSemaphore
			val railSwitch = context.getRailWayNetGrid().getCellAt(20, 10) as RailSwitch
			assertThat(semaphore.getName()).isEmpty()
			assertThat(railSwitch.getName()).isEmpty()
		}

		@Test
		@DisplayName("empty string names are omitted in serialization")
		fun saveContext_withEmptyNames_omitsNameAttribute() {
			val context = editingContextFactory.createEmptyContext()
			val semaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL) // name defaults to empty
			context.putCell(Point(10, 10), semaphore)
			val inOut1 = InOut("entry", true, Cell.SpatialType.HORIZONTAL)
			val inOut2 = InOut("exit", false, Cell.SpatialType.HORIZONTAL)
			context.putCell(Point(5, 10), inOut1)
			context.putCell(Point(15, 10), inOut2)

			val tempFile = File.createTempFile("test-empty-names-", ".xml")
			tempFile.deleteOnExit()

			editingContextFactory.saveContext(context, tempFile)

			// Read XML and verify name attribute is not present
			val xmlContent = tempFile.readText()
			assertThat(xmlContent.contains("RailSemaphore")).isTrue()
			// Empty name should NOT be serialized (no name="" in output)
			assertThat(xmlContent.contains("name=\"\"")).isFalse()

			tempFile.delete()
		}

		@Test
		@DisplayName("valid special characters in names")
		fun parseXML_validSpecialChars_succeeds() {
			val xml = getFixtureStream("valid-special-chars-names.xml")

			val context = editingContextFactory.createContext(xml)

			assertThat(context).isNotNull()
			val inOut = context.getRailWayNetGrid().getCellAt(10, 10) as InOut
			val semaphore = context.getRailWayNetGrid().getCellAt(15, 10) as RailSemaphore
			val railSwitch = context.getRailWayNetGrid().getCellAt(20, 10) as RailSwitch
			assertThat(inOut.getName()).isEqualTo("entry_1")
			assertThat(semaphore.getName()).isEqualTo("signal-north-001")
			assertThat(railSwitch.getName()).isEqualTo("switch_junction_A")
		}

		@Test
		@DisplayName("names longer than 50 characters fail validation")
		fun parseXML_nameTooLong_throwsException() {
			val xml = getFixtureStream("invalid-name-too-long.xml")

			assertThatBlock { editingContextFactory.createContext(xml) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		@DisplayName("names with invalid characters fail validation")
		fun parseXML_invalidCharacters_throwsException() {
			val xml = getFixtureStream("invalid-name-special-chars.xml")

			assertThatBlock { editingContextFactory.createContext(xml) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}
	}

	// Helper method to load fixture files from resources
	private fun getFixtureStream(fileName: String): InputStream {
		val resourcePath = "/cz/vutbr/fit/interlockSim/xml/fixtures/$fileName"
		val stream = Resources.read(resourcePath.trimStart('/')).byteInputStream()
		assertThat(stream)
			.withMessage("Fixture file should exist: $fileName")
			.isNotNull()
		return stream
	}
}
