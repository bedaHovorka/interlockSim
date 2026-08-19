/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.TimeUnit

/**
 * A `NoTopologicalPath` reported **mid-journey** must end the run through
 * `env.errorStop`, not spin forever.
 *
 * ## The defect this pins
 *
 * `Train.Site.actions()` split its handling on `where is DynamicInOut && current == null`. Only
 * that origin case was bounded (Issue #905 AC2, [Issue905OriginNoPathErrorStopTest]); everything
 * else fell into an `else` arm that did `hold(5.0)` and `continue` with no counter, no
 * `errorStop`, no timeout, and nothing in the cycle that could change the outcome.
 *
 * A measured `shuntingLoopAI` run showed the cost: a train stopped at semaphore `zA` logged
 * `"No topological path exists ... train reached dead-end"` 48 times — every 5 s to the end of the
 * run — on `vyhybna.xml`, a passing loop with no dead end anywhere. It held block `kA` throughout,
 * so every train behind it was blocked too. That is a deadlock, and nothing in the simulation could
 * end it.
 *
 * ## Why bounding it is safe only now
 *
 * The same run recovered elsewhere after 10 retries, because the condition was being *misreported*:
 * a train merely waiting for the dispatcher to extend its route was classified as a permanent
 * topology failure. Bounding this branch while that misclassification stood would have killed runs
 * that were about to succeed. `DefaultTrainNavigationService` now reports that case as
 * [PathResult.OwnershipConflict] — which waits on `createPathAvailableCondition` and never reaches
 * here — so reaching this branch means navigation genuinely cannot serve the train.
 *
 * ## How the scenario is produced
 *
 * [RouteHidingNavigationService] hides routes unconditionally, which would fire the *origin* branch
 * before the train ever moves. This test's stub instead delegates while the train is still at its
 * entry `DynamicInOut` and only hides the route once it asks from a **semaphore** — putting it
 * exactly where the production failure occurred, mid-journey and holding track.
 *
 * If the bound were removed from `Train.Site.actions()`, no `errorStop` would fire, the captured
 * throwable would stay `null`, and assertion (2) fails — so this cannot pass vacuously.
 */
@DisplayName("mid-journey NoTopologicalPath → bounded retries → env.errorStop")
@Tag("integration-test")
class MidJourneyNoPathErrorStopTest {
	private val fixture = LiftedStackFixture()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	/**
	 * Serves the real navigation result while the train sits at its entry `InOut`, then reports
	 * [PathResult.NoTopologicalPath] for every query from anywhere else — i.e. once the train is
	 * under way and holding track.
	 */
	private class MidJourneyRouteHidingNavigationService(
		private val delegate: TrainNavigationService
	) : TrainNavigationService by delegate {
		override fun findReservedPathForTrain(
			trainId: String,
			separator: PathSeparator
		): PathResult =
			if (separator is DynamicInOut) {
				delegate.findReservedPathForTrain(trainId, separator)
			} else {
				PathResult.NoTopologicalPath
			}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("a train stranded mid-journey stops the run instead of looping to the end time")
	fun midJourneyNoTopologicalPathFiresErrorStopAfterRetries() {
		val context = fixture.loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val capturedError = arrayOfNulls<Throwable>(1)
		val hidingContext =
			RouteHidingContext(
				context,
				MidJourneyRouteHidingNavigationService(
					delegate = context.getRoutingServices().getTrainNavigationService()
				),
				onErrorStop = { capturedError[0] = it }
			)

		val loop = ShuntingLoop(hidingContext, endTime = END_TIME)

		var admittedOne = false
		loop.controlStepListener =
			ControlStepListener {
				if (!admittedOne) {
					loop.getQueuedTrains().firstOrNull()?.let {
						loop.approveQueuedTrain(it.trainId)
						admittedOne = true
					}
				}
			}

		context.setMainProcess(loop)
		context.run()

		// (1) The scenario engaged: a train was admitted and therefore left its InOut.
		assertThat(admittedOne, name = "a train was admitted").isTrue()

		// (2) The bound fired rather than the train polling to END_TIME.
		val error = capturedError[0]
		assertThat(error, name = "captured errorStop throwable").isNotNull()
		assertThat(error!!.message ?: "", name = "errorStop message")
			.contains("navigation reports no usable path")
	}

	private companion object {
		/**
		 * Ample room for admission, the train to reach its first semaphore, and the bounded retry
		 * sequence (10 attempts × 5 s hold ≈ 50 sim seconds) to complete. `errorStop` ends the run
		 * as soon as the bound trips, so a passing run returns well before this; the margin exists
		 * so a *failing* run is distinguishable from a slow one rather than timing out ambiguously.
		 */
		const val END_TIME: Long = 400L
	}
}
