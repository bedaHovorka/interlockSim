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
			assertThat(exampleNames).hasSize(5)
			assertThat(exampleNames.contains("shuntingLoop")).isTrue()
			assertThat(exampleNames.contains("shuntingLoopAI")).isTrue()
			assertThat(exampleNames.contains("shuntingLoopSync")).isTrue()
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
	@DisplayName("ShuntingLoopAI Example Factory")
	inner class ShuntingLoopAIFactoryTests {
		/**
		 * Test that createShuntingLoopAIGuiExample registers its MeasuringPlanAdapter into
		 * the context's Koin scope, so callers outside ExampleRegistry (e.g. Frame's
		 * SimulationController.STOPPED handler) can retrieve it after the run ends.
		 */
		@Test
		fun `createShuntingLoopAIGuiExample registers MeasuringPlanAdapter in context scope`() {
			val registry = get<ExampleRegistry>()
			val createMethod =
				ExampleRegistry::class.java.getDeclaredMethod(
					"createShuntingLoopAIGuiExample",
					cz.vutbr.fit.interlockSim.context.SimulationContextFactory::class.java,
					Array<String>::class.java
				)
			createMethod.isAccessible = true
			val factory = get<cz.vutbr.fit.interlockSim.context.SimulationContextFactory>()
			val args = arrayOf("exampleGui", "shuntingLoopAI", "100")

			try {
				val result = createMethod.invoke(registry, factory, args)
				val context = result as cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
				assertThat(
					context.scope.getOrNull<cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter>()
				).isNotNull()
			} catch (e: java.lang.reflect.InvocationTargetException) {
				val cause = e.cause
				// ContextCreationException is acceptable if vyhybna.xml isn't found in the
				// test environment — mirrors the tolerance in ShuntingLoopFactoryTests above.
				if (cause is ContextCreationException) {
					assertThat((cause.message ?: "").contains("vyhybna.xml")).isTrue()
				} else {
					throw e
				}
			}
		}

		/**
		 * Test that createShuntingLoopAIExample (headless) registers its MeasuringPlanAdapter
		 * into the context's Koin scope (Issue #873 — headless AI console example).
		 *
		 * Mirrors the pattern of [createShuntingLoopAIGuiExample] but uses a
		 * [cz.vutbr.fit.interlockSim.context.ThrottlingSimulationController] instead of
		 * [cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController] so the
		 * headless run satisfies [assertPlannerPacingCompatible].
		 */
		@Test
		fun `createShuntingLoopAIExample registers MeasuringPlanAdapter in context scope`() {
			val registry = get<ExampleRegistry>()
			val createMethod =
				ExampleRegistry::class.java.getDeclaredMethod(
					"createShuntingLoopAIExample",
					cz.vutbr.fit.interlockSim.context.SimulationContextFactory::class.java,
					Array<String>::class.java
				)
			createMethod.isAccessible = true
			val factory = get<cz.vutbr.fit.interlockSim.context.SimulationContextFactory>()
			val args = arrayOf("example", "shuntingLoopAI", "100")

			try {
				val result = createMethod.invoke(registry, factory, args)
				val context = result as cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
				assertThat(
					context.scope.getOrNull<cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter>()
				).isNotNull()
			} catch (e: java.lang.reflect.InvocationTargetException) {
				val cause = e.cause
				if (cause is ContextCreationException) {
					assertThat((cause.message ?: "").contains("vyhybna.xml")).isTrue()
				} else {
					throw e
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
			assertThat(sortedNames).isEqualTo(
				listOf("multiTrainLoop", "shuntingLoop", "shuntingLoopAI", "shuntingLoopSync", "threeTrainLoop")
			)
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
		 * Test that shuntingLoopAI is in BOTH console examples and GUI examples (Issue #873).
		 *
		 * Before Issue #873 (SP2c.26 follow-up I2), shuntingLoopAI was GUI-only because
		 * [assertPlannerPacingCompatible] rejected async planners bound to [NoOpSimulationController],
		 * which was the only controller available for console runs (R8 in Issue #822).
		 *
		 * Issue #873 resolves R8: [createShuntingLoopAIExample] wires the async LLM planner to a
		 * [cz.vutbr.fit.interlockSim.context.ThrottlingSimulationController] at
		 * [cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER],
		 * which is a real pacing controller constructible without `:desktop-ui` on the classpath.
		 * The guard ([assertPlannerPacingCompatible]) is **unchanged** — it still rejects async +
		 * [cz.vutbr.fit.interlockSim.context.NoOpSimulationController]; the fix is to give headless
		 * runs a controller that legitimately satisfies it.
		 */
		@Test
		fun `shuntingLoopAI is in both console and GUI examples`() {
			val registry = get<ExampleRegistry>()
			// shuntingLoopAI is now also registered as a console example (Issue #873, R8 resolution)
			assertThat(registry.examples.containsKey("shuntingLoopAI")).isTrue()
			// shuntingLoopAI remains in GUI examples as before
			assertThat(registry.guiExamples.containsKey("shuntingLoopAI")).isTrue()
		}

		/**
		 * Test that GUI example names include shuntingLoopAI (SP2b.9, Issue #566)
		 */
		@Test
		fun `GUI example names include shuntingLoopAI`() {
			val registry = get<ExampleRegistry>()
			val guiNames = registry.getAvailableGuiExamples()
			assertThat(guiNames).contains("shuntingLoopAI")
		}
	}
}
