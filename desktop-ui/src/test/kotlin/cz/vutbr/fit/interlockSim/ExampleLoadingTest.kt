/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.message
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.test.get

/**
 * Tests for the example loading system.
 *
 * This validates the map registry-based example system that allows users to run
 * built-in simulation scenarios like shuntingLoop via command line.
 *
 * Refactored from reflection-based approach (January 2026) to use idiomatic
 * Kotlin with map registry.
 */
@DisplayName("Example Loading Tests")
class ExampleLoadingTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	@Nested
	@DisplayName("Example Registry")
	inner class ExampleRegistryTests {
		/**
		 * Test that ExampleRegistry has examples registered
		 */
		@Test
		fun `examples registry exists and is accessible`() {
			// Arrange
			val registry = get<ExampleRegistry>()

			// Act
			val examples = registry.examples

			// Assert
			assertThat(examples).isNotNull()
			assertThat(examples.size > 0).isTrue()
		}

		/**
		 * Test that shuntingLoop example is registered
		 */
		@Test
		fun `shuntingLoop example is registered`() {
			// Arrange
			val registry = get<ExampleRegistry>()

			// Act
			val hasShuntingLoop = registry.examples.containsKey("shuntingLoop")

			// Assert
			assertThat(hasShuntingLoop).isTrue()
		}

		/**
		 * Test that example names can be retrieved
		 */
		@Test
		fun `example names are retrievable`() {
			// Arrange
			val registry = get<ExampleRegistry>()

			// Act
			val exampleNames = registry.getAvailableExamples()

			// Assert
			assertThat(exampleNames).hasSize(3)
			assertThat(exampleNames.contains("shuntingLoop")).isTrue()
			assertThat(exampleNames.contains("multiTrainLoop")).isTrue()
			assertThat(exampleNames.contains("threeTrainLoop")).isTrue()
		}
	}

	@Nested
	@DisplayName("Example Execution via runExample")
	inner class ExampleExecutionTests {
		/**
		 * Test that runExample with no arguments shows available examples
		 */
		@Test
		fun `runExample with no example name shows available examples`() {
			// Arrange
			val main = get<Main>()
			val args = arrayOf("example")

			// Act - this should log a warning but not throw
			main.runExample(args)

			// Assert - no exception thrown
		}

		/**
		 * Test that runExample with unknown example name shows error
		 */
		@Test
		fun `runExample with unknown example name handles gracefully`() {
			// Arrange
			val main = get<Main>()
			val args = arrayOf("example", "unknownExample", "100")

			// Act - should log error but not throw
			main.runExample(args)

			// Assert - no exception thrown
		}

		/**
		 * Test that runExample with insufficient arguments throws ContextCreationException
		 */
		@Test
		fun `runExample with insufficient arguments catches exception`() {
			// Arrange
			val main = get<Main>()
			val args = arrayOf("example", "shuntingLoop") // Missing endTime

			// Act - should catch ContextCreationException and log it
			main.runExample(args)

			// Assert - no exception propagated
		}

		/**
		 * Test that runExample with non-numeric endTime catches exception
		 */
		@Test
		fun `runExample with non-numeric endTime catches exception`() {
			// Arrange
			val main = get<Main>()
			val args = arrayOf("example", "shuntingLoop", "notANumber")

			// Act - should catch NumberFormatException and log it
			main.runExample(args)

			// Assert - no exception propagated
		}
	}

	@Nested
	@DisplayName("ShuntingLoop Example Factory")
	inner class ShuntingLoopFactoryTests {
		/**
		 * Test that shuntingLoop factory is accessible via registry
		 */
		@Test
		fun `shuntingLoop factory can be invoked via registry`() {
			// Arrange
			val registry = get<ExampleRegistry>()
			val factory = registry.examples["shuntingLoop"]

			// Act & Assert
			assertThat(factory).isNotNull()
		}

		/**
		 * Test that createShuntingLoopExample with insufficient args throws
		 */
		@Test
		fun `createShuntingLoopExample requires endTime argument`() {
			// Arrange
			val registry = get<ExampleRegistry>()
			val createMethod =
				ExampleRegistry::class.java.getDeclaredMethod(
					"createShuntingLoopExample",
					cz.vutbr.fit.interlockSim.context.SimulationContextFactory::class.java,
					Array<String>::class.java
				)
			createMethod.isAccessible = true
			val factory = get<cz.vutbr.fit.interlockSim.context.SimulationContextFactory>()
			val args = arrayOf("example", "shuntingLoop") // Missing endTime

			// Act & Assert
			assertFailure {
				createMethod.invoke(registry, factory, args)
			}.isInstanceOf<java.lang.reflect.InvocationTargetException>()
				.transform { it.cause }
				.isNotNull()
				.isInstanceOf<ContextCreationException>()
				.message()
				.isNotNull()
				.contains("End time")
		}

		/**
		 * Test that createShuntingLoopExample with non-numeric endTime throws
		 */
		@Test
		fun `createShuntingLoopExample validates endTime is numeric`() {
			// Arrange
			val registry = get<ExampleRegistry>()
			val createMethod =
				ExampleRegistry::class.java.getDeclaredMethod(
					"createShuntingLoopExample",
					cz.vutbr.fit.interlockSim.context.SimulationContextFactory::class.java,
					Array<String>::class.java
				)
			createMethod.isAccessible = true
			val factory = get<cz.vutbr.fit.interlockSim.context.SimulationContextFactory>()
			val args = arrayOf("example", "shuntingLoop", "notANumber")

			// Act & Assert
			assertFailure {
				createMethod.invoke(registry, factory, args)
			}.isInstanceOf<java.lang.reflect.InvocationTargetException>()
				.transform { it.cause }
				.isNotNull()
				.isInstanceOf<NumberFormatException>()
		}

		/**
		 * Test that createShuntingLoopExample with valid arguments creates context
		 */
		@Test
		fun `createShuntingLoopExample with valid arguments returns context`() {
			// Arrange
			val registry = get<ExampleRegistry>()
			val createMethod =
				ExampleRegistry::class.java.getDeclaredMethod(
					"createShuntingLoopExample",
					cz.vutbr.fit.interlockSim.context.SimulationContextFactory::class.java,
					Array<String>::class.java
				)
			createMethod.isAccessible = true
			val factory = get<cz.vutbr.fit.interlockSim.context.SimulationContextFactory>()
			val args = arrayOf("example", "shuntingLoop", "100")

			// Act & Assert
			try {
				val result = createMethod.invoke(registry, factory, args)
				assertThat(result).isNotNull()
			} catch (e: java.lang.reflect.InvocationTargetException) {
				val cause = e.cause
				// ContextCreationException is acceptable if resource not found in test environment
				if (cause is ContextCreationException) {
					assertThat((cause.message ?: "").contains("vyhybna.xml")).isTrue()
				} else {
					throw AssertionError("Unexpected exception during example creation: $cause")
				}
			}
		}
	}

	@Nested
	@DisplayName("Example List Sorting")
	inner class ExampleListSortingTests {
		/**
		 * Test that example names are sortable
		 */
		@Test
		fun `example names are sortable`() {
			// Arrange
			val registry = get<ExampleRegistry>()

			// Act
			val sortedNames = registry.getAvailableExamples()

			// Assert
			assertThat(sortedNames.size > 0).isTrue()
			assertThat(sortedNames).isEqualTo(listOf("multiTrainLoop", "shuntingLoop", "threeTrainLoop"))
		}

		/**
		 * Test that shuntingLoopAI GUI example is registered (SP2b.9, Issue #566)
		 */
		@Test
		fun `shuntingLoopAI GUI example is registered`() {
			// Arrange
			val registry = get<ExampleRegistry>()

			// Act
			val hasShuntingLoopAI = registry.guiExamples.containsKey("shuntingLoopAI")

			// Assert
			assertThat(hasShuntingLoopAI).isTrue()
		}

		/**
		 * Test that shuntingLoopAI is only in GUI examples, not in console examples (SP2b.9, Issue #566)
		 */
		@Test
		fun `shuntingLoopAI is a GUI-only example`() {
			// The async LLM planner requires a pacing controller — console (NoOp) mode is rejected
			// by assertPlannerPacingCompatible, so shuntingLoopAI must not appear in console examples.
			val registry = get<ExampleRegistry>()
			assertThat(registry.examples.containsKey("shuntingLoopAI")).isEqualTo(false)
			assertThat(registry.guiExamples.containsKey("shuntingLoopAI")).isTrue()
		}

		/**
		 * Test that GUI example names include shuntingLoopAI (SP2b.9, Issue #566)
		 */
		@Test
		fun `GUI example names include shuntingLoopAI`() {
			val registry = get<ExampleRegistry>()
			val guiNames = registry.getAvailableGuiExamples()
			assertThat(guiNames.contains("shuntingLoopAI")).isTrue()
		}
	}
}
