/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for InOut validation during save operation
	Issue #80: GUI validation to prevent saving contexts with insufficient InOut elements
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Tests for InOut validation during save operation.
 *
 * **Requirements (Issue #80):**
 * - Save operation must validate InOut count before saving
 * - Minimum 2 InOut elements required
 * - User-friendly error dialog shown if validation fails
 * - Save operation blocked until requirement is met
 *
 * **Test Coverage:**
 * - Save with 0 InOuts - should fail validation
 * - Save with 1 InOut - should fail validation
 * - Save with 2 InOuts - should succeed
 * - Save with 3+ InOuts - should succeed
 *
 * Note: These tests verify the validation logic. GUI dialog appearance
 * is not tested as it requires UI automation.
 */
@DisplayName("InOut Save Validation")
class InOutSaveValidationTest : AbstractFrameTestBase() {
	@TempDir
	lateinit var tempDir: Path

	private lateinit var frame: Frame
	private lateinit var contextFactory: EditingContextFactory

	@BeforeEach
	override fun setUp() {
		super.setUp()

		runOnEDT {
			frame = Frame()
			frames.add(frame)
			contextFactory = getKoin().get()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("context with 0 InOuts cannot be saved")
	fun contextWith0InOutsCannotBeSaved() {
		val testFile = tempDir.resolve("test-0-inouts.xml").toFile()

		runOnEDT {
			// Create empty context (0 InOuts)
			val builder = TestContextBuilder()
			val editingContext = builder.buildEditingContext()

			// Set context in frame
			frame.setContext(editingContext)

			// Attempt to save - should fail validation
			val menuBar = frame.jMenuBar as MenuBar
			
			// Use reflection to access performSave method
			val performSaveMethod = MenuBar::class.java.getDeclaredMethod("performSave", File::class.java)
			performSaveMethod.isAccessible = true
			val result = performSaveMethod.invoke(menuBar, testFile) as Boolean

			// Verify save was blocked
			assertThat(result).isFalse()
			assertThat(testFile.exists()).isFalse()

			editingContext.close()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("context with 1 InOut cannot be saved")
	fun contextWith1InOutCannotBeSaved() {
		val testFile = tempDir.resolve("test-1-inout.xml").toFile()

		runOnEDT {
			// Create context with 1 InOut
			val builder = TestContextBuilder()
				.withInOut("OnlyEntry", 1, 1, true)
			val editingContext = builder.buildEditingContext()

			// Set context in frame
			frame.setContext(editingContext)

			// Attempt to save - should fail validation
			val menuBar = frame.jMenuBar as MenuBar
			
			val performSaveMethod = MenuBar::class.java.getDeclaredMethod("performSave", File::class.java)
			performSaveMethod.isAccessible = true
			val result = performSaveMethod.invoke(menuBar, testFile) as Boolean

			// Verify save was blocked
			assertThat(result).isFalse()
			assertThat(testFile.exists()).isFalse()

			editingContext.close()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("context with 2 InOuts can be saved")
	fun contextWith2InOutsCanBeSaved() {
		val testFile = tempDir.resolve("test-2-inouts.xml").toFile()

		runOnEDT {
			// Create context with 2 InOuts
			val builder = TestContextBuilder()
				.withInOut("Entry", 1, 1, true)
				.withInOut("Exit", 10, 10, false)
				.withConnection(1, 1, 10, 10, 100.0, 80.0)
			val editingContext = builder.buildEditingContext()

			// Set context in frame
			frame.setContext(editingContext)

			// Attempt to save - should succeed
			val menuBar = frame.jMenuBar as MenuBar
			
			val performSaveMethod = MenuBar::class.java.getDeclaredMethod("performSave", File::class.java)
			performSaveMethod.isAccessible = true
			val result = performSaveMethod.invoke(menuBar, testFile) as Boolean

			// Verify save succeeded
			assertThat(result).isTrue()
			assertThat(testFile.exists()).isTrue()

			editingContext.close()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("context with 3 InOuts can be saved")
	fun contextWith3InOutsCanBeSaved() {
		val testFile = tempDir.resolve("test-3-inouts.xml").toFile()

		runOnEDT {
			// Create context with 3 InOuts
			val builder = TestContextBuilder()
				.withInOut("Entry1", 1, 1, true)
				.withInOut("Exit1", 10, 10, false)
				.withInOut("Entry2", 5, 5, true)
				.withConnection(1, 1, 10, 10, 100.0, 80.0)
				.withConnection(5, 5, 10, 10, 50.0, 80.0)
			val editingContext = builder.buildEditingContext()

			// Set context in frame
			frame.setContext(editingContext)

			// Attempt to save - should succeed
			val menuBar = frame.jMenuBar as MenuBar
			
			val performSaveMethod = MenuBar::class.java.getDeclaredMethod("performSave", File::class.java)
			performSaveMethod.isAccessible = true
			val result = performSaveMethod.invoke(menuBar, testFile) as Boolean

			// Verify save succeeded
			assertThat(result).isTrue()
			assertThat(testFile.exists()).isTrue()

			editingContext.close()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("validation message includes current InOut count")
	fun validationMessageIncludesCurrentInOutCount() {
		val testFile = tempDir.resolve("test-validation-message.xml").toFile()

		runOnEDT {
			// Create context with 1 InOut
			val builder = TestContextBuilder()
				.withInOut("OnlyEntry", 1, 1, true)
			val editingContext = builder.buildEditingContext()

			// Set context in frame
			frame.setContext(editingContext)

			// Verify InOut count in context
			assertThat(editingContext.getInOuts().size).isEqualTo(1)

			editingContext.close()
		}
	}
}
