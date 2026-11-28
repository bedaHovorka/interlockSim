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
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.ContentionQuery
import cz.vutbr.fit.interlockSim.ports.ContentionReading
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.PlanningSnapshotLiveComparator
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Issue #1470 (Goal 9B SP4.4, #1467 spec §7): each [ContentionReading] in the published
 * [SimulationSnapshot] equals the live [ContentionQuery] state at the same tick, on the rule-based
 * control arm, where trains really wait for each other.
 *
 * The example is built from [ExampleRegistry] with all its wiring; then the control-step listener is
 * wrapped with [PlanningSnapshotLiveComparator.wrap] (installed last, so under spec rule P3 it is the
 * first code after publication). At every control step, before the dispatcher's own listener drains
 * any command, the comparator compares [SimulationSnapshot.contentions] of the published observation,
 * entry by entry and in order, with [ContentionQuery.openContentions] read live on the simulation
 * thread: waiting train, section id (the grid id of the live block from the grid locations of its
 * ends, an oracle independent of the port's graph scan), owning train and first-observed time. The
 * comparator checks the switch facet (#1468) on the same ticks.
 *
 * Non-vacuous on Červený Újezd: the run must reach the two-train wait state the issue names, a tick
 * on which a train waits for a section another train holds (its control arm has one failed
 * reservation per run, `ControlArmGoldenOutcomeTest`). The Praha control arm never refuses a
 * reservation (0 failed reservations on every golden seed), so there the test pins only that the
 * facet equals the live query, empty included, at every tick. Mismatches are collected and asserted after the run: a
 * throw inside the listener would end the run (`MainProcessFailureRunEndTest`).
 *
 * @since Issue #1470 (Goal 9B SP4.4)
 */
@DisplayName("Snapshot contentions equal the live ContentionQuery at every control step")
class ContentionReadingLiveEqualityTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun runAndCompare(
		exampleName: String,
		seed: String?,
		requireTwoTrainWait: Boolean
	) {
		val ctx = buildContext(exampleName, seed)
		val loop = ctx.mainProcess as ShuntingLoop
		val dispatcherStep = checkNotNull(loop.controlStepListener) { "$exampleName wires no control-step listener" }
		val comparator = PlanningSnapshotLiveComparator(ctx, loop)
		loop.controlStepListener = comparator.wrap(dispatcherStep)

		ctx.run()

		assertThat(comparator.mismatches, name = "snapshot vs live mismatches").isEmpty()
		assertThat(comparator.comparedTicks, name = "compared ticks").isGreaterThan(0)
		if (requireTwoTrainWait) {
			assertThat(
				comparator.contentionsWaitingForAnotherTrain,
				name = "ticks x contentions where a train waits for another"
			).isGreaterThan(0)
			assertThat(comparator.comparedContentionReadings, name = "compared contention readings").isGreaterThan(0)
		}
	}

	/** Builds [exampleName] from the CLI map [ExampleRegistry.examples], as `example <name> 600 [seed]` does. */
	private fun buildContext(
		exampleName: String,
		seed: String?
	): DefaultSimulationContext {
		val factory = checkNotNull(get<ExampleRegistry>().examples[exampleName]) { "$exampleName: not registered" }
		val args = listOfNotNull("example", exampleName, END_TIME, seed).toTypedArray()
		return (factory(get(), args) as DefaultSimulationContext).tracked()
	}

	@Test
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	fun `cervenyUjezd snapshot contentions equal the live contention query at every tick`() {
		runAndCompare(ExampleNames.CERVENY_UJEZD, seed = null, requireTwoTrainWait = true)
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 300, unit = TimeUnit.SECONDS)
	fun `prahaHlavniNadrazi snapshot contentions equal the live contention query at every tick`() {
		runAndCompare(ExampleNames.PRAHA_HLAVNI_NADRAZI, seed = PRAHA_SEED, requireTwoTrainWait = false)
	}

	private companion object {
		const val END_TIME = "600"

		/** The SP10 reference seed of the Praha control arm (`ControlArmGoldenOutcomeTest`). */
		const val PRAHA_SEED = "1366"
	}
}
