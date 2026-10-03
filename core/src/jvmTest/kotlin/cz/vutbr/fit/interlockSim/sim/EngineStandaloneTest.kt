/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1059 — Engine runs standalone against a fake Host, with no Train involved.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import cz.ksimulantenbande.kdisco.Variable
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.domain.MAXIMAL_TRAIN_ACCELERATION
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Runs a bare [Engine] against a minimal fake [Engine.Host], with no [Train] in the loop at all.
 *
 * This is the extraction's stated payoff (Issue #1059): kinematics are testable without living
 * inside the multi-thousand-line [Train] process. The fake host wires only the two [Variable]s
 * ([Engine.Host.velocityVariable], [Engine.Host.accelerationVariable]) and a fixed distance —
 * enough for [Engine.derivatives] to run the braking-law formula it always has.
 */
@Tag("integration-test")
@DisplayName("Engine runs standalone against a fake Host (Issue #1059)")
class EngineStandaloneTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, warmUpDynamicWrappers = true)

	private companion object {
		/** Fixed distance-to-semaphore the fake host reports; far enough that `s > 0` throughout. */
		const val DISTANCE_M = 200.0

		/** Commanded target speed. */
		const val TARGET_SPEED_MPS = 20.0

		/** How long the driver lets the engine run before stopping the simulation. */
		const val RUN_SECONDS = 5.0
	}

	/**
	 * Minimal [Engine.Host]: fixed distance, no semaphore to stop short of, no next semaphore.
	 * Everything [Engine] needs to drive the two [Variable]s and nothing a [Train] would add.
	 */
	private class FakeEngineHost(
		override val trainNumber: Int = 1
	) : Engine.Host {
		val velocity: Variable = Variable(0.0)
		val acceleration: Variable = Variable(0.0)

		override val velocityVariable: Variable get() = velocity
		override val accelerationVariable: Variable get() = acceleration

		override fun getVelocity(): Double = velocity.state

		override fun distanceToSemaphore(): Double = DISTANCE_M

		override fun semaphoreToStopShortOf(): DynamicRailSemaphore? = null

		override fun nextSemaphore(): OrientedPathSeparator? = null

		override val currentSpeedLimitMps: Double get() = TARGET_SPEED_MPS
		override val signalAheadAspect: Signal? get() = null
		override val semaphoreStopClearanceMeters: Double get() = 0.0

		override fun reportDebug(message: String) {}
	}

	/**
	 * Wires the fake host's two [Variable]s into the active-variable/active-continuous lists
	 * (mirroring [Train.start]), issues the one command, tracks the peak acceleration seen, and
	 * stops the simulation after [RUN_SECONDS].
	 */
	private class EngineDriverProcess(
		env: SimulationEnvironment,
		private val host: FakeEngineHost,
		private val engine: Engine
	) : Interlocking(env) {
		private val velocityIntegration = SimpleIntegration(host.velocity, host.acceleration)

		/** Highest acceleration [host] reported, sampled once per [iteration]. */
		var peakAcceleration: Double = 0.0
			private set

		override suspend fun startAction() {
			host.acceleration.start()
			host.velocity.start()
			velocityIntegration.start()
			engine.accelerateTo(TARGET_SPEED_MPS)
		}

		override suspend fun iteration() {
			peakAcceleration = maxOf(peakAcceleration, host.acceleration.state)
			if (time() >= RUN_SECONDS) env.stop()
		}

		override suspend fun interLoopSleep() {
			hold(0.1)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("velocity integrates toward the commanded target without a Train")
	fun velocityIntegratesTowardTargetWithoutTrain() {
		val host = FakeEngineHost()
		val engine = Engine(host)

		loadContext().use { ctx ->
			ctx.setMainProcess(EngineDriverProcess(ctx, host, engine))
			ctx.run()
		}

		assertThat(host.velocity.state, name = "velocity after $RUN_SECONDS s").isGreaterThan(0.0)
		assertThat(host.velocity.state, name = "velocity stayed at or below target")
			.isLessThanOrEqualTo(TARGET_SPEED_MPS)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("acceleration respects MAXIMAL_TRAIN_ACCELERATION without a Train")
	fun accelerationRespectsBoundWithoutTrain() {
		val host = FakeEngineHost()
		val engine = Engine(host)
		val driver =
			loadContext().use { ctx ->
				EngineDriverProcess(ctx, host, engine).also {
					ctx.setMainProcess(it)
					ctx.run()
				}
			}

		assertThat(driver.peakAcceleration, name = "peak acceleration")
			.isLessThanOrEqualTo(MAXIMAL_TRAIN_ACCELERATION.toDouble())
	}
}
