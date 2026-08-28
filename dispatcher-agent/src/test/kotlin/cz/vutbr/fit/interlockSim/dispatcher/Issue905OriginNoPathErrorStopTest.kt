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
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Behavioral test for Issue #905 AC2: a train driven into `NoTopologicalPath` at its **origin**
 * `DynamicInOut` must, after the bounded retry cap ([`Train.MAX_ORIGIN_NO_PATH_RETRIES`]), call
 * `env.errorStop(...)` with a message naming the misconfigured InOut — rather than looping
 * silently forever or abandoning the journey.
 *
 * ## How it pins the `errorStop` contract (not a no-op)
 *
 * The [RouteHidingNavigationService] is wired to return [PathResult.NoTopologicalPath] for every
 * `findReservedPathForTrain` call, while the admission gate (`isPathReservedForTrain`) still
 * delegates to the real service — so the train is genuinely admitted and its `Front` then sees a
 * permanent dead-end at the entry InOut.  [NavigationDecoratingContext] captures every `env.errorStop`
 * invocation via its `onErrorStop` callback (and still forwards it so the simulation shuts
 * down).  After `context.run()` returns the test asserts the captured [Throwable]:
 *
 * 1. is non-null — the `errorStop` call actually fired;
 * 2. its message contains `"No topological path from origin InOut"` — the AC2 wording; and
 * 3. its message contains the name of one of the network's InOuts — the misconfigured origin is
 *    named, not elided.
 *
 * If the `env.errorStop` call were removed from `Train.Front.actions()` (leaving the `return`, or
 * the former `break`), the `Front` would terminate without ever calling `errorStop`, so the
 * captured throwable would stay `null` and assertion (1) fails.  The test therefore genuinely
 * pins the contract rather than passing vacuously.
 *
 * ## Reachability
 *
 * As with [EntryRouteLossAccountingTest], this state is not reachable by cancelling a route from
 * outside on current wiring (every releaser runs synchronously on the single kDisco sim thread
 * before the `Front`'s first query), so the injected [RouteHidingNavigationService] produces it
 * directly instead of asserting a race that does not exist.
 *
 * @since Issue #905 (AC2)
 */
@DisplayName("Issue #905 AC2: origin NoTopologicalPath → bounded retries → env.errorStop")
@Tag("integration-test")
class Issue905OriginNoPathErrorStopTest : DispatcherKoinTestBase() {
	private val fixture = LiftedStackFixture()

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	@DisplayName("origin NoTopologicalPath fires env.errorStop after bounded retries, naming the InOut")
	fun originNoTopologicalPathFiresErrorStopAfterRetries() {
		val context = fixture.loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()
		val inOutNames = context.getInOuts().map { it.name }

		val capturedError = arrayOfNulls<Throwable>(1)
		val hidingContext =
			NavigationDecoratingContext(
				context,
				RouteHidingNavigationService(
					delegate = context.getRoutingServices().getTrainNavigationService(),
					result = PathResult.NoTopologicalPath
				),
				onErrorStop = { capturedError[0] = it }
			)

		// The loop is handed the route-hiding view so the trains its generator builds resolve
		// their navigation service (and errorStop) through it; the run itself is driven by the
		// real context.
		val loop = ShuntingLoop(hidingContext, endTime = END_TIME)

		// Admit exactly one train so a single Front drives the AC2 branch; keep the rest of the
		// network quiet.  The bounded retry is 5 attempts with a 5s hold between each (≈20 sim
		// seconds to the final attempt that triggers errorStop), so END_TIME is sized to allow
		// the full retry sequence plus admission/control-tick slack.
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

		// The scenario engaged: a train was admitted.
		assertThat(admittedOne, name = "a train was admitted").isTrue()

		// AC2 contract: env.errorStop actually fired.
		val error = capturedError[0]
		assertThat(error, name = "captured errorStop throwable").isNotNull()
		val message = error!!.message ?: ""
		assertThat(message, name = "errorStop message").contains("No topological path from origin InOut")
		// The misconfigured origin InOut is named (quoted) in the message — not elided.  The
		// message format is "...origin InOut 'NAME' after N retries...", so one of the network's
		// InOut names must appear quoted.
		val quotedNames = inOutNames.map { "'$it'" }
		val namesOrigin = quotedNames.any { message.contains(it) }
		assertThat(namesOrigin, name = "errorStop message names a quoted InOut").isTrue()
	}

	private companion object {
		/**
		 * Long enough for one train to be queued, admitted, and for its `Front` to exhaust the
		 * bounded retry cap (5 attempts × 5s hold ≈ 20 sim seconds) and call `env.errorStop`.
		 * `errorStop` stops the sim immediately, so in the passing case `run()` returns well
		 * before this; the margin is for admission and control-tick scheduling slack only.
		 */
		const val END_TIME: Long = 60L
	}
}
