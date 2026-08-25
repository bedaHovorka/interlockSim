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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Wiring regression test for the supervised dispatcher driver loop
 * (Issue #847 round 4, finding R4-2).
 *
 * Round 3's run 1 logged its last planner summary at `simTime=216.0s, totalCycles=10` and then went
 * quiet, while the run carried on to 600.9 s and exited 0. The mechanism: the driver action was a
 * bare `while (loop.isSimActive()) { driver.runCycle() }` on a daemon thread with no
 * uncaught-exception handler, so one escaped exception ended the dispatcher without ending — or even
 * marking — the run.
 *
 * Two things have to hold for that to be impossible to miss again, and both are wiring properties
 * that a unit test of [AgentDriverLoop] alone cannot establish:
 *
 * 1. the production driver action is the supervised loop, not a bare `while`;
 * 2. the loop and the snapshot signal are reachable from the context scope, so the end-of-run
 *    summary can report cycles, failures and coalesced ticks.
 *
 * The "built, fully tested, never wired" failure mode is the one this code path keeps producing —
 * `ActionValidator`, `DispatchTickLoop` and `PausedClockTickBudget` are all fully tested and never
 * constructed in production, and round 1's entire defect was a `ThrottlingSimulationController`
 * built but never reached. A unit test proving [AgentDriverLoop] behaves correctly proves nothing
 * about whether the run uses it.
 *
 * Builds the example reflectively, as [ExampleRegistryOrphanSweeperWiringTest] does. No Ollama is
 * required: `KoogAgentPlanAdapter` construction performs no network I/O and this test never runs the
 * simulation.
 *
 * @since Issue #847 (round 4)
 */
@DisplayName("ExampleRegistry wires a supervised driver loop that cannot die silently")
class ExampleRegistryDriverLoopWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun createShuntingLoopAIContext(): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		return createExampleContext(
			registry,
			get<SimulationContextFactory>(),
			"createShuntingLoopAIExample",
			"shuntingLoopAI",
			"60"
		)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the supervised driver loop is declared in the context scope")
	fun driverLoopIsReachableFromTheScope() {
		val context = createShuntingLoopAIContext()

		assertThat(context.scope.getOrNull<AgentDriverLoop>()).isNotNull()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the snapshot signal is declared in scope so coalesced ticks can be reported")
	fun snapshotSignalIsReachableFromTheScope() {
		val context = createShuntingLoopAIContext()

		assertThat(context.scope.getOrNull<DefaultSnapshotSignal>()).isNotNull()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the per-tick control step signals the driver, and the signal counts the tick")
	fun controlStepSignalsTheDriver() {
		val context = createShuntingLoopAIContext()
		val loop = context.getMainProcess() as ShuntingLoop
		val signal = checkNotNull(context.scope.getOrNull<DefaultSnapshotSignal>())
		val before = signal.signalCount

		checkNotNull(loop.controlStepListener) { "no ControlStepListener was wired" }.onControlStep()

		assertThat(signal.signalCount).isGreaterThanOrEqualTo(before + 1)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a freshly built example has run no cycles and recorded no failures")
	fun freshLoopHasCleanCounters() {
		val context = createShuntingLoopAIContext()
		val driverLoop = checkNotNull(context.scope.getOrNull<AgentDriverLoop>())

		assertThat(driverLoop.cycleCount, "cycleCount").isEqualTo(0L)
		assertThat(driverLoop.failureCount, "failureCount").isEqualTo(0)
		assertThat(driverLoop.stoppedByFailures, "stoppedByFailures").isFalse()
	}
}
