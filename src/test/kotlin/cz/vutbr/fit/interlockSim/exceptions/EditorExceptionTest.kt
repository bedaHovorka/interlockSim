/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Unit tests for EditorException
 * Phase 6.2 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.exceptions

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for EditorException.
 *
 * Tests cover:
 * - All constructor variants (no-arg, severity, message, cause, object combinations)
 * - Severity preservation and propagation
 * - Message formatting with toString()
 * - Cause chain preservation
 * - getObject() functionality
 * - Exception hierarchy validation
 *
 * Coverage target: ~110 instructions (Phase 6.2)
 */
@DisplayName("EditorException")
class EditorExceptionTest {
	private val testMessage = "Test editor exception message"
	private val testCause = RuntimeException("Root cause")
	private val testObj = Any()

	// ==================== Constructor Tests ====================

	@Nested
	@DisplayName("Constructors")
	inner class ConstructorTests {
		@Test
		fun `no-arg constructor creates exception with FATAL severity`() {
			// Act
			val exception = EditorException()

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with object creates exception with FATAL severity`() {
			// Act
			val exception = EditorException(testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `constructor with message creates exception with FATAL severity`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with severity and message creates exception`() {
			// Act
			val exception = EditorException(Severity.ERROR, testMessage)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with severity, message, and object creates exception`() {
			// Act
			val exception = EditorException(Severity.WARN, testMessage, testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.WARN)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `constructor with cause creates exception with FATAL severity`() {
			// Act
			val exception = EditorException(testCause)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with severity, cause, and object creates exception`() {
			// Act
			val exception = EditorException(Severity.ERROR, testCause, testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `primary constructor with all parameters creates exception`() {
			// Act
			val exception = EditorException(Severity.WARN, testMessage, testCause, testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.WARN)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}
	}

	// ==================== Severity Tests ====================

	@Nested
	@DisplayName("Severity Preservation")
	inner class SeverityTests {
		@Test
		fun `FATAL severity is preserved`() {
			// Act
			val exception = EditorException(Severity.FATAL, testMessage)

			// Assert
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
		}

		@Test
		fun `ERROR severity is preserved`() {
			// Act
			val exception = EditorException(Severity.ERROR, testMessage)

			// Assert
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
		}

		@Test
		fun `WARN severity is preserved`() {
			// Act
			val exception = EditorException(Severity.WARN, testMessage)

			// Assert
			assertThat(exception.severity).isEqualTo(Severity.WARN)
		}

		@Test
		fun `default severity is FATAL`() {
			// Act
			val exception1 = EditorException()
			val exception2 = EditorException(testMessage)
			val exception3 = EditorException(testCause)

			// Assert
			assertThat(exception1.severity).isEqualTo(Severity.FATAL)
			assertThat(exception2.severity).isEqualTo(Severity.FATAL)
			assertThat(exception3.severity).isEqualTo(Severity.FATAL)
		}

		@Test
		fun `severity levels propagate correctly in toString`() {
			// Arrange
			val fatalException = EditorException(Severity.FATAL, testMessage)
			val errorException = EditorException(Severity.ERROR, testMessage)
			val warnException = EditorException(Severity.WARN, testMessage)

			// Act
			val fatalString = fatalException.toString()
			val errorString = errorException.toString()
			val warnString = warnException.toString()

			// Assert
			assertThat(fatalString).contains("FATAL")
			assertThat(errorString).contains("ERROR")
			assertThat(warnString).contains("WARN")
		}
	}

	// ==================== Message Formatting Tests ====================

	@Nested
	@DisplayName("Message Formatting")
	inner class MessageFormattingTests {
		@Test
		fun `toString includes class name`() {
			// Act
			val exception = EditorException(testMessage)
			val result = exception.toString()

			// Assert
			assertThat(result).contains("EditorException")
		}

		@Test
		fun `toString includes severity`() {
			// Act
			val exception = EditorException(Severity.ERROR, testMessage)
			val result = exception.toString()

			// Assert
			assertThat(result).contains("ERROR")
		}

		@Test
		fun `toString includes message`() {
			// Act
			val exception = EditorException(testMessage)
			val result = exception.toString()

			// Assert
			assertThat(result).contains(testMessage)
		}

		@Test
		fun `toString format is correct`() {
			// Act
			val exception = EditorException(Severity.WARN, testMessage)
			val result = exception.toString()

			// Assert - format: EditorException[SEVERITY]: message
			assertThat(result).contains("EditorException[WARN]:")
			assertThat(result).contains(testMessage)
		}

		@Test
		fun `toString handles empty message`() {
			// Act
			val exception = EditorException()
			val result = exception.toString()

			// Assert
			assertThat(result).contains("EditorException[FATAL]:")
		}

		@Test
		fun `toString handles message with special characters`() {
			// Arrange
			val specialMessage = "Error: [test] with {brackets} and \"quotes\""
			val exception = EditorException(specialMessage)

			// Act
			val result = exception.toString()

			// Assert
			assertThat(result).contains("[test]")
			assertThat(result).contains("{brackets}")
			assertThat(result).contains("\"quotes\"")
		}
	}

	// ==================== Cause Chain Tests ====================

	@Nested
	@DisplayName("Cause Chain Preservation")
	inner class CauseChainTests {
		@Test
		fun `cause is preserved`() {
			// Act
			val exception = EditorException(testCause)

			// Assert
			assertThat(exception.cause).isEqualTo(testCause)
		}

		@Test
		fun `cause message is preserved`() {
			// Act
			val exception = EditorException(testCause)

			// Assert
			assertThat(exception.cause?.message).isEqualTo("Root cause")
		}

		@Test
		fun `nested exception chain is preserved`() {
			// Arrange
			val deepCause = Exception("Deep cause")
			val middleCause = Exception("Middle cause", deepCause)
			val exception = EditorException(middleCause)

			// Act
			val directCause = exception.cause
			val nestedCause = directCause?.cause

			// Assert
			assertThat(directCause).isEqualTo(middleCause)
			assertThat(nestedCause).isEqualTo(deepCause)
		}

		@Test
		fun `exception without cause has null cause`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert
			assertThat(exception.cause).isEqualTo(null)
		}

		@Test
		fun `cause and message can coexist`() {
			// Act
			val exception = EditorException(Severity.ERROR, testMessage, testCause, null)

			// Assert
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
		}

		@Test
		fun `stack trace is preserved`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert
			assertThat(exception.stackTrace).isNotNull()
		}
	}

	// ==================== Object Storage Tests ====================

	@Nested
	@DisplayName("getObject() Functionality")
	inner class ObjectStorageTests {
		@Test
		fun `getObject returns stored object`() {
			// Act
			val exception = EditorException(testObj)

			// Assert
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `getObject returns null when no object is stored`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `getObject preserves object type`() {
			// Arrange
			data class TestData(
				val value: Int
			)
			val data = TestData(42)
			val exception = EditorException(Severity.ERROR, testMessage, data)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isNotNull()
			assertThat(result as Any).isInstanceOf<TestData>()
			assertThat((result as TestData).value).isEqualTo(42)
		}

		@Test
		fun `multiple exceptions can store different objects`() {
			// Arrange - use non-String objects to avoid constructor ambiguity
			val obj1 = 42
			val obj2 = listOf("test", "data")
			val exception1 = EditorException(obj1)
			val exception2 = EditorException(obj2)

			// Act
			val result1 = exception1.getObject()
			val result2 = exception2.getObject()

			// Assert
			assertThat(result1).isEqualTo(obj1)
			assertThat(result2).isEqualTo(obj2)
		}

		@Test
		fun `object and cause can coexist`() {
			// Act
			val exception = EditorException(Severity.ERROR, testCause, testObj)

			// Assert
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}
	}

	// ==================== Exception Hierarchy Tests ====================

	@Nested
	@DisplayName("Exception Hierarchy")
	inner class ExceptionHierarchyTests {
		@Test
		fun `EditorException is Exception`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `EditorException is throwable`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert & Act
			assertThatThrownBy { throw exception }
				.isInstanceOf(EditorException::class)
		}

		@Test
		fun `can be caught as Exception`() {
			// Arrange
			val exception = EditorException(testMessage)

			// Act & Assert - verify catch compatibility
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `exception type can be distinguished`() {
			// Act
			val exception = EditorException(testMessage)

			// Assert
			assertThat(exception::class.java.simpleName).isEqualTo("EditorException")
		}
	}

	// ==================== Integration Tests ====================

	@Nested
	@DisplayName("Integration Scenarios")
	inner class IntegrationTests {
		@Test
		fun `exception with all properties set`() {
			// Act
			val exception = EditorException(Severity.ERROR, testMessage, testCause, testObj)

			// Assert
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `exception chain with multiple EditorExceptions`() {
			// Arrange
			val innerException = EditorException(Severity.WARN, "Inner error")
			val outerException = EditorException(Severity.ERROR, "Outer error", innerException, null)

			// Act
			val result = outerException.cause

			// Assert
			assertThat(result).isEqualTo(innerException)
			assertThat(result as Any).isInstanceOf<EditorException>()
		}

		@Test
		fun `exception properties are independent`() {
			// Arrange
			val obj1 = "Object 1"
			val obj2 = "Object 2"
			val exception1 = EditorException(Severity.ERROR, "Message 1", null, obj1)
			val exception2 = EditorException(Severity.WARN, "Message 2", null, obj2)

			// Assert - changes to one don't affect the other
			assertThat(exception1.severity).isEqualTo(Severity.ERROR)
			assertThat(exception2.severity).isEqualTo(Severity.WARN)
			assertThat(exception1.getObject()).isEqualTo(obj1)
			assertThat(exception2.getObject()).isEqualTo(obj2)
		}

		@Test
		fun `toString produces consistent format across different severities`() {
			// Arrange
			val fatalEx = EditorException(Severity.FATAL, "Fatal")
			val errorEx = EditorException(Severity.ERROR, "Error")
			val warnEx = EditorException(Severity.WARN, "Warning")

			// Act
			val fatalString = fatalEx.toString()
			val errorString = errorEx.toString()
			val warnString = warnEx.toString()

			// Assert - all follow the pattern EditorException[SEVERITY]: message
			assertThat(fatalString).contains("EditorException[FATAL]: Fatal")
			assertThat(errorString).contains("EditorException[ERROR]: Error")
			assertThat(warnString).contains("EditorException[WARN]: Warning")
		}
	}
}
