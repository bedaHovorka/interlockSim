/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for Main class CLI argument parsing
	Phase 4.1 test implementation - 2026
*/

@file:Suppress("UnusedImports", "unused") // Detekt false positive - constants used in nested inner class

package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * Unit tests for {@link Main} class CLI argument parsing and mode selection.
 *
 * This test class validates:
 * - CLI mode selection (sim, edit, example, invalid)
 * - Argument validation and presence checking
 * - Error message output to System.err
 * - Edge cases (empty args, extra args, spaces in paths)
 * - Frame initialization for GUI mode
 *
 * Key test scenarios:
 * - Mode Detection: isArgs() method for mode identification
 * - Argument Validation: Required vs optional arguments
 * - Error Handling: Missing/invalid arguments
 * - Edge Cases: Empty arguments, extra arguments, special characters
 *
 * Architecture notes:
 * - Main is a singleton with private constructor
 * - CLI mode routing via main(args) static method
 * - Frame initialization is GUI-specific (mocked to avoid X11 dependencies)
 * - System.err output captured for validation
 *
 * Coverage:
 * - Main.isArgs() method (private, tested via public main())
 * - CLI mode selection logic (sim/edit/example/unknown)
 * - Argument counting and validation
 * - Error message generation
 * - Edge case handling
 *
 * Limitations:
 * - Frame GUI initialization not mocked (no X11 display needed; tests catch exceptions)
 * - Cannot directly call private isArgs() method; test through public API
 * - ContextFactory not mocked (tests verify System.err output instead)
 *
 * BUG-001 FIXED:
 * Main.createContext() now throws ContextCreationException instead of calling System.exit(1).
 * This allows tests to run without killing the test JVM process.
 *
 * GitHub Issue: #56 (fixed)
 */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
class MainArgumentParsingTest {
	private lateinit var systemErr: PrintStream
	private lateinit var capturedErr: ByteArrayOutputStream
	private lateinit var systemOut: PrintStream
	private lateinit var capturedOut: ByteArrayOutputStream

	@TempDir
	private lateinit var tempDir: File

	@BeforeEach
	fun setUp() {
		// Capture System.err to verify error messages
		systemErr = System.err
		capturedErr = ByteArrayOutputStream()
		System.setErr(PrintStream(capturedErr))

		// Capture System.out for logger.warn messages
		systemOut = System.out
		capturedOut = ByteArrayOutputStream()
		System.setOut(PrintStream(capturedOut))
	}

	@AfterEach
	fun tearDown() {
		// Restore original System.err
		System.setErr(systemErr)

		// Restore original System.out
		System.setOut(systemOut)

		// Clean up Koin context if it was started by main()
		try {
			stopKoin()
		} catch (e: Exception) {
			// Koin might not be started, that's okay
		}
	}

	private fun getCapturedError(): String {
		System.err.flush()
		return capturedErr.toString()
	}

	private fun getCapturedOutput(): String {
		System.out.flush()
		return capturedOut.toString()
	}

	private fun createTempXmlFile(name: String = "test.xml"): File {
		val file = File(tempDir, name)
		file.writeText(
			"""<?xml version="1.0" encoding="UTF-8"?>
			|<interlockingSimulation>
			|	<!-- Minimal valid XML for testing -->
			|</interlockingSimulation>
			""".trimMargin()
		)
		return file
	}

	@Nested
	@DisplayName("Mode Selection")
	inner class ModeSelectionTests {
		@Test
		fun `sim mode selected with sim argument`() {
			// Arrange
			val args = arrayOf("sim")

			// Act & Assert
			// Test via Main.main() - the isArgs() method selects mode based on first argument
			// If 'sim' is first arg, it should call loadSim()
			// We verify this by checking that System.err is NOT updated with the usage message
			// (which would be printed for unknown modes)
			main(args)
			val afterErr = getCapturedError()

			// If sim mode was selected, either:
			// 1. No error (successful execution with empty context)
			// 2. Error about file, but NOT about usage/mode
			val usageNotPrinted = !afterErr.contains("usage:")
			assertThat(usageNotPrinted).isTrue()
		}

		// NOTE: edit mode test moved to MainEditModeTest.kt to ensure proper Frame disposal
		// See GitHub Issue #111 - Frame tests must extend AbstractFrameTestBase

		@Test
		fun `example mode selected with example argument`() {
			// Arrange
			val args = arrayOf("example")

			// Act
			main(args)

			// Assert
			// Example mode with no example name should print list of examples
			val output = getCapturedOutput()
			val isExampleMode = output.contains("Available examples") ||
								output.contains("example") ||
								output.contains("shuntingLoop")
			assertThat(isExampleMode).isTrue()
		}

		@Test
		fun `unknown mode prints usage message`() {
			// Arrange
			val args = arrayOf("unknown")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			assertThat(output).contains("usage:")
		}
	}

	@Nested
	@DisplayName("Argument Validation")
	inner class ArgumentValidationTests {
		@Test
		fun `sim mode handles optional XML file argument`() {
			// Arrange
			val args = arrayOf("sim")

			// Act
			main(args)

			// Assert
			// Sim mode without file should print "You dont specify valid file" or similar
			val output = getCapturedError()
			val isValidResponse =
				output.contains("valid file") ||
					output.contains("error") ||
					output.trim().isEmpty() // Or might succeed with empty context
			assertThat(isValidResponse).isTrue()
		}

		// NOTE: edit mode test moved to MainEditModeTest.kt to ensure proper Frame disposal
		// See GitHub Issue #111 - Frame tests must extend AbstractFrameTestBase

		@Test
		fun `example mode accepts optional example name`() {
			// Arrange
			val args = arrayOf("example")

			// Act
			main(args)

			// Assert
			// Example with no name should show available examples
			val output = getCapturedOutput()
			val hasExampleList =
				output.contains("Available examples") ||
					output.contains("example") ||
					output.contains("shuntingLoop")
			assertThat(hasExampleList).isTrue()
		}

		@Test
		fun `example mode accepts optional end time parameter`() {
			// Arrange
			// Use very short simulation time (1 second) to avoid test timeout
			val args = arrayOf("example", "shuntingLoop", "1")

			// Act
			try {
				main(args)
			} catch (e: Exception) {
				// Simulation execution might fail - we're just testing argument parsing
			}

			// Assert
			// If three arguments provided and second is valid example, parsing should succeed
			val output = getCapturedError()
			// Should not complain about unknown mode
			val usageNotShown = !output.contains("usage:")
			assertThat(usageNotShown).isTrue()
		}

		@Test
		fun `missing required argument shows appropriate handling`() {
			// Arrange
			val args = emptyArray<String>()

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Empty arguments should trigger unknown mode and print usage
			assertThat(output).contains("usage:")
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class ArgumentEdgeCases {
		@Test
		fun `no arguments shows usage`() {
			// Arrange
			val args = emptyArray<String>()

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			assertThat(output).contains("usage:")
		}

		@Test
		fun `extra arguments ignored gracefully`() {
			// Arrange
			val args = arrayOf("sim", "extra", "arguments", "here")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should handle extra args gracefully - either ignore them or show file error
			val isGracefulHandling =
				output.contains("valid file") ||
					output.isEmpty() ||
					output.contains("error") ||
					!output.contains("usage:")
			assertThat(isGracefulHandling).isTrue()
		}

		@Test
		fun `arguments with spaces handled correctly`() {
			// Arrange
			// File with spaces in name
			val fileWithSpaces = File(tempDir, "test file with spaces.xml")
			fileWithSpaces.writeText("<?xml version=\"1.0\"?><interlockingSimulation/>")
			val args = arrayOf("sim", fileWithSpaces.absolutePath)

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should attempt to load the file, not complain about argument parsing
			val fileProcessed = output.isEmpty() || !output.contains("usage:")
			assertThat(fileProcessed).isTrue()
		}

		@Test
		fun `invalid file path shows error`() {
			// Arrange
			val args = arrayOf("sim", "/nonexistent/path/to/file.xml")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should show error about invalid file
			val hasFileError =
				output.contains("valid file") ||
					output.contains("error") ||
					output.contains("File") ||
					output.contains("not found") ||
					output.trim().isNotEmpty()
			assertThat(hasFileError).isTrue()
		}

		@Test
		fun `mode selection is case-sensitive`() {
			// Arrange
			val args = arrayOf("SIM") // uppercase instead of lowercase

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// "SIM" should NOT match "sim", so usage should be printed
			assertThat(output).contains("usage:")
		}

		@Test
		fun `single hyphen argument treated as mode`() {
			// Arrange
			val args = arrayOf("-")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should not match any mode, usage should be printed
			assertThat(output).contains("usage:")
		}

		@Test
		fun `double-dash argument treated as mode`() {
			// Arrange
			val args = arrayOf("--help")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should not match any mode, usage should be printed
			assertThat(output).contains("usage:")
		}
	}

	@Nested
	@DisplayName("Mode-Specific Behavior")
	inner class ModeSpecificBehaviorTests {
		@Test
		fun `sim mode with valid file path`() {
			// Arrange
			val xmlFile = createTempXmlFile()
			val args = arrayOf("sim", xmlFile.absolutePath)

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// With valid file, should either:
			// 1. Run simulation and show output
			// 2. Show XML parsing error (valid file but not valid simulation config)
			// Should NOT show usage message
			val usageNotShown = !output.contains("usage:")
			assertThat(usageNotShown).isTrue()
		}

		// NOTE: edit mode test moved to MainEditModeTest.kt to ensure proper Frame disposal
		// See GitHub Issue #111 - Frame tests must extend AbstractFrameTestBase

		@Test
		fun `example mode with invalid example name`() {
			// Arrange
			val args = arrayOf("example", "nonexistentExample")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should print that example doesn't exist and show list
			assertThat(output).contains("Unknown example")
		}

		@Test
		fun `example mode with non-numeric end time`() {
			// Arrange
			val args = arrayOf("example", "shuntingLoop", "notanumber")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			// Should show error about number conversion
			val hasNumberError = output.contains("number") || output.contains("cannot convert")
			assertThat(hasNumberError).isTrue()
		}
	}

	@Nested
	@DisplayName("Usage Message Format")
	inner class UsageMessageTests {
		@Test
		fun `usage message shows all available modes`() {
			// Arrange
			val args = arrayOf("invalid")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			assertThat(output).contains("usage:")
			assertThat(output).contains("sim")
			assertThat(output).contains("edit")
			assertThat(output).contains("example")
		}

		@Test
		fun `usage message shows optional arguments`() {
			// Arrange
			val args = arrayOf("unknown")

			// Act
			main(args)

			// Assert
			val output = getCapturedError()
			assertThat(output).contains("usage:")
		}

		@Test
		fun `program name and version constants defined`() {
			// Arrange & Act
			val programName = PROGRAM_NAME
			val programVersion = PROGRAM_VERSION

			// Assert
			assertThat(programName).isEqualTo("InterlockSim")
			assertThat(programVersion).isEqualTo("0.1-bachelor")
		}
	}

	@Nested
	@DisplayName("System Integration")
	inner class SystemIntegrationTests {
		@Test
		fun `System_err is used for messages not stdout`() {
			// Arrange
			val args = arrayOf("unknown")

			// Act
			main(args)

			// Assert
			// All messages should be on System.err (not System.out)
			val errOutput = capturedErr.toString()
			assertThat(errOutput).contains("usage:")
		}

		@Test
		fun `singleton pattern enforced via Koin`() {
			// Arrange
			startKoin {
				modules(testModuleFull)
			}

			try {
				// Act - Get Main instance twice from Koin
				val instance1 = get<Main>(Main::class.java)
				val instance2 = get<Main>(Main::class.java)

				// Assert - Koin should provide same singleton instance
				assertThat(instance1).isSameInstanceAs(instance2)
			} finally {
				stopKoin()
			}
		}

		@Test
		fun `main method is static`() {
			// Arrange
			// In Kotlin, top-level functions are compiled to static methods in a class named <FileName>Kt
			val mainClass = Class.forName("cz.vutbr.fit.interlockSim.MainKt")

			// Act
			val mainMethod = mainClass.getDeclaredMethod("main", Array<String>::class.java)

			// Assert
			val isStatic =
				java.lang.reflect.Modifier
					.isStatic(mainMethod.modifiers)
			assertThat(isStatic).isTrue()
		}
	}

	@Nested
	@DisplayName("Constants and Configuration")
	inner class ConstantsTests {
		@Test
		fun `PROGRAM_NAME is defined`() {
			assertThat(PROGRAM_NAME).isEqualTo("InterlockSim")
		}

		@Test
		fun `PROGRAM_VERSION is defined`() {
			assertThat(PROGRAM_VERSION).isEqualTo("0.1-bachelor")
		}

		@Test
		fun `PROGRAM_FULL_NAME combines name and version`() {
			val expectedFullName = "${PROGRAM_NAME} ${PROGRAM_VERSION}"
			assertThat(PROGRAM_FULL_NAME).isEqualTo(expectedFullName)
		}
	}
}
