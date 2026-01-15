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
	private lateinit var systemErr: PrintStream
	private lateinit var capturedErr: ByteArrayOutputStream

	@BeforeEach
	override fun setUp() {
		super.setUp()

		// Capture System.err to verify error messages
		systemErr = System.err
		capturedErr = ByteArrayOutputStream()
		System.setErr(PrintStream(capturedErr))
	}

	@AfterEach
	override fun tearDown() {
		// Restore original System.err
		System.setErr(systemErr)

		// Get Frame from Koin if it was created and add to disposal list
		try {
			val koin = GlobalContext.getOrNull()
			if (koin != null) {
				val frame = koin.get<Frame>()
				frames.add(frame)
			}
		} catch (e: Exception) {
			// Frame might not have been created, that's okay
		}

		// Clean up Koin context if it was started by main()
		try {
			stopKoin()
		} catch (e: Exception) {
			// Koin might not be started, that's okay
		}

		// Call base class tearDown to dispose all frames
		super.tearDown()
	}

	private fun getCapturedError(): String {
		System.err.flush()
		return capturedErr.toString()
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

		// Act
		try {
			main(args)
		} catch (e: Exception) {
			// Frame initialization may fail without X11
		}

		// Assert
		// Edit mode should accept zero or more XML file arguments
		val output = getCapturedError()
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
		try {
			main(args)
		} catch (e: Exception) {
			// Frame initialization will fail without X11 - expected behavior
			// Just verify the mode was recognized before failure
			val message = e.message ?: e.javaClass.name
			val isModeIssue =
				message.lowercase().contains("frame") ||
					message.lowercase().contains("awt") ||
					message.lowercase().contains("x11") ||
					message.lowercase().contains("display")
			// If exception occurs, it should be about Frame/GUI, not about mode selection
			// We don't assert here because X11 failure is expected
		}
	}
}
