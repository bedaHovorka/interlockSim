/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.timing

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.ActionValidator
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionCandidateEnumerator
import cz.vutbr.fit.interlockSim.dispatcher.agents.AffordanceAnnotator
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
import cz.vutbr.fit.interlockSim.dispatcher.agents.NoTimeoutBudget
import cz.vutbr.fit.interlockSim.dispatcher.agents.ObservationRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer
import cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard
import cz.vutbr.fit.interlockSim.dispatcher.agents.TickRingBuffer
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector
import cz.vutbr.fit.interlockSim.gui.SimulationRunner
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Throwaway prototype harness for the SP2c.26 (Issue #849) F1 paused-clock spike.
 *
 * Wires a real `vyhybna.xml` [ShuntingLoop] to a real [DispatcherObservationProjector] and paces
 * it with a real [SimulationRunner], so the spike's questions can be answered against production
 * components rather than a mock of them:
 *
 * - does [DispatcherObservationProjector.captureOnSimThread] still run while the simulation is
 *   paused (the deadlock hazard in #849), and
 * - how much simulation time elapses between [SimulationRunner.isPaused] being set and the
 *   simulation thread actually parking (the *pause latency* on which F1's reproducibility
 *   argument depends).
 *
 * ## Why this does not reuse `RuleBasedDispatcherDeterminismRunner`
 *
 * That runner is the P10 determinism gate. Parameterising it for a spike risks drifting the
 * gate's wiring, and it deliberately uses
 * [NoOpSimulationController][cz.vutbr.fit.interlockSim.context.NoOpSimulationController] — the one
 * controller that cannot pause. This harness therefore duplicates the minimum wiring it needs and
 * leaves the gate untouched.
 *
 * ## Why the simulation thread is owned here instead of by [SimulationRunner.start]
 *
 * [SimulationRunner.start] passes *itself* as the controller. The spike needs to observe what the
 * controlled event loop does — the simulation clock it reports to
 * [SimulationController.throttle], and the exact moment it enters
 * [SimulationController.awaitIfPaused] — which requires wrapping the runner in
 * [RecordingPacingController]. So the harness starts `context.run(controller)` on its own daemon
 * thread and drives [runner] purely as the pause/speed implementation.
 *
 * Not merged as production code: Issue #849 states the prototype is throwaway and the deliverable
 * is the ruling. It is kept in the test tree because the ruling's claims must stay re-runnable.
 *
 * @property context The live simulation context.
 * @property loop The `vyhybna.xml` shunting loop driving the simulation.
 * @property projector Publishes a [DispatcherObservation] on every sim-thread capture.
 * @property runner Real pacing controller — the pause/resume and speed-multiplier implementation.
 * @property controller The recording decorator actually handed to `context.run`.
 *
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
class PausedClockSpikeHarness private constructor(
	val context: DefaultSimulationContext,
	val loop: ShuntingLoop,
	val projector: DispatcherObservationProjector,
	val runner: SimulationRunner,
	val controller: RecordingPacingController
) {
	private var simThread: Thread? = null

	/** Latest observation published by the projector; [DispatcherObservation.EMPTY] before tick 1. */
	fun latest(): DispatcherObservation = projector.latest()

	/**
	 * Builds a real [DispatchTickLoop] over this harness's projector, driven by [emission].
	 *
	 * `snapshotSignal` is deliberately left null so a test can call
	 * [DispatchTickLoop.runTick] exactly once from the test thread and observe that single tick,
	 * instead of racing an `agentDriverAction` loop.
	 *
	 * The loop is given a [DelegatingSimulationController] bound to [runner], mirroring production
	 * (`ExampleRegistry.wireDispatcherAgent`): the driver must never call
	 * [SimulationRunner.awaitIfPaused] directly, because that consumes single-step requests owned
	 * by the simulation thread.
	 */
	fun buildTickLoop(emission: EmissionStrategy): DispatchTickLoop {
		val topology = StationTopologySerializer.describe(context)
		val validator =
			ActionValidator(
				validEndpointNames = (topology.inOuts + topology.signals.map { it.name }).toSet(),
				blockIds = topology.blocks.map { it.name }.toSet()
			)
		return DispatchTickLoop(
			observations = projector,
			annotator = AffordanceAnnotator(validator, ActionCandidateEnumerator()),
			renderer = ObservationRenderer { "" },
			emission = emission,
			validator = validator,
			queue = ActuatorCommandQueue(),
			ring = TickRingBuffer(),
			workingMemory = WorkingMemory.EMPTY,
			budget = NoTimeoutBudget,
			fallbackGuard = TerminalFallbackGuard(),
			controller = DelegatingSimulationController().apply { delegate = runner },
			snapshotSignal = null
		)
	}

	/**
	 * Polls until the projector publishes a tick beyond [afterTick].
	 *
	 * @return `true` if a fresher observation arrived within [timeoutMillis]. Used by the spike to
	 *   demonstrate that this never happens while the simulation is paused.
	 */
	fun awaitFreshCapture(
		afterTick: Long,
		timeoutMillis: Long
	): Boolean = awaitTick(minTick = afterTick + 1L, timeoutMillis = timeoutMillis) > afterTick

	/** Starts the simulation on a dedicated daemon thread. */
	fun start() {
		check(simThread == null) { "Harness already started" }
		val thread =
			Thread({
				runCatching { context.run(controller) }
					.onFailure { logger.debug(it) { "Spike simulation thread ended" } }
			}, "paused-clock-spike-sim")
		thread.isDaemon = true
		simThread = thread
		thread.start()
	}

	/**
	 * Stops the simulation and waits for the thread to die.
	 *
	 * Clears the pause first: a thread parked in [SimulationRunner.awaitIfPaused] would otherwise
	 * never observe the stop request.
	 */
	fun stop(joinMillis: Long = STOP_JOIN_MILLIS) {
		runner.isPaused = false
		runCatching { context.stop() }
		val thread = simThread ?: return
		thread.join(joinMillis)
		if (thread.isAlive) thread.interrupt()
		simThread = null
	}

	/**
	 * Blocks until the projector has published a tick of at least [minTick].
	 *
	 * @return the tick actually observed, which is `< minTick` if [timeoutMillis] expired first —
	 *   callers assert on the returned value rather than on this method throwing.
	 */
	fun awaitTick(
		minTick: Long,
		timeoutMillis: Long = AWAIT_TICK_MILLIS
	): Long {
		val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
		var tick = latest().tick
		while (tick < minTick && System.nanoTime() < deadline) {
			Thread.sleep(POLL_MILLIS)
			tick = latest().tick
		}
		return tick
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default per-poll sleep while waiting for the simulation to make progress. */
		const val POLL_MILLIS: Long = 5L

		/** Default ceiling for [awaitTick]. Generous: CI machines are slower than dev machines. */
		const val AWAIT_TICK_MILLIS: Long = 15_000L

		/** Default ceiling for the simulation thread to die in [stop]. */
		const val STOP_JOIN_MILLIS: Long = 5_000L

		private const val NANOS_PER_MILLI: Long = 1_000_000L

		/**
		 * Default speed multiplier for spike runs.
		 *
		 * [ShuntingLoop.iteration] ends with `hold(1.0)`, so at 1x each dispatcher tick costs a
		 * wall-clock second. 10x compresses that to ~100 ms, keeping the spike's tests fast while
		 * leaving [SimulationRunner]'s throttling — the mechanism under study — fully engaged.
		 */
		const val DEFAULT_SPEED_MULTIPLIER: Double = 10.0

		/**
		 * Creates a harness, starts it, waits for tick [minTick], runs [body], and always stops it.
		 *
		 * Thirteen paused-clock tests opened with the same four lines — create, `try`, `start()`,
		 * `awaitTick(minTick = 2L)` — and closed with `finally { stop() }` (Issue #955, cluster U4).
		 * A harness left running holds a live simulation thread, so the `finally` is not optional;
		 * folding it in here means a test cannot forget it.
		 *
		 * [body] receives the harness and the tick reached before it ran.
		 */
		fun <T> withStarted(
			factory: SimulationContextFactory,
			minTick: Long = 2L,
			endTime: Long = 600L,
			speedMultiplier: Double = DEFAULT_SPEED_MULTIPLIER,
			body: (PausedClockSpikeHarness, Long) -> T
		): T {
			val harness = create(factory, endTime, speedMultiplier)
			try {
				harness.start()
				return body(harness, harness.awaitTick(minTick = minTick))
			} finally {
				harness.stop()
			}
		}

		/**
		 * Builds the harness. The caller must supply a Koin-resolved [SimulationContextFactory]
		 * so the context's scope carries the same navigation-service bindings production uses.
		 *
		 * `enableRealTimeSync` is left off: [SimulationRunner.throttle] already paces the run
		 * against wall-clock time, and stacking `ShuntingLoop`'s own `RealTimeSynch` process on top
		 * would make the observed pacing a mixture of two mechanisms rather than the one under study.
		 */
		fun create(
			factory: SimulationContextFactory,
			endTime: Long = 600L,
			speedMultiplier: Double = DEFAULT_SPEED_MULTIPLIER
		): PausedClockSpikeHarness {
			val context =
				TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }
			// Initialise the dynamic wrapper map — required before ShuntingLoop construction.
			context.getInOuts()

			val loop = ShuntingLoop(context, endTime)
			val perceptionPort = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
			val projector =
				DispatcherObservationProjector(
					perceptionPort = perceptionPort,
					dispatchLoopSensorPort = DefaultDispatchLoopSensorPort(loop::getLatestObservation),
					pathReservationRegistry = context.scope.get<PathReservationRegistry>(),
					environment = context
				)

			// Same composite hook production uses (ExampleRegistry.wireDispatcherAgent): refresh the
			// perception snapshot first, then project on the same sim thread.
			loop.snapshotCaptureHook = {
				perceptionPort.captureSnapshot()
				projector.captureOnSimThread()
			}

			val runner = SimulationRunner(context).apply { this.speedMultiplier = speedMultiplier }
			context.setMainProcess(loop)
			return PausedClockSpikeHarness(context, loop, projector, runner, RecordingPacingController(runner))
		}
	}
}

/**
 * [SimulationController] decorator that records what the controlled event loop reports, so the
 * spike can measure the simulation clock and the pause hand-off without modifying `:core`.
 *
 * `DefaultSimulationContext.advanceControlledStep` calls [throttle] with the simulation time
 * advanced since the previous event, and only then calls [awaitIfPaused]. Accumulating those
 * deltas reconstructs the simulation clock as the controlled loop sees it, and recording the
 * clock value on entry to [awaitIfPaused] captures the moment the simulation thread actually
 * parks — which is *not* the moment [SimulationRunner.isPaused] was set.
 *
 * @property delegate The real pacing implementation.
 * @since Issue #849 (SP2c.26 — Goal 10 F1 paused-clock spike)
 */
class RecordingPacingController(
	private val delegate: SimulationRunner
) : SimulationController {
	/** Simulation clock as reported to [throttle], in seconds. Written by the simulation thread only. */
	@Volatile
	var observedSimTime: Double = 0.0
		private set

	/** Simulation clock at the most recent entry into [awaitIfPaused] while actually paused. */
	@Volatile
	var simTimeAtPark: Double? = null
		private set

	/** Number of completed [awaitIfPaused] calls that genuinely parked. */
	@Volatile
	var parkCount: Int = 0
		private set

	override suspend fun awaitIfPaused() {
		if (delegate.isPaused()) {
			simTimeAtPark = observedSimTime
			parkCount += 1
		}
		delegate.awaitIfPaused()
	}

	/** Clears [simTimeAtPark] so a caller can wait for the *next* park rather than re-reading an old one. */
	fun clearParkRecord() {
		simTimeAtPark = null
	}

	override fun throttle(simDeltaSeconds: Double) {
		if (simDeltaSeconds > 0.0) observedSimTime += simDeltaSeconds
		delegate.throttle(simDeltaSeconds)
	}

	override fun isPaused(): Boolean = delegate.isPaused()

	override fun pollStepEvent(): Boolean = delegate.pollStepEvent()

	override fun pollStepTime(): Double? = delegate.pollStepTime()

	override fun requestPause() {
		delegate.requestPause()
	}

	override fun requestResume() {
		delegate.requestResume()
	}

	override fun currentSpeedMultiplier(): Double = delegate.currentSpeedMultiplier()
}
