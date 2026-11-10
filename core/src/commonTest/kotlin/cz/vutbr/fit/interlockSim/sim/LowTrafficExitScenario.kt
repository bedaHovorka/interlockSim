/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B exit calibration (#591 part 1, Issue #1346; scope of SP11 #1158).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isZero
import cz.ksimulantenbande.kdisco.dtMax
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

/**
 * The low-traffic exit scenario of Goal 1B (#591 part 1, Issue #1346): the owner-approved exit
 * parameters, the seeded arrival builder, one run on the Praha stress fixture and the functional
 * assertions. A plain helper, not a test: the JVM integration test `LowTrafficExitCalibrationTest` runs it
 * for every seed of [LOW_TRAFFIC_SEEDS], the native seed test for the first seed, and the JVM
 * `ExitCalibrationRowsTest` reuses [runScenario] for its measurement rows.
 *
 * The topology is the one of the existing stress path (`MultiTrainScaleStressTest`):
 * `praha-hlavni-nadrazi.xml` and the switch-legal pairs of [MultiTrainStressSpecs.LEGAL_PAIRS].
 *
 * **Functional assertions only** ([assertFunctional]): every spec enters and exits, no block
 * resource stays occupied, and the observed concurrency stays within [MAX_CONCURRENT_TRAINS]. The
 * wall time of a run is logged, never asserted; the 15 s per run / 3 min total budget is a
 * measurement taken on a quiet machine. A failing seed is a finding: its cause is classified
 * ([MultiTrainRun.failureCause]) and its spec list logged, and the assertions are never loosened.
 */
internal object LowTrafficExitScenario {
	/**
	 * The one logger of the exit calibration: the helper and the calibration tests log through it.
	 * The JVM `logback-test.xml` lets its INFO lines through (`EXIT_CALIBRATION` appender, stdout),
	 * because the per-run lines are the measurement the coordinator reads.
	 */
	const val LOGGER_NAME: String = "cz.vutbr.fit.interlockSim.sim.ExitCalibration"

	val logger = KotlinLogging.logger(LOGGER_NAME)

	/**
	 * The seeds of the calibration, fixed before the first run. A seed is never replaced after its
	 * result is known: a failing seed is a finding, not a bad seed.
	 */
	val LOW_TRAFFIC_SEEDS: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

	/** Mean of the exponential inter-arrival time in simulated seconds (owner-approved: at least 120 s). */
	const val MEAN_INTER_ARRIVAL_SECONDS: Double = 120.0

	/**
	 * Minimum gap between two injections that name the same InOut, as entry or as exit. Applying it
	 * to the exit InOut too is stricter than the #1158 wording (TSE ruling on PR #1349) and lowers
	 * the offered traffic slightly.
	 */
	const val MIN_GAP_PER_IN_OUT_SECONDS: Double = 30.0

	/** Minimum gap between any two consecutive injections. */
	const val MIN_GAP_GLOBAL_SECONDS: Double = 20.0

	/** Concurrency cap passed to [MultiTrainLoop]. */
	const val MAX_CONCURRENT_TRAINS: Int = 4

	/** Simulated horizon of a run in seconds. */
	const val END_TIME: Long = 3600L

	/** No injection is later than [END_TIME] minus this margin, so the last train can still exit. */
	const val RUN_END_MARGIN_SECONDS: Double = 600.0

	/**
	 * The integration step cap of the exit parameters: the global [Generator.DT_MAX] (Goal 1B SP13).
	 * [MultiTrainLoop]'s generator sets kDisco's `dtMax` to it when it starts
	 * (`Generator.startAction`), so the helper does not set it; every run records the effective value
	 * at its last dispatcher tick in [MultiTrainRun.dtMax] and logs it.
	 */
	const val DT_MAX: Double = Generator.DT_MAX

	/**
	 * The seeded arrivals of one run: exponential gaps with mean [MEAN_INTER_ARRIVAL_SECONDS] from
	 * `kotlin.random.Random(seed)`, each pair drawn from [MultiTrainStressSpecs.LEGAL_PAIRS]. An
	 * injection is moved later when it would come less than [MIN_GAP_GLOBAL_SECONDS] after the
	 * previous one, or less than [MIN_GAP_PER_IN_OUT_SECONDS] after the last injection naming one of
	 * its InOuts; the next gap counts from the moved time. Every injection is rounded up to a whole
	 * second, because the loop's generator injects on its one-second ticks anyway; whole seconds also
	 * keep the gaps exact. Arrivals stop at the first injection later than
	 * `END_TIME - RUN_END_MARGIN_SECONDS`.
	 */
	fun arrivals(seed: Int): List<MultiTrainLoop.TrainSpec> {
		val random = Random(seed)
		val lastInjection = END_TIME - RUN_END_MARGIN_SECONDS
		val lastAtInOut = mutableMapOf<String, Double>()
		val specs = mutableListOf<MultiTrainLoop.TrainSpec>()
		var time = 0.0
		while (true) {
			time = ceil(time - MEAN_INTER_ARRIVAL_SECONDS * ln(1.0 - random.nextDouble()))
			val pairs = MultiTrainStressSpecs.LEGAL_PAIRS
			val (inName, outName) = pairs[random.nextInt(pairs.size)]
			val previous = specs.lastOrNull()?.inTime
			time =
				maxOf(
					time,
					previous?.plus(MIN_GAP_GLOBAL_SECONDS) ?: 0.0,
					lastAtInOut[inName]?.plus(MIN_GAP_PER_IN_OUT_SECONDS) ?: 0.0,
					lastAtInOut[outName]?.plus(MIN_GAP_PER_IN_OUT_SECONDS) ?: 0.0
				)
			if (time > lastInjection) {
				return specs
			}
			specs += MultiTrainLoop.TrainSpec(inName, outName, time, MultiTrainStressSpecs.TRAIN_LENGTH)
			lastAtInOut[inName] = time
			lastAtInOut[outName] = time
		}
	}

	/** Runs the low-traffic scenario of [seed] with the exit parameters and logs the result. */
	fun run(
		seed: Int,
		processFactory: SimulationProcessFactory
	): MultiTrainRun =
		runScenario(
			label = "low-traffic seed $seed",
			specs = arrivals(seed),
			maxConcurrentTrains = MAX_CONCURRENT_TRAINS,
			endTime = END_TIME,
			maxEntryAttempts = MultiTrainLoop.DEFAULT_MAX_ENTRY_ATTEMPTS,
			processFactory = processFactory
		)

	/**
	 * Runs [specs] through a [MultiTrainLoop] on a fresh Praha context and logs one result line
	 * (label, counts, first blocking times and the wall time). The wall time is measured, never
	 * judged here.
	 */
	fun runScenario(
		label: String,
		specs: List<MultiTrainLoop.TrainSpec>,
		maxConcurrentTrains: Int,
		endTime: Long,
		maxEntryAttempts: Int,
		processFactory: SimulationProcessFactory
	): MultiTrainRun {
		val ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, processFactory)
		try {
			ctx.getInOuts() // initialize dynamic wrappers
			var reservationExpiries = 0
			ctx.onReservationExpired { reservationExpiries++ }
			val recorder = FirstBlockedRecorder(ctx.getRoutingServices().getPathReservationService())
			val loop = ProbedMultiTrainLoop(ctx, endTime, specs, maxConcurrentTrains, maxEntryAttempts, recorder)
			ctx.setMainProcess(loop)

			val startMs = currentTimeMillisKMP()
			ctx.run()
			val wallMs = currentTimeMillisKMP() - startMs

			val entryFailures = loop.getEntryFailures()
			val approvedAtEnd = loop.getApprovedTrains().size
			val run =
				MultiTrainRun(
					label = label,
					specs = specs,
					maxConcurrentTrainsCap = maxConcurrentTrains,
					entered = loop.getTrainsEntered(),
					exited = loop.getTrainsExited(),
					maxConcurrent = loop.getMaxConcurrentTrains(),
					occupied = loop.getOccupiedResourceCount(),
					entryFailures = entryFailures,
					stallReport = loop.stall?.message,
					stallSimTime = loop.stall?.simTime,
					waitingAtEnd = loop.getWaitingTrainNames(),
					heldByCapAtEnd =
						loop.getTrainsEntered() - loop.getTrainsExited() - entryFailures.size - approvedAtEnd,
					firstBlockedSimTime = recorder.firstBlockedTime,
					firstCapHoldSimTime = loop.firstCapHoldTime,
					reservationExpiries = reservationExpiries,
					dtMax = loop.observedDtMax,
					wallMs = wallMs
				)
			logger.info { run.summary() }
			return run
		} finally {
			ctx.close()
		}
	}

	/**
	 * The functional assertions of the exit gate: entered == exited == specs, occupied == 0 and
	 * observed concurrency at most the run's cap. On a failure the classified cause and the full
	 * spec list are logged before the assertion error propagates.
	 */
	fun assertFunctional(run: MultiTrainRun) {
		try {
			val context = "${run.label}, cause ${run.failureCause}"
			assertAll {
				assertThat(run.entered, name = "trains entered ($context)").isEqualTo(run.specs.size)
				assertThat(run.exited, name = "trains exited ($context)").isEqualTo(run.specs.size)
				assertThat(run.occupied, name = "occupied resources ($context)").isZero()
				assertThat(run.maxConcurrent, name = "max concurrent trains ($context)")
					.isLessThanOrEqualTo(run.maxConcurrentTrainsCap)
			}
		} catch (e: AssertionError) {
			logger.error {
				"Exit calibration ${run.label} FAILED, cause ${run.failureCause}: " +
					"entryFailures=${run.entryFailures}, stall=${run.stallReport}, " +
					"waitingAtEnd=${run.waitingAtEnd}, heldByCapAtEnd=${run.heldByCapAtEnd}, " +
					"specs=${run.specs}"
			}
			throw e
		}
	}

	/**
	 * Observes the loop's entry reservations and records the simulation time of the first refusal
	 * that makes a train wait (any result other than success or a permanent refusal). The time is
	 * the one of the dispatcher tick that is running: a train's first attempt is always made on a
	 * tick, so the first refusal of a run is too.
	 */
	private class FirstBlockedRecorder(
		private val delegate: PathReservationService
	) : PathReservationService by delegate {
		var tickTime: Double = 0.0
		var firstBlockedTime: Double? = null
			private set

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): ReservationResult {
			val result = delegate.reservePath(trainId, start, target, maxDepth)
			val waits =
				result !is ReservationResult.Success &&
					result !is ReservationResult.NoPathExists &&
					result !is ReservationResult.GeometricallyImpossible
			if (waits && firstBlockedTime == null) {
				firstBlockedTime = tickTime
			}
			return result
		}
	}

	/**
	 * A [MultiTrainLoop] that, at the top of each dispatcher tick, hands the tick time to the
	 * [FirstBlockedRecorder] and records the first tick at which a generated train is held back by
	 * the concurrency cap (the cap is full of trains still running and a generated train is not yet
	 * admitted). Observation only: the tick itself is the unchanged [MultiTrainLoop.iteration].
	 */
	private class ProbedMultiTrainLoop(
		context: SimulationContext,
		endTime: Long,
		specs: List<MultiTrainLoop.TrainSpec>,
		private val cap: Int,
		maxEntryAttempts: Int,
		private val recorder: FirstBlockedRecorder
	) : MultiTrainLoop(
			context,
			endTime = endTime,
			trainSpecs = specs,
			maxConcurrentTrains = cap,
			pathReservationService = recorder,
			maxEntryAttempts = maxEntryAttempts
		) {
		var firstCapHoldTime: Double? = null
			private set

		/** kDisco's `dtMax` as the last tick saw it; it can only be read inside the simulation. */
		var observedDtMax: Double = Double.NaN
			private set

		override suspend fun iteration() {
			recorder.tickTime = time()
			observedDtMax = dtMax
			if (firstCapHoldTime == null) {
				val approved = getApprovedTrains()
				val notYetAdmitted = getTrainsEntered() - getTrainsExited() - getEntryFailures().size - approved.size
				if (notYetAdmitted > 0 && approved.count { !it.terminated() } >= cap) {
					firstCapHoldTime = time()
				}
			}
			super.iteration()
		}
	}
}

/** Why a run missed the functional exit assertions, as the calibration classifies it (Issue #1346). */
internal enum class ExitFailureCause {
	/** The run met every functional assertion. */
	NONE,

	/** The deadlock watchdog aborted the run (`MultiTrainLoop.stall`, Issue #1154). */
	WATCHDOG_FIRED,

	/** A train used up its entry attempts ([MultiTrainLoop.EntryFailureKind.ATTEMPTS_EXHAUSTED]). */
	ENTRY_ATTEMPTS_USED_UP,

	/** The run ended with generated trains still held back by the concurrency cap. */
	MAX_CONCURRENCY_REACHED,

	/** Any other miss, for example trains still waiting for a release at run end. */
	OTHER
}

/**
 * The observed result of one [LowTrafficExitScenario.runScenario] run.
 *
 * @property stallSimTime simulation time of the deadlock watchdog's report, or `null` when none fired
 * @property heldByCapAtEnd generated trains never admitted by the concurrency cap at run end
 * @property firstBlockedSimTime simulation time of the first entry refusal that made a train wait,
 *   or `null` when no entry was ever refused ("blocking begins at")
 * @property firstCapHoldSimTime simulation time of the first tick at which the concurrency cap held
 *   a generated train back, or `null` when it never did
 * @property reservationExpiries `ReservationExpired` events seen during the run
 * @property dtMax kDisco's effective `dtMax` at the run's last dispatcher tick
 * @property wallMs wall time of `ctx.run()` in milliseconds (a measurement, never asserted)
 */
internal data class MultiTrainRun(
	val label: String,
	val specs: List<MultiTrainLoop.TrainSpec>,
	val maxConcurrentTrainsCap: Int,
	val entered: Int,
	val exited: Int,
	val maxConcurrent: Int,
	val occupied: Int,
	val entryFailures: List<MultiTrainLoop.EntryFailure>,
	val stallReport: String?,
	val stallSimTime: Double?,
	val waitingAtEnd: List<String>,
	val heldByCapAtEnd: Int,
	val firstBlockedSimTime: Double?,
	val firstCapHoldSimTime: Double?,
	val reservationExpiries: Int,
	val dtMax: Double,
	val wallMs: Long
) {
	/**
	 * Effective mean entry gap of [specs] in simulated seconds: the mean gap between consecutive
	 * injections, `(last - first) / (n - 1)`; `0.0` for fewer than two specs. For the low-traffic
	 * arrivals it is the realized sample mean, not the distribution parameter: the minimum gaps and
	 * the whole-second rounding only ever move an injection later, but the mean of the 16 to 28
	 * exponential gaps of one seed still scatters around [LowTrafficExitScenario.MEAN_INTER_ARRIVAL_SECONDS].
	 */
	val meanEntryGapSeconds: Double
		get() {
			if (specs.size < 2) return 0.0
			val times = specs.map { it.inTime }
			return (times.max() - times.min()) / (specs.size - 1)
		}

	/** Simulation time of the first recorded [MultiTrainLoop.EntryFailure], or `null` when none. */
	val firstEntryFailureSimTime: Double?
		get() = entryFailures.firstOrNull()?.simTime

	/** Whether the run met every functional assertion of the exit gate. */
	val meetsFunctionalAssertions: Boolean
		get() =
			entered == specs.size && exited == specs.size && occupied == 0 && maxConcurrent <= maxConcurrentTrainsCap

	/** The classified cause of a functional miss, [ExitFailureCause.NONE] for a passing run. */
	val failureCause: ExitFailureCause
		get() =
			when {
				meetsFunctionalAssertions -> ExitFailureCause.NONE
				stallReport != null -> ExitFailureCause.WATCHDOG_FIRED
				entryFailures.any { it.kind == MultiTrainLoop.EntryFailureKind.ATTEMPTS_EXHAUSTED } ->
					ExitFailureCause.ENTRY_ATTEMPTS_USED_UP
				heldByCapAtEnd > 0 -> ExitFailureCause.MAX_CONCURRENCY_REACHED
				else -> ExitFailureCause.OTHER
			}

	/**
	 * One log line with the counts, the mean entry gap, the blocking, entry-failure and stall times,
	 * and the wall time of this run.
	 */
	fun summary(): String =
		"Exit calibration $label: specs=${specs.size}, meanEntryGap=${meanEntryGapSeconds.roundedToTenths()}s, " +
			"entered=$entered, exited=$exited, " +
			"maxConcurrent=$maxConcurrent (cap $maxConcurrentTrainsCap), occupied=$occupied, " +
			"entryFailures=${entryFailures.size}, cause=$failureCause, " +
			"blockingBeginsAt=${firstBlockedSimTime ?: "never"}, capHoldBeginsAt=${firstCapHoldSimTime ?: "never"}, " +
			"firstEntryFailureAt=${firstEntryFailureSimTime ?: "none"}, stallAt=${stallSimTime ?: "none"}, " +
			"reservationExpiries=$reservationExpiries, dtMax=$dtMax, wall=${wallMs}ms"

	private fun Double.roundedToTenths(): Double = kotlin.math.round(this * TENTHS) / TENTHS

	private companion object {
		const val TENTHS: Double = 10.0
	}
}
