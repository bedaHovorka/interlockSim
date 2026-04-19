/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for InOut validation during save operation
	Issue #80: GUI validation to prevent saving contexts with insufficient InOut elements
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for InOut validation during save operation.
 *
 * **Requirements (Issue #80):**
 * - Save operation must validate InOut count before saving
 * - Minimum [cz.vutbr.fit.interlockSim.xml.XMLContextFactory.MIN_INOUT_ELEMENTS] InOut required
 * - Save operation blocked until requirement is met
 *
 * **Test Coverage:**
 * - Save with 0 InOuts - should fail validation
 * - Save with 1 InOut - should pass (min is 1 per PR #356 bidirectional operation)
 * - Save with 2 InOuts - should pass
 * - Save with 3+ InOuts - should pass
 *
 * Tests call [MenuBar.validateForSave] directly — no dialogs, no EDT, no reflection.
 */
@DisplayName("InOut Save Validation")
class InOutSaveValidationTest : KoinTestBase() {

	@Test
	@DisplayName("context with 0 InOuts cannot be saved")
	fun contextWith0InOutsCannotBeSaved() {
		TestContextBuilder().buildEditingContext().use { context ->
			assertThat(MenuBar.validateForSave(context)).isFalse()
		}
	}

	@Test
	@DisplayName("context with 1 InOut can be saved")
	fun contextWith1InOutCanBeSaved() {
		TestContextBuilder()
			.withInOut("OnlyEntry", 1, 1, true)
			.buildEditingContext().use { context ->
				assertThat(MenuBar.validateForSave(context)).isTrue()
			}
	}

	@Test
	@DisplayName("context with 2 InOuts can be saved")
	fun contextWith2InOutsCanBeSaved() {
		TestContextBuilder()
			.withInOut("Entry", 1, 1, true)
			.withInOut("Exit", 10, 10, false)
			.buildEditingContext().use { context ->
				assertThat(MenuBar.validateForSave(context)).isTrue()
			}
	}

	@Test
	@DisplayName("context with 3 InOuts can be saved")
	fun contextWith3InOutsCanBeSaved() {
		TestContextBuilder()
			.withInOut("Entry1", 1, 1, true)
			.withInOut("Exit1", 10, 10, false)
			.withInOut("Entry2", 5, 5, true)
			.buildEditingContext().use { context ->
				assertThat(MenuBar.validateForSave(context)).isTrue()
			}
	}

	@Test
	@DisplayName("MIN_INOUT_ELEMENTS constant value is 1")
	fun minInOutElementsConstantIsOne() {
		assertThat(XMLContextFactory.MIN_INOUT_ELEMENTS).isEqualTo(1)
	}

	@Test
	@DisplayName("validateForSave is idempotent — repeated calls return same result")
	fun validateForSave_isIdempotent() {
		TestContextBuilder()
			.withInOut("OnlyEntry", 1, 1, true)
			.buildEditingContext().use { context ->
				val first = MenuBar.validateForSave(context)
				val second = MenuBar.validateForSave(context)
				val third = MenuBar.validateForSave(context)
				assertThat(first).isTrue()
				assertThat(second).isTrue()
				assertThat(third).isTrue()
			}
	}

	@Test
	@DisplayName("context with 10 InOuts can be saved")
	fun contextWith10InOutsCanBeSaved() {
		var builder = TestContextBuilder()
		for (i in 1..10) {
			builder = builder.withInOut("InOut$i", i, i, true)
		}
		builder.buildEditingContext().use { context ->
			assertThat(MenuBar.validateForSave(context)).isTrue()
		}
	}
}
