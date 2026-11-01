/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.sweep.SweepCell
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * [SweepCell.arm] decides the arm of an aborted sweep run before the run exists, from the example
 * name alone; `DispatcherRunSummaries` decides the arm of a completed run from what the run wired.
 * They must agree on whether a dispatcher is wired, or an aborted dispatcher-free run lands in the
 * rule-based arm and counts towards its gate (Issue #676 fix round 2). Each registered console
 * example is built as `Main` builds it (not run), one per test so each gets a fresh Koin, as each
 * sweep child gets its own JVM.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
@DisplayName("SweepCell.arm agrees with the wiring of every registered example")
class SweepCellArmAgreementTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	@ParameterizedTest(name = "{0}")
	@ValueSource(
		strings = [
			ExampleNames.SHUNTING_LOOP,
			ExampleNames.SHUNTING_LOOP_AI,
			ExampleNames.SHUNTING_LOOP_SYNC,
			ExampleNames.CERVENY_UJEZD,
			ExampleNames.CERVENY_UJEZD_AI,
			ExampleNames.CERVENY_UJEZD_SYNC,
			ExampleNames.MULTI_TRAIN_LOOP,
			ExampleNames.THREE_TRAIN_LOOP
		]
	)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("an example maps to an arm with a dispatcher exactly when its wiring has one")
	fun exampleAgrees(name: String) {
		val factory = checkNotNull(get<ExampleRegistry>().examples[name])
		val cell = SweepCell(name, model = null, temperature = null, tickPeriodMs = 0L, historyN = 0, maxActionsPerTick = 3)
		val context = factory(get<SimulationContextFactory>(), arrayOf("example", name, "60")) as DefaultSimulationContext

		context.use {
			assertThat(cell.arm.hasDispatcher, name).isEqualTo(DispatcherRunSummaries.wiresDispatcher(it.scope))
		}
	}

	@Test
	@DisplayName("every registered console example is checked above")
	fun everyExampleIsListed() {
		assertThat(get<ExampleRegistry>().examples.keys).isEqualTo(CHECKED)
	}

	private companion object {
		val CHECKED =
			setOf(
				ExampleNames.SHUNTING_LOOP,
				ExampleNames.SHUNTING_LOOP_AI,
				ExampleNames.SHUNTING_LOOP_SYNC,
				ExampleNames.CERVENY_UJEZD,
				ExampleNames.CERVENY_UJEZD_AI,
				ExampleNames.CERVENY_UJEZD_SYNC,
				ExampleNames.MULTI_TRAIN_LOOP,
				ExampleNames.THREE_TRAIN_LOOP
			)
	}
}
