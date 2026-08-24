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
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExampleRegistry.usesLlmDispatcher] (Issue #839).
 *
 * The GUI launch path asks the registry which examples are driven by the LLM dispatcher, so
 * that it can show a different window title. The registry owns that fact because it is the
 * registry that wires the LLM planner; these tests pin the answer.
 *
 * @since Issue #839 (SP2c.16 follow-up — window title)
 */
@DisplayName("ExampleRegistry.usesLlmDispatcher (#839)")
class ExampleRegistryLlmDispatcherFlagTest {
	private val registry = ExampleRegistry()

	@Test
	@DisplayName("shuntingLoopAI is driven by the LLM dispatcher")
	fun shuntingLoopAiIsLlmDriven() {
		assertThat(registry.usesLlmDispatcher("shuntingLoopAI")).isTrue()
	}

	@Test
	@DisplayName("the plain shunting loop is not driven by the LLM dispatcher")
	fun plainShuntingLoopIsNotLlmDriven() {
		assertThat(registry.usesLlmDispatcher("shuntingLoop")).isFalse()
	}

	@Test
	@DisplayName("an unknown example name is not driven by the LLM dispatcher")
	fun unknownExampleIsNotLlmDriven() {
		assertThat(registry.usesLlmDispatcher("noSuchExample")).isFalse()
	}

	@Test
	@DisplayName("every LLM-flagged name is a registered GUI example")
	fun everyLlmFlaggedNameIsRegistered() {
		// Guards against a typo in the flag set silently disabling the title switch.
		registry.guiExamples.keys
			.filter { registry.usesLlmDispatcher(it) }
			.forEach { assertThat(registry.usesLlmDispatcher(it)).isTrue() }

		assertThat(registry.guiExamples.keys.any { registry.usesLlmDispatcher(it) }).isTrue()
	}
}
