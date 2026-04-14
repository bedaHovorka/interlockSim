/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for RequireFunctions utility functions (Issue #453)
 * Relocated to :core so inline-function coverage is attributed to :core JaCoCo.
 */
package cz.vutbr.fit.interlockSim.exceptions

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.testutil.hasMessageContaining
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Unit tests for RequireFunctions utility validation functions.
 *
 * Covers requireSimulation/requireEditor/requireValidArgument/requireValidState
 * across positive, negative, lazy-message, and severity variants.
 */
@DisplayName("RequireFunctions")
class RequireFunctionsTest {
	@Nested
	@DisplayName("requireSimulation")
	inner class RequireSimulationTests {
		@Test
		fun `passes when condition is true`() {
			requireSimulation(true) { "Should not be thrown" }
		}

		@Test
		fun `throws SimulationException when false`() {
			assertFailure {
				requireSimulation(false) { "Test failure message" }
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Test failure message")
		}

		@Test
		fun `uses default message when not provided`() {
			assertFailure {
				requireSimulation(false)
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Simulation requirement failed")
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireSimulation(true, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}

		@Test
		fun `evaluates message when condition fails`() {
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message evaluated"
			}
			assertFailure {
				requireSimulation(false, lazyMessage)
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Lazy message evaluated")
			assertThat(messageEvaluated).isEqualTo(true)
		}
	}

	@Nested
	@DisplayName("requireSimulation with Severity")
	inner class RequireSimulationSeverityTests {
		@Test
		fun `passes when condition is true with severity`() {
			requireSimulation(true, Severity.ERROR) { "Should not be thrown" }
		}

		@ParameterizedTest(name = "severity {0}")
		@EnumSource(Severity::class, names = ["FATAL", "ERROR", "WARN"])
		fun `throws SimulationException with correct severity`(severity: Severity) {
			assertFailure {
				requireSimulation(false, severity) { "$severity message" }
			}.isInstanceOf<SimulationException>().all {
				hasMessage("$severity message")
				prop(SimulationException::severity).isEqualTo(severity)
			}
		}

		@Test
		fun `uses default message with severity when not provided`() {
			assertFailure {
				requireSimulation(false, Severity.ERROR)
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Simulation requirement failed")
		}
	}

	@Nested
	@DisplayName("requireSimulationNotNull")
	inner class RequireSimulationNotNullTests {
		@Test
		fun `returns value when not null`() {
			val value = "test value"
			val result = requireSimulationNotNull(value) { "Should not be thrown" }
			assertThat(result).isEqualTo(value)
		}

		@Test
		fun `throws SimulationException when null`() {
			assertFailure {
				requireSimulationNotNull(null) { "Value was null" }
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Value was null")
		}

		@Test
		fun `uses default message when null and no message provided`() {
			assertFailure {
				requireSimulationNotNull(null)
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Required value was null")
		}

		@Test
		fun `works with different types`() {
			val intValue: Int? = 42
			val stringValue: String? = "test"
			val objectValue: Any? = Any()
			assertThat(requireSimulationNotNull(intValue)).isEqualTo(42)
			assertThat(requireSimulationNotNull(stringValue)).isEqualTo("test")
			assertThat(requireSimulationNotNull(objectValue)).isNotNull()
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val value = "not null"
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireSimulationNotNull(value, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireSimulationState")
	inner class RequireSimulationStateTests {
		@Test
		fun `passes when state is valid`() {
			requireSimulationState(true) { "Should not be thrown" }
		}

		@Test
		fun `throws SimulationException when state is invalid`() {
			assertFailure {
				requireSimulationState(false) { "Invalid state message" }
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Invalid state message")
		}

		@Test
		fun `uses default message when not provided`() {
			assertFailure {
				requireSimulationState(false)
			}.isInstanceOf<SimulationException>()
				.hasMessageContaining("Invalid simulation state")
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireSimulationState(true, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireEditor")
	inner class RequireEditorTests {
		@Test
		fun `passes when condition is true`() {
			requireEditor(true) { "Should not be thrown" }
		}

		@Test
		fun `throws EditorException when false`() {
			assertFailure {
				requireEditor(false) { "Test editor failure" }
			}.isInstanceOf<EditorException>()
				.hasMessageContaining("Test editor failure")
		}

		@Test
		fun `uses default message when not provided`() {
			assertFailure {
				requireEditor(false)
			}.isInstanceOf<EditorException>()
				.hasMessageContaining("Editor requirement failed")
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireEditor(true, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireEditor with Severity")
	inner class RequireEditorSeverityTests {
		@Test
		fun `passes when condition is true with severity`() {
			requireEditor(true, Severity.ERROR) { "Should not be thrown" }
		}

		@ParameterizedTest(name = "severity {0}")
		@EnumSource(Severity::class, names = ["FATAL", "ERROR", "WARN"])
		fun `throws EditorException with correct severity`(severity: Severity) {
			assertFailure {
				requireEditor(false, severity) { "$severity editor message" }
			}.isInstanceOf<EditorException>().all {
				hasMessage("$severity editor message")
				prop(EditorException::severity).isEqualTo(severity)
			}
		}

		@Test
		fun `uses default message with severity when not provided`() {
			assertFailure {
				requireEditor(false, Severity.ERROR)
			}.isInstanceOf<EditorException>()
				.hasMessageContaining("Editor requirement failed")
		}
	}

	@Nested
	@DisplayName("requireEditorNotNull")
	inner class RequireEditorNotNullTests {
		@Test
		fun `returns value when not null`() {
			val value = "editor value"
			val result = requireEditorNotNull(value) { "Should not be thrown" }
			assertThat(result).isEqualTo(value)
		}

		@Test
		fun `throws EditorException when null`() {
			assertFailure {
				requireEditorNotNull(null) { "Editor value was null" }
			}.isInstanceOf<EditorException>()
				.hasMessageContaining("Editor value was null")
		}

		@Test
		fun `uses default message when null and no message provided`() {
			assertFailure {
				requireEditorNotNull(null)
			}.isInstanceOf<EditorException>()
				.hasMessageContaining("Required value was null")
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val value = "not null"
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireEditorNotNull(value, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireValidArgument")
	inner class RequireValidArgumentTests {
		@Test
		fun `passes when condition is true`() {
			requireValidArgument(true) { "Should not be thrown" }
		}

		@Test
		fun `throws IllegalArgumentException when false`() {
			assertFailure {
				requireValidArgument(false) { "Invalid argument message" }
			}.isInstanceOf<IllegalArgumentException>()
				.hasMessageContaining("Invalid argument message")
		}

		@Test
		fun `uses default message when not provided`() {
			assertFailure {
				requireValidArgument(false)
			}.isInstanceOf<IllegalArgumentException>()
				.hasMessageContaining("Invalid argument")
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireValidArgument(true, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}

	@Nested
	@DisplayName("requireValidState")
	inner class RequireValidStateTests {
		@Test
		fun `passes when condition is true`() {
			requireValidState(true) { "Should not be thrown" }
		}

		@Test
		fun `throws IllegalStateException when false`() {
			assertFailure {
				requireValidState(false) { "Invalid state message" }
			}.isInstanceOf<IllegalStateException>()
				.hasMessageContaining("Invalid state message")
		}

		@Test
		fun `uses default message when not provided`() {
			assertFailure {
				requireValidState(false)
			}.isInstanceOf<IllegalStateException>()
				.hasMessageContaining("Invalid state")
		}

		@Test
		fun `evaluates message lazily`() {
			var messageEvaluated = false
			val lazyMessage = {
				messageEvaluated = true
				"Lazy message"
			}
			requireValidState(true, lazyMessage)
			assertThat(messageEvaluated).isEqualTo(false)
		}
	}
}
