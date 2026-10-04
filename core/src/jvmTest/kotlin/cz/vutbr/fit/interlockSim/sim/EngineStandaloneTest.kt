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
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.ksimulantenbande.kdisco.Variable
import cz.ksimulantenbande.kdisco.dtMax
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.domain.MAXIMAL_TRAIN_ACCELERATION
import cz.vutbr.fit.interlockSim.domain.MINIMAL_TRAIN_DECELERATION
import cz.vutbr.fit.interlockSim.domain.brakingDistanceFrom
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

		/**
		 * Integration step the wait-site tests pin (Issue #760). Large enough that one step of
		 * lateness — up to 4 m/s² × 0.1 s of speed — dwarfs the error of a root-found crossing.
		 */
		const val PINNED_DT_MAX = 0.1

		/** Run length of the wait-site tests: every leg they drive has ended or parked by then. */
		const val WAIT_SITE_RUN_SECONDS = 15.0

		/** Simulated time at which a wait-site test changes the aspect or cancels the engine. */
		const val ACTION_TIME = 1.0

		/**
		 * Distance to the signal for the braking-room test: short enough that the braking room
		 * (49 m to the stop line) runs out at 17.1 m/s, below the 20 m/s target.
		 */
		const val SHORT_DISTANCE_M = 50.0

		/**
		 * Distance to the signal for the brake-to-stop tests: phase 1 reaches half speed at
		 * about 2.5 s with braking room to spare, so the braking phase is running at [LATE_ACTION_TIME].
		 */
		const val BRAKING_DISTANCE_M = 30.0

		/** Simulated time at which a brake-to-stop or resumed-leg test acts: inside that leg. */
		const val LATE_ACTION_TIME = 4.0

		/** How far a root-found crossing may land from the exact threshold (m/s or m). */
		const val CROSSING_TOLERANCE = 1e-6

		/**
		 * The stand test's stop-line distance per (m/s)² of speed: `0.1` makes the law ask for
		 * 5 m/s², past the 3 m/s² bound at every speed (s²/m).
		 */
		const val STAND_DISTANCE_GAIN = 0.1

		/** Delay from the stand test's law switch to its sample: several 0.1 s steps (s). */
		const val STAND_SAMPLE_DELAY = 0.3
	}

	/**
	 * Minimal [Engine.Host]: fixed distance, and by default no signal ahead at all.
	 * Everything [Engine] needs to drive the two [Variable]s and nothing a [Train] would add.
	 *
	 * With [semaphore] set, the host reports it the way [Train] does: as the signal to stop short
	 * of while its aspect does not allow, as the next semaphore, and as the aspect ahead — so a
	 * test changes all three at once by setting the semaphore's signal.
	 *
	 * The distance never shrinks by default, so the brake-to-stop law `a = -v²/(2s)` is asymptotic
	 * here (`v` never reaches 0) and a brake-to-stop test leaves that wait through its discrete
	 * aspect term or through `terminate()`. [distanceAtSpeed] replaces the fixed distance with one
	 * that depends on the speed, which lets a test drive `v` to 0 in finite time.
	 *
	 * @param distanceMeters the fixed distance to the signal the braking law aims at
	 */
	private class FakeEngineHost(
		override val trainNumber: Int = 1,
		private val distanceMeters: Double = DISTANCE_M
	) : Engine.Host {
		val velocity: Variable = Variable(0.0)
		val acceleration: Variable = Variable(0.0)

		/** The signal ahead; `null` is open line. */
		var semaphore: DynamicRailSemaphore? = null

		/** When set, the distance to the signal as a function of the current velocity. */
		var distanceAtSpeed: ((Double) -> Double)? = null

		/**
		 * `false` hides [semaphore] from [nextSemaphore] only. A leg that ends on its own then goes
		 * idle instead of arming the late-aspect watch, so the velocity stays at the value the
		 * wait ended on — the value the crossing tests read.
		 */
		var nextSemaphoreVisible: Boolean = true

		override val velocityVariable: Variable get() = velocity
		override val accelerationVariable: Variable get() = acceleration

		override fun getVelocity(): Double = velocity.state

		override fun distanceToSemaphore(): Double = distanceAtSpeed?.invoke(velocity.state) ?: distanceMeters

		override fun semaphoreToStopShortOf(): DynamicRailSemaphore? = semaphore?.takeUnless { it.signal.isAllowing() }

		override fun nextSemaphore(): OrientedPathSeparator? = semaphore?.takeIf { nextSemaphoreVisible }

		override val currentSpeedLimitMps: Double get() = TARGET_SPEED_MPS
		override val signalAheadAspect: Signal? get() = semaphore?.signal
		override val semaphoreStopClearanceMeters: Double
			get() = if (semaphore == null) 0.0 else Train.SEMAPHORE_STOP_CLEARANCE_METERS

		override fun reportDebug(message: String) {}
	}

	/** One step of a wait-site test's script: [run] at simulated [time]. */
	private class TimedAction(
		val time: Double,
		val run: (Engine) -> Unit
	)

	/**
	 * Wires the fake host's two [Variable]s into the active-variable/active-continuous lists
	 * (mirroring [Train.start]), issues the one command, tracks the peak acceleration seen, and
	 * stops the simulation after [runSeconds].
	 *
	 * The wait-site tests (Issue #760) also pin `dtMax` to [pinnedDtMax] and run each of [actions]
	 * once, at its time; the state read at those moments and at the end of the run is kept for the
	 * assertions.
	 */
	private class EngineDriverProcess(
		env: SimulationEnvironment,
		private val host: FakeEngineHost,
		private val engine: Engine,
		private val runSeconds: Double = RUN_SECONDS,
		private val pinnedDtMax: Double? = null,
		private val command: (Engine) -> Unit = { it.accelerateTo(TARGET_SPEED_MPS) },
		private val actions: List<TimedAction> = emptyList()
	) : Interlocking(env) {
		private val velocityIntegration = SimpleIntegration(host.velocity, host.acceleration)
		private val pending = actions.sortedBy { it.time }.toMutableList()

		/** Highest acceleration [host] reported, sampled once per [iteration]. */
		var peakAcceleration: Double = 0.0
			private set

		/** Velocity at each of [actions] that ran, in time order. */
		val velocitiesAtActions: MutableList<Double> = mutableListOf()

		/** Simulated time at which each of [actions] ran, in time order. */
		val timesAtActions: MutableList<Double> = mutableListOf()

		/** Every simulated time at which the engine went idle (passivated), in time order. */
		val engineIdleTimes: MutableList<Double> = mutableListOf()

		/** Acceleration at the end of the run. */
		var finalAcceleration: Double = Double.NaN
			private set

		/** Whether the engine process had ended by the end of the run. */
		var engineTerminatedAtEnd: Boolean = false
			private set

		/** Whether the engine was idle (passivated) at the end of the run. */
		var enginePassivatedAtEnd: Boolean = false
			private set

		override suspend fun startAction() {
			pinnedDtMax?.let { dtMax = it }
			host.acceleration.start()
			host.velocity.start()
			velocityIntegration.start()
			command(engine)
		}

		override suspend fun iteration() {
			peakAcceleration = maxOf(peakAcceleration, host.acceleration.state)
			while (pending.isNotEmpty() && time() >= pending.first().time) {
				velocitiesAtActions += host.velocity.state
				timesAtActions += time()
				pending.removeAt(0).run(engine)
			}
			if (time() >= runSeconds) {
				finalAcceleration = host.acceleration.state
				engineTerminatedAtEnd = engine.terminated()
				enginePassivatedAtEnd = engine.isPassivated()
				env.stop()
			}
		}

		override suspend fun interLoopSleep() {
			hold(0.1)
		}
	}

	/**
	 * Runs [host]'s engine on the linear semaphore fixture with `dtMax` pinned to [PINNED_DT_MAX]:
	 * [command] at the start, then the script [actions] builds from the fixture's semaphore.
	 * [facing] puts that semaphore ahead of the fake train, showing that aspect.
	 */
	private fun runWaitSite(
		host: FakeEngineHost,
		command: (Engine) -> Unit,
		facing: Signal? = null,
		actions: (DynamicRailSemaphore) -> List<TimedAction> = { emptyList() }
	): EngineDriverProcess {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val ctx = network.context.tracked()
		val semaphore = network.semaphore
		if (facing != null) {
			semaphore.signal = facing
			host.semaphore = semaphore
		}
		val engine = Engine(host)
		val driver =
			EngineDriverProcess(
				ctx,
				host,
				engine,
				runSeconds = WAIT_SITE_RUN_SECONDS,
				pinnedDtMax = PINNED_DT_MAX,
				command = command,
				actions = actions(semaphore)
			)
		ctx.onSimulationEvent { event ->
			if (event is SimulationEvent.ProcessPassivated && event.process === engine) {
				driver.engineIdleTimes += event.time
			}
		}
		ctx.setMainProcess(driver)
		ctx.run()
		return driver
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
	// --- Issue #760: the three velocity-target waits end on root-found crossings -------------

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("accelerateTo arm resumes at the braking-room crossing, not one step late (Issue #760)")
	fun accelerateToArmResumesAtTheBrakingRoomCrossing() {
		val host = FakeEngineHost(distanceMeters = SHORT_DISTANCE_M).apply { nextSemaphoreVisible = false }

		runWaitSite(host, command = { it.accelerateTo(TARGET_SPEED_MPS) }, facing = Signal.STOP)

		// The leg went idle where its wait ended, so the frozen velocity is the exit speed. A
		// whole-step poll exits up to one 0.1 s step past the root: about 0.6 m of room overrun.
		val roomAtExit =
			SHORT_DISTANCE_M - Train.SEMAPHORE_STOP_CLEARANCE_METERS - brakingDistanceFrom(host.velocity.state)
		assertThat(abs(roomAtExit), name = "braking room left when the wait ended (m)")
			.isLessThanOrEqualTo(CROSSING_TOLERANCE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("resume arm resumes at the velocity crossing, not one step late (Issue #760)")
	fun resumeArmResumesAtTheVelocityCrossing() {
		val host = FakeEngineHost()

		runWaitSite(host, command = { it.onWarning(TARGET_SPEED_MPS) }, facing = Signal.STOP) { semaphore ->
			listOf(TimedAction(ACTION_TIME) { semaphore.signal = Signal.FREE })
		}

		// The resumed leg runs up at a constant rate and then coasts, so the velocity it coasts at
		// is the exit speed. A whole-step poll overshoots by up to 2 m/s² × 0.1 s.
		assertThat(abs(host.velocity.state - TARGET_SPEED_MPS), name = "overshoot of the resumed cap (m/s)")
			.isLessThanOrEqualTo(CROSSING_TOLERANCE)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("resume arm still ends when the aspect turns restrictive again (Issue #760)")
	fun resumeArmEndsWhenTheAspectTurnsRestrictiveAgain() {
		val host = FakeEngineHost()

		val driver =
			runWaitSite(host, command = { it.onWarning(TARGET_SPEED_MPS) }, facing = Signal.STOP) { semaphore ->
				listOf(
					TimedAction(ACTION_TIME) { semaphore.signal = Signal.FREE },
					TimedAction(LATE_ACTION_TIME) { semaphore.signal = Signal.STOP }
				)
			}

		// Still running up when the aspect returned to STOP; the return handed over to braking.
		val velocityAtReturn = driver.velocitiesAtActions.last()
		assertThat(velocityAtReturn, name = "velocity when the aspect returned to STOP").isLessThan(TARGET_SPEED_MPS)
		assertThat(host.velocity.state, name = "velocity at the end").isLessThan(velocityAtReturn)
		assertThat(driver.finalAcceleration, name = "acceleration at the end").isLessThan(0.0)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("brake-to-stop arm ends when the aspect clears before the stand (Issue #760)")
	fun brakeToStopArmEndsWhenTheAspectClears() {
		val host = FakeEngineHost(distanceMeters = BRAKING_DISTANCE_M)

		val driver =
			runWaitSite(host, command = { it.onWarning(TARGET_SPEED_MPS) }, facing = Signal.STOP) { semaphore ->
				listOf(TimedAction(LATE_ACTION_TIME) { semaphore.signal = Signal.FREE })
			}

		// Still braking toward the stand when the aspect cleared; the clear resumed the run.
		val velocityAtClear = driver.velocitiesAtActions.single()
		assertThat(velocityAtClear, name = "velocity when the aspect cleared").isGreaterThan(0.0)
		assertThat(host.velocity.state, name = "velocity at the end").isGreaterThan(velocityAtClear)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("brake-to-stop arm ends at the stand's velocity crossing, not one step late (Issue #760)")
	fun brakeToStopArmEndsAtTheStandCrossing() {
		val host = FakeEngineHost(distanceMeters = BRAKING_DISTANCE_M)

		// From the first action on, the stop line is `STAND_DISTANCE_GAIN × v²` away, so the law
		// asks for -5 m/s² at every speed: the bound clamps it to a constant
		// MINIMAL_TRAIN_DECELERATION, and `v` reaches 0 in finite time. The aspect stays STOP, so
		// the speed term is the only exit left. The second action only samples: the velocity
		// integration still runs one step on the old law after a switch (#1126), so the stand time
		// is exact in closed form only from a sample taken after that step.
		val driver =
			runWaitSite(host, command = { it.onWarning(TARGET_SPEED_MPS) }, facing = Signal.STOP) {
				listOf(
					TimedAction(LATE_ACTION_TIME) {
						host.distanceAtSpeed = { v -> Train.SEMAPHORE_STOP_CLEARANCE_METERS + STAND_DISTANCE_GAIN * v * v }
					},
					TimedAction(LATE_ACTION_TIME + STAND_SAMPLE_DELAY) {}
				)
			}

		val sampleTime = driver.timesAtActions.last()
		val speedAtSample = driver.velocitiesAtActions.last()
		assertThat(speedAtSample, name = "velocity at the sample, still braking").isGreaterThan(0.0)
		val standTime = sampleTime + speedAtSample / -MINIMAL_TRAIN_DECELERATION.toDouble()

		// A whole-step poll ends at the end of the step that crosses the stand: 0.059 s late here.
		// The crossing wait bisects that step, but [Engine.derivatives] holds `v` at exactly 0 past
		// the stand, so the guard is flat zero there and the first probe that lands past the stand
		// ends the wait: at most half a step late, never at the step's end. The state is the same
		// as at the stand itself (`v` and the law's acceleration are both 0).
		val idleAfterSample = driver.engineIdleTimes.first { it > sampleTime }
		assertThat(idleAfterSample - standTime, name = "engine idle time minus stand time (s)")
			.isBetween(-CROSSING_TOLERANCE, PINNED_DT_MAX / 2)
		assertThat(host.velocity.state, name = "velocity at the stand").isEqualTo(0.0)
		assertThat(driver.finalAcceleration, name = "acceleration at the stand").isEqualTo(0.0)
		assertThat(driver.engineTerminatedAtEnd, name = "engine terminated").isEqualTo(false)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("accelerateTo arm ends at once when the leg is cancelled (Issue #760)")
	fun accelerateToArmEndsOnCancel() {
		val host = FakeEngineHost()

		val driver =
			runWaitSite(host, command = { it.accelerateTo(TARGET_SPEED_MPS) }) {
				listOf(TimedAction(ACTION_TIME) { it.cancelAccelerating() })
			}

		assertThat(driver.finalAcceleration, name = "acceleration after the cancel").isEqualTo(0.0)
		assertThat(host.velocity.state, name = "velocity after the cancel")
			.isEqualTo(driver.velocitiesAtActions.single())
		assertThat(driver.enginePassivatedAtEnd, name = "engine idle after the cancel").isTrue()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("terminate() ends the accelerateTo arm's crossing wait (Issue #760)")
	fun terminateEndsTheAccelerateToArmWait() {
		val host = FakeEngineHost()

		val driver =
			runWaitSite(host, command = { it.accelerateTo(TARGET_SPEED_MPS) }) {
				listOf(TimedAction(ACTION_TIME) { it.terminate() })
			}

		// A plain `waitUntil` re-parked here: reactivate re-tests its condition, which a running
		// leg does not meet, so the engine kept integrating until the leg ended on its own. The
		// velocity frozen at the terminate instant is what tells the two apart.
		assertThat(driver.engineTerminatedAtEnd, name = "engine terminated").isTrue()
		assertThat(driver.finalAcceleration, name = "acceleration after terminate").isEqualTo(0.0)
		assertThat(host.velocity.state, name = "velocity after terminate").isEqualTo(driver.velocitiesAtActions.last())
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("terminate() ends the resume arm's crossing wait (Issue #760)")
	fun terminateEndsTheResumeArmWait() {
		val host = FakeEngineHost()

		val driver =
			runWaitSite(host, command = { it.onWarning(TARGET_SPEED_MPS) }, facing = Signal.STOP) { semaphore ->
				listOf(
					TimedAction(ACTION_TIME) { semaphore.signal = Signal.FREE },
					TimedAction(LATE_ACTION_TIME) { it.terminate() }
				)
			}

		assertThat(driver.velocitiesAtActions.last(), name = "velocity when terminated").isLessThan(TARGET_SPEED_MPS)
		assertThat(driver.engineTerminatedAtEnd, name = "engine terminated").isTrue()
		assertThat(driver.finalAcceleration, name = "acceleration after terminate").isEqualTo(0.0)
		assertThat(host.velocity.state, name = "velocity after terminate").isEqualTo(driver.velocitiesAtActions.last())
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("terminate() ends the brake-to-stop arm's crossing wait (Issue #760)")
	fun terminateEndsTheBrakeToStopArmWait() {
		val host = FakeEngineHost(distanceMeters = BRAKING_DISTANCE_M)

		val driver =
			runWaitSite(host, command = { it.onWarning(TARGET_SPEED_MPS) }, facing = Signal.STOP) {
				listOf(TimedAction(LATE_ACTION_TIME) { it.terminate() })
			}

		assertThat(driver.velocitiesAtActions.single(), name = "velocity when terminated").isGreaterThan(0.0)
		assertThat(driver.engineTerminatedAtEnd, name = "engine terminated").isTrue()
		assertThat(driver.finalAcceleration, name = "acceleration after terminate").isEqualTo(0.0)
		assertThat(host.velocity.state, name = "velocity after terminate").isEqualTo(driver.velocitiesAtActions.last())
	}
}
