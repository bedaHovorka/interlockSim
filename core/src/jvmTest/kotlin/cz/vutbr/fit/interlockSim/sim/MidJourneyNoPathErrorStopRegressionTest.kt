/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Regression test for the mid-journey NoTopologicalPath bound (PR #940)
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertCapturedErrorStop
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Core-level regression test for the mid-journey `NoTopologicalPath` bound introduced in PR #940
 * (`Train.MAX_MID_JOURNEY_NO_PATH_RETRIES` / `Train.Site.holdOrStopAfterNoUsablePath`).
 *
 * ## Why a second test exists alongside `MidJourneyNoPathErrorStopTest` (dispatcher-agent)
 *
 * The dispatcher-agent test pins the same behaviour end-to-end through the lifted stack, but its
 * coverage is attributed to `:dispatcher-agent`'s JaCoCo report, whose `classDirectories` are
 * scoped to dispatcher-agent's own classes. `Train.holdOrStopAfterNoUsablePath` lives in `:core`,
 * so from Sonar's perspective (which reads `:core`'s JaCoCo report for `Train.kt`) those ~30 new
 * production lines were uncovered — the PR's new-code coverage dropped to 55.6 % and the quality
 * gate failed. This test drives the bound from within `:core:jvmTest` so the coverage lands where
 * Sonar looks. It mirrors how [Issue905OriginAbandonRegressionTest] anchors the origin bound in
 * core alongside its own dispatcher-agent counterpart.
 *
 * ## The defect this pins
 *
 * Before the bound, a `NoTopologicalPath` returned **mid-journey** (the train has left its entry
 * `InOut` and is holding track) fell into an unbounded `hold(5.0)` + `continue` with no counter,
 * no `errorStop`, and nothing in the cycle that could change the outcome. A measured run logged
 * the same error 48 times to the end of the run while the train held its block against everything
 * behind it. The bound ends the run via `env.errorStop` after
 * [Train.MAX_MID_JOURNEY_NO_PATH_RETRIES] consecutive failures instead.
 *
 * ## How the scenario is produced
 *
 * [MidJourneyRouteHidingNavigationService] delegates while the train sits at its entry `InOut`
 * (so it is genuinely admitted and leaves the origin) and returns [PathResult.NoTopologicalPath]
 * for every query from a non-`DynamicInOut` separator — i.e. once the train is under way and
 * holding track, exactly where the production failure occurred. The `errorStop` call is captured
 * through the wrapper described below.
 *
 * If the bound were removed from `Train.Site.actions()`, no `errorStop` would fire and the
 * captured list would stay empty, failing the assertion — so this cannot pass vacuously.
 *
 * ## How the decorator is injected
 *
 * Through [NavigationDecoratingContext], a plain delegating wrapper. It replaced a MockK `spyk`
 * here (Issue #943): the spy worked, because this scenario's `errorStop` ends the run within ~60
 * simulated seconds, but MockK records every call made on a spy and a suspended train queries the
 * context after every event — so the pattern does not survive being copied into a longer-running
 * test. The wrapper carries the same guarantees with no recording; see its KDoc.
 */
@DisplayName("PR #940: mid-journey NoTopologicalPath → bounded retries → env.errorStop (core coverage anchor)")
@Tag("integration-test")
class MidJourneyNoPathErrorStopRegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

	/**
	 * Verifies that [Train.MAX_MID_JOURNEY_NO_PATH_RETRIES] is defined and positive — the
	 * bounded-retry policy for mid-journey `NoTopologicalPath`, the counterpart of
	 * [Train.MAX_ORIGIN_NO_PATH_RETRIES].
	 */
	@Test
	fun `MAX_MID_JOURNEY_NO_PATH_RETRIES is defined and positive`() {
		assertThat(Train.MAX_MID_JOURNEY_NO_PATH_RETRIES).isGreaterThan(0)
	}

	/**
	 * A train stranded mid-journey (holding track, navigation genuinely cannot serve it) stops the
	 * run through `env.errorStop` after the bounded retry count, instead of polling to the end time.
	 *
	 * Asserts:
	 * 1. A train was admitted and therefore left its origin `InOut` (the scenario is genuinely
	 *    mid-journey, not the origin case [Issue905OriginAbandonRegressionTest] already covers).
	 * 2. `env.errorStop` fired with the mid-journey wording — distinguishing it from the origin
	 *    bound's "No topological path from origin InOut" message.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `mid-journey NoTopologicalPath fires errorStop after the bounded retries`() {
		val context = loadVyhybnaContext()
		assertThat(context.getInOuts()).isNotEmpty()

		val realNav = context.getRoutingServices().getTrainNavigationService()

		// Delegate at the entry InOut (so the train is admitted and leaves the origin), then report
		// NoTopologicalPath from every non-DynamicInOut separator — mid-journey, holding track.
		val hidingNav =
			object : TrainNavigationService {
				override fun findReservedPathForTrain(
					trainId: String,
					separator: PathSeparator
				): PathResult =
					if (separator is DynamicInOut) {
						realNav.findReservedPathForTrain(trainId, separator)
					} else {
						PathResult.NoTopologicalPath
					}

				override fun isPathReservedForTrain(
					trainId: String,
					separator: PathSeparator
				): Boolean = realNav.isPathReservedForTrain(trainId, separator)

				override fun reservedSeparatorsAhead(
					trainId: String,
					separator: PathSeparator,
					limit: Int
				): List<OrientedPathSeparator> = realNav.reservedSeparatorsAhead(trainId, separator, limit)
			}

		// The wrapper both serves the hiding navigation service and captures every errorStop while
		// still forwarding it, so the simulation actually shuts down.
		val capturedErrors = CopyOnWriteArrayList<Throwable>()
		val hidingContext = NavigationDecoratingContext(context, hidingNav) { capturedErrors.add(it) }

		val loop = ShuntingLoop(hidingContext, END_TIME)
		wireSynchronousDispatcher(hidingContext, loop)
		context.setMainProcess(loop)
		context.run()

		logger.info {
			"mid-journey bound test complete: trainsEntered=${loop.getTrainsEntered()}, " +
				"trainsExited=${loop.getTrainsExited()}, errorStops=${capturedErrors.size}"
		}

		// (1) The scenario engaged mid-journey: a train was admitted and left its origin InOut.
		assertThat(loop.getTrainsEntered()).isGreaterThan(0)

		// (2) The bound fired rather than the train polling to END_TIME. The mid-journey wording
		//     distinguishes this from the origin bound's "No topological path from origin InOut".
		assertCapturedErrorStop(capturedErrors, "navigation reports no usable path")
	}

	private companion object {
		/**
		 * Ample room for admission, the train to reach its first semaphore, and the bounded retry
		 * sequence (10 attempts × 5 s hold ≈ 50 sim seconds) to complete. `errorStop` ends the run
		 * as soon as the bound trips, so a passing run returns well before this.
		 */
		const val END_TIME: Long = 200L
	}
}
