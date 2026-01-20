/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Extended unit tests for SimulationException to achieve 100% coverage
 * Phase 6.2 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.exceptions

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.assertThatThrownBy
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Extended unit tests for SimulationException to complete coverage from 88% to 100%.
 *
 * This test class covers the missing constructor variants:
 * - No-arg constructor
 * - Constructor with object only
 * - Constructor with cause and object
 * - Primary constructor with severity parameter
 *
 * Coverage target: Complete remaining ~14 instructions (Phase 6.2)
 */
@DisplayName("SimulationException Extended Coverage")
class SimulationExceptionExtendedTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext
	private val testMessage = "Test exception message"
	private val testCause = RuntimeException("Root cause")
	private val testObj = Any()

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	// ==================== Missing Constructor Tests ====================

	@Nested
	@DisplayName("Additional Constructor Coverage")
	inner class AdditionalConstructorTests {
		@Test
		fun `no-arg constructor creates exception with FATAL severity`() {
			// Act
			val exception = SimulationException()

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `constructor with object only creates exception with FATAL severity`() {
			// Act
			val exception = SimulationException(testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(testObj)
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `constructor with cause and object creates exception with FATAL severity`() {
			// Act
			val exception = SimulationException(testCause, testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo("")
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `primary constructor with severity parameter creates exception`() {
			// Act
			val exception = SimulationException(Severity.ERROR, testMessage, testCause, testObj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
			assertThat(exception.getTime()).isNotNull()
		}
	}

	// ==================== Severity Parameter Tests ====================

	@Nested
	@DisplayName("Severity Parameter Coverage")
	inner class SeverityParameterTests {
		@Test
		fun `constructor with FATAL severity creates exception`() {
			// Act
			val exception = SimulationException(Severity.FATAL, testMessage, null, null)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.FATAL)
			assertThat(exception.message).isEqualTo(testMessage)
		}

		@Test
		fun `constructor with ERROR severity creates exception`() {
			// Act
			val exception = SimulationException(Severity.ERROR, testMessage, null, null)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.ERROR)
			assertThat(exception.message).isEqualTo(testMessage)
		}

		@Test
		fun `constructor with WARN severity creates exception`() {
			// Act
			val exception = SimulationException(Severity.WARN, testMessage, null, null)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.severity).isEqualTo(Severity.WARN)
			assertThat(exception.message).isEqualTo(testMessage)
		}

		@Test
		fun `severity is preserved in toString`() {
			// Arrange
			val fatalException = SimulationException(Severity.FATAL, testMessage, null, null)
			val errorException = SimulationException(Severity.ERROR, testMessage, null, null)
			val warnException = SimulationException(Severity.WARN, testMessage, null, null)

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

	// ==================== Object and Cause Combination Tests ====================

	@Nested
	@DisplayName("Object and Cause Combinations")
	inner class ObjectCauseCombinationTests {
		@Test
		fun `exception with object but no cause`() {
			// Act
			val exception = SimulationException(testMessage, testObj)

			// Assert
			assertThat(exception.getObject()).isEqualTo(testObj)
			assertThat(exception.cause).isEqualTo(null)
		}

		@Test
		fun `exception with cause but no object`() {
			// Act
			val exception = SimulationException(testCause)

			// Assert
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `exception with both cause and object`() {
			// Act
			val exception = SimulationException(testCause, testObj)

			// Assert
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}

		@Test
		fun `exception with message, cause, and object`() {
			// Act
			val exception = SimulationException(testMessage, testCause, testObj)

			// Assert
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(testObj)
		}
	}

	// ==================== Time Recording Tests ====================

	@Nested
	@DisplayName("Time Recording")
	inner class TimeRecordingTests {
		@Test
		fun `no-arg constructor records time`() {
			// Arrange
			mockContext.setTime(5.5)

			// Act
			val exception = SimulationException()

			// Assert
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `object constructor records time`() {
			// Arrange
			mockContext.setTime(10.0)

			// Act
			val exception = SimulationException(testObj)

			// Assert
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `cause and object constructor records time`() {
			// Arrange
			mockContext.setTime(15.5)

			// Act
			val exception = SimulationException(testCause, testObj)

			// Assert
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `time is included in toString for no-arg constructor`() {
			// Arrange
			mockContext.setTime(0.0)

			// Act
			val exception = SimulationException()
			val result = exception.toString()

			// Assert
			assertThat(result).contains("at time")
		}
	}

	// ==================== toString Format Tests ====================

	@Nested
	@DisplayName("toString Format Coverage")
	inner class ToStringFormatTests {
		@Test
		fun `toString handles empty message from no-arg constructor`() {
			// Act
			val exception = SimulationException()
			val result = exception.toString()

			// Assert
			assertThat(result).contains("SimulationException[FATAL]:")
			assertThat(result).contains("at time")
		}

		@Test
		fun `toString includes class name for object constructor`() {
			// Act
			val exception = SimulationException(testObj)
			val result = exception.toString()

			// Assert
			assertThat(result).contains("SimulationException")
		}

		@Test
		fun `toString format with severity parameter`() {
			// Act
			val exception = SimulationException(Severity.ERROR, testMessage, null, null)
			val result = exception.toString()

			// Assert
			assertThat(result).contains("SimulationException[ERROR]:")
			assertThat(result).contains(testMessage)
			assertThat(result).contains("at time")
		}
	}

	// ==================== Integration Tests ====================

	@Nested
	@DisplayName("Integration Scenarios")
	inner class IntegrationTests {
		@Test
		fun `multiple no-arg exceptions have independent properties`() {
			// Act
			val exception1 = SimulationException()
			val exception2 = SimulationException()

			// Assert
			assertThat(exception1.severity).isEqualTo(Severity.FATAL)
			assertThat(exception2.severity).isEqualTo(Severity.FATAL)
			assertThat(exception1.getTime()).isNotNull()
			assertThat(exception2.getTime()).isNotNull()
		}

		@Test
		fun `exception hierarchy is correct for all constructors`() {
			// Act
			val exception1 = SimulationException()
			val exception2 = SimulationException(testObj)
			val exception3 = SimulationException(testCause, testObj)

			// Assert
			assertThat(exception1).isInstanceOf(Exception::class)
			assertThat(exception2).isInstanceOf(Exception::class)
			assertThat(exception3).isInstanceOf(Exception::class)
		}

		@Test
		fun `all constructors are throwable`() {
			// Act & Assert
			assertThatThrownBy { throw SimulationException() }
				.isInstanceOf(SimulationException::class)

			assertThatThrownBy { throw SimulationException(testObj) }
				.isInstanceOf(SimulationException::class)

			assertThatThrownBy { throw SimulationException(testCause, testObj) }
				.isInstanceOf(SimulationException::class)
		}

		@Test
		fun `severity parameter works with all combinations`() {
			// Act
			val ex1 = SimulationException(Severity.ERROR, "", null, null)
			val ex2 = SimulationException(Severity.WARN, testMessage, null, null)
			val ex3 = SimulationException(Severity.FATAL, testMessage, testCause, null)
			val ex4 = SimulationException(Severity.ERROR, testMessage, testCause, testObj)

			// Assert
			assertThat(ex1.severity).isEqualTo(Severity.ERROR)
			assertThat(ex2.severity).isEqualTo(Severity.WARN)
			assertThat(ex3.severity).isEqualTo(Severity.FATAL)
			assertThat(ex4.severity).isEqualTo(Severity.ERROR)
		}
	}
}
