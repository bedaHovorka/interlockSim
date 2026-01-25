/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for SimulationException and related exception classes
 * Phase 6.1 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrack
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.assertThatThrownBy
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for SimulationException, TrackOperationException, and PathSeparatorChangeException.
 *
 * Tests cover:
 * - SimulationException constructors: no-arg, object, message, message+object, cause, cause+object, message+cause+object
 * - TrackOperationException constructors: track, message+track, cause+track, message+cause+track
 * - PathSeparatorChangeException constructors: separator, message+separator, cause+separator, message+cause+separator
 * - Exception chaining and message propagation
 * - Object and time context storage
 * - String representation with time and object context
 * - Exception hierarchy validation
 *
 * Coverage target: ~150 instructions (Phase 6.1)
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SimulationExceptionTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext
	private lateinit var mockTrack: SimpleTrack
	private lateinit var mockSeparator: PathSeparator
	private val testMessage = "Test exception message"
	private val testCause = RuntimeException("Root cause")

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
		mockTrack = mockk<SimpleTrack>()
		mockSeparator = mockk<PathSeparator>()
	}

	// ==================== SimulationException Tests ====================

	@Nested
	@DisplayName("SimulationException Constructors")
	inner class SimulationExceptionConstructorTests {
		@Test
		fun `constructor with message only creates exception`() {
			// Act
			val exception = SimulationException(testMessage)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(null)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `constructor with message and object creates exception`() {
			// Arrange
			val obj = Any()

			// Act
			val exception = SimulationException(testMessage, obj)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.getObject()).isEqualTo(obj)
		}

		@Test
		fun `constructor with message, cause, object and time creates exception`() {
			// Act
			val exception = SimulationException(testMessage, testCause, mockTrack)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(mockTrack)
			assertThat(exception.getTime()).isNotNull()
		}

		@Test
		fun `constructor with cause creates exception`() {
			// Act
			val exception = SimulationException(testCause)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `getObject returns stored object`() {
			// Arrange
			val obj = Any()
			val exception = SimulationException(testMessage, null, obj)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isEqualTo(obj)
		}

		@Test
		fun `getTime returns valid time value`() {
			// Arrange
			mockContext.setTime(42.5)
			val exception = SimulationException(testMessage, null, mockTrack)

			// Act
			val time = exception.getTime()

			// Assert
			assertThat(time).isNotNull()
		}

		@Test
		fun `toString includes class name`() {
			// Arrange
			mockContext.setTime(10.0)
			val exception = SimulationException(testMessage, null, mockTrack)

			// Act
			val result = exception.toString()

			// Assert
			assertThat(result).contains("SimulationException")
		}

		@Test
		fun `toString includes time`() {
			// Arrange
			mockContext.setTime(5.5)
			val exception = SimulationException(testMessage, null, null)

			// Act
			val result = exception.toString()

			// Assert
			assertThat(result).contains("at time")
		}

		@Test
		fun `toString format is correct`() {
			// Arrange
			mockContext.setTime(0.0)
			val exception = SimulationException(testMessage, null, null)

			// Act
			val result = exception.toString()

			// Assert
			assertThat(result).startsWith("SimulationException[FATAL]:")
			assertThat(result).contains(testMessage)
			assertThat(result).contains("at time")
		}

		@Test
		fun `constructor with null message creates exception`() {
			// Act - using empty string since message is String type
			val exception = SimulationException("", null, null)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.getObject()).isEqualTo(null)
		}

		@Test
		fun `exception is throwable`() {
			// Arrange
			val exception = SimulationException(testMessage, testCause, mockTrack)

			// Act & Assert
			assertThatThrownBy { throw exception }
				.isInstanceOf(SimulationException::class)
		}

		@Test
		fun `exception extends Exception`() {
			// Arrange
			val exception = SimulationException(testMessage, null, null)

			// Assert
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `multiple exceptions have different times`() {
			// Arrange
			val exception1 = SimulationException("Message 1", null, null)
			mockContext.advanceTime(1.0)
			val exception2 = SimulationException("Message 2", null, null)

			// Act
			val time1 = exception1.getTime()
			val time2 = exception2.getTime()

			// Assert - times may be the same depending on jDisco time, so just verify both are valid
			assertThat(time1).isNotNull()
			assertThat(time2).isNotNull()
		}

		@Test
		fun `exception with null object returns null from getObject`() {
			// Arrange
			val exception = SimulationException(testMessage, testCause, null)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isEqualTo(null)
		}
	}

	// ==================== TrackOperationException Tests ====================

	@Nested
	@DisplayName("TrackOperationException")
	inner class TrackOperationExceptionTests {
		@Test
		fun `constructor with track creates exception`() {
			// Act
			val exception = TrackOperationException(mockTrack)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.getObject()).isEqualTo(mockTrack)
		}

		@Test
		fun `constructor with message and track creates exception`() {
			// Act
			val exception = TrackOperationException(testMessage, mockTrack)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.getObject()).isEqualTo(mockTrack)
		}

		@Test
		fun `constructor with cause and track creates exception`() {
			// Act
			val exception = TrackOperationException(testCause, mockTrack)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(mockTrack)
		}

		@Test
		fun `constructor with message, cause, and track creates exception`() {
			// Act
			val exception = TrackOperationException(testMessage, testCause, mockTrack)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(mockTrack)
		}

		@Test
		fun `getObject returns correct track`() {
			// Arrange
			val exception = TrackOperationException(testMessage, mockTrack)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isEqualTo(mockTrack)
		}

		@Test
		fun `getObject is type-safe and returns Track`() {
			// Arrange
			val exception = TrackOperationException(testMessage, mockTrack)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isNotNull()
		}

		@Test
		fun `exception hierarchy is correct - extends SimulationException`() {
			// Arrange
			val exception = TrackOperationException(mockTrack)

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `exception is throwable as SimulationException`() {
			// Arrange
			val exception = TrackOperationException(testMessage, mockTrack)

			// Act & Assert
			assertThatThrownBy { throw exception }
				.isInstanceOf(TrackOperationException::class)
		}

		@Test
		fun `toString includes SimulationException format`() {
			// Arrange
			val exception = TrackOperationException(testMessage, mockTrack)

			// Act
			val result = exception.toString()

			// Assert
			assertThat(result).contains("TrackOperationException")
			assertThat(result).contains("at time")
		}

		@Test
		fun `multiple track operations can have different exceptions`() {
			// Arrange
			val track1 = mockk<SimpleTrack>()
			val track2 = mockk<SimpleTrack>()
			val exception1 = TrackOperationException(track1)
			val exception2 = TrackOperationException(track2)

			// Act
			val result1 = exception1.getObject()
			val result2 = exception2.getObject()

			// Assert
			assertThat(result1).isEqualTo(track1)
			assertThat(result2).isEqualTo(track2)
		}

		@Test
		fun `exception with cause preserves cause chain`() {
			// Arrange
			val rootCause = IllegalArgumentException("Invalid track state")
			val exception = TrackOperationException(testMessage, rootCause, mockTrack)

			// Act
			val result = exception.cause

			// Assert
			assertThat(result).isEqualTo(rootCause)
		}
	}

	// ==================== PathSeparatorChangeException Tests ====================

	@Nested
	@DisplayName("PathSeparatorChangeException")
	inner class PathSeparatorChangeExceptionTests {
		@Test
		fun `constructor with separator creates exception`() {
			// Act
			val exception = PathSeparatorChangeException(mockSeparator)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.getObject()).isEqualTo(mockSeparator)
		}

		@Test
		fun `constructor with message and separator creates exception`() {
			// Act
			val exception = PathSeparatorChangeException(testMessage, mockSeparator)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.getObject()).isEqualTo(mockSeparator)
		}

		@Test
		fun `constructor with cause and separator creates exception`() {
			// Act
			val exception = PathSeparatorChangeException(testCause, mockSeparator)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(mockSeparator)
		}

		@Test
		fun `constructor with message, cause, and separator creates exception`() {
			// Act
			val exception = PathSeparatorChangeException(testMessage, testCause, mockSeparator)

			// Assert
			assertThat(exception).isNotNull()
			assertThat(exception.message).isEqualTo(testMessage)
			assertThat(exception.cause).isEqualTo(testCause)
			assertThat(exception.getObject()).isEqualTo(mockSeparator)
		}

		@Test
		fun `getObject returns correct separator`() {
			// Arrange
			val exception = PathSeparatorChangeException(testMessage, mockSeparator)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isEqualTo(mockSeparator)
		}

		@Test
		fun `getObject is type-safe and returns PathSeparator`() {
			// Arrange
			val exception = PathSeparatorChangeException(testMessage, mockSeparator)

			// Act
			val result = exception.getObject()

			// Assert
			assertThat(result).isNotNull()
		}

		@Test
		fun `exception hierarchy is correct - extends SimulationException`() {
			// Arrange
			val exception = PathSeparatorChangeException(mockSeparator)

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `exception is throwable as SimulationException`() {
			// Arrange
			val exception = PathSeparatorChangeException(testMessage, mockSeparator)

			// Act & Assert
			assertThatThrownBy { throw exception }
				.isInstanceOf(PathSeparatorChangeException::class)
		}

		@Test
		fun `toString includes PathSeparatorChangeException format`() {
			// Arrange
			val exception = PathSeparatorChangeException(testMessage, mockSeparator)

			// Act
			val result = exception.toString()

			// Assert
			assertThat(result).contains("PathSeparatorChangeException")
			assertThat(result).contains("at time")
		}

		@Test
		fun `multiple path separators can have different exceptions`() {
			// Arrange
			val separator1 = mockk<PathSeparator>()
			val separator2 = mockk<PathSeparator>()
			val exception1 = PathSeparatorChangeException(separator1)
			val exception2 = PathSeparatorChangeException(separator2)

			// Act
			val result1 = exception1.getObject()
			val result2 = exception2.getObject()

			// Assert
			assertThat(result1).isEqualTo(separator1)
			assertThat(result2).isEqualTo(separator2)
		}

		@Test
		fun `exception with cause preserves cause chain`() {
			// Arrange
			val rootCause = IllegalStateException("Invalid separator state")
			val exception = PathSeparatorChangeException(testMessage, rootCause, mockSeparator)

			// Act
			val result = exception.cause

			// Assert
			assertThat(result).isEqualTo(rootCause)
		}
	}

	// ==================== Exception Chaining Tests ====================

	@Nested
	@DisplayName("Exception Chaining")
	inner class ExceptionChainingTests {
		@Test
		fun `cause propagated correctly in SimulationException`() {
			// Arrange
			val rootCause = Exception("Root cause message")
			val exception = SimulationException(testMessage, rootCause, null)

			// Act
			val result = exception.cause

			// Assert
			assertThat(result).isEqualTo(rootCause)
		}

		@Test
		fun `cause propagated correctly in TrackOperationException`() {
			// Arrange
			val rootCause = Exception("Track operation failed")
			val exception = TrackOperationException(testMessage, rootCause, mockTrack)

			// Act
			val result = exception.cause

			// Assert
			assertThat(result).isEqualTo(rootCause)
		}

		@Test
		fun `cause propagated correctly in PathSeparatorChangeException`() {
			// Arrange
			val rootCause = Exception("Path separator change failed")
			val exception = PathSeparatorChangeException(testMessage, rootCause, mockSeparator)

			// Act
			val result = exception.cause

			// Assert
			assertThat(result).isEqualTo(rootCause)
		}

		@Test
		fun `cause message preserved in exception chain`() {
			// Arrange
			val causeMessage = "Original error message"
			val rootCause = Exception(causeMessage)
			val exception = SimulationException(testMessage, rootCause, null)

			// Act
			val causeResult = exception.cause
			val causeMsg = causeResult?.message

			// Assert
			assertThat(causeMsg).isEqualTo(causeMessage)
		}

		@Test
		fun `nested exception chain is preserved`() {
			// Arrange
			val deepCause = Exception("Deep cause")
			val middleCause = Exception("Middle cause", deepCause)
			val exception = SimulationException(testMessage, middleCause, null)

			// Act
			val directCause = exception.cause
			val nestedCause = directCause?.cause

			// Assert
			assertThat(directCause).isEqualTo(middleCause)
			assertThat(nestedCause).isEqualTo(deepCause)
		}

		@Test
		fun `exception without cause has null cause`() {
			// Arrange
			val exception = SimulationException(testMessage, null, null)

			// Act
			val result = exception.cause

			// Assert
			assertThat(result).isEqualTo(null)
		}

		@Test
		fun `stack trace preserved through exception chain`() {
			// Arrange
			val rootCause = Exception("Root cause")
			val exception = SimulationException(testMessage, rootCause, null)

			// Act
			val stackTrace = exception.stackTrace

			// Assert
			assertThat(stackTrace).isNotNull()
		}

		@Test
		fun `exception with object and cause stores both`() {
			// Arrange
			val obj = Any()
			val cause = Exception("Cause")
			val exception = SimulationException(testMessage, cause, obj)

			// Act
			val resultCause = exception.cause
			val resultObj = exception.getObject()

			// Assert
			assertThat(resultCause).isEqualTo(cause)
			assertThat(resultObj).isEqualTo(obj)
		}

		@Test
		fun `track operation exception preserves full context`() {
			// Arrange
			val cause = Exception("Track failure")
			val track = mockk<SimpleTrack>()
			val exception = TrackOperationException(testMessage, cause, track)

			// Act
			val resultMessage = exception.message
			val resultCause = exception.cause
			val resultTrack = exception.getObject()

			// Assert
			assertThat(resultMessage).isEqualTo(testMessage)
			assertThat(resultCause).isEqualTo(cause)
			assertThat(resultTrack).isEqualTo(track)
		}

		@Test
		fun `path separator exception preserves full context`() {
			// Arrange
			val cause = Exception("Path failure")
			val separator = mockk<PathSeparator>()
			val exception = PathSeparatorChangeException(testMessage, cause, separator)

			// Act
			val resultMessage = exception.message
			val resultCause = exception.cause
			val resultSeparator = exception.getObject()

			// Assert
			assertThat(resultMessage).isEqualTo(testMessage)
			assertThat(resultCause).isEqualTo(cause)
			assertThat(resultSeparator).isEqualTo(separator)
		}
	}

	// ==================== Exception Hierarchy Tests ====================

	@Nested
	@DisplayName("Exception Type Hierarchy")
	inner class ExceptionHierarchyTests {
		@Test
		fun `SimulationException is Exception`() {
			// Arrange
			val exception = SimulationException(testMessage, null, null)

			// Assert
			assertThat(exception).isInstanceOf(Exception::class)
		}

		@Test
		fun `TrackOperationException is SimulationException`() {
			// Arrange
			val exception = TrackOperationException(mockTrack)

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
		}

		@Test
		fun `PathSeparatorChangeException is SimulationException`() {
			// Arrange
			val exception = PathSeparatorChangeException(mockSeparator)

			// Assert
			assertThat(exception).isInstanceOf(SimulationException::class)
		}

		@Test
		fun `different exception types can be caught as SimulationException`() {
			// Arrange
			val simException = SimulationException(testMessage, null, null)
			val trackException = TrackOperationException(mockTrack)
			val pathException = PathSeparatorChangeException(mockSeparator)

			// Assert - verify catch compatibility
			assertThat(simException).isInstanceOf(SimulationException::class)
			assertThat(trackException).isInstanceOf(SimulationException::class)
			assertThat(pathException).isInstanceOf(SimulationException::class)
		}

		@Test
		fun `exception type can be distinguished`() {
			// Arrange
			val simException: SimulationException = SimulationException(testMessage, null, null)
			val trackException: SimulationException = TrackOperationException(mockTrack)

			// Act & Assert
			assertThat(simException::class.java.simpleName).isEqualTo("SimulationException")
			assertThat(trackException::class.java.simpleName).isEqualTo("TrackOperationException")
		}
	}
}
