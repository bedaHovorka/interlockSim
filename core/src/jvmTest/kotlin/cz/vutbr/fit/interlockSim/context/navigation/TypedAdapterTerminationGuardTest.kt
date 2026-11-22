/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.onRunEnded
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * kDisco's process control at the typed adapters of the switch-claim release and the run end (Issue
 * #1361): both wrap their listener in a WARN guard, which until the fix of #1361 also swallowed a
 * cancellation and kDisco's `ProcessTerminatedException` -- a subscriber terminating the process was
 * WARN-continued instead of terminated. The rethrow is pinned the way
 * [ReservationExpiredEmitGuardTest] pins its adapter: every subscriber terminating the process really
 * terminates it -- the process never runs on past the point where the terminating subscriber was
 * delivered, and kDisco reports it terminated. The evidence is the process, never the WARN log.
 *
 * For the switch-claim release the emitting scenario is the real teardown that
 * [BareBusEmitGuardTest] also runs on a bare bus. The run end is different: production never sends
 * `RunEnded` on the kDisco bus -- the run-end delivery of `DefaultSimulationContext` happens on the
 * caller thread after the kernel stopped, where there is no live process -- so its pin hands the
 * adapter its payload itself, on the simulation thread of a live process, which is the delivery the
 * adapter's guard exists for.
 *
 * @since Issue #1361
 */
@DisplayName("Process control at the typed adapters (Issue #1361)")
@Timeout(30, unit = TimeUnit.SECONDS)
class TypedAdapterTerminationGuardTest : EmitSiteScenarios() {
	/** The tearing-down process; [reachedAfterTeardown] tells whether it ran on past the teardown. */
	private inner class TeardownProcess : Process() {
		var reachedAfterTeardown = false

		override suspend fun actions() {
			// The route of the reserve crosses switches, so the teardown releases switch claims and
			// announces them; the scenario's own assertion fails the test if it does not.
			unregisterScenario()
			reachedAfterTeardown = true
		}
	}

	/**
	 * Runs one [TeardownProcess] on a bare kDisco `Simulation`, with [listenerFor] the bus subscriber
	 * under test. Unlike [atSimTimes], nothing here catches what leaves the process, so a termination
	 * really ends it.
	 */
	private fun runTeardown(listenerFor: (TeardownProcess) -> (SimulationEvent) -> Unit): TeardownProcess {
		val teardown = TeardownProcess()
		runBlocking {
			val simulation = Simulation.create { Process.activate(teardown) }
			simulation.onEvent(listenerFor(teardown))
			simulation.run(10.0)
		}
		return teardown
	}

	@Test
	fun `an onSwitchClaimsReleased subscriber terminating the process still terminates it`() {
		// Subscribe through the real extension; the environment hands its wrapper to the kDisco bus.
		val env = mockk<SimulationEnvironment>()
		val wrappers = mutableListOf<(SimulationEvent) -> Unit>()
		every { env.onSimulationEvent(capture(wrappers)) } just Runs
		lateinit var target: Process
		env.onSwitchClaimsReleased { target.terminate() }

		val teardown =
			runTeardown { process ->
				target = process
				wrappers.single()
			}

		assertThat(teardown.reachedAfterTeardown, "process ran on past its own termination").isFalse()
		assertThat(teardown.terminated(), "process terminated").isTrue()
	}

	/** The process of the run-end pin; [reachedAfterDelivery] tells whether it ran on past the delivery. */
	private inner class RunEndProcess : Process() {
		/** The adapter under test: the wrapper the real registration handed to the environment. */
		lateinit var wrapper: (SimulationEvent) -> Unit

		var reachedAfterDelivery = false

		override suspend fun actions() {
			wrapper(SimulationEvent.Custom(0.0, RunEnded(0.0, true, 0.0)))
			reachedAfterDelivery = true
		}
	}

	@Test
	fun `an onRunEnded subscriber terminating the process still terminates it`() {
		// Subscribe through the real extension; the environment hands its wrapper to the kDisco bus.
		val env = mockk<SimulationEnvironment>()
		val wrappers = mutableListOf<(SimulationEvent) -> Unit>()
		every { env.onSimulationEvent(capture(wrappers)) } just Runs
		lateinit var target: Process
		env.onRunEnded { target.terminate() }

		val deliverer = RunEndProcess()
		runBlocking {
			val simulation = Simulation.create { Process.activate(deliverer) }
			target = deliverer
			// Production never sends RunEnded on the bus (see the class KDoc), so the process hands the
			// wrapper its payload itself, as the run-end delivery would.
			deliverer.wrapper = wrappers.single()
			simulation.run(10.0)
		}

		assertThat(deliverer.reachedAfterDelivery, "process ran on past its own termination").isFalse()
		assertThat(deliverer.terminated(), "process terminated").isTrue()
	}
}
