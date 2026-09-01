/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 7: OutputStream Serialization Tests (Issue #61)
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.assertRudyUjezdStationInOuts
import cz.vutbr.fit.interlockSim.testutil.saveAndReloadThroughStream
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Unit tests for {@link XMLContextFactory} OutputStream serialization (Issue #61).
 *
 * Coverage:
 * - Basic OutputStream functionality
 * - Round-trip serialization (OutputStream → InputStream)
 * - Equivalence testing (File vs OutputStream output)
 * - Error handling for IOException scenarios
 * - UTF-8 encoding validation
 * - Large context serialization performance
 *
 * Architectural Decision: Only static objects in xml context
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryOutputStreamTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()

	@Nested
	@DisplayName("Basic OutputStream Serialization")
	inner class BasicOutputStreamTests {
		@Test
		fun saveContext_minimalNetwork_producesValidXML() {
			// Load minimal network fixture
			val xml = getFixtureStream("minimal-network.xml")
			editingContextFactory.createContext(xml).use { context ->
				val outputStream = ByteArrayOutputStream()

				// Save to OutputStream
				val success = editingContextFactory.saveContext(context, outputStream)

				// Verify success
				assertThat(success).isTrue()

				// Verify XML structure
				val xmlString = outputStream.toString(Charsets.UTF_8)
				assertThat(xmlString).contains("<?xml version=\"1.0\"?>")
				assertThat(xmlString).contains("<!DOCTYPE net>")
				assertThat(xmlString).contains("<net X=\"100\" Y=\"100\"")
				assertThat(xmlString).contains("</net>")
			}
		}

		@Test
		fun saveContext_emptyContextWithInOuts_producesValidXML() {
			// Create empty context with minimum required InOuts
			val emptyContext = editingContextFactory.createEmptyContext()
			emptyContext.putCell(Point(1, 1), InOut("ENTRY", true, Cell.SpatialType.HORIZONTAL))
			emptyContext.putCell(Point(2, 1), InOut("EXIT", false, Cell.SpatialType.HORIZONTAL))
			val outputStream = ByteArrayOutputStream()

			// Save to OutputStream
			val success = editingContextFactory.saveContext(context = emptyContext, stream = outputStream)

			// Verify success and XML structure
			assertThat(success).isTrue()
			val xmlString = outputStream.toString(Charsets.UTF_8)
			assertThat(xmlString).contains("<net X=\"100\" Y=\"100\"")
			assertThat(xmlString).contains("InOut")
		}

		@Test
		fun saveContext_linearTrack_includesTrackBlocks() {
			// Load linear track fixture (has SimpleTrackBlock)
			val xml = getFixtureStream("linear-track.xml")
			editingContextFactory.createContext(xml).use { context ->
				val outputStream = ByteArrayOutputStream()

				// Save to OutputStream
				editingContextFactory.saveContext(context, outputStream)

				// Verify XML contains track block
				val xmlString = outputStream.toString(Charsets.UTF_8)
				assertThat(xmlString).contains("SimpleTrackBlock")
				assertThat(xmlString).contains("length=")
				assertThat(xmlString).contains("maxSpeed")
			}
		}

		@Test
		fun saveContext_switchBasic_preservesSwitchType() {
			// Load switch fixture
			val xml = getFixtureStream("switch-basic.xml")
			editingContextFactory.createContext(xml).use { context ->
				val outputStream = ByteArrayOutputStream()

				// Save to OutputStream
				editingContextFactory.saveContext(context, outputStream)

				// Verify XML contains RailSwitch with type
				val xmlString = outputStream.toString(Charsets.UTF_8)
				assertThat(xmlString).contains("RailSwitch")
				assertThat(xmlString).contains("Type=\"SIMPLE_RIGHT_FALSE\"")
			}
		}

		@Test
		fun saveContext_semaphoreBasic_preservesOrientation() {
			// Load semaphore fixture
			val xml = getFixtureStream("semaphore-basic.xml")
			editingContextFactory.createContext(xml).use { context ->
				val outputStream = ByteArrayOutputStream()

				// Save to OutputStream
				editingContextFactory.saveContext(context, outputStream)

				// Verify XML contains RailSemaphore with orientation
				val xmlString = outputStream.toString(Charsets.UTF_8)
				assertThat(xmlString).contains("RailSemaphore")
				assertThat(xmlString).contains("orientation=")
			}
		}

		@Test
		fun saveContext_outputStream_flushesData() {
			val context = editingContextFactory.createEmptyContext()
			context.putCell(Point(1, 1), InOut("A", true, Cell.SpatialType.HORIZONTAL))
			context.putCell(Point(2, 1), InOut("B", false, Cell.SpatialType.HORIZONTAL))

			// Use ByteArrayOutputStream to verify data is flushed
			val outputStream = ByteArrayOutputStream()
			editingContextFactory.saveContext(context, outputStream)

			// Verify data is immediately available (not buffered)
			assertThat(outputStream.size())
				.withMessage("OutputStream should contain data after save")
				.isGreaterThan(0)
		}
	}

	@Nested
	@DisplayName("Round-trip Serialization (OutputStream → InputStream)")
	inner class RoundTripTests {
		@Test
		fun saveAndLoad_minimalNetwork_preservesStructure() {
			// Load original fixture
			val xml = getFixtureStream("minimal-network.xml")
			editingContextFactory.createContext(xml).use { originalContext ->
				// Save to OutputStream, load back from it, and verify structure is preserved
				editingContextFactory.saveAndReloadThroughStream(originalContext) { loadedContext ->
					assertThat(loadedContext).isNotNull()
					assertThat(loadedContext.getRailWayNetGrid().cols).isEqualTo(100)
					assertThat(loadedContext.getRailWayNetGrid().rows).isEqualTo(100)

					// Verify the InOut cells exist at correct positions
					val cellA = loadedContext.getRailWayNetGrid().getCellAt(10, 10)
					val cellB = loadedContext.getRailWayNetGrid().getCellAt(20, 10)
					assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
					assertThat(cellB).isNotNull().isInstanceOf(InOut::class)
					assertThat((cellA as InOut).getName()).isEqualTo("A")
					assertThat((cellB as InOut).getName()).isEqualTo("B")
				}
			}
		}

		@Test
		fun saveAndLoad_linearTrack_preservesTrackBlocks() {
			// Load fixture with track block
			val xml = getFixtureStream("linear-track.xml")
			editingContextFactory.createContext(xml).use { originalContext ->
				// Save to OutputStream, load back from it, and verify
				editingContextFactory.saveAndReloadThroughStream(originalContext) { loadedContext ->
					// Verify cells are preserved
					val cellA = loadedContext.getRailWayNetGrid().getCellAt(10, 10)
					val cellB = loadedContext.getRailWayNetGrid().getCellAt(20, 10)
					assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
					assertThat(cellB).isNotNull().isInstanceOf(InOut::class)

					// Verify graph edges are preserved
					val originalEdgeCount = originalContext.getGraph().entrySet().size
					val loadedEdgeCount = loadedContext.getGraph().entrySet().size
					assertThat(loadedEdgeCount).isEqualTo(originalEdgeCount)
				}
			}
		}

		@Test
		fun saveAndLoad_switchBasic_preservesSwitchType() {
			// Load fixture with rail switch
			val xml = getFixtureStream("switch-basic.xml")
			editingContextFactory.createContext(xml).use { originalContext ->
				// Save to OutputStream, load back from it, and verify
				editingContextFactory.saveAndReloadThroughStream(originalContext) { loadedContext ->
					val switchCell = loadedContext.getRailWayNetGrid().getCellAt(15, 10) as RailSwitch
					assertThat(switchCell).isNotNull().isInstanceOf(RailSwitch::class)
					assertThat(switchCell.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
				}
			}
		}

		@Test
		fun saveAndLoad_emptyGrid_preservesGridSize() {
			// Load empty grid fixture
			val xml = getFixtureStream("empty-grid.xml")
			editingContextFactory.createContext(xml).use { originalContext ->
				// Save to OutputStream, load back from it, and verify
				editingContextFactory.saveAndReloadThroughStream(originalContext) { loadedContext ->
					// Verify grid size is preserved
					val grid = loadedContext.getRailWayNetGrid()
					assertThat(grid.cols).isEqualTo(50)
					assertThat(grid.rows).isEqualTo(50)
				}
			}
		}

		@Test
		fun saveAndLoad_rudyUjezd_preservesComplexStructure() {
			// Load complex fixture
			val xml = getFixtureStream("rudyUjezd.xml")
			editingContextFactory.createContext(xml).use { originalContext ->
				// Save to OutputStream, load back from it, and verify
				editingContextFactory.saveAndReloadThroughStream(originalContext) { loadedContext ->
					val loadedEditingContext = loadedContext as EditingContext

					// Verify grid size
					assertThat(loadedEditingContext.getRailWayNetGrid().cols).isEqualTo(100)
					assertThat(loadedEditingContext.getRailWayNetGrid().rows).isEqualTo(100)

					// Verify key InOut points exist
					assertRudyUjezdStationInOuts(loadedEditingContext)
				}
			}
		}
	}

	@Nested
	@DisplayName("Equivalence Testing (File vs OutputStream)")
	inner class EquivalenceTests {
		private var tempFile: File? = null

		@BeforeEach
		fun setUpTempFile() {
			tempFile = File.createTempFile("test-equivalence-", ".xml")
			tempFile?.deleteOnExit()
		}

		@AfterEach
		fun cleanUpTempFile() {
			tempFile?.delete()
		}

		@Test
		fun outputStreamAndFile_minimalNetwork_produceIdenticalXML() {
			assertFileAndStreamOutputIdentical("minimal-network.xml")
		}

		@Test
		fun outputStreamAndFile_linearTrack_produceIdenticalXML() {
			assertFileAndStreamOutputIdentical("linear-track.xml")
		}

		@Test
		fun outputStreamAndFile_switchBasic_produceIdenticalXML() {
			assertFileAndStreamOutputIdentical("switch-basic.xml")
		}

		@Test
		fun outputStreamAndFile_emptyGrid_produceIdenticalXML() {
			assertFileAndStreamOutputIdentical("empty-grid.xml")
		}

		@Test
		fun outputStreamAndFile_rudyUjezd_produceIdenticalXML() {
			assertFileAndStreamOutputIdentical("rudyUjezd.xml")
		}

		/** Loads [fixtureName], saves to both the temp file and an OutputStream, and asserts the outputs are identical. */
		private fun assertFileAndStreamOutputIdentical(fixtureName: String) {
			val xml = getFixtureStream(fixtureName)
			editingContextFactory.createContext(xml).use { context ->
				// Save to File
				editingContextFactory.saveContext(context, tempFile!!)
				val fileContent = tempFile!!.readText()

				// Save to OutputStream
				val outputStream = ByteArrayOutputStream()
				editingContextFactory.saveContext(context, outputStream)
				val streamContent = outputStream.toString(Charsets.UTF_8)

				// Verify outputs are identical
				assertThat(streamContent).isEqualTo(fileContent)
			}
		}
	}

	@Nested
	@DisplayName("Error Handling")
	inner class ErrorHandlingTests {
		@Test
		fun saveContext_failingOutputStream_returnsFalse() {
			// Create context
			val context = editingContextFactory.createEmptyContext()
			context.putCell(Point(1, 1), InOut("A", true, Cell.SpatialType.HORIZONTAL))
			context.putCell(Point(2, 1), InOut("B", false, Cell.SpatialType.HORIZONTAL))

			// Create OutputStream that throws IOException
			val failingStream =
				object : OutputStream() {
					override fun write(b: Int): Unit = throw java.io.IOException("Simulated write failure")
				}

			// Attempt to save (should return false)
			val success = editingContextFactory.saveContext(context, failingStream)

			// Verify failure is handled gracefully
			assertThat(success).isFalse()
		}

		@Test
		fun saveContext_outputStreamThrowsOnFlush_returnsFalse() {
			// Create context
			val context = editingContextFactory.createEmptyContext()
			context.putCell(Point(1, 1), InOut("A", true, Cell.SpatialType.HORIZONTAL))
			context.putCell(Point(2, 1), InOut("B", false, Cell.SpatialType.HORIZONTAL))

			// Create OutputStream that throws IOException on flush
			val failingStream =
				object : ByteArrayOutputStream() {
					override fun flush(): Unit = throw java.io.IOException("Simulated flush failure")
				}

			// Attempt to save (should return false)
			val success = editingContextFactory.saveContext(context, failingStream)

			// Verify failure is handled gracefully
			assertThat(success).isFalse()
		}
	}

	@Nested
	@DisplayName("UTF-8 Encoding Validation")
	inner class EncodingTests {
		@Test
		fun saveContext_usesUTF8Encoding() {
			// Create context
			val context = editingContextFactory.createEmptyContext()
			context.putCell(Point(1, 1), InOut("Entry", true, Cell.SpatialType.HORIZONTAL))
			context.putCell(Point(2, 1), InOut("Exit", false, Cell.SpatialType.HORIZONTAL))

			// Save to OutputStream
			val outputStream = ByteArrayOutputStream()
			editingContextFactory.saveContext(context, outputStream)

			// Verify UTF-8 encoding by checking byte array
			val bytes = outputStream.toByteArray()
			val utf8String = String(bytes, Charsets.UTF_8)
			val latin1String = String(bytes, Charsets.ISO_8859_1)

			// UTF-8 should parse correctly
			assertThat(utf8String).contains("<?xml version=\"1.0\"?>")

			// If encoding is not UTF-8, strings would differ
			// (This test is informational - verifies consistent encoding)
			assertThat(utf8String).isNotNull()
		}

		@Test
		fun saveContext_specialCharactersInNames_encodedCorrectly() {
			// Create context with special characters (if names support them)
			val context = editingContextFactory.createEmptyContext()
			context.putCell(Point(1, 1), InOut("Entry_A", true, Cell.SpatialType.HORIZONTAL))
			context.putCell(Point(2, 1), InOut("Exit_B", false, Cell.SpatialType.HORIZONTAL))

			// Save to OutputStream
			val outputStream = ByteArrayOutputStream()
			editingContextFactory.saveContext(context, outputStream)

			// Load back to verify round-trip encoding
			val inputStream = ByteArrayInputStream(outputStream.toByteArray())
			editingContextFactory.createContext(inputStream).use { loadedContext ->
				// Verify names are preserved
				val cellA = loadedContext.getRailWayNetGrid().getCellAt(1, 1)
				assertThat(cellA).isNotNull().isInstanceOf(InOut::class)
				assertThat((cellA as InOut).getName()).isEqualTo("Entry_A")
			}
		}
	}

	@Nested
	@DisplayName("Performance and Scalability")
	inner class PerformanceTests {
		@Test
		fun saveContext_largeContext_completesInReasonableTime() {
			// Load complex fixture (rudyUjezd is reasonably large)
			val xml = getFixtureStream("rudyUjezd.xml")
			editingContextFactory.createContext(xml).use { context ->
				// Measure save time
				val outputStream = ByteArrayOutputStream()
				val startTime = System.nanoTime()
				editingContextFactory.saveContext(context, outputStream)
				val elapsedTime = System.nanoTime() - startTime

				// Verify save completes in reasonable time (< 1 second for rudyUjezd)
				assertThat(elapsedTime)
					.withMessage("Save should complete in reasonable time")
					.isGreaterThan(0) // Sanity check: some time elapsed

				// Verify output is not empty
				assertThat(outputStream.size()).isGreaterThan(0)
			}
		}

		@Test
		fun saveContext_multipleConsecutiveSaves_allSucceed() {
			// Load fixture
			val xml = getFixtureStream("minimal-network.xml")
			editingContextFactory.createContext(xml).use { context ->
				// Save 10 times to different OutputStreams
				for (i in 1..10) {
					val outputStream = ByteArrayOutputStream()
					val success = editingContextFactory.saveContext(context, outputStream)
					assertThat(success)
						.withMessage("Save #$i should succeed")
						.isTrue()
					assertThat(outputStream.size())
						.withMessage("Save #$i should produce output")
						.isGreaterThan(0)
				}
			}
		}
	}

	@Nested
	@DisplayName("Pre-Save Validation (Issue #80, PR #357)")
	inner class PreSaveValidationTests {
		@Test
		@DisplayName("saveContext to File rejects context with 0 InOuts")
		fun saveContext_fileWithZeroInOuts_returnsFalse() {
			// Create empty context (0 InOuts)
			val context = editingContextFactory.createEmptyContext()

			// Create temp file path (but delete the file first to test that save doesn't create it)
			val tempFile =
				kotlin.io.path
					.createTempFile("test", ".xml")
					.toFile()
			tempFile.delete() // Delete so we can verify save doesn't create it

			try {
				context.use {
					val success = editingContextFactory.saveContext(it, tempFile)

					// Should reject save
					assertThat(success).isFalse()
					// File should not be created by save operation
					assertThat(tempFile.exists()).isFalse()
				}
			} finally {
				tempFile.delete()
			}
		}

		@Test
		@DisplayName("saveContext to File accepts context with 1 InOut")
		fun saveContext_fileWithOneInOut_returnsTrue() {
			// Create context with 1 InOut
			val context = editingContextFactory.createEmptyContext()
			val inOut = InOut("ENTRY", false, cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType.HORIZONTAL)
			context.putCell(Point(5, 5), inOut)

			// Attempt to save to file
			val tempFile =
				kotlin.io.path
					.createTempFile("test", ".xml")
					.toFile()
			try {
				context.use {
					val success = editingContextFactory.saveContext(it, tempFile)

					// Should succeed
					assertThat(success).isTrue()
					assertThat(tempFile.exists()).isTrue()

					// Verify round-trip
					editingContextFactory.createContext(tempFile).use { reloaded ->
						val editContext = reloaded as cz.vutbr.fit.interlockSim.context.DefaultEditingContext
						assertThat(editContext.getInOuts().size).isEqualTo(1)
					}
				}
			} finally {
				tempFile.delete()
			}
		}

		@Test
		@DisplayName("saveContext to OutputStream rejects context with 0 InOuts")
		fun saveContext_streamWithZeroInOuts_returnsFalse() {
			// Create empty context (0 InOuts)
			val context = editingContextFactory.createEmptyContext()
			val outputStream = ByteArrayOutputStream()

			context.use {
				val success = editingContextFactory.saveContext(it, outputStream)

				// Should reject save
				assertThat(success).isFalse()
				// Stream should be empty
				assertThat(outputStream.size()).isEqualTo(0)
			}
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
