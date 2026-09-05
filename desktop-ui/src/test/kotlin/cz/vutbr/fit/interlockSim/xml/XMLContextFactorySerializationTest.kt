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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.exists
import cz.vutbr.fit.interlockSim.testutil.isFile
import cz.vutbr.fit.interlockSim.testutil.saveAndReloadThroughFile
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Serialization / round-trip tests for [XMLContextFactory]: save a context to a file,
 * load it back, and verify the structure is preserved.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactorySerializationTest : XMLContextFactoryTestBase() {
	@Test
	fun saveContext_minimalNetwork_createsValidFile(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Load fixture
		val xml = getFixtureStream("minimal-network.xml")

		editingContextFactory.createContext(xml).use { context ->
			// Save to file
			assertThat(
				editingContextFactory.saveContext(context, tempFile),
				name = "saveContext(file) must succeed"
			).isTrue()

			// Verify file exists and is readable
			assertThat(tempFile).exists().isFile()
			assertThat(tempFile.canRead()).isTrue()
			assertThat(tempFile.length()).isGreaterThan(0)
		}
	}

	@Test
	fun saveAndLoad_minimalNetwork_preservesStructure(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Load original fixture
		val xml = getFixtureStream("minimal-network.xml")

		editingContextFactory.createContext(xml).use { originalContext ->
			// Save to file, load back, and verify structure is preserved
			editingContextFactory.saveAndReloadThroughFile(originalContext, tempFile) { loadedContext ->
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
		}
	}

	@Test
	fun saveContext_emptyContext_createsValidXML(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Create empty context
		val emptyContext = editingContextFactory.createEmptyContext()

		// Add minimum required InOut elements to satisfy validation
		emptyContext.putCell(Point(1, 1), InOut("ENTRY", true, Cell.SpatialType.HORIZONTAL))
		emptyContext.putCell(Point(2, 1), InOut("EXIT", false, Cell.SpatialType.HORIZONTAL))

		emptyContext.use {
			// Save to file and verify it is valid XML by loading it
			editingContextFactory.saveAndReloadThroughFile(it, tempFile) { loadedContext ->
				assertThat(loadedContext).isNotNull()
			}
		}
	}

	@Test
	fun saveAndLoad_emptyGrid_preservesGridSize(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Load empty grid fixture
		val xml = getFixtureStream("empty-grid.xml")

		editingContextFactory.createContext(xml).use { originalContext ->
			// Save to file, load back, and verify grid size is preserved
			editingContextFactory.saveAndReloadThroughFile(originalContext, tempFile) { loadedContext ->
				val grid = loadedContext.getRailWayNetGrid()
				assertThat(grid.cols).isEqualTo(50)
				assertThat(grid.rows).isEqualTo(50)
			}
		}
	}

	@Test
	fun saveAndLoad_linearTrack_preservesTrackBlocks(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Load fixture with track block
		val xml = getFixtureStream("linear-track.xml")

		editingContextFactory.createContext(xml).use { originalContext ->
			// Save to file, load back, and verify
			editingContextFactory.saveAndReloadThroughFile(originalContext, tempFile) { loadedContext ->
				// Verify cells are preserved
				val cellA = loadedContext.getRailWayNetGrid().getCellAt(10, 10)
				val cellB = loadedContext.getRailWayNetGrid().getCellAt(20, 10)
				assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
				assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
			}
		}
	}

	@Test
	fun saveAndLoad_switchBasic_preservesSwitchType(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Load fixture with rail switch
		val xml = getFixtureStream("switch-basic.xml")

		editingContextFactory.createContext(xml).use { originalContext ->
			// Save to file, load back, and verify
			editingContextFactory.saveAndReloadThroughFile(originalContext, tempFile) { loadedContext ->
				// Verify switch is preserved
				val switchCell = loadedContext.getRailWayNetGrid().getCellAt(15, 10)
				assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
				val railSwitch = switchCell as RailSwitch
				assertThat(railSwitch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			}
		}
	}

	@Test
	fun saveContext_overwritesExistingFile(
		@TempDir tempDir: File
	) {
		val tempFile = File(tempDir, "network.xml")

		// Create the initial context and save it. It needs its own two InOuts at coordinates
		// that minimal-network.xml does not use: with 0 InOuts the save fails validation, no
		// file is written, and the test would pass even if overwriting were broken.
		editingContextFactory.createEmptyContext().use { context1 ->
			context1.putCell(Point(50, 50), InOut("FIRST_A", true, Cell.SpatialType.HORIZONTAL))
			context1.putCell(Point(60, 50), InOut("FIRST_B", false, Cell.SpatialType.HORIZONTAL))
			assertThat(
				editingContextFactory.saveContext(context1, tempFile),
				name = "the first saveContext must succeed, otherwise nothing is overwritten"
			).isTrue()
		}
		assertThat(tempFile.exists(), name = "the first save must write the file").isTrue()

		// Load a different fixture and save to the same file
		val xml = getFixtureStream("minimal-network.xml")
		editingContextFactory.createContext(xml).use { context2 ->
			assertThat(
				editingContextFactory.saveContext(context2, tempFile),
				name = "the overwriting saveContext must succeed"
			).isTrue()
		}

		// Verify loaded context matches context2 (not context1)
		editingContextFactory.createContext(tempFile).use { loadedContext ->
			val grid = loadedContext.getRailWayNetGrid()
			assertThat(grid.getCellAt(10, 10))
				.isNotNull()
				.isInstanceOf(InOut::class)
				.withMessage("Loaded context should contain the InOut from context2, proving file was overwritten")
			assertThat(grid.getCellAt(50, 50), name = "the InOut of context1 must be gone").isEqualTo(null)
		}
	}
}
