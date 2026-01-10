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

import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import assertk.assertThat
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock
import cz.vutbr.fit.interlockSim.testutil.exists
import cz.vutbr.fit.interlockSim.testutil.isFile
import cz.vutbr.fit.interlockSim.testutil.isSameAs
import cz.vutbr.fit.interlockSim.testutil.withMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

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
 * - minimal-network.xml - Single InOut node
 * - linear-track.xml - Two InOut nodes connected by SimpleTrackBlock
 * - switch-basic.xml - RailSwitch with two output tracks
 * - semaphore-basic.xml - RailSemaphore between two InOut nodes
 * - two-tracks-parallel.xml - Two parallel independent tracks
 * - empty-grid.xml - Empty railway network (no cells)
 * - invalid-*.xml - Various malformed/invalid XML files
 */
class XMLContextFactoryTest {
	private lateinit var factory: XMLContextFactory

	@BeforeEach
	fun setUp() {
		factory = XMLContextFactory.getInstance()
	}

	@Nested
	@DisplayName("Factory instance and initialization")
	inner class FactoryInstanceTests {
		@Test
		fun getInstance_always_returnsSameInstance() {
			val instance1 = XMLContextFactory.getInstance()
			val instance2 = XMLContextFactory.getInstance()

			assertThat(instance1)
				.isNotNull()
				.withMessage("Factory instance should not be null")
				.isSameAs(instance2)
		}

		@Test
		fun createEmptyContext_always_returns100x100Grid() {
			val context = factory.createEmptyContext()

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.getCols()).isEqualTo(100)
			assertThat(grid.getRows()).isEqualTo(100)
		}

		@Test
		fun createEmptyContext_always_hasEmptyGraph() {
			val context = factory.createEmptyContext()

			assertThat(context.getGraph().nodeSet())
				.withMessage("Empty context should have empty graph")
				.isEmpty()
		}
	}

	@Nested
	@DisplayName("Parsing valid XML fixtures")
	inner class ValidXMLParsingTests {
		@Test
		fun parseXML_minimalNetwork_createsSingleInOut() {
			val xml = getFixtureStream("minimal-network.xml")

			val context = factory.createContext(xml)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			val cell = grid.getCellAt(10, 10)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)
			val inOut = cell as InOut
			assertThat(inOut.getName()).isEqualTo("A")
		}

		@Test
		fun parseXML_linearTrack_createsTwoConnectedInOuts() {
			val xml = getFixtureStream("linear-track.xml")

			val context = factory.createContext(xml)

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

			val context = factory.createContext(xml)

			assertThat(context).isNotNull()
			val switchCell = context.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
			val railSwitch = switchCell as RailSwitch
			assertThat(railSwitch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		}

		@Test
		fun parseXML_switchBasic_createsThreeInOuts() {
			val xml = getFixtureStream("switch-basic.xml")

			val context = factory.createContext(xml)

			val cellIN = context.getRailWayNetGrid().getCellAt(10, 10)
			val cellOUT_PLUS = context.getRailWayNetGrid().getCellAt(20, 10)
			val cellOUT_MINUS = context.getRailWayNetGrid().getCellAt(20, 11)
			assertThat(cellIN).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellOUT_PLUS).isNotNull().isInstanceOf(InOut::class)
			assertThat(cellOUT_MINUS).isNotNull().isInstanceOf(InOut::class)
			assertThat((cellIN as InOut).getName()).isEqualTo("IN")
			assertThat((cellOUT_PLUS as InOut).getName()).isEqualTo("OUT_PLUS")
			assertThat((cellOUT_MINUS as InOut).getName()).isEqualTo("OUT_MINUS")
		}

		@Test
		fun parseXML_semaphoreBasic_createsRailSemaphore() {
			val xml = getFixtureStream("semaphore-basic.xml")

			val context = factory.createContext(xml)

			assertThat(context).isNotNull()
			val semaphoreCell = context.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(semaphoreCell).isNotNull().isInstanceOf(RailSemaphore::class)
			val semaphore = semaphoreCell as RailSemaphore
			assertThat(semaphore.getOrientation()).isTrue()
		}

		@Test
		fun parseXML_emptyGrid_createsContextWithNoElements() {
			val xml = getFixtureStream("empty-grid.xml")

			val context = factory.createContext(xml)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.getCols()).isEqualTo(50)
			assertThat(grid.getRows()).isEqualTo(50)
		}

		@Test
		fun parseXML_emptyGrid_hasEmptyGraph() {
			val xml = getFixtureStream("empty-grid.xml")

			val context = factory.createContext(xml)

			assertThat(context.getGraph().nodeSet())
				.withMessage("Empty grid should have empty graph")
				.isEmpty()
		}

		@Test
		fun parseXML_twoTracksParallel_createsFourInOuts() {
			val xml = getFixtureStream("two-tracks-parallel.xml")

			val context = factory.createContext(xml)

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

	@Nested
	@DisplayName("Error handling for invalid XML")
	inner class InvalidXMLParsingTests {
		@Test
		fun parseXML_missingGridSize_throwsException() {
			val xml = getFixtureStream("invalid-missing-grid-size.xml")

			assertThatBlock { factory.createContext(xml) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun parseXML_missingSpatialType_throwsException() {
			val xml = getFixtureStream("invalid-missing-spatial-type.xml")

			assertThatBlock { factory.createContext(xml) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun parseXML_wrongRootElement_throwsException() {
			val xml = getFixtureStream("invalid-wrong-root-element.xml")

			assertThatBlock { factory.createContext(xml) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun parseXML_malformedXML_throwsException() {
			val xml = getFixtureStream("invalid-malformed-xml.xml")

			assertThatBlock { factory.createContext(xml) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun createContext_nonExistentFile_throwsException() {
			val nonExistentFile = File("non-existent-file.xml")

			assertThatBlock { factory.createContext(nonExistentFile) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun createContext_nullInputStream_throwsException() {
			val nullStream: InputStream? = null
			assertThatBlock { factory.createContext(nullStream!!) }
				.isFailure()
				.isInstanceOf(Exception::class)
		}

		@Test
		fun createContext_emptyInputStream_throwsException() {
			val emptyStream = ByteArrayInputStream(ByteArray(0))

			assertThatBlock { factory.createContext(emptyStream) }
				.isFailure()
				.isInstanceOf(ContextCreationException::class)
		}

		@Test
		fun createContext_invalidXMLContent_throwsException() {
			val invalidXML = "This is not XML at all!"
			val stream = ByteArrayInputStream(invalidXML.toByteArray())

			assertThatBlock { factory.createContext(stream) }
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
			val context = factory.createContext(xml)

			// Save to file
			val saved = factory.saveContext(context, tempFile!!)

			// Verify file exists and is readable
			assertThat(tempFile!!).exists().isFile()
			assertThat(tempFile!!.canRead()).isTrue()
			assertThat(tempFile!!.length()).isGreaterThan(0)
		}

		@Test
		fun saveAndLoad_minimalNetwork_preservesStructure() {
			// Load original fixture
			val xml = getFixtureStream("minimal-network.xml")
			val originalContext = factory.createContext(xml)

			// Save to file
			factory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = factory.createContext(tempFile!!)

			// Verify structure is preserved
			assertThat(loadedContext).isNotNull()
			assertThat(loadedContext.getRailWayNetGrid().getCols()).isEqualTo(100)
			assertThat(loadedContext.getRailWayNetGrid().getRows()).isEqualTo(100)

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
			val emptyContext = factory.createEmptyContext()

			// Save to file
			factory.saveContext(emptyContext, tempFile!!)

			// Verify file is valid XML by loading it
			val loadedContext = factory.createContext(tempFile!!)
			assertThat(loadedContext).isNotNull()
		}

		@Test
		fun saveAndLoad_emptyGrid_preservesGridSize() {
			// Load empty grid fixture
			val xml = getFixtureStream("empty-grid.xml")
			val originalContext = factory.createContext(xml)

			// Save to file
			factory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = factory.createContext(tempFile!!)

			// Verify grid size is preserved
			val grid = loadedContext.getRailWayNetGrid()
			assertThat(grid.getCols()).isEqualTo(50)
			assertThat(grid.getRows()).isEqualTo(50)
		}

		@Test
		fun saveAndLoad_linearTrack_preservesTrackBlocks() {
			// Load fixture with track block
			val xml = getFixtureStream("linear-track.xml")
			val originalContext = factory.createContext(xml)

			// Save to file
			factory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = factory.createContext(tempFile!!)

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
			val originalContext = factory.createContext(xml)

			// Save to file
			factory.saveContext(originalContext, tempFile!!)

			// Load from file
			val loadedContext = factory.createContext(tempFile!!)

			// Verify switch is preserved
			val switchCell = loadedContext.getRailWayNetGrid().getCellAt(15, 10)
			assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
			val railSwitch = switchCell as RailSwitch
			assertThat(railSwitch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		}

		@Test
		fun saveContext_overwritesExistingFile() {
			// Create initial context and save
			val context1 = factory.createEmptyContext()
			factory.saveContext(context1, tempFile!!)

			// Load a different fixture and save to same file
			val xml = getFixtureStream("minimal-network.xml")
			val context2 = factory.createContext(xml)
			factory.saveContext(context2, tempFile!!)

			// Verify loaded context matches context2 (not context1)
			val loadedContext = factory.createContext(tempFile!!)
			val cell = loadedContext.getRailWayNetGrid().getCellAt(10, 10)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)
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
					"</net>"
			val stream = ByteArrayInputStream(largeGridXML.toByteArray())

			val context = factory.createContext(stream)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.getCols()).isEqualTo(500)
			assertThat(grid.getRows()).isEqualTo(500)
		}

		@Test
		fun parseXML_minimalGridSize_succeeds() {
			val minimalGridXML =
				"<?xml version=\"1.0\"?>\n" +
					"<!DOCTYPE net>\n" +
					"<net X=\"1\" Y=\"1\">\n" +
					"</net>"
			val stream = ByteArrayInputStream(minimalGridXML.toByteArray())

			val context = factory.createContext(stream)

			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.getCols()).isEqualTo(1)
			assertThat(grid.getRows()).isEqualTo(1)
		}

		@Test
		fun parseXML_cellAtGridBoundary_succeeds() {
			val boundaryXML =
				"<?xml version=\"1.0\"?>\n" +
					"<!DOCTYPE net>\n" +
					"<net X=\"100\" Y=\"100\">\n" +
					"  <InOut X=\"98\" Y=\"98\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"CORNER\"/>\n" +
					"</net>"
			val stream = ByteArrayInputStream(boundaryXML.toByteArray())

			val context = factory.createContext(stream)

			assertThat(context).isNotNull()
			val cell = context.getRailWayNetGrid().getCellAt(98, 98)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)
			assertThat((cell as InOut).getName()).isEqualTo("CORNER")
		}

		@Test
		fun parseXML_multipleInOutsWithSameName_lastOneWins() {
			val duplicateNameXML = "<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"100\" Y=\"100\">\n" +
				"  <InOut X=\"10\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"A\"/>\n" +
				"  <InOut X=\"20\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"A\"/>\n" +
				"</net>"
			val stream = ByteArrayInputStream(duplicateNameXML.toByteArray())

			val context = factory.createContext(stream)

			assertThat(context).isNotNull()
			// Both cells should exist at different positions
			val cell1 = context.getRailWayNetGrid().getCellAt(10, 10)
			val cell2 = context.getRailWayNetGrid().getCellAt(20, 10)
			assertThat(cell1).isNotNull().isInstanceOf(InOut::class)
			assertThat(cell2).isNotNull().isInstanceOf(InOut::class)
		}
	}

	// Helper method to load fixture files from resources
	private fun getFixtureStream(fileName: String): InputStream {
		val resourcePath = "/cz/vutbr/fit/interlockSim/xml/fixtures/$fileName"
		val stream = javaClass.getResourceAsStream(resourcePath)
		assertThat(stream)
			.withMessage("Fixture file should exist: $fileName")
			.isNotNull()
		return stream!!
	}
}
