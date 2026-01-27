/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for RequireFunctions utility functions
 * Phase 6.2 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.exceptions

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.assertThatThrownBy
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.hasMessageContaining
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for RequireFunctions utility validation functions.
 *
 * Tests cover:
 * - requireSimulation: boolean validation, severity parameter, null checks, state validation
 * - requireEditor: boolean validation, severity parameter, null checks
 * - requireValidArgument: IllegalArgumentException throwing
 * - requireValidState: IllegalStateException throwing
 * - Exception message evaluation and propagation
 * - Lazy message evaluation
 *
 * Coverage target: ~315 instructions (Phase 6.2)
 */
@DisplayName("RequireFunctions")
class RequireFunctionsTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	// ==================== Simulation Validation Functions ====================

	@Nested
	@DisplayName("requireSimulation")
	inner class RequireSimulationTests {
		@Test
		fun `passes when condition is true`() {
			// Act & Assert - should not throw
			requireSimulation(true) { "Should not be thrown" }
		}

		@Test
		fun `throws SimulationException when false`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulation(false) { "Test failure message" }
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Test failure message")
		}

		@Test
		fun `uses default message when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulation(false)
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Simulation requirement failed")
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - condition is true, so message should not be evaluated
			requireSimulation(true, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}

		@Test
		fun `evaluates message when condition fails`() {
			// Arrange
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message evaluated"
			}

			// Act & Assert
			assertThatThrownBy {
				requireSimulation(false, lazyMessage)
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Lazy message evaluated")

			assertThat(messageEvaluated).isEqualTo(true)
		}
	}

	@Nested
	@DisplayName("requireSimulation with Severity")
	inner class RequireSimulationSeverityTests {
		@Test
		fun `passes when condition is true with severity`() {
			// Act & Assert - should not throw
			requireSimulation(true, Severity.ERROR) { "Should not be thrown" }
		}

		@Test
		fun `throws SimulationException with FATAL severity`() {
			// Act
			val exception =
				try {
					requireSimulation(false, Severity.FATAL) { "Fatal error" }
					throw AssertionError("Expected exception to be thrown")
				} catch (e: SimulationException) {
					e
				}

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
			assertThat(exception.message).isEqualTo("Fatal error")
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
		}

		@Test
		fun `throws SimulationException with ERROR severity`() {
			// Act
			val exception =
				try {
					requireSimulation(false, Severity.ERROR) { "Error message" }
					throw AssertionError("Expected exception to be thrown")
				} catch (e: SimulationException) {
					e
				}

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
			assertThat(exception.message).isEqualTo("Error message")
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
		}

		@Test
		fun `throws SimulationException with WARN severity`() {
			// Act
			val exception =
				try {
					requireSimulation(false, Severity.WARN) { "Warning message" }
					throw AssertionError("Expected exception to be thrown")
				} catch (e: SimulationException) {
					e
				}

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
			assertThat(exception.message).isEqualTo("Warning message")
			assertThat(exception.severity).isEqualTo(Severity.WARN)
		}

		@Test
		fun `uses default message with severity when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulation(false, Severity.ERROR)
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Simulation requirement failed")
		}
	}

	@Nested
	@DisplayName("requireSimulationNotNull")
	inner class RequireSimulationNotNullTests {
		@Test
		fun `returns value when not null`() {
			// Arrange
			val value = "test value"

			// Act
			val result = requireSimulationNotNull(value) { "Should not be thrown" }

			// Assert
			assertThat(result).isEqualTo(value)
		}

		@Test
		fun `throws SimulationException when null`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulationNotNull(null) { "Value was null" }
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Value was null")
		}

		@Test
		fun `uses default message when null and no message provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulationNotNull(null)
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Required value was null")
		}

		@Test
		fun `works with different types`() {
			// Arrange
			val intValue: Int? = 42
			val stringValue: String? = "test"
			val objectValue: Any? = Any()

			// Act & Assert
			assertThat(requireSimulationNotNull(intValue)).isEqualTo(42)
			assertThat(requireSimulationNotNull(stringValue)).isEqualTo("test")
			assertThat(requireSimulationNotNull(objectValue)).isNotNull()
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val value = "not null"
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - value is not null, so message should not be evaluated
			requireSimulationNotNull(value, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireSimulationState")
	inner class RequireSimulationStateTests {
		@Test
		fun `passes when state is valid`() {
			// Act & Assert - should not throw
			requireSimulationState(true) { "Should not be thrown" }
		}

		@Test
		fun `throws SimulationException when state is invalid`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulationState(false) { "Invalid state message" }
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Invalid state message")
		}

		@Test
		fun `uses default message when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulationState(false)
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("Invalid simulation state")
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - condition is true, so message should not be evaluated
			requireSimulationState(true, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	// ==================== Editor Validation Functions ====================

	@Nested
	@DisplayName("requireEditor")
	inner class RequireEditorTests {
		@Test
		fun `passes when condition is true`() {
			// Act & Assert - should not throw
			requireEditor(true) { "Should not be thrown" }
		}

		@Test
		fun `throws EditorException when false`() {
			// Act & Assert
			assertThatThrownBy {
				requireEditor(false) { "Test editor failure" }
			}.isInstanceOf(EditorException::class)
				.hasMessageContaining("Test editor failure")
		}

		@Test
		fun `uses default message when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireEditor(false)
			}.isInstanceOf(EditorException::class)
				.hasMessageContaining("Editor requirement failed")
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - condition is true, so message should not be evaluated
			requireEditor(true, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireEditor with Severity")
	inner class RequireEditorSeverityTests {
		@Test
		fun `passes when condition is true with severity`() {
			// Act & Assert - should not throw
			requireEditor(true, Severity.ERROR) { "Should not be thrown" }
		}

		@Test
		fun `throws EditorException with FATAL severity`() {
			// Act
			val exception =
				try {
					requireEditor(false, Severity.FATAL) { "Fatal editor error" }
					throw AssertionError("Expected exception to be thrown")
				} catch (e: EditorException) {
					e
				}

			// Assert
			assertThat(exception).isInstanceOf(EditorException::class)
			assertThat(exception.message).isEqualTo("Fatal editor error")
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
		}

		@Test
		fun `throws EditorException with ERROR severity`() {
			// Act
			val exception =
				try {
					requireEditor(false, Severity.ERROR) { "Editor error" }
					throw AssertionError("Expected exception to be thrown")
				} catch (e: EditorException) {
					e
				}

			// Assert
			assertThat(exception).isInstanceOf(EditorException::class)
			assertThat(exception.message).isEqualTo("Editor error")
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
		}

		@Test
		fun `throws EditorException with WARN severity`() {
			// Act
			val exception =
				try {
					requireEditor(false, Severity.WARN) { "Editor warning" }
					throw AssertionError("Expected exception to be thrown")
				} catch (e: EditorException) {
					e
				}

			// Assert
			assertThat(exception).isInstanceOf(EditorException::class)
			assertThat(exception.message).isEqualTo("Editor warning")
			assertThat(exception.severity).isEqualTo(Severity.WARN)
		}

		@Test
		fun `uses default message with severity when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireEditor(false, Severity.ERROR)
			}.isInstanceOf(EditorException::class)
				.hasMessageContaining("Editor requirement failed")
		}
	}

	@Nested
	@DisplayName("requireEditorNotNull")
	inner class RequireEditorNotNullTests {
		@Test
		fun `returns value when not null`() {
			// Arrange
			val value = "editor value"

			// Act
			val result = requireEditorNotNull(value) { "Should not be thrown" }

			// Assert
			assertThat(result).isEqualTo(value)
		}

		@Test
		fun `throws EditorException when null`() {
			// Act & Assert
			assertThatThrownBy {
				requireEditorNotNull(null) { "Editor value was null" }
			}.isInstanceOf(EditorException::class)
				.hasMessageContaining("Editor value was null")
		}

		@Test
		fun `uses default message when null and no message provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireEditorNotNull(null)
			}.isInstanceOf(EditorException::class)
				.hasMessageContaining("Required value was null")
		}

		@Test
		fun `works with different types`() {
			// Arrange
			val intValue: Int? = 42
			val stringValue: String? = "test"
			val objectValue: Any? = Any()

			// Act & Assert
			assertThat(requireEditorNotNull(intValue)).isEqualTo(42)
			assertThat(requireEditorNotNull(stringValue)).isEqualTo("test")
			assertThat(requireEditorNotNull(objectValue)).isNotNull()
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val value = "not null"
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - value is not null, so message should not be evaluated
			requireEditorNotNull(value, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	// ==================== Standard Validation Functions ====================

	@Nested
	@DisplayName("requireValidArgument")
	inner class RequireValidArgumentTests {
		@Test
		fun `passes when condition is true`() {
			// Act & Assert - should not throw
			requireValidArgument(true) { "Should not be thrown" }
		}

		@Test
		fun `throws IllegalArgumentException when false`() {
			// Act & Assert
			assertThatThrownBy {
				requireValidArgument(false) { "Invalid argument message" }
			}.isInstanceOf(IllegalArgumentException::class)
				.hasMessageContaining("Invalid argument message")
		}

		@Test
		fun `uses default message when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireValidArgument(false)
			}.isInstanceOf(IllegalArgumentException::class)
				.hasMessageContaining("Invalid argument")
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - condition is true, so message should not be evaluated
			requireValidArgument(true, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}

		@Test
		fun `exception message includes context information`() {
			// Arrange
			val paramName = "speed"
			val value = -5

			// Act & Assert
			assertThatThrownBy {
				requireValidArgument(value >= 0) { "Parameter $paramName must be non-negative, got $value" }
			}.isInstanceOf(IllegalArgumentException::class)
				.hasMessageContaining("speed")
				.hasMessageContaining("-5")
				.hasMessageContaining("non-negative")
		}
	}

	@Nested
	@DisplayName("requireValidState")
	inner class RequireValidStateTests {
		@Test
		fun `passes when condition is true`() {
			// Act & Assert - should not throw
			requireValidState(true) { "Should not be thrown" }
		}

		@Test
		fun `throws IllegalStateException when false`() {
			// Act & Assert
			assertThatThrownBy {
				requireValidState(false) { "Invalid state message" }
			}.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("Invalid state message")
		}

		@Test
		fun `uses default message when not provided`() {
			// Act & Assert
			assertThatThrownBy {
				requireValidState(false)
			}.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("Invalid state")
		}

		@Test
		fun `evaluates message lazily`() {
			// Arrange
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}

			// Act - condition is true, so message should not be evaluated
			requireValidState(true, lazyMessage)

			// Assert
			assertThat(messageEvaluated).isEqualTo(false)
		}

		@Test
		fun `exception message includes context information`() {
			// Arrange
			val currentState = "RUNNING"
			val expectedState = "STOPPED"

			// Act & Assert
			assertThatThrownBy {
				requireValidState(false) { "Expected state $expectedState but was $currentState" }
			}.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("RUNNING")
				.hasMessageContaining("STOPPED")
		}
	}

	// ==================== Edge Cases and Integration Tests ====================

	@Nested
	@DisplayName("Edge Cases and Integration")
	inner class EdgeCasesTests {
		@Test
		fun `multiple require calls can be chained`() {
			// Arrange
			val value1: String? = "test"
			val value2: Int? = 42

			// Act & Assert - should not throw
			val result1 = requireSimulationNotNull(value1) { "Value1 was null" }
			val result2 = requireSimulationNotNull(value2) { "Value2 was null" }
			requireSimulation(result1.isNotEmpty()) { "Value1 was empty" }
			requireSimulation(result2 > 0) { "Value2 was not positive" }

			// Assert
			assertThat(result1).isEqualTo("test")
			assertThat(result2).isEqualTo(42)
		}

		@Test
		fun `different exception types are thrown by different functions`() {
			// Act - capture different exception types
			val simulationException =
				try {
					requireSimulation(false) { "Simulation error" }
					throw AssertionError("Expected SimulationException")
				} catch (e: SimulationException) {
					e
				}

			val editorException =
				try {
					requireEditor(false) { "Editor error" }
					throw AssertionError("Expected EditorException")
				} catch (e: EditorException) {
					e
				}

			val argumentException =
				try {
					requireValidArgument(false) { "Argument error" }
					throw AssertionError("Expected IllegalArgumentException")
				} catch (e: IllegalArgumentException) {
					e
				}

			val stateException =
				try {
					requireValidState(false) { "State error" }
					throw AssertionError("Expected IllegalStateException")
				} catch (e: IllegalStateException) {
					e
				}

			// Assert - verify all exceptions are different types
			assertThat(simulationException).isInstanceOf(SimulationException::class)
			assertThat(editorException).isInstanceOf(EditorException::class)
			assertThat(argumentException).isInstanceOf(IllegalArgumentException::class)
			assertThat(stateException).isInstanceOf(IllegalStateException::class)
		}

		@Test
		fun `complex validation scenarios work correctly`() {
			// Arrange
			data class Config(
				val speed: Int?,
				val name: String?
			)

			val config = Config(speed = 100, name = "TestConfig")

			// Act & Assert - validate complex object
			val speed = requireSimulationNotNull(config.speed) { "Speed is required" }
			val name = requireEditorNotNull(config.name) { "Name is required" }
			requireValidArgument(speed > 0) { "Speed must be positive" }
			requireValidArgument(name.isNotEmpty()) { "Name must not be empty" }

			// Assert
			assertThat(speed).isEqualTo(100)
			assertThat(name).isEqualTo("TestConfig")
		}

		@Test
		fun `message with special characters is preserved`() {
			// Act & Assert
			assertThatThrownBy {
				requireSimulation(false) { "Error: [test] with {brackets} and \"quotes\"" }
			}.isInstanceOf(SimulationException::class)
				.hasMessageContaining("[test]")
				.hasMessageContaining("{brackets}")
				.hasMessageContaining("\"quotes\"")
		}
	}
}
