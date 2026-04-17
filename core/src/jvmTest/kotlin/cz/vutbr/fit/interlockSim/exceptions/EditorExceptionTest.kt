/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for EditorException (Issue #453)
 * Relocated/duplicated into :core so JaCoCo :core attributes coverage.
 */
package cz.vutbr.fit.interlockSim.exceptions

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Unit tests for EditorException — covers all constructor overloads, Severity propagation,
 * toString formatting, cause chain preservation, object storage, and hierarchy checks.
 */
@DisplayName("EditorException")
class EditorExceptionTest {
	private val testMessage = "Test editor exception message"
	private val testCause = RuntimeException("Root cause")
	private val testObj = Any()

	@Nested
	@DisplayName("Constructors")
	inner class ConstructorTests {
		@Test
		fun `no-arg constructor creates exception with FATAL severity`() {
			val exception = EditorException()
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with object creates exception with FATAL severity`() {
			val exception = EditorException(testObj)
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `constructor with message creates exception with FATAL severity`() {
			val exception = EditorException(testMessage)
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with severity and message creates exception`() {
			val exception = EditorException(Severity.ERROR, testMessage)
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with severity, message, and object creates exception`() {
			val exception = EditorException(Severity.WARN, testMessage, testObj)
			assertThat(exception.severity).isEqualTo(Severity.WARN)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `constructor with cause creates exception with FATAL severity`() {
			val exception = EditorException(testCause)
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with severity, cause, and object creates exception`() {
			val exception = EditorException(Severity.ERROR, testCause, testObj)
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `primary constructor with all parameters creates exception`() {
			val exception = EditorException(Severity.WARN, testMessage, testCause, testObj)
			assertThat(exception.severity).isEqualTo(Severity.WARN)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}
	}

	@Nested
	@DisplayName("Severity Preservation")
	inner class SeverityTests {
		@ParameterizedTest(name = "{0} severity is preserved")
		@EnumSource(Severity::class, names = ["FATAL", "ERROR", "WARN"])
		fun `severity is preserved`(severity: Severity) {
			val exception = EditorException(severity, testMessage)
			assertThat(exception.severity).isEqualTo(severity)
		}

		@Test
		fun `default severity is FATAL`() {
			assertThat(EditorException().severity).isEqualTo(Severity.FATAL)
			assertThat(EditorException(testMessage).severity).isEqualTo(Severity.FATAL)
			assertThat(EditorException(testCause).severity).isEqualTo(Severity.FATAL)
		}

		@ParameterizedTest(name = "{0} in toString")
		@EnumSource(Severity::class, names = ["FATAL", "ERROR", "WARN"])
		fun `severity propagates in toString`(severity: Severity) {
			val exception = EditorException(severity, testMessage)
			assertThat(exception.toString()).contains(severity.name)
		}
	}

	@Nested
	@DisplayName("Message Formatting")
	inner class MessageFormattingTests {
		@Test
		fun `toString includes class name`() {
			val exception = EditorException(testMessage)
			assertThat(exception.toString()).contains("EditorException")
		}

		@Test
		fun `toString format is correct`() {
			val exception = EditorException(Severity.WARN, testMessage)
			val result = exception.toString()
			assertThat(result).contains("EditorException[WARN]:")
			assertThat(result).contains(testMessage)
		}

		@Test
		fun `toString handles empty message`() {
			val exception = EditorException()
			assertThat(exception.toString()).contains("EditorException[FATAL]:")
		}

		@Test
		fun `toString handles message with special characters`() {
			val specialMessage = "Error: [test] with {brackets} and \"quotes\""
			val exception = EditorException(specialMessage)
			val result = exception.toString()
			assertThat(result).contains("[test]")
			assertThat(result).contains("{brackets}")
			assertThat(result).contains("\"quotes\"")
		}
	}

	@Nested
	@DisplayName("Cause Chain Preservation")
	inner class CauseChainTests {
		@Test
		fun `nested exception chain is preserved`() {
			val deepCause = Exception("Deep cause")
			val middleCause = Exception("Middle cause", deepCause)
			val exception = EditorException(middleCause)
			assertThat(exception.cause).isEqualTo(middleCause)
			assertThat(exception.cause?.cause).isEqualTo(deepCause)
		}

		@Test
		fun `stack trace is preserved`() {
			val exception = EditorException(testMessage)
			assertThat(exception.stackTrace.isNotEmpty()).isEqualTo(true)
		}
	}

	@Nested
	@DisplayName("getObject and hierarchy")
	inner class ObjectAndHierarchyTests {
		@Test
		fun `getObject preserves object type`() {
			data class TestData(
				val value: Int
			)
			val data = TestData(42)
			val exception = EditorException(Severity.ERROR, testMessage, data)
			val result = exception.getObject()
			assertThat(result).isNotNull().isInstanceOf<TestData>()
			val testData = result as TestData
			assertThat(testData.value).isEqualTo(42)
		}

		@Test
		fun `EditorException is Exception`() {
			val exception = EditorException(testMessage)
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `EditorException is throwable`() {
			val exception = EditorException(testMessage)
			assertFailure { throw exception }.isInstanceOf<EditorException>()
		}
	}
}
