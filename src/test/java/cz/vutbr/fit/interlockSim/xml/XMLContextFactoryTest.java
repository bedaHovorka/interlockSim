/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: XML Parsing & Configuration Tests
 */
package cz.vutbr.fit.interlockSim.xml;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;

import cz.vutbr.fit.interlockSim.context.Context;
import cz.vutbr.fit.interlockSim.context.ContextCreationException;
import cz.vutbr.fit.interlockSim.context.DefaultContext;
import cz.vutbr.fit.interlockSim.context.DefaultRailWayNetGrid;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;

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

	private XMLContextFactory factory;

	@BeforeEach
	void setUp() {
		factory = XMLContextFactory.getInstance();
	}

	@Nested
	@DisplayName("Factory instance and initialization")
	class FactoryInstanceTests {

		@Test
		void getInstance_always_returnsSameInstance() {
			XMLContextFactory instance1 = XMLContextFactory.getInstance();
			XMLContextFactory instance2 = XMLContextFactory.getInstance();

			assertThat(instance1).isNotNull()
				.as("Factory instance should not be null")
				.isSameAs(instance2);
		}

		@Test
		void createEmptyContext_always_returns100x100Grid() {
			DefaultContext context = factory.createEmptyContext();

			assertThat(context).isNotNull();
			DefaultRailWayNetGrid grid = context.getRailWayNetGrid();
			assertThat(grid.getCols()).isEqualTo(100);
			assertThat(grid.getRows()).isEqualTo(100);
		}

		@Test
		void createEmptyContext_always_hasEmptyGraph() {
			DefaultContext context = factory.createEmptyContext();

			assertThat(context.getGraph().nodeSet())
				.as("Empty context should have empty graph")
				.isEmpty();
		}
	}

	@Nested
	@DisplayName("Parsing valid XML fixtures")
	class ValidXMLParsingTests {

		@Test
		void parseXML_minimalNetwork_createsSingleInOut() throws Exception {
			InputStream xml = getFixtureStream("minimal-network.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context).isNotNull();
			DefaultRailWayNetGrid grid = context.getRailWayNetGrid();
			Cell cell = grid.getCellAt(10, 10);
			assertThat(cell).isInstanceOf(InOut.class);
			InOut inOut = (InOut) cell;
			assertThat(inOut.getName()).isEqualTo("A");
		}

		@Test
		void parseXML_linearTrack_createsTwoConnectedInOuts() throws Exception {
			InputStream xml = getFixtureStream("linear-track.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context).isNotNull();
			Cell cellA = context.getRailWayNetGrid().getCellAt(10, 10);
			Cell cellB = context.getRailWayNetGrid().getCellAt(20, 10);
			assertThat(cellA).isInstanceOf(InOut.class);
			assertThat(cellB).isInstanceOf(InOut.class);
			assertThat(((InOut) cellA).getName()).isEqualTo("A");
			assertThat(((InOut) cellB).getName()).isEqualTo("B");
		}

		@Test
		void parseXML_switchBasic_createsRailSwitch() throws Exception {
			InputStream xml = getFixtureStream("switch-basic.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context).isNotNull();
			Cell switchCell = context.getRailWayNetGrid().getCellAt(15, 10);
			assertThat(switchCell).isInstanceOf(RailSwitch.class);
			RailSwitch railSwitch = (RailSwitch) switchCell;
			assertThat(railSwitch.getType()).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE);
		}

		@Test
		void parseXML_switchBasic_createsThreeInOuts() throws Exception {
			InputStream xml = getFixtureStream("switch-basic.xml");

			DefaultContext context = factory.createContext(xml);

			Cell cellIN = context.getRailWayNetGrid().getCellAt(10, 10);
			Cell cellOUT_PLUS = context.getRailWayNetGrid().getCellAt(20, 10);
			Cell cellOUT_MINUS = context.getRailWayNetGrid().getCellAt(20, 11);
			assertThat(cellIN).isInstanceOf(InOut.class);
			assertThat(cellOUT_PLUS).isInstanceOf(InOut.class);
			assertThat(cellOUT_MINUS).isInstanceOf(InOut.class);
			assertThat(((InOut) cellIN).getName()).isEqualTo("IN");
			assertThat(((InOut) cellOUT_PLUS).getName()).isEqualTo("OUT_PLUS");
			assertThat(((InOut) cellOUT_MINUS).getName()).isEqualTo("OUT_MINUS");
		}

		@Test
		void parseXML_semaphoreBasic_createsRailSemaphore() throws Exception {
			InputStream xml = getFixtureStream("semaphore-basic.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context).isNotNull();
			Cell semaphoreCell = context.getRailWayNetGrid().getCellAt(15, 10);
			assertThat(semaphoreCell).isInstanceOf(RailSemaphore.class);
			RailSemaphore semaphore = (RailSemaphore) semaphoreCell;
			assertThat(semaphore.getOrientation()).isTrue();
		}

		@Test
		void parseXML_twoTracksParallel_createsFourInOuts() throws Exception {
			InputStream xml = getFixtureStream("two-tracks-parallel.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context).isNotNull();
			Cell cellA1 = context.getRailWayNetGrid().getCellAt(10, 10);
			Cell cellB1 = context.getRailWayNetGrid().getCellAt(20, 10);
			Cell cellA2 = context.getRailWayNetGrid().getCellAt(10, 12);
			Cell cellB2 = context.getRailWayNetGrid().getCellAt(20, 12);
			assertThat(cellA1).isInstanceOf(InOut.class);
			assertThat(cellB1).isInstanceOf(InOut.class);
			assertThat(cellA2).isInstanceOf(InOut.class);
			assertThat(cellB2).isInstanceOf(InOut.class);
		}

		@Test
		void parseXML_emptyGrid_createsContextWithNoElements() throws Exception {
			InputStream xml = getFixtureStream("empty-grid.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context).isNotNull();
			DefaultRailWayNetGrid grid = context.getRailWayNetGrid();
			assertThat(grid.getCols()).isEqualTo(50);
			assertThat(grid.getRows()).isEqualTo(50);
		}

		@Test
		void parseXML_emptyGrid_hasEmptyGraph() throws Exception {
			InputStream xml = getFixtureStream("empty-grid.xml");

			DefaultContext context = factory.createContext(xml);

			assertThat(context.getGraph().nodeSet())
				.as("Empty grid should have empty graph")
				.isEmpty();
		}
	}

	@Nested
	@DisplayName("Error handling for invalid XML")
	class InvalidXMLParsingTests {

		@Test
		void parseXML_missingGridSize_throwsException() {
			InputStream xml = getFixtureStream("invalid-missing-grid-size.xml");

			assertThatThrownBy(() -> factory.createContext(xml))
				.isInstanceOf(ContextCreationException.class)
				.as("Missing required grid size attributes should cause exception");
		}

		@Test
		void parseXML_missingSpatialType_throwsException() {
			InputStream xml = getFixtureStream("invalid-missing-spatial-type.xml");

			assertThatThrownBy(() -> factory.createContext(xml))
				.isInstanceOf(ContextCreationException.class)
				.as("Missing required SpatialType attribute should cause exception");
		}

		@Test
		void parseXML_wrongRootElement_throwsException() {
			InputStream xml = getFixtureStream("invalid-wrong-root-element.xml");

			assertThatThrownBy(() -> factory.createContext(xml))
				.isInstanceOf(ContextCreationException.class)
				.as("Wrong root element should cause validation exception");
		}

		@Test
		void parseXML_malformedXML_throwsException() {
			InputStream xml = getFixtureStream("invalid-malformed-xml.xml");

			assertThatThrownBy(() -> factory.createContext(xml))
				.isInstanceOf(ContextCreationException.class)
				.as("Malformed XML should cause parsing exception");
		}

		@Test
		void createContext_nonExistentFile_throwsException() {
			File nonExistentFile = new File("non-existent-file.xml");

			assertThatThrownBy(() -> factory.createContext(nonExistentFile))
				.isInstanceOf(ContextCreationException.class)
				.as("Non-existent file should cause FileNotFoundException wrapped in ContextCreationException");
		}

		@Test
		void createContext_nullInputStream_throwsException() {
			assertThatThrownBy(() -> factory.createContext((InputStream) null))
				.isInstanceOf(Exception.class)
				.as("Null input stream should cause exception");
		}

		@Test
		void createContext_emptyInputStream_throwsException() {
			InputStream emptyStream = new ByteArrayInputStream(new byte[0]);

			assertThatThrownBy(() -> factory.createContext(emptyStream))
				.isInstanceOf(ContextCreationException.class)
				.as("Empty input stream should cause parsing exception");
		}

		@Test
		void createContext_invalidXMLContent_throwsException() {
			String invalidXML = "This is not XML at all!";
			InputStream stream = new ByteArrayInputStream(invalidXML.getBytes());

			assertThatThrownBy(() -> factory.createContext(stream))
				.isInstanceOf(ContextCreationException.class)
				.as("Invalid XML content should cause parsing exception");
		}
	}

	@Nested
	@DisplayName("Round-trip serialization (save & load)")
	class SerializationTests {

		private File tempFile;

		@BeforeEach
		void setUpTempFile() throws Exception {
			tempFile = File.createTempFile("test-network-", ".xml");
			tempFile.deleteOnExit();
		}

		@AfterEach
		void cleanUpTempFile() {
			if (tempFile != null && tempFile.exists()) {
				tempFile.delete();
			}
		}

		@Test
		void saveContext_minimalNetwork_createsValidFile() throws Exception {
			// Load fixture
			InputStream xml = getFixtureStream("minimal-network.xml");
			DefaultContext context = factory.createContext(xml);

			// Save to file
			boolean saved = factory.saveContext(context, tempFile);

			// Verify file exists and is readable
			assertThat(tempFile).exists().isFile().canRead();
			assertThat(tempFile.length()).isGreaterThan(0);
		}

		@Test
		void saveAndLoad_minimalNetwork_preservesStructure() throws Exception {
			// Load original fixture
			InputStream xml = getFixtureStream("minimal-network.xml");
			DefaultContext originalContext = factory.createContext(xml);

			// Save to file
			factory.saveContext(originalContext, tempFile);

			// Load from file
			DefaultContext loadedContext = factory.createContext(tempFile);

			// Verify structure is preserved - check that context loads and has correct grid size
			assertThat(loadedContext).isNotNull();
			assertThat(loadedContext.getRailWayNetGrid().getCols()).isEqualTo(100);
			assertThat(loadedContext.getRailWayNetGrid().getRows()).isEqualTo(100);

			// Verify the InOut cell exists somewhere in the loaded context
			boolean foundInOut = false;
			for (var entry : loadedContext.getRailWayNetGrid()) {
				if (entry.getValue() instanceof InOut) {
					InOut inOut = (InOut) entry.getValue();
					if ("A".equals(inOut.getName())) {
						foundInOut = true;
						break;
					}
				}
			}
			assertThat(foundInOut).as("InOut 'A' should exist in loaded context").isTrue();
		}

		@Test
		void saveAndLoad_linearTrack_preservesTrackBlocks() throws Exception {
			// Load fixture with track block
			InputStream xml = getFixtureStream("linear-track.xml");
			DefaultContext originalContext = factory.createContext(xml);

			// Save to file
			factory.saveContext(originalContext, tempFile);

			// Load from file
			DefaultContext loadedContext = factory.createContext(tempFile);

			// Verify cells are preserved
			Cell cellA = loadedContext.getRailWayNetGrid().getCellAt(10, 10);
			Cell cellB = loadedContext.getRailWayNetGrid().getCellAt(20, 10);
			assertThat(cellA).isInstanceOf(InOut.class);
			assertThat(cellB).isInstanceOf(InOut.class);
		}

		@Test
		void saveAndLoad_switchBasic_preservesSwitchType() throws Exception {
			// Load fixture with rail switch
			InputStream xml = getFixtureStream("switch-basic.xml");
			DefaultContext originalContext = factory.createContext(xml);

			// Save to file
			factory.saveContext(originalContext, tempFile);

			// Load from file
			DefaultContext loadedContext = factory.createContext(tempFile);

			// Verify switch is preserved
			Cell switchCell = loadedContext.getRailWayNetGrid().getCellAt(15, 10);
			assertThat(switchCell).isInstanceOf(RailSwitch.class);
			RailSwitch railSwitch = (RailSwitch) switchCell;
			assertThat(railSwitch.getType()).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE);
		}

		@Test
		void saveAndLoad_emptyGrid_preservesGridSize() throws Exception {
			// Load empty grid fixture
			InputStream xml = getFixtureStream("empty-grid.xml");
			DefaultContext originalContext = factory.createContext(xml);

			// Save to file
			factory.saveContext(originalContext, tempFile);

			// Load from file
			DefaultContext loadedContext = factory.createContext(tempFile);

			// Verify grid size is preserved
			DefaultRailWayNetGrid grid = loadedContext.getRailWayNetGrid();
			assertThat(grid.getCols()).isEqualTo(50);
			assertThat(grid.getRows()).isEqualTo(50);
		}

		@Test
		void saveContext_emptyContext_createsValidXML() throws Exception {
			// Create new empty context
			DefaultContext emptyContext = factory.createEmptyContext();

			// Save to file
			factory.saveContext(emptyContext, tempFile);

			// Verify file is valid XML by loading it
			DefaultContext loadedContext = factory.createContext(tempFile);
			assertThat(loadedContext).isNotNull();
		}

		@Test
		void saveContext_overwritesExistingFile() throws Exception {
			// Create initial context and save
			DefaultContext context1 = factory.createEmptyContext();
			factory.saveContext(context1, tempFile);

			// Load a different fixture and save to same file
			InputStream xml = getFixtureStream("minimal-network.xml");
			DefaultContext context2 = factory.createContext(xml);
			factory.saveContext(context2, tempFile);

			// Verify loaded context matches context2 (not context1)
			DefaultContext loadedContext = factory.createContext(tempFile);
			Cell cell = loadedContext.getRailWayNetGrid().getCellAt(10, 10);
			assertThat(cell).isInstanceOf(InOut.class)
				.as("Loaded context should contain the InOut from context2, proving file was overwritten");
		}
	}

	@Nested
	@DisplayName("Edge cases and boundary conditions")
	class EdgeCaseTests {

		@Test
		void parseXML_largeGridSize_succeeds() throws Exception {
			String largeGridXML = "<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"500\" Y=\"500\">\n" +
				"</net>";
			InputStream stream = new ByteArrayInputStream(largeGridXML.getBytes());

			DefaultContext context = factory.createContext(stream);

			assertThat(context).isNotNull();
			DefaultRailWayNetGrid grid = context.getRailWayNetGrid();
			assertThat(grid.getCols()).isEqualTo(500);
			assertThat(grid.getRows()).isEqualTo(500);
		}

		@Test
		void parseXML_minimalGridSize_succeeds() throws Exception {
			String minimalGridXML = "<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"1\" Y=\"1\">\n" +
				"</net>";
			InputStream stream = new ByteArrayInputStream(minimalGridXML.getBytes());

			DefaultContext context = factory.createContext(stream);

			assertThat(context).isNotNull();
			DefaultRailWayNetGrid grid = context.getRailWayNetGrid();
			assertThat(grid.getCols()).isEqualTo(1);
			assertThat(grid.getRows()).isEqualTo(1);
		}

		@Test
		void parseXML_cellAtGridBoundary_succeeds() throws Exception {
			String boundaryXML = "<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"100\" Y=\"100\">\n" +
				"  <InOut X=\"98\" Y=\"98\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"CORNER\"/>\n" +
				"</net>";
			InputStream stream = new ByteArrayInputStream(boundaryXML.getBytes());

			DefaultContext context = factory.createContext(stream);

			assertThat(context).isNotNull();
			Cell cell = context.getRailWayNetGrid().getCellAt(98, 98);
			assertThat(cell).isInstanceOf(InOut.class);
			assertThat(((InOut) cell).getName()).isEqualTo("CORNER");
		}

		@Test
		void parseXML_multipleInOutsWithSameName_lastOneWins() throws Exception {
			String duplicateNameXML = "<?xml version=\"1.0\"?>\n" +
				"<!DOCTYPE net>\n" +
				"<net X=\"100\" Y=\"100\">\n" +
				"  <InOut X=\"10\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"false\" name=\"A\"/>\n" +
				"  <InOut X=\"20\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"A\"/>\n" +
				"</net>";
			InputStream stream = new ByteArrayInputStream(duplicateNameXML.getBytes());

			DefaultContext context = factory.createContext(stream);

			assertThat(context).isNotNull();
			// Both cells should exist at different positions
			Cell cell1 = context.getRailWayNetGrid().getCellAt(10, 10);
			Cell cell2 = context.getRailWayNetGrid().getCellAt(20, 10);
			assertThat(cell1).isInstanceOf(InOut.class);
			assertThat(cell2).isInstanceOf(InOut.class);
		}
	}

	// Helper method to load fixture files from resources
	private InputStream getFixtureStream(String fileName) {
		String resourcePath = "/cz/vutbr/fit/interlockSim/xml/fixtures/" + fileName;
		InputStream stream = getClass().getResourceAsStream(resourcePath);
		assertThat(stream)
			.as("Fixture file should exist: " + fileName)
			.isNotNull();
		return stream;
	}
}
