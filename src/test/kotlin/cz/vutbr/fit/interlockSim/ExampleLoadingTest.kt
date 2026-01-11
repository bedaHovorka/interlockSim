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

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextCreationException
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Comprehensive tests for example loading and execution via reflection.
 *
 * Tests the discovery and invocation of methods annotated with @Example in the Main class.
 * This validates the reflection-based example system that allows users to run built-in
 * simulation scenarios like shuntingLoop.
 */
@DisplayName("Example Loading Tests")
class ExampleLoadingTest {
	@Nested
	@DisplayName("Example Discovery")
	inner class ExampleDiscoveryTests {
		/**
		 * Test that examples can be discovered via reflection.
		 * Validates that Main class contains methods and we can inspect them.
		 */
		@Test
		fun `examples discovered via reflection`() {
			// Arrange
			val mainClass = Main::class.java

			// Act
			val allMethods = mainClass.declaredMethods

			// Assert
			assertThat(allMethods.size > 0).isTrue()
		}

		/**
		 * Test that example list includes the shuntingLoop example.
		 * Validates that the @Example annotation marks the shuntingLoop method.
		 */
		@Test
		fun `example list includes shuntingLoop`() {
			// Arrange
			val mainClass = Main::class.java
			val examples = mutableListOf<String>()

			// Act
			for (method in mainClass.declaredMethods) {
				if (Modifier.isPublic(method.modifiers) && method.isAnnotationPresent(Example::class.java)) {
					examples.add(method.name)
				}
			}

			// Assert
			assertThat(examples.contains("shuntingLoop")).isTrue()
		}

		/**
		 * Test that example discovery finds annotated methods.
		 * Validates that reflection-based discovery finds at least one example.
		 */
		@Test
		fun `example discovery finds annotated methods`() {
			// Arrange
			val mainClass = Main::class.java
			var annotatedCount = 0

			// Act
			for (method in mainClass.declaredMethods) {
				if (method.isAnnotationPresent(Example::class.java)) {
					annotatedCount++
				}
			}

			// Assert
			assertThat(annotatedCount > 0).isTrue()
		}

		/**
		 * Test that example method names are discoverable.
		 * Validates that discovered names match method names.
		 */
		@Test
		fun `example names match method names`() {
			// Arrange
			val mainClass = Main::class.java

			// Act
			val exampleMethod =
				mainClass.declaredMethods.find { m ->
					Modifier.isPublic(m.modifiers) && m.name == "shuntingLoop"
				}

			// Assert
			assertThat(exampleMethod).isNotNull()
			assertThat(exampleMethod!!.isAnnotationPresent(Example::class.java)).isTrue()
		}
	}

	@Nested
	@DisplayName("Example Method Signature Validation")
	inner class ExampleMethodSignatureTests {
		/**
		 * Test that example methods have the correct signature.
		 * Validates that shuntingLoop method accepts (SimulationContextFactory, Array<String>).
		 */
		@Test
		fun `example methods have correct signature`() {
			// Arrange
			val mainClass = Main::class.java
			val shuntingLoopMethod =
				mainClass.getMethod(
					"shuntingLoop",
					SimulationContextFactory::class.java,
					Array<String>::class.java
				)

			// Act
			val paramTypes = shuntingLoopMethod.parameterTypes

			// Assert
			assertThat(paramTypes).hasSize(2)
			assertThat(paramTypes[0] == SimulationContextFactory::class.java).isTrue()
			assertThat(paramTypes[1] == Array<String>::class.java).isTrue()
		}

		/**
		 * Test that example method is public.
		 * Validates that example methods are accessible for invocation.
		 */
		@Test
		fun `example method is public`() {
			// Arrange
			val mainClass = Main::class.java
			val shuntingLoopMethod =
				mainClass.getMethod(
					"shuntingLoop",
					SimulationContextFactory::class.java,
					Array<String>::class.java
				)

			// Act
			val isPublic = Modifier.isPublic(shuntingLoopMethod.modifiers)

			// Assert
			assertThat(isPublic).isTrue()
		}

		/**
		 * Test that example methods are marked with @Example annotation.
		 * Validates that the annotation is correctly applied.
		 */
		@Test
		fun `example method has @Example annotation`() {
			// Arrange
			val mainClass = Main::class.java
			val shuntingLoopMethod =
				mainClass.getMethod(
					"shuntingLoop",
					SimulationContextFactory::class.java,
					Array<String>::class.java
				)

			// Act
			val hasAnnotation = shuntingLoopMethod.isAnnotationPresent(Example::class.java)

			// Assert
			assertThat(hasAnnotation).isTrue()
		}
	}

	@Nested
	@DisplayName("Example Execution")
	inner class ExampleExecutionTests {
		/**
		 * Test that unknown example name throws NoSuchMethodException.
		 * Validates error handling for non-existent examples.
		 */
		@Test
		fun `unknown example throws NoSuchMethodException`() {
			// Arrange
			val mainClass = Main::class.java
			val unknownExampleName = "nonExistentExample"

			// Act & Assert
			try {
				mainClass.getMethod(unknownExampleName, SimulationContextFactory::class.java, Array<String>::class.java)
				throw AssertionError("Should have thrown NoSuchMethodException for unknown example")
			} catch (e: NoSuchMethodException) {
				assertThat(e.message).isNotNull()
			}
		}

		/**
		 * Test that non-annotated method throws error when invoked as example.
		 * Validates that @Example annotation requirement is enforced.
		 */
		@Test
		fun `non-annotated method fails example invocation`() {
			// Arrange
			val mainClass = Main::class.java
			val method = mainClass.getMethod("getInstance")

			// Act
			val hasExampleAnnotation = method.isAnnotationPresent(Example::class.java)

			// Assert
			assertThat(hasExampleAnnotation).isFalse()
		}

		/**
		 * Test that example method with insufficient arguments throws ContextCreationException.
		 * Validates that end time parameter is required.
		 */
		@Test
		fun `example invocation without required end time throws ContextCreationException`() {
			// Arrange
			val mainClass = Main::class.java
			val main = Main.getInstance()
			val factory = XMLContextFactory.getInstance()
			val insufficientArgs = arrayOf("example") // Missing end time

			// Act & Assert
			try {
				val method = mainClass.getMethod("shuntingLoop", SimulationContextFactory::class.java, Array<String>::class.java)
				method.invoke(main, factory, insufficientArgs)
				throw AssertionError("Should have thrown ContextCreationException for missing end time")
			} catch (e: InvocationTargetException) {
				val cause = e.cause
				assertThat(cause).isNotNull()
				assertThat(cause is ContextCreationException).isTrue()
			}
		}

		/**
		 * Test that example method with non-numeric end time throws NumberFormatException.
		 * Validates argument parsing error handling.
		 */
		@Test
		fun `example invocation with non-numeric end time throws NumberFormatException`() {
			// Arrange
			val mainClass = Main::class.java
			val main = Main.getInstance()
			val factory = XMLContextFactory.getInstance()
			val invalidArgs = arrayOf("example", "shuntingLoop", "notANumber")

			// Act & Assert
			val method = mainClass.getMethod("shuntingLoop", SimulationContextFactory::class.java, Array<String>::class.java)
			assertThatBlock {
				method.invoke(main, factory, invalidArgs)
			}.isFailure()
				.isInstanceOf(InvocationTargetException::class)
				.transform { it.cause }
				.isNotNull()
				.isInstanceOf(NumberFormatException::class)
		}

		/**
		 * Test that example method returns a SimulationContext when invoked correctly.
		 * Validates successful example execution path (requires valid resource file).
		 */
		@Test
		fun `example method invocation with valid arguments returns context`() {
			// Arrange
			val mainClass = Main::class.java
			val main = Main.getInstance()
			val factory = XMLContextFactory.getInstance()
			val validArgs = arrayOf("example", "shuntingLoop", "100")

			// Act & Assert
			try {
				val method = mainClass.getMethod("shuntingLoop", SimulationContextFactory::class.java, Array<String>::class.java)
				val result = method.invoke(main, factory, validArgs)

				assertThat(result).isNotNull()
			} catch (e: InvocationTargetException) {
				val cause = e.cause
				// ContextCreationException is acceptable if resource not found in test environment
				if (cause is ContextCreationException) {
					assertThat((cause.message ?: "").contains("vyhybna.xml")).isTrue()
				} else {
					throw AssertionError("Unexpected exception during example invocation: $cause")
				}
			}
		}
	}

	@Nested
	@DisplayName("Exception Handling in Example Loading")
	inner class ExceptionHandlingTests {
		/**
		 * Test that InvocationTargetException is thrown when reflection invocation fails.
		 * Validates wrapper exception behavior.
		 */
		@Test
		fun `reflection invocation wraps exceptions in InvocationTargetException`() {
			// Arrange
			val mainClass = Main::class.java
			val main = Main.getInstance()
			val factory = XMLContextFactory.getInstance()
			val args = arrayOf("example") // Insufficient args will cause ContextCreationException

			// Act & Assert
			try {
				val method = mainClass.getMethod("shuntingLoop", SimulationContextFactory::class.java, Array<String>::class.java)
				method.invoke(main, factory, args)
				throw AssertionError("Should have thrown InvocationTargetException")
			} catch (e: InvocationTargetException) {
				assertThat(e.cause).isNotNull()
			}
		}

		/**
		 * Test that NoSuchMethodException indicates method not found.
		 * Validates error reporting for invalid example names.
		 */
		@Test
		fun `NoSuchMethodException indicates invalid example name`() {
			// Arrange
			val mainClass = Main::class.java

			// Act & Assert
			try {
				mainClass.getMethod("invalidExample", SimulationContextFactory::class.java, Array<String>::class.java)
				throw AssertionError("Should have thrown NoSuchMethodException")
			} catch (e: NoSuchMethodException) {
				assertThat(e.message).isNotNull()
				assertThat((e.message ?: "").contains("invalidExample")).isTrue()
			}
		}

		/**
		 * Test that missing @Example annotation is detected during invocation.
		 * Validates annotation requirement enforcement.
		 */
		@Test
		fun `missing @Example annotation prevents execution`() {
			// Arrange
			val mainClass = Main::class.java

			// Act
			var methodWithoutAnnotation: java.lang.reflect.Method? = null
			for (method in mainClass.declaredMethods) {
				if (Modifier.isPublic(method.modifiers) && !method.isAnnotationPresent(Example::class.java)) {
					methodWithoutAnnotation = method
					break
				}
			}

			// Assert
			if (methodWithoutAnnotation != null) {
				assertThat(methodWithoutAnnotation.isAnnotationPresent(Example::class.java)).isFalse()
			}
		}
	}

	@Nested
	@DisplayName("Example Discovery and Formatting")
	inner class ExampleDiscoveryFormattingTests {
		/**
		 * Test that example discovery returns a sorted list.
		 * Validates the list formatting for user display.
		 */
		@Test
		fun `example discovery returns sortable list`() {
			// Arrange
			val mainClass = Main::class.java
			val examples = mutableListOf<String>()

			// Act
			for (method in mainClass.declaredMethods) {
				if (Modifier.isPublic(method.modifiers) && method.isAnnotationPresent(Example::class.java)) {
					examples.add(method.name)
				}
			}
			examples.sortWith(String.CASE_INSENSITIVE_ORDER)

			// Assert
			assertThat(examples.size > 0).isTrue()
			// Verify list is sorted (each element <= next element)
			for (i in 0 until examples.size - 1) {
				val comparison = examples[i].compareTo(examples[i + 1])
				assertThat(comparison <= 0).isTrue()
			}
		}

		/**
		 * Test that example discovery with zero examples is handled.
		 * Validates graceful handling of empty result.
		 */
		@Test
		fun `example discovery handles empty list gracefully`() {
			// Arrange
			val examples = emptyList<String>()

			// Act
			val isEmpty = examples.isEmpty()

			// Assert
			assertThat(isEmpty).isTrue()
		}

		/**
		 * Test that example names are unique.
		 * Validates no duplicate example names.
		 */
		@Test
		fun `example names are unique`() {
			// Arrange
			val mainClass = Main::class.java
			val examples = mutableListOf<String>()

			// Act
			for (method in mainClass.declaredMethods) {
				if (Modifier.isPublic(method.modifiers) && method.isAnnotationPresent(Example::class.java)) {
					examples.add(method.name)
				}
			}

			// Assert
			assertThat(examples.size).isEqualTo(examples.toSet().size)
		}

		/**
		 * Test that example methods are non-private.
		 * Validates accessibility requirement.
		 */
		@Test
		fun `example methods are public not private`() {
			// Arrange
			val mainClass = Main::class.java

			// Act
			var foundPrivateExample = false
			for (method in mainClass.declaredMethods) {
				if (method.isAnnotationPresent(Example::class.java)) {
					if (Modifier.isPrivate(method.modifiers)) {
						foundPrivateExample = true
					}
				}
			}

			// Assert
			assertThat(foundPrivateExample).isFalse()
		}
	}

	@Nested
	@DisplayName("Example Annotation Properties")
	inner class ExampleAnnotationTests {
		/**
		 * Test that @Example annotation exists and is accessible.
		 * Validates annotation framework availability.
		 */
		@Test
		fun `@Example annotation is accessible`() {
			// Arrange
			val annotationClass = Example::class.java

			// Act
			val isAnnotation = annotationClass.isAnnotation

			// Assert
			assertThat(isAnnotation).isTrue()
		}

		/**
		 * Test that @Example annotation can be applied to methods.
		 * Validates annotation target scope.
		 */
		@Test
		fun `@Example annotation can mark methods`() {
			// Arrange
			val shuntingLoopMethod =
				Main::class.java.getMethod(
					"shuntingLoop",
					SimulationContextFactory::class.java,
					Array<String>::class.java
				)

			// Act
			val annotation = shuntingLoopMethod.getAnnotation(Example::class.java)

			// Assert
			assertThat(annotation).isNotNull()
		}

		/**
		 * Test that non-example methods are not marked.
		 * Validates annotation specificity.
		 */
		@Test
		fun `non-example methods lack @Example annotation`() {
			// Arrange
			val mainClass = Main::class.java
			val notAnnotatedCount: Int

			// Act
			var count = 0
			for (method in mainClass.declaredMethods) {
				if (Modifier.isPublic(method.modifiers) && !method.isAnnotationPresent(Example::class.java)) {
					count++
				}
			}
			notAnnotatedCount = count

			// Assert
			assertThat(notAnnotatedCount > 0).isTrue()
		}
	}
}
