/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.single
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.exceptions.Severity
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import kotlin.test.Test

/**
 * Pins the fault-injection fixture (Issue #1451): it throws at the configured simulated time, the
 * escape is visible through [UncaughtSimulationExceptions], and the other processes keep running.
 * Pinning an activation after the target time (rejected with an explanatory error) and the
 * uncached pre-throw preview of the default fault belong here too, because neither can escape on
 * Kotlin/Native without ending the test process.
 *
 * Deliberately not pinned: whether `run()` returns normally. kotlinx-coroutines-test fails any
 * runTest on uncaught coroutine exceptions, and the run outcome is the job of Issue #1112.
 */
class FaultInjectionProcessFixtureTest : CommonKoinTestBase() {
	/** Another process: counts one tick per simulated time unit, so it shows that the run goes on. */
	private class Witness : Process() {
		val tickTimes = mutableListOf<Double>()

		override suspend fun actions() {
			while (true) {
				hold(1.0)
				tickTimes += time()
			}
		}
	}

	/** Main process: starts [fixture] with a delay of [activationDelay], stops the run at [END_TIME]. */
	private class Scenario(
		private val context: DefaultSimulationContext,
		private val fixture: FaultInjectionProcessFixture,
		val witness: Witness,
		private val activationDelay: Double = 0.0
	) : LoopProcess() {
		override suspend fun startAction() {
			activate(fixture, delay = activationDelay)
			activate(witness)
		}

		override suspend fun iteration() {
			// Nothing to do: the work is in the two processes.
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
			if (time() >= END_TIME) context.stop()
		}
	}

	private fun run(
		fixture: FaultInjectionProcessFixture,
		activationDelay: Double = 0.0
	): Pair<Witness, List<Throwable>> {
		val context = buildMinimalSimulation().tracked()
		val witness = Witness()
		context.setMainProcess(Scenario(context, fixture, witness, activationDelay))
		val recorded = UncaughtSimulationExceptions.record { context.run() }
		return witness to recorded.uncaught
	}

	@Test
	fun `the fixture throws at the configured time and the other processes keep running`() {
		val fixture = FaultInjectionProcessFixture(failAt = FAIL_AT)

		val (witness, uncaught) = run(fixture)

		assertThat(fixture.thrownAtTime).isEqualTo(FAIL_AT)
		assertThat(uncaught).hasSize(1)
		assertThat(uncaught.single()).isSameInstanceAs(fixture.throwable)
		// The witness ticked at every time unit, also after the fault, until the run stopped.
		assertThat(witness.tickTimes.last()).isGreaterThan(FAIL_AT)
	}

	@Test
	fun `the default throwable is a FATAL SimulationException that names the process`() {
		val fixture = FaultInjectionProcessFixture(failAt = FAIL_AT, name = "injected-fault")

		val (_, uncaught) = run(fixture)

		val thrown = assertThat(uncaught).single()
		thrown.isInstanceOf<SimulationException>()
		val exception = uncaught.single() as SimulationException
		assertThat(exception.severity).isEqualTo(Severity.FATAL)
		assertThat(exception.message.orEmpty()).contains("injected-fault")
		assertThat(exception.time).isEqualTo(FAIL_AT)
	}

	@Test
	fun `a configured throwable escapes as given`() {
		val custom = IllegalStateException("custom fault")
		val fixture = FaultInjectionProcessFixture(failAt = 2.5, throwable = custom)

		val (_, uncaught) = run(fixture)

		assertThat(fixture.thrownAtTime).isEqualTo(2.5)
		assertThat(uncaught).single().isSameInstanceAs(custom)
	}

	@Test
	fun `an activation after the target time is rejected with an IllegalStateException`() {
		val fixture = FaultInjectionProcessFixture(failAt = 4.0, name = "late-fault")

		// Activation at simulated time 5.0, past the failAt of 4.0: no fault, just the rejection.
		val (_, uncaught) = run(fixture, activationDelay = 5.0)

		assertThat(uncaught).hasSize(1)
		assertThat(uncaught.single()).isInstanceOf<IllegalStateException>()
		val rejection = uncaught.single() as IllegalStateException
		assertThat(rejection.message.orEmpty()).contains("late-fault")
		assertThat(rejection.message.orEmpty()).contains("5.0")
		assertThat(rejection.message.orEmpty()).contains("4.0")
		assertThat(fixture.thrownAtTime).isNull()
	}

	@Test
	fun `a pre-run read of the throwable does not poison the default fault's model time`() {
		val fixture = FaultInjectionProcessFixture(failAt = FAIL_AT)

		// The read happens outside the run; a cached instance would carry that wrong model time.
		val preview = fixture.throwable

		val (_, uncaught) = run(fixture)

		assertThat(fixture.thrownAtTime).isEqualTo(FAIL_AT)
		val exception = uncaught.single() as SimulationException
		assertThat(exception.time).isEqualTo(FAIL_AT)
		// The preview is a throwaway: the escaped fault is the instance built at the throw.
		assertThat(exception).isNotSameInstanceAs(preview)
	}

	private companion object {
		const val FAIL_AT = 4.0
		const val END_TIME = 10.0
	}
}
