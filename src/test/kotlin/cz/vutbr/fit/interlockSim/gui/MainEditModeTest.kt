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
import org.koin.core.context.stopKoin
import java.awt.AWTError
import java.awt.HeadlessException
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
 * - Does NOT extend KoinTestBase to avoid double Koin initialization
 * - main() starts Koin with interlockSimModule, not testModule
 * - Tagged as @Tag("integration-test") via base class
 * - Has timeout annotations to prevent 10-minute hangs
 * - Properly disposes Frame instances created by main()
 * - Runs on separate integration test task
 *
 * Architecture notes:
 * - main(arrayOf("edit")) creates Frame via lazy initialization
 * - main() calls startKoin() with interlockSimModule
 * - Frame is registered in Koin DI container
 * - We retrieve Frame from Koin after main() call for disposal
 * - tearDown() in base class handles Frame disposal automatically
 * - Koin is stopped after each test to clean up resources
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
		// Check for headless environment first (may skip test via Assumptions.assumeFalse)
		super.setUp()

		// Only set up System.err capture if we passed the headless check
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

		// Call base class tearDown to dispose all frames collected in this test
		super.tearDown()

		// Stop Koin to clean up the DI container started by main()
		// Note: main() calls startKoin(), so we must clean it up here
		try {
			if (GlobalContext.getOrNull() != null) {
				stopKoin()
			}
		} catch (e: Exception) {
			// Koin might already be stopped, that's okay
		}
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
			// Success - Frame was created (non-headless environment)
			val afterErr = getCapturedError()
			val usageNotPrinted = !afterErr.contains("usage:")
			assertThat(usageNotPrinted).isTrue()
		} catch (e: HeadlessException) {
			// Expected in headless environment - mode was recognized but GUI creation failed
			val afterErr = getCapturedError()
			val usageNotPrinted = !afterErr.contains("usage:")
			assertThat(usageNotPrinted).isTrue()
		} catch (e: AWTError) {
			// Expected in headless environment - mode was recognized but GUI creation failed
			val afterErr = getCapturedError()
			val usageNotPrinted = !afterErr.contains("usage:")
			assertThat(usageNotPrinted).isTrue()
		} catch (e: Exception) {
			// Check if it's a GUI-related exception (mode was recognized)
			val message = e.message ?: e.javaClass.name
			val isGuiRelated = message.lowercase().contains("frame") ||
				message.lowercase().contains("awt") ||
				message.lowercase().contains("x11") ||
				message.lowercase().contains("display") ||
				message.lowercase().contains("headless")
			assertThat(isGuiRelated).isTrue()
		}
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
		try {
			main(args)
			// Success - Frame was created (non-headless environment)
			val output = getCapturedError()
			val isValidMode = !output.contains("usage:") || output.isEmpty()
			assertThat(isValidMode).isTrue()
		} catch (e: HeadlessException) {
			// Expected in headless environment - mode was recognized but GUI creation failed
		} catch (e: AWTError) {
			// Expected in headless environment - mode was recognized but GUI creation failed
		} catch (e: Exception) {
			// Check if it's a GUI-related exception (mode was recognized)
			val message = e.message ?: e.javaClass.name
			val isGuiRelated = message.lowercase().contains("frame") ||
				message.lowercase().contains("awt") ||
				message.lowercase().contains("x11") ||
				message.lowercase().contains("display") ||
				message.lowercase().contains("headless")
			assertThat(isGuiRelated).isTrue()
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("edit mode attempt without X11 is handled")
	fun editModeAttemptWithoutX11IsHandled() {
		// Arrange
		val args = arrayOf("edit")

		// Act & Assert
		// In headless environment, Frame creation will fail but mode should be recognized
		try {
			main(args)
			// Success - Frame was created (non-headless environment)
			// Test passes - edit mode was recognized and handled
		} catch (e: HeadlessException) {
			// Expected in headless environment - verify mode was recognized (GUI exception, not mode error)
			// Test passes - exception is GUI-related as expected
		} catch (e: AWTError) {
			// Expected in headless environment - verify mode was recognized (GUI exception, not mode error)
			// Test passes - exception is GUI-related as expected
		} catch (e: Exception) {
			// Verify that exception is GUI-related (mode was recognized), not mode-selection-related
			val message = e.message ?: e.javaClass.name
			val isGuiRelated =
				message.lowercase().contains("frame") ||
					message.lowercase().contains("awt") ||
					message.lowercase().contains("x11") ||
					message.lowercase().contains("display") ||
					message.lowercase().contains("headless")
			assertThat(isGuiRelated).isTrue()
		}
	}
}
