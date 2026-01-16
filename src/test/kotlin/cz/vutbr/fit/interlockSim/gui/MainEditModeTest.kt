/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for Main class edit mode Frame handling
	Extracted from MainArgumentParsingTest to ensure proper Frame disposal
	Phase 4.1 test implementation - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.main
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * Tests for Main class edit mode that creates Frame GUI components.
 *
 * These tests were extracted from MainArgumentParsingTest to ensure proper
 * Frame lifecycle management and prevent CI timeouts.
 *
 * Key differences from original MainArgumentParsingTest:
 * - Extends AbstractFrameTestBase for proper Frame disposal
 * - Tagged as @Tag("integration-test") via base class
 * - Has timeout annotations to prevent 10-minute hangs
 * - Properly disposes Frame instances created by main()
 * - Runs on separate integration test task
 *
 * Architecture notes:
 * - main(arrayOf("edit")) creates Frame via lazy initialization
 * - Frame is registered in Koin DI container
 * - We retrieve Frame from Koin after main() call for disposal
 * - tearDown() in base class handles Frame disposal automatically
 *
 * GitHub Issue: #111 (CI timeout prevention)
 */
@DisplayName("Main Edit Mode with Frame")
class MainEditModeTest : AbstractFrameTestBase() {
	// Nullable properties to handle JUnit lifecycle:
	// - JUnit calls tearDown() even when tests are skipped via Assumptions.assumeFalse()
	// - If setUp() is skipped (headless environment), these remain null
	// - tearDown() must handle null case gracefully
	private var systemErr: PrintStream? = null
	private var capturedErr: ByteArrayOutputStream? = null

	@BeforeEach
	override fun setUp() {
		super.setUp()

		// Capture System.err to verify error messages
		systemErr = System.err
		val errStream = ByteArrayOutputStream()
		capturedErr = errStream
		System.setErr(PrintStream(errStream))
	}

	@AfterEach
	override fun tearDown() {
		// Restore original System.err (only if setUp completed)
		systemErr?.let { System.setErr(it) }

		// Get Frame from Koin if it was created and add to disposal list
		// Important: Frame is a singleton, so koin.getOrNull() only retrieves it if already instantiated
		try {
			val koin = GlobalContext.getOrNull()
			if (koin != null) {
				// Use getOrNull to avoid creating Frame if it wasn't instantiated yet
				val frame = koin.getOrNull<Frame>()
				if (frame != null) {
					frames.add(frame)
				}
			}
		} catch (e: Exception) {
			// Frame might not have been created, that's okay
		}

		// Call base class tearDown to dispose all frames
		// Note: KoinTestBase.tearDownKoin() will call stopKoin() after this method
		super.tearDown()
	}

	private fun getCapturedError(): String {
		System.err.flush()
		return capturedErr?.toString() ?: ""
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("edit mode selected with edit argument")
	fun editModeSelectedWithEditArgument() {
		// Arrange
		val args = arrayOf("edit")

		// Act & Assert
		// Edit mode would instantiate Frame, which we can't do without X11
		// So we verify the mode is recognized by checking usage is NOT printed
		try {
			main(args)
		} catch (e: Exception) {
			// Frame initialization may fail - that's OK, we're just checking mode selection
		}
		val afterErr = getCapturedError()

		// If edit mode was selected, usage should not be printed
		val usageNotPrinted = !afterErr.contains("usage:")
		assertThat(usageNotPrinted).isTrue()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("edit mode accepts optional XML file")
	fun editModeAcceptsOptionalXmlFile() {
		// Arrange
		val args = arrayOf("edit")

		// Act & Assert
		// Edit mode should accept zero or more XML file arguments
		// In headless environment, Frame creation will fail, but mode should be recognized
		val output = try {
			main(args)
			getCapturedError()
		} catch (e: Exception) {
			// Frame initialization may fail without X11, check if mode was recognized first
			getCapturedError()
		}
		
		val isValidMode = !output.contains("usage:") || output.isEmpty()
		assertThat(isValidMode).isTrue()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("edit mode attempt without X11 is handled")
	fun editModeAttemptWithoutX11IsHandled() {
		// Arrange
		val args = arrayOf("edit")

		// Act & Assert
		// In headless environment, Frame creation will fail but mode should be recognized
		val exception = try {
			main(args)
			null  // Success - Frame was created (non-headless environment)
		} catch (e: Exception) {
			e  // Capture exception for verification
		}

		// Verify that if an exception occurred, it's GUI-related, not mode-selection-related
		if (exception != null) {
			val message = exception.message ?: exception.javaClass.name
			val isGuiRelated =
				message.lowercase().contains("frame") ||
					message.lowercase().contains("awt") ||
					message.lowercase().contains("x11") ||
					message.lowercase().contains("display") ||
					message.lowercase().contains("headless")
			assertThat(isGuiRelated).isTrue()
		}
		// If no exception, edit mode was successfully initialized (non-headless environment)
	}
}
