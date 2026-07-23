/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * SP4.3 integration test: single reactive train end-to-end acceleration (Issue #565).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * SP4.3 end-to-end integration test: a single train's reactive acceleration is driven by
 * [AlgorithmicTrainDecisionPolicy] wired through [wireSynchronousDispatcher] (Issue #565).
 *
 * ## What this tests
 *
 * - [wireSynchronousDispatcher] with a spy [TrainDecisionPolicy] successfully processes a full
 *   shunting-loop run: at least one train reaches the exit.
 * - The spy receives at least one [AccelerationTarget.ACCELERATE] decision (train moves).
 * - The spy receives at least one [AccelerationTarget.BRAKE] or [AccelerationTarget.COAST]
 *   decision (train slows near a STOP signal or destination).
 * - A custom [TrainDecisionPolicy] implementation can replace [AlgorithmicTrainDecisionPolicy]
 *   entirely via the [trainDecisionPolicy] parameter; an always-brake replacement stops every
 *   train before it reaches the exit (asserts `getTrainsExited() == 0`), pinning the
 *   `setTargetSpeed` actuator effect — not just that `decide` was invoked.
 *
 * ## Design choice
 *
 * Runs at the `:core` JVM level so it exercises [wireSynchronousDispatcher] (not
 * `ExampleRegistry`).  The spy is injected via the [trainDecisionPolicy] parameter added
 * in SP4.3; real [AlgorithmicTrainDecisionPolicy] logic is always called, so the actuator
 * still sets real target speeds (no silent no-op).
 *
 * End time 120 s is short enough for CI but long enough for a train to complete its trip on
 * `vyhybna.xml` in deterministic synchronous mode.
 *
 * @since Issue #565 (SP4.3 — Goal 10 single reactive train end-to-end)
 */
@DisplayName("SP4.3 single reactive train end-to-end (#565)")
@Tag("integration-test")
class SingleReactiveTrainEndToEndTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	// ── Helpers ─────────────────────────────────────────────────────────────

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	/**
	 * Records every [TrainAccelerationDecision] emitted by [AlgorithmicTrainDecisionPolicy].
	 * [wireSynchronousDispatcher] always calls it on the kDisco sim thread, so a plain
	 * [mutableListOf] is correct and sufficient — no concurrent collection needed.
	 */
	private inner class SpyTrainDecisionPolicy : TrainDecisionPolicy {
		val decisions: MutableList<TrainAccelerationDecision> = mutableListOf()
		private val delegate = AlgorithmicTrainDecisionPolicy()

		override fun decide(reading: TrainPerceptionReading): TrainAccelerationDecision =
			delegate.decide(reading).also { decisions.add(it) }
	}

	// ── Tests ────────────────────────────────────────────────────────────────

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `reactive train policy is applied and drives a train to completion`() {
		val context = loadVyhybnaContext()
		val loop = ShuntingLoop(context, endTime = 120L)
		val spy = SpyTrainDecisionPolicy()

		wireSynchronousDispatcher(context, loop, trainDecisionPolicy = spy)
		context.setMainProcess(loop)
		context.run()

		// At least one train must have exited
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(1)

		// The spy must have been called (policy is active)
		assertThat(spy.decisions.isNotEmpty()).isTrue()

		// There must have been at least one ACCELERATE decision (train moved)
		val hadAccelerate = spy.decisions.any { it.target == AccelerationTarget.ACCELERATE }
		assertThat(hadAccelerate).isTrue()

		// There must have been at least one non-ACCELERATE decision (train slowed / stopped)
		val hadBrakeOrCoast =
			spy.decisions.any { it.target == AccelerationTarget.BRAKE || it.target == AccelerationTarget.COAST }
		assertThat(hadBrakeOrCoast).isTrue()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `custom policy replaces algorithmic policy and is called for each approved train`() {
		val context = loadVyhybnaContext()
		val loop = ShuntingLoop(context, endTime = 120L)
		val callCounts = mutableListOf<String>()

		// Custom policy: always brake (stops trains), records trainId each call
		val customPolicy =
			object : TrainDecisionPolicy {
				override fun decide(reading: TrainPerceptionReading): TrainAccelerationDecision {
					callCounts.add(reading.trainId)
					return TrainAccelerationDecision(AccelerationTarget.BRAKE, 0.0, "test-policy")
				}
			}

		wireSynchronousDispatcher(context, loop, trainDecisionPolicy = customPolicy)
		context.setMainProcess(loop)
		context.run()

		// The custom policy must have been invoked (even if no trains completed
		// because we brake them).
		assertThat(callCounts.isNotEmpty()).isTrue()

		// The always-brake policy must actually take effect via the actuator: every train is
		// braked to a stand before it can reach the exit, so none exits.  This pins the
		// setTargetSpeed wiring — a bug that called decide() but dropped the actuator call
		// would still pass the callCounts assertion above but fail this one.
		assertThat(loop.getTrainsExited()).isEqualTo(0)
	}
}
