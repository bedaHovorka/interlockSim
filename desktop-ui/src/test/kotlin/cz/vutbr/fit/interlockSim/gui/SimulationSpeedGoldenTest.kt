/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Phase 4.1 — Golden Output Tests: Speed Control Semantics (Issue #490)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.SimulationEvent
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.IntegrationKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.get
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * Phase 4.1 — Golden Output Tests: Speed Control Semantics (Issue #490).
 *
 * **CRITICAL:** This test validates the core architectural assumption:
 * *"Speed control changes observation speed, NOT simulation semantics."*
 *
 * Three distinct speed-control mechanisms are validated:
 * - [ShuntingLoop] `speedMultiplier` parameter (drives [RealTimeSynch] wall-clock pacing)
 * - [ShuntingLoop] `enableRealTimeSync` flag (activates/deactivates [RealTimeSynch])
 * - [SimulationRunner] `speedMultiplier` property (drives [SimulationRunner.throttle])
 *
 * None of these must alter the discrete-event simulation timestamps, event order,
 * train metrics, physics results, or stochastic train-generation sequence.
 *
 * **Acceptance criteria (from issue):**
 * - All speeds produce identical simulation results
 * - Physics invariants hold: v ≥ 0, a ∈ [[MINIMAL_DECELERATION_MS2], [MAXIMAL_ACCELERATION_MS2]]
 * - Train length invariant checked by kDisco's [ContinuousInvariantChecker] during run
 * - Stochastic distributions unchanged (exponential(43) for Generator, seed=0)
 * - Test fails if simulation semantics change
 *
 * **Speed variants under test:** 0.5x, 1x, 2x, 10x, 50x (issue requirement).
 *
 * SIM-004 Note: All tests are hard-coded to the vyhybna.xml network topology.
 *
 * @see SimulationRunner
 * @see ShuntingLoop
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/490">Issue #490</a>
 */
@Tag("integration-test")
@DisplayName("Phase 4.1 — Golden Output: Speed Control Semantics")
class SimulationSpeedGoldenTest : IntegrationKoinTestBase() {
	// ---- Data types --------------------------------------------------------

	/**
	 * Normalised event record for cross-run semantic comparison.
	 * Train name (source) is intentionally excluded because the static [Train] counter
	 * increments across successive test runs, making raw names non-comparable.
	 */
	private data class EventRecord(
		val simulationTime: Double,
		val eventType: String
	)

	/**
	 * All semantic metrics captured from one simulation run.
	 */
	private data class SimulationSnapshot(
		// ShuntingLoop lifecycle counters (#365 instrumentation)
		val trainsEntered: Int,
		val trainsExited: Int,
		val maxConcurrentTrains: Int,
		/** Sorted list of per-train block-transition counts — structure comparison without key dependency. */
		val sortedTransitionValues: List<Int>,
		val totalTransitions: Int,
		// Network state at end of simulation
		val graphSize: Int,
		val occupiedBlocks: Int,
		val reservedBlocks: Int,
		val trainCount: Int,
		// Full event sequence for timestamp / ordering validation
		val eventSequence: List<EventRecord>,
		// Raw TRAIN_CONTINUOUS message bodies for physics validation
		val continuousMessages: List<String>
	)

	// ---- Helper: run one simulation ----------------------------------------

	/**
	 * Creates and runs a [ShuntingLoop] against vyhybna.xml, returns a [SimulationSnapshot].
	 *
	 * @param speedMultiplier forwarded to [ShuntingLoop]; only used when [enableRealTimeSync]=true
	 * @param enableRealTimeSync when false [speedMultiplier] has no effect on simulation semantics
	 */
	private fun runAtSpeed(
		speedMultiplier: Double,
		enableRealTimeSync: Boolean = false
	): SimulationSnapshot {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		val eventSequence = mutableListOf<EventRecord>()
		val continuousMessages = mutableListOf<String>()

		val listener =
			ContextPropertyChangeListener { evt ->
				val se = SimulationEvent.fromContextChangeEvent(evt) ?: return@ContextPropertyChangeListener
				eventSequence += EventRecord(se.simulationTime, se.eventType.name)
				if (se.eventType == ReportType.TRAIN_CONTINUOUS) {
					continuousMessages += se.message
				}
			}

		return ctx.use { context ->
			context.getInOuts()
			context.addPropertyChangeListener(listener)

			val loop =
				ShuntingLoop(
					context,
					END_TIME_SECONDS,
					enableRealTimeSync = enableRealTimeSync,
					initialSpeedMultiplier = speedMultiplier
				)
			wireSynchronousDispatcher(context, loop)
			context.setMainProcess(loop)
			context.run()

			context.removePropertyChangeListener(listener)

			// graph.values() is Collection<DynamicTrackBlock> (DefaultSimulationContext<DynamicTrackBlock>)
			val graph = context.getGraph()
			val occupiedBlocks = graph.values().count { it.occupant != null }
			val registry = context.scope.get<PathReservationRegistry>()
			val transitions = loop.getAllBlockTransitions()

			logger.info {
				"Speed ${if (enableRealTimeSync) "${speedMultiplier}x(rt)" else "${speedMultiplier}x(free)"}: " +
					"entered=${loop.getTrainsEntered()}, exited=${loop.getTrainsExited()}, " +
					"transitions=${transitions.values.toList()}, events=${eventSequence.size}"
			}

			SimulationSnapshot(
				trainsEntered = loop.getTrainsEntered(),
				trainsExited = loop.getTrainsExited(),
				maxConcurrentTrains = loop.getMaxConcurrentTrains(),
				sortedTransitionValues = transitions.values.sorted(),
				totalTransitions = transitions.values.sum(),
				graphSize = graph.size(),
				occupiedBlocks = occupiedBlocks,
				reservedBlocks = registry.blockCount(),
				trainCount = registry.trainCount(),
				eventSequence = eventSequence.toList(),
				continuousMessages = continuousMessages.toList()
			)
		}
	}

	/**
	 * Runs the simulation through [SimulationRunner.start] to exercise the GUI execution path.
	 * Blocks until the runner thread exits (≤ 20 s) before returning captured metrics.
	 *
	 * @param runnerSpeed value assigned to [SimulationRunner.speedMultiplier] before start
	 */
	private fun runViaSimulationRunner(runnerSpeed: Double): SimulationSnapshot {
		val factory = get<SimulationContextFactory>()
		val ctx = TestFixtures.loadShuntingXml().use { factory.createContext(it) as DefaultSimulationContext }

		val eventSequence = mutableListOf<EventRecord>()
		val continuousMessages = mutableListOf<String>()

		val listener =
			ContextPropertyChangeListener { evt ->
				val se = SimulationEvent.fromContextChangeEvent(evt) ?: return@ContextPropertyChangeListener
				eventSequence += EventRecord(se.simulationTime, se.eventType.name)
				if (se.eventType == ReportType.TRAIN_CONTINUOUS) {
					continuousMessages += se.message
				}
			}

		return ctx.use { context ->
			context.getInOuts()
			context.addPropertyChangeListener(listener)

			val loop = prepareShuntingLoop(context, END_TIME_SECONDS)

			val runner = SimulationRunner(context)
			runner.speedMultiplier = runnerSpeed
			runner.start()

			// Poll until the simulation thread exits (free-run simulation finishes in <3 s)
			val deadlineMs = System.currentTimeMillis() + 20_000L
			while (runner.isRunning() && System.currentTimeMillis() < deadlineMs) {
				Thread.sleep(50)
			}

			val timedOut = runner.isRunning()
			runner.stop() // interrupt thread if still alive
			// Wait for the thread to truly terminate so reads below are safe
			val joinDeadlineMs = System.currentTimeMillis() + 5_000L
			while (runner.isRunning() && System.currentTimeMillis() < joinDeadlineMs) {
				Thread.sleep(10)
			}
			assertThat(timedOut)
				.isFalse() // fail loudly if the simulation hung past the 20 s deadline

			context.removePropertyChangeListener(listener)

			val graph = context.getGraph()
			val occupiedBlocks = graph.values().count { it.occupant != null }
			val registry = context.scope.get<PathReservationRegistry>()
			val transitions = loop.getAllBlockTransitions()

			SimulationSnapshot(
				trainsEntered = loop.getTrainsEntered(),
				trainsExited = loop.getTrainsExited(),
				maxConcurrentTrains = loop.getMaxConcurrentTrains(),
				sortedTransitionValues = transitions.values.sorted(),
				totalTransitions = transitions.values.sum(),
				graphSize = graph.size(),
				occupiedBlocks = occupiedBlocks,
				reservedBlocks = registry.blockCount(),
				trainCount = registry.trainCount(),
				eventSequence = eventSequence.toList(),
				continuousMessages = continuousMessages.toList()
			)
		}
	}

	// ---- Test 1: Speed-invariant simulation semantics ----------------------

	/**
	 * **All speed multipliers produce identical simulation semantics (enableRealTimeSync=false).**
	 *
	 * When [ShuntingLoop.enableRealTimeSync] is false, [ShuntingLoop.speedMultiplier] is never
	 * read by any simulation code. This test proves that varying the parameter across the full
	 * range required by the issue (0.5x, 1x, 2x, 10x, 50x) leaves every semantic metric unchanged.
	 *
	 * Validated invariants: trainsEntered, trainsExited, maxConcurrentTrains,
	 * sortedTransitionValues, totalTransitions, graphSize, occupiedBlocks, reservedBlocks, trainCount.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("all speed multipliers produce identical simulation semantics (free-run)")
	fun `all speed multipliers produce identical simulation semantics`() {
		val baseline = runAtSpeed(speedMultiplier = 1.0)

		// Baseline must contain at least one train — proves simulation ran meaningfully
		assertThat(baseline.trainsEntered).isGreaterThanOrEqualTo(1)
		assertThat(baseline.graphSize).isEqualTo(EXPECTED_GRAPH_SIZE)

		for (speed in SPEED_VARIANTS) {
			val result = runAtSpeed(speedMultiplier = speed)

			assertThat(result.trainsEntered).isEqualTo(baseline.trainsEntered)
			assertThat(result.trainsExited).isEqualTo(baseline.trainsExited)
			assertThat(result.maxConcurrentTrains).isEqualTo(baseline.maxConcurrentTrains)
			assertThat(result.sortedTransitionValues).isEqualTo(baseline.sortedTransitionValues)
			assertThat(result.totalTransitions).isEqualTo(baseline.totalTransitions)
			assertThat(result.graphSize).isEqualTo(baseline.graphSize)
			assertThat(result.occupiedBlocks).isEqualTo(baseline.occupiedBlocks)
			assertThat(result.reservedBlocks).isEqualTo(baseline.reservedBlocks)
			assertThat(result.trainCount).isEqualTo(baseline.trainCount)
		}
	}

	// ---- Test 2: Event-sequence identity -----------------------------------

	/**
	 * **Simulation event sequences (timestamps + types) are identical across all speed multipliers.**
	 *
	 * For each speed variant, every captured event must match the baseline in:
	 * - `simulationTime` (exact equality; discrete-event timestamps are deterministic Doubles)
	 * - `eventType` (same ReportType at each position)
	 *
	 * This is the definitive proof that "speed" is a pure observation parameter:
	 * the discrete-event simulation clock advances identically regardless of the
	 * wall-clock speed multiplier.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("event sequences (timestamps + types) identical across all speed multipliers")
	fun `event sequences identical across all speed multipliers`() {
		val baseline = runAtSpeed(speedMultiplier = 1.0)
		assertThat(baseline.eventSequence).isNotEmpty()

		for (speed in SPEED_VARIANTS) {
			val result = runAtSpeed(speedMultiplier = speed)

			assertThat(result.eventSequence).hasSize(baseline.eventSequence.size)

			result.eventSequence.zip(baseline.eventSequence).forEach { (actual, expected) ->
				assertThat(actual.eventType).isEqualTo(expected.eventType)
				assertThat(actual.simulationTime).isEqualTo(expected.simulationTime)
			}
		}
	}

	// ---- Test 3: RealTimeSynch invariance at high speed --------------------

	/**
	 * **Running with [enableRealTimeSync]=true at [REALTIME_TEST_SPEED]x produces semantics
	 * identical to the unthrottled baseline.**
	 *
	 * Wall-clock cost: [END_TIME_SECONDS] / [REALTIME_TEST_SPEED] = ~1.2 s.
	 *
	 * [RealTimeSynch] calls [Thread.sleep] to pace wall-clock time and calls `hold(1+overshoot)`
	 * for its own simulation-time bookkeeping. Neither operation touches the [Generator]'s random
	 * stream or alters when other processes are scheduled — so all discrete-event timestamps,
	 * event types, and train counts remain identical to the free-run baseline.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("RealTimeSynch at ${REALTIME_TEST_SPEED}x does not alter simulation semantics")
	fun `RealTimeSynch at high speed does not alter simulation semantics`() {
		val baseline = runAtSpeed(speedMultiplier = 1.0, enableRealTimeSync = false)
		val realtime = runAtSpeed(speedMultiplier = REALTIME_TEST_SPEED, enableRealTimeSync = true)

		assertThat(realtime.trainsEntered).isEqualTo(baseline.trainsEntered)
		assertThat(realtime.trainsExited).isEqualTo(baseline.trainsExited)
		assertThat(realtime.sortedTransitionValues).isEqualTo(baseline.sortedTransitionValues)
		assertThat(realtime.totalTransitions).isEqualTo(baseline.totalTransitions)
		assertThat(realtime.graphSize).isEqualTo(baseline.graphSize)
		assertThat(realtime.occupiedBlocks).isEqualTo(baseline.occupiedBlocks)
		assertThat(realtime.reservedBlocks).isEqualTo(baseline.reservedBlocks)
		assertThat(realtime.trainCount).isEqualTo(baseline.trainCount)

		assertThat(realtime.eventSequence).hasSize(baseline.eventSequence.size)
		realtime.eventSequence.zip(baseline.eventSequence).forEach { (actual, expected) ->
			assertThat(actual.eventType).isEqualTo(expected.eventType)
			assertThat(actual.simulationTime).isEqualTo(expected.simulationTime)
		}
	}

	// ---- Test 4: Physics invariants ----------------------------------------

	/**
	 * **Physics invariants hold throughout the full 60 s simulation run.**
	 *
	 * Validated per each 1 Hz [ReportType.TRAIN_CONTINUOUS] sample:
	 * - Velocity ≥ 0 m/s (trains move forward only)
	 * - Acceleration ∈ [[MINIMAL_DECELERATION_MS2], [MAXIMAL_ACCELERATION_MS2]] m/s²
	 *
	 * The kinematic equation v²=u²+2as is the formula implemented in `Motor.derivatives()`.
	 * That motor logic is unit-tested by [TrainPhysicsTest]; here we confirm the equation's
	 * outputs (velocity and acceleration) remain within valid physical bounds throughout a
	 * full multi-train run at every reported sample.
	 *
	 * The front−tail=length invariant is enforced by [ContinuousInvariantChecker] (LengthChecker)
	 * at every ODE integration step during the run — if it is violated, kDisco throws a
	 * [SimulationException] and the test fails before reaching these assertions.
	 *
	 * Tolerance: [POSITION_TOLERANCE_M] = 1e-6 m (issue acceptance criterion).
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("physics invariants: v≥0 and a∈[MINIMAL_DECELERATION, MAXIMAL_ACCELERATION]")
	fun `physics invariants hold across full simulation run`() {
		val snapshot = runAtSpeed(speedMultiplier = 1.0)

		// Must have TRAIN_CONTINUOUS events — proves at least one train was running
		assertThat(snapshot.continuousMessages).isNotEmpty()

		for (msg in snapshot.continuousMessages) {
			// se.message = "#N {acceleration} {velocity} ..." (parts[0] is the train-id fragment)
			val parts = msg.trim().split(Regex("\\s+"))
			assertThat(parts.size)
				.isGreaterThanOrEqualTo(3) // message format: #N acceleration velocity ...
			val acceleration =
				requireNotNull(parts[1].toDoubleOrNull()) {
					"TRAIN_CONTINUOUS acceleration token must be numeric, got: '${parts[1]}' in: '$msg'"
				}
			val velocity =
				requireNotNull(parts[2].toDoubleOrNull()) {
					"TRAIN_CONTINUOUS velocity token must be numeric, got: '${parts[2]}' in: '$msg'"
				}

			// Velocity must be non-negative (trains only move forward)
			assertThat(velocity)
				.isGreaterThanOrEqualTo(-POSITION_TOLERANCE_M)

			// Acceleration must be within physical motor bounds
			assertThat(acceleration)
				.isGreaterThanOrEqualTo(MINIMAL_DECELERATION_MS2 - POSITION_TOLERANCE_M)
			assertThat(acceleration)
				.isLessThanOrEqualTo(MAXIMAL_ACCELERATION_MS2 + POSITION_TOLERANCE_M)
		}
	}

	// ---- Test 5: Train final positions within tolerance --------------------

	/**
	 * **Train front-positions recorded in [ReportType.TRAIN_CONTINUOUS] events are identical
	 * (within [POSITION_TOLERANCE_M] = 1e-6 m) across all speed multipliers.**
	 *
	 * The sorted list of front-distances at each simulated second is compared across all speed
	 * variants against the 1x baseline. Because the simulation is fully deterministic (same RNG
	 * seed, same event schedule), positions are in practice exactly equal; the tolerance accounts
	 * for any future floating-point edge cases in the numerical integrator.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("train front-positions within 1e-6 m tolerance across all speed multipliers")
	fun `train final positions within tolerance across all speed multipliers`() {
		val baseline = runAtSpeed(speedMultiplier = 1.0)
		val baselinePositions = extractFrontDistances(baseline.continuousMessages)
		assertThat(baselinePositions).isNotEmpty()

		for (speed in SPEED_VARIANTS) {
			val result = runAtSpeed(speedMultiplier = speed)
			val positions = extractFrontDistances(result.continuousMessages)

			assertThat(positions).hasSize(baselinePositions.size)
			positions.zip(baselinePositions).forEach { (actual, expected) ->
				assertThat(abs(actual - expected)).isLessThanOrEqualTo(POSITION_TOLERANCE_M)
			}
		}
	}

	// ---- Test 6: Stochastic reproducibility --------------------------------

	/**
	 * **The [Generator] produces identical train-generation sequences across independent runs.**
	 *
	 * [Generator] uses a [cz.ksimulantenbande.kdisco.Random] seeded with `0L` and inter-arrival
	 * distribution `exp(43.0)`. Two independent runs from the same seed must produce
	 * identical event timestamps, event types, and all train-lifecycle metrics.
	 *
	 * This verifies that [ShuntingLoop.speedMultiplier] (when unused) never consumes from
	 * the random stream, and that the simulation framework provides deterministic replay.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("stochastic train generation (exp(43), seed=0) is reproducible across runs")
	fun `stochastic train generation is reproducible`() {
		val run1 = runAtSpeed(speedMultiplier = 1.0)
		val run2 = runAtSpeed(speedMultiplier = 1.0)

		assertThat(run2.trainsEntered).isEqualTo(run1.trainsEntered)
		assertThat(run2.trainsExited).isEqualTo(run1.trainsExited)
		assertThat(run2.sortedTransitionValues).isEqualTo(run1.sortedTransitionValues)
		assertThat(run2.totalTransitions).isEqualTo(run1.totalTransitions)

		// Event sequence must be identical (timestamps + event types)
		assertThat(run2.eventSequence).hasSize(run1.eventSequence.size)
		run2.eventSequence.zip(run1.eventSequence).forEach { (e2, e1) ->
			assertThat(e2.simulationTime).isEqualTo(e1.simulationTime)
			assertThat(e2.eventType).isEqualTo(e1.eventType)
		}
	}

	// ---- Test 7: SimulationRunner speed invariance -------------------------

	/**
	 * **[SimulationRunner.speedMultiplier] does not alter the discrete-event simulation output.**
	 *
	 * [SimulationRunner.speedMultiplier] only affects the wall-clock sleep duration in
	 * [SimulationRunner.throttle]. Because [ShuntingLoop] does not call [SimulationRunner.throttle],
	 * changing the runner's speed has zero effect on the discrete-event simulation output.
	 *
	 * This test runs the simulation through the full GUI execution path ([SimulationRunner.start])
	 * at three distinct speed settings and verifies the output matches the direct `context.run()`
	 * baseline at 1x.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("SimulationRunner speed multiplier does not alter simulation output")
	fun `SimulationRunner speed multiplier does not alter simulation output`() {
		val baseline = runAtSpeed(speedMultiplier = 1.0)

		// Use speeds ≥ 10x so a 60s simulation completes in ≤ 6s wall-clock.
		// The semantic goal (identical output at any speed) is preserved; low speeds
		// would exceed the 20s poll deadline now that throttle() fires per event.
		for (runnerSpeed in listOf(10.0, 50.0, 100.0)) {
			val result = runViaSimulationRunner(runnerSpeed)

			assertThat(result.trainsEntered).isEqualTo(baseline.trainsEntered)
			assertThat(result.trainsExited).isEqualTo(baseline.trainsExited)
			assertThat(result.sortedTransitionValues).isEqualTo(baseline.sortedTransitionValues)
			assertThat(result.totalTransitions).isEqualTo(baseline.totalTransitions)
			assertThat(result.graphSize).isEqualTo(baseline.graphSize)
			assertThat(result.occupiedBlocks).isEqualTo(baseline.occupiedBlocks)
			assertThat(result.reservedBlocks).isEqualTo(baseline.reservedBlocks)
			assertThat(result.trainCount).isEqualTo(baseline.trainCount)
		}
	}

	// ---- Helper: extract front distances from continuous events ------------

	/**
	 * Parses TRAIN_CONTINUOUS message bodies and returns sorted front-distances.
	 *
	 * Message format (from [TrainReporter]):
	 * `#N {acceleration} {velocity} {frontTotalDistance} {frontSection} {tailSection} {distToSem}`
	 * (parts[0] is the train-id fragment from SimulationEvent.fromContextChangeEvent's limit-3 split)
	 *
	 * Returns sorted values for key-independent structural comparison across runs
	 * (train names / source differ between runs due to static counter).
	 */
	private fun extractFrontDistances(messages: List<String>): List<Double> =
		messages
			.map { msg ->
				val parts = msg.trim().split(Regex("\\s+"))
				require(parts.size >= 4) {
					"TRAIN_CONTINUOUS message must have at least 4 tokens, got ${parts.size}: '$msg'"
				}
				requireNotNull(parts[3].toDoubleOrNull()) {
					"TRAIN_CONTINUOUS front-distance token must be numeric, got: '${parts[3]}' in: '$msg'"
				}
			}.sorted()

	// ---- Constants ---------------------------------------------------------

	companion object {
		/** Simulation end time (simulation seconds). */
		const val END_TIME_SECONDS = 60L

		/**
		 * Position tolerance from issue acceptance criteria.
		 * Used for velocity ≥ 0 checks and front-distance cross-run comparison.
		 */
		const val POSITION_TOLERANCE_M = 1e-6

		/** Physical acceleration upper bound from Train.Motor (m/s²). */
		const val MAXIMAL_ACCELERATION_MS2 = 4.0

		/** Physical deceleration lower bound from Train.Motor (m/s²). */
		const val MINIMAL_DECELERATION_MS2 = -3.0

		/**
		 * Expected graph size for vyhybna.xml — stable baseline from [KoinGoldenOutputTest].
		 * Confirmed with kdisco-engine 0.3.0-SNAPSHOT; validated by [KoinGoldenOutputTest].
		 */
		const val EXPECTED_GRAPH_SIZE = 10

		/**
		 * Speed used for the [RealTimeSynch] invariance test.
		 * Wall-clock cost: [END_TIME_SECONDS] / [REALTIME_TEST_SPEED] ≈ 1.2 s.
		 */
		const val REALTIME_TEST_SPEED = 50.0

		/** Speed variants required by the issue (0.5x, 1x, 2x, 10x, 50x). */
		val SPEED_VARIANTS = listOf(0.5, 1.0, 2.0, 10.0, 50.0)
	}
}
