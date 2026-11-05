/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Reproducer of the dtMax = 1e-1 "stall" (Goal 1B SP13, Issue #1160)
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.dtMax
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.decoratingTrainNavigationService
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * Reproducer of the `dtMax` = 1e-1 "stall" of the Issue #760 ladder (Goal 1B SP13, Issue #1160).
 *
 * ## What failed
 *
 * PR #1133 walked `dtMax` up to 1e-1 and saw `:dispatcher-agent`'s
 * `OwnershipConflictStallWarningTest.stallWarnsTwiceAfterResolveAndRestall` fail 2 of 2: no
 * `errorStop` for the stalled train within the 400 s run. A train is held in an ownership-conflict
 * wait until its WARN, then freed, then held again at the next separator; its second wait must
 * reach the 180 s `errorStop` horizon before the run ends.
 *
 * ## Cause (recorded in `docs/goal9b-demands/SP13-dtmax.md`)
 *
 * It was not a deadlock. The freed train leaves a stand at `zB` and crawls the 5 m to `vB`, where
 * it is held again. Before PR #1146 (Issue #1126) the velocity integration read the step-start
 * acceleration, so at 0.1 s steps the run-up of that short leg came out slow: the crawl took
 * 19.5–36.5 s instead of about 7 s (measured with this reproducer on `4683bee7`, the parent of
 * PR #1146). The second hold therefore began 30 s later, its 180 s horizon moved past that of
 * another train queued behind it, and the run stopped on the other train's `errorStop` first.
 * Since PR #1146 every RK stage uses its own acceleration and the crawl is the same at 1e-1 and
 * at the global step.
 *
 * ## How the step is set
 *
 * A wrapper main process starts the loop and, in its own `startAction`, sets `dtMax` half a second
 * later: the loop's generator sets the global value when it starts at t = 0, and the first train
 * enters only at about 10 s. kDisco keeps `dtMax` per simulation context, so the value cannot leak
 * into the next run; the second run of each comparison proves it by observing the global value.
 *
 * Bounded by the simulated `endTime` ([END_TIME]); the `@Timeout` is only a safety ceiling.
 */
@DisplayName("SP13: the dtMax 1e-1 resolve-and-restall stall does not reproduce")
class DtMaxTenthStallReproducerTest : KoinTestBase() {
	/**
	 * The freed train crawls from its first hold to its second as fast at 1e-1 as at the global
	 * step, so its second hold reaches its `errorStop` before the run ends.
	 *
	 * Measured on this branch: crawl 7.66 s at 1e-1 and 7.22 s at 1e-3. On `4683bee7` (before
	 * PR #1146): 36.5 s at 1e-1, so this test is red there.
	 */
	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `a freed train crawls to its next hold as fast at a tenth of a second as at the global step`() {
		val tenth = runResolveAndRestall(TENTH_OF_A_SECOND)
		val global = runResolveAndRestall(null)

		logger.info { "SP13 reproducer: 1e-1 $tenth; global $global" }

		assertThat(tenth.observedDtMax, name = "dtMax seen in the 1e-1 run").isEqualTo(TENTH_OF_A_SECOND)
		// The next run (a fresh context) is back at the global step: the override did not leak.
		assertThat(global.observedDtMax, name = "dtMax seen in the next run").isEqualTo(Generator.DT_MAX)
		assertThat(tenth.crawlSeconds, name = "crawl at 1e-1 (s)").isNotNull()
		assertThat(global.crawlSeconds, name = "crawl at the global step (s)").isNotNull()
		assertThat(abs(tenth.crawlSeconds!! - global.crawlSeconds!!), name = "crawl difference (s)")
			.isLessThanOrEqualTo(CRAWL_TOLERANCE_SECONDS)
		assertThat(tenth.firstErrorStopNamesTheHeldTrain, name = "1e-1 run stopped on the held train").isTrue()
	}

	/**
	 * Both scenario loops run at the one global step: `MultiTrainLoop` (the Goal 1B exit harness)
	 * and `ShuntingLoop` get it from their generators, with no override of their own.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `MultiTrainLoop and ShuntingLoop run at the global dtMax`() {
		val multiContext = loadVyhybnaContext()
		val multi = DtMaxRecordingMultiTrainLoop(multiContext)
		multiContext.setMainProcess(multi)
		multiContext.run()

		val shuntingContext = loadVyhybnaContext()
		val shuntingLoop = prepareShuntingLoop(shuntingContext, SHORT_END_TIME)
		val shunting = StepOverride(shuntingLoop, null)
		shuntingContext.setMainProcess(shunting)
		shuntingContext.run()

		assertThat(multi.observedDtMax, name = "MultiTrainLoop dtMax").isEqualTo(Generator.DT_MAX)
		assertThat(shunting.observedDtMax, name = "ShuntingLoop dtMax").isEqualTo(Generator.DT_MAX)
	}

	/** A train-less [MultiTrainLoop] that records kDisco's `dtMax` at every tick. */
	private class DtMaxRecordingMultiTrainLoop(
		context: SimulationContext
	) : MultiTrainLoop(context, SHORT_END_TIME) {
		var observedDtMax: Double = Double.NaN
			private set

		override suspend fun iteration() {
			observedDtMax = dtMax
			super.iteration()
		}
	}

	/** What one resolve-and-restall run observed. */
	private data class Outcome(
		val observedDtMax: Double,
		val crawlSeconds: Double?,
		val firstErrorStopNamesTheHeldTrain: Boolean,
		val firstErrorStop: String?
	)

	/**
	 * Runs the resolve-and-restall scenario of `OwnershipConflictStallWarningTest` on vyhybna, with
	 * `dtMax` set to [stepOverride] or, when `null`, left at the generator's global value.
	 */
	private fun runResolveAndRestall(stepOverride: Double?): Outcome {
		val context = loadVyhybnaContext()
		val hold = ResolveAndRestall(context.getRoutingServices().getTrainNavigationService())
		val errorStops = CopyOnWriteArrayList<Throwable>()
		val env = NavigationDecoratingContext(context, hold.navigation) { errorStops.add(it) }
		val loop = prepareShuntingLoop(context, END_TIME, env)
		val main = StepOverride(loop, stepOverride)
		context.setMainProcess(main)
		context.run()

		val heldTrain = hold.heldTrainId
		val first = errorStops.firstOrNull()?.message
		return Outcome(
			observedDtMax = main.observedDtMax,
			crawlSeconds = hold.secondHoldAt?.let { second -> hold.freedAt?.let { second - it } },
			firstErrorStopNamesTheHeldTrain =
				heldTrain != null && first != null && first.contains(ROUTE_EXTENSION_FRAGMENT) && first.contains("$heldTrain:"),
			firstErrorStop = first
		)
	}

	/**
	 * The main process: starts [loop], then sets `dtMax` to [step] (when given) in its own
	 * `startAction`, after the generator has set the global value at t = 0.
	 */
	private class StepOverride(
		private val loop: LoopProcess,
		private val step: Double?
	) : LoopProcess() {
		var observedDtMax: Double = Double.NaN
			private set

		override suspend fun startAction() {
			Process.activate(loop)
			hold(OVERRIDE_DELAY_SECONDS)
			if (step != null) dtMax = step
		}

		override suspend fun iteration() {
			observedDtMax = dtMax
		}
	}

	/**
	 * Holds the first train under way in an ownership conflict for the WARN horizon, frees it at
	 * that separator, and holds it again at the next one: the three phases of
	 * `OwnershipConflictStallWarningTest.stallWarnsTwiceAfterResolveAndRestall`, switched by
	 * simulated time instead of by the WARN log line (`:core` tests have no logback appender).
	 * Called on the simulation thread only.
	 */
	private class ResolveAndRestall(
		private val realNav: TrainNavigationService
	) {
		var heldTrainId: String? = null
			private set
		var freedAt: Double? = null
			private set
		var secondHoldAt: Double? = null
			private set
		private var firstSeparator: PathSeparator? = null
		private var firstHoldAt = 0.0

		val navigation: TrainNavigationService =
			decoratingTrainNavigationService(realNav) { trainId, separator -> answer(trainId, separator) }

		private fun answer(
			trainId: String,
			separator: PathSeparator
		): PathResult {
			val now = Process.time()
			if (separator !is DynamicInOut && heldTrainId == null) {
				heldTrainId = trainId
				firstSeparator = separator
				firstHoldAt = now
			}
			return when {
				trainId != heldTrainId || separator is DynamicInOut -> realNav.findReservedPathForTrain(trainId, separator)
				now < firstHoldAt + Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS -> PathResult.OwnershipConflict
				separator === firstSeparator -> {
					if (freedAt == null) freedAt = now
					realNav.findReservedPathForTrain(trainId, separator)
				}
				else -> {
					if (secondHoldAt == null) secondHoldAt = now
					PathResult.OwnershipConflict
				}
			}
		}
	}

	private companion object {
		/** The rung of the Issue #760 ladder that failed in PR #1133. */
		const val TENTH_OF_A_SECOND = 1e-1

		/** As in `OwnershipConflictStallWarningTest`: room for both holds and the 180 s horizon. */
		const val END_TIME = 400L

		/** Long enough for a few ticks after the generator has started. */
		const val SHORT_END_TIME = 5L

		/** After the generator's start at t = 0, before the first train enters (about 10 s). */
		const val OVERRIDE_DELAY_SECONDS = 0.5

		/** Crawl at 1e-1 vs the global step: 0.44 s measured; before PR #1146 about 29 s. */
		const val CRAWL_TOLERANCE_SECONDS = 1.0

		/** Distinctive fragment of the mid-journey stall `errorStop` message. */
		const val ROUTE_EXTENSION_FRAGMENT = "no route extension"
	}
}
