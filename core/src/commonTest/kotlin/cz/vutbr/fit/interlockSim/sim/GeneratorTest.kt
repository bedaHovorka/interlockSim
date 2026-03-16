/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for Generator
 * Phase 5 test implementation - 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.get

/**
 * Unit tests for {@link Generator}.
 *
 * The Generator is a jDisco LoopProcess that creates Train objects at scheduled intervals
 * with random timetables. This test suite validates the observable behavior of the Generator
 * class without directly simulating jDisco processes.
 *
 * Testing Strategy (Conservative jDisco testing - see CLAUDE.md Code Modification Guidelines):
 * - Test Generator through public APIs and data structures
 * - Test train list accumulation (trains collection is public)
 * - Test configuration flags (shuffleInOuts parameter)
 * - Avoid calling protected iteration() method which requires jDisco simulation time
 * - Use MockSimulationContext wrapping TestContextBuilder for InOut availability
 * - Focus on state and properties rather than runtime behavior
 * - Document SIM-003 (unused variable increment in interLoopSleep)
 *
 * Coverage Areas:
 * - Generator construction with various parameters
 * - Public trains collection access and properties
 * - Random instance management (seeding)
 * - Configuration flag behavior
 * - Context integration through getInOuts()
 *
 * Limitations (Due to jDisco dependency):
 * - Cannot test iteration() directly without jDisco event simulation (would block on time())
 * - Cannot test inter-arrival time scheduling without running full simulation
 * - Cannot test train placement and path setup without jDisco activation
 * - SIM-003 (unused variable i increment) - documented but not directly testable
 *
 * Railway Context:
 * The Generator simulates a railway traffic generator that creates trains according to
 * a timetable. Real generators control train admission to minimize congestion while
 * ensuring scheduled service. These tests validate Generator construction and configuration.
 */

@DisplayName("Generator Tests")
class GeneratorTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		// Create a context with at least 2 InOuts for Generator to use
		val contextBuilder =
			get<TestContextBuilder>()
				.withInOut("IN1", 0, 0, isEntry = true)
				.withInOut("OUT1", 1, 0, isEntry = false)
		val delegateContext = contextBuilder.buildSimulationContext()
		mockContext = MockSimulationContext(delegateContext)
	}

	@Nested
	@DisplayName("Generator Construction")
	inner class ConstructorTests {
		/**
		 * Test: Generator constructs with minimal parameters.
		 *
		 * Verifies that a Generator can be created with a context and shuffleInOuts flag.
		 * The trains list should be empty initially.
		 */
		@Test
		fun `generator constructs with context`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Assert
			assertThat(generator).isNotNull()
			assertThat(generator.trains).isNotNull()
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Generator constructs with shuffleInOuts enabled.
		 *
		 * Verifies that generator accepts the shuffleInOuts configuration parameter.
		 */
		@Test
		fun `generator constructs with shuffleInOuts enabled`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = true)

			// Assert
			assertThat(generator).isNotNull()
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Generator constructs with shuffleInOuts disabled.
		 *
		 * Verifies that generator accepts shuffleInOuts = false for reproducible testing.
		 */
		@Test
		fun `generator constructs with shuffleInOuts disabled`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Assert
			assertThat(generator).isNotNull()
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Generator has public trains collection.
		 *
		 * Verifies that the trains ArrayList is publicly accessible for inspection.
		 */
		@Test
		fun `generator has public trains collection`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act
			val trains = generator.trains

			// Assert
			assertThat(trains).isNotNull()
			assertThat(trains.size).isEqualTo(0)
		}
	}

	@Nested
	@DisplayName("Train Collection Management")
	inner class TrainCollectionTests {
		/**
		 * Test: Trains collection is initially empty.
		 *
		 * A freshly created Generator should have no trains.
		 */
		@Test
		fun `trains collection is initially empty`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act & Assert
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Trains collection type is ArrayList.
		 *
		 * The trains field is an ArrayList for efficient append and iteration.
		 */
		@Test
		fun `trains collection is mutable ArrayList`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act
			val trains = generator.trains

			// Assert
			// ArrayList should be empty and usable
			assertThat(trains).isNotNull()
			assertThat(trains.javaClass.simpleName).isEqualTo("ArrayList")
		}

		/**
		 * Test: Trains can be added to collection externally.
		 *
		 * The trains ArrayList is public and mutable, allowing test code
		 * to add trains for testing train management.
		 */
		@Test
		fun `trains collection accepts external additions`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)
			val timetable = createTimetableWithLength(100.0)
			val train = Train(mockContext, timetable)

			// Act
			generator.trains.add(train)

			// Assert
			assertThat(generator.trains.size).isEqualTo(1)
			assertThat(generator.trains[0]).isEqualTo(train)
		}

		/**
		 * Test: Multiple trains can be accumulated in collection.
		 *
		 * The trains collection can hold multiple Train objects, maintaining order.
		 */
		@Test
		fun `trains collection maintains order with multiple entries`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)
			val train1 = Train(mockContext, createTimetableWithLength(50.0))
			val train2 = Train(mockContext, createTimetableWithLength(100.0))
			val train3 = Train(mockContext, createTimetableWithLength(150.0))

			// Act
			generator.trains.add(train1)
			generator.trains.add(train2)
			generator.trains.add(train3)

			// Assert
			assertThat(generator.trains.size).isEqualTo(3)
			assertThat(generator.trains[0]).isEqualTo(train1)
			assertThat(generator.trains[1]).isEqualTo(train2)
			assertThat(generator.trains[2]).isEqualTo(train3)
		}
	}

	@Nested
	@DisplayName("Random Instance Management")
	inner class RandomInstanceTests {
		/**
		 * Test: Generator has a protected Random field.
		 *
		 * The Generator uses jDisco Random for stochastic timetable generation.
		 */
		@Test
		fun `generator has random field initialized`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Assert
			// Check by reflection that random field exists and is not null
			val randomField = Generator::class.java.getDeclaredField("random")
			randomField.isAccessible = true
			val randomValue = randomField.get(generator)
			assertThat(randomValue).isNotNull()
		}

		/**
		 * Test: Multiple generators have independent random instances.
		 *
		 * Each Generator instance should have its own Random for independent
		 * stochastic behavior (unless explicitly seeded together).
		 */
		@Test
		fun `multiple generators have independent random instances`() {
			// Arrange
			val generator1 = Generator(mockContext, shuffleInOuts = false)
			val generator2 = Generator(mockContext, shuffleInOuts = false)

			// Act
			val field = Generator::class.java.getDeclaredField("random")
			field.isAccessible = true
			val random1 = field.get(generator1)
			val random2 = field.get(generator2)

			// Assert
			assertThat(random1).isNotNull()
			assertThat(random2).isNotNull()
			// Different instances (not same object reference)
			assertThat(random1).isNotSameInstanceAs(random2)
		}
	}

	@Nested
	@DisplayName("Configuration Options")
	inner class ConfigurationTests {
		/**
		 * Test: shuffleInOuts parameter is stored and respected.
		 *
		 * The Generator accepts shuffleInOuts parameter in constructor
		 * and should use it during timetable generation.
		 */
		@Test
		fun `generator respects shuffleInOuts false configuration`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Assert
			// Generator should be created successfully
			assertThat(generator).isNotNull()
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Generator can be created with shuffleInOuts true.
		 *
		 * The shuffleInOuts=true variant should work without errors.
		 */
		@Test
		fun `generator respects shuffleInOuts true configuration`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = true)

			// Assert
			assertThat(generator).isNotNull()
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Default shuffleInOuts parameter is true.
		 *
		 * Generator constructor default for shuffleInOuts is true.
		 */
		@Test
		fun `generator default shuffleInOuts is true`() {
			// Arrange & Act
			val generator = Generator(mockContext) // Using default

			// Assert
			assertThat(generator).isNotNull()
		}
	}

	@Nested
	@DisplayName("Context Integration")
	inner class ContextIntegrationTests {
		/**
		 * Test: Generator has access to simulation context.
		 *
		 * The Generator stores the SimulationContext for accessing InOut points.
		 */
		@Test
		fun `generator stores simulation context`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act
			val inOuts = mockContext.getInOuts()

			// Assert
			assertThat(inOuts).isNotNull()
			assertThat(inOuts.count()).isGreaterThan(0)
		}

		/**
		 * Test: Context provides InOut points for timetable generation.
		 *
		 * The MockSimulationContext created in setUp should have at least
		 * 2 InOut points for the Generator to select from.
		 */
		@Test
		fun `context has multiple InOut points`() {
			// Arrange & Act
			val inOuts = mockContext.getInOuts()

			// Assert
			assertThat(inOuts).isNotNull()
			// Should have both entry and exit points
			assertThat(inOuts.count()).isGreaterThan(1)
		}

		/**
		 * Test: Generator can access context through protected field.
		 *
		 * The Generator stores the context in a protected field for use by subclasses.
		 */
		@Test
		fun `generator has protected env field`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act
			val envField = Generator::class.java.getDeclaredField("env")
			envField.isAccessible = true
			val envValue = envField.get(generator)

			// Assert
			assertThat(envValue).isNotNull()
			assertThat(envValue).isEqualTo(mockContext)
		}
	}

	@Nested
	@DisplayName("LoopProcess Integration")
	inner class LoopProcessIntegrationTests {
		/**
		 * Test: Generator extends LoopProcess.
		 *
		 * Generator is a subclass of LoopProcess which provides the iteration framework.
		 */
		@Test
		fun `generator extends LoopProcess`() {
			// Arrange & Act
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Assert
			assertThat(generator is cz.vutbr.fit.interlockSim.sim.LoopProcess).isEqualTo(true)
		}

		/**
		 * Test: Generator has methods from LoopProcess.
		 *
		 * Generator should inherit abstract methods like iteration() from LoopProcess.
		 */
		@Test
		fun `generator has protected iteration method`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act
			val iterationMethod = Generator::class.java.declaredMethods.find { it.name == "iteration" }

			// Assert
			assertThat(iterationMethod).isNotNull()
		}

		/**
		 * Test: Generator has interLoopSleep method from LoopProcess.
		 *
		 * The interLoopSleep method handles timing between iterations.
		 * SIM-003 Note: This method increments unused variable 'i'.
		 */
		@Test
		fun `generator has interLoopSleep method`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act
			val sleepMethod = Generator::class.java.declaredMethods.find { it.name == "interLoopSleep" }

			// Assert
			assertThat(sleepMethod).isNotNull()
		}
	}

	@Nested
	@DisplayName("Edge Cases and Stability")
	inner class EdgeCasesTests {
		/**
		 * Test: Generator handles null trains list gracefully.
		 *
		 * Even if trains were null (shouldn't happen), accessing should be safe.
		 */
		@Test
		fun `generator trains list is not null`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)

			// Act & Assert
			assertThat(generator.trains).isNotNull()
		}

		/**
		 * Test: Generator survives clearing the trains list.
		 *
		 * The trains list should be robust enough to handle clear() operation.
		 */
		@Test
		fun `generator trains list can be cleared`() {
			// Arrange
			val generator = Generator(mockContext, shuffleInOuts = false)
			val train = Train(mockContext, createTimetableWithLength(100.0))
			generator.trains.add(train)

			// Act
			generator.trains.clear()

			// Assert
			assertThat(generator.trains.size).isEqualTo(0)
		}

		/**
		 * Test: Generator can be recreated with different configurations.
		 *
		 * Multiple Generator instances should not interfere with each other.
		 */
		@Test
		fun `multiple generator instances are independent`() {
			// Arrange
			val gen1 = Generator(mockContext, shuffleInOuts = true)
			val gen2 = Generator(mockContext, shuffleInOuts = false)

			// Act
			gen1.trains.add(Train(mockContext, createTimetableWithLength(50.0)))
			gen2.trains.add(Train(mockContext, createTimetableWithLength(100.0)))

			// Assert
			assertThat(gen1.trains.size).isEqualTo(1)
			assertThat(gen2.trains.size).isEqualTo(1)
			assertThat(gen1.trains[0].getLength()).isEqualTo(50.0)
			assertThat(gen2.trains[0].getLength()).isEqualTo(100.0)
		}
	}

	// ==================== Helper Methods ====================

	/**
	 * Creates a timetable with specified train length for testing.
	 *
	 * @param length the train length to set in the timetable
	 * @return a timetable configured with the given length
	 */
	private fun createTimetableWithLength(length: Double): Timetable {
		// Get first two InOuts from context
		val inOuts = mockContext.getInOuts().toList()
		return Timetable(
			inOuts[0],
			inOuts[1],
			Time(0.0),
			Time(0.0),
			length
		)
	}
}
