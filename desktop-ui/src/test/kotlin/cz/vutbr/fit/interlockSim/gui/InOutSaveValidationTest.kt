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
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
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
		val context = TestContextBuilder().buildEditingContext()
		assertThat(MenuBar.validateForSave(context)).isFalse()
	}

	@Test
	@DisplayName("context with 1 InOut can be saved")
	fun contextWith1InOutCanBeSaved() {
		val context = TestContextBuilder()
			.withInOut("OnlyEntry", 1, 1, true)
			.buildEditingContext()
		assertThat(MenuBar.validateForSave(context)).isTrue()
	}

	@Test
	@DisplayName("context with 2 InOuts can be saved")
	fun contextWith2InOutsCanBeSaved() {
		val context = TestContextBuilder()
			.withInOut("Entry", 1, 1, true)
			.withInOut("Exit", 10, 10, false)
			.buildEditingContext()
		assertThat(MenuBar.validateForSave(context)).isTrue()
	}

	@Test
	@DisplayName("context with 3 InOuts can be saved")
	fun contextWith3InOutsCanBeSaved() {
		val context = TestContextBuilder()
			.withInOut("Entry1", 1, 1, true)
			.withInOut("Exit1", 10, 10, false)
			.withInOut("Entry2", 5, 5, true)
			.buildEditingContext()
		assertThat(MenuBar.validateForSave(context)).isTrue()
	}
}
