/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Regression test for the origin NoTopologicalPath bound (Issue #905 AC2)
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
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
 * Core-level regression test for Issue #905 AC2: a train driven into `NoTopologicalPath` at its
 * **origin** `DynamicInOut` must, after the bounded retry cap ([`Train.MAX_ORIGIN_NO_PATH_RETRIES`]),
 * call `env.errorStop(...)` naming the misconfigured InOut — rather than looping silently forever.
 *
 * ## Why a second test exists alongside `Issue905OriginNoPathErrorStopTest` (dispatcher-agent)
 *
 * The dispatcher-agent test pins the same AC2 contract end-to-end through the lifted stack, but its
 * coverage is attributed to `:dispatcher-agent`'s JaCoCo report, whose `classDirectories` are
 * scoped to dispatcher-agent's own classes. `Train.Site.holdOrStopAtOriginWithoutPath` lives in
 * `:core`, so from Sonar's perspective (which reads only `:core`'s JaCoCo report for `Train.kt`)
 * those new production lines were uncovered — the `atOrigin` branches of `waitForPathOrReportStall`
 * and the whole `holdOrStopAtOriginWithoutPath` body showed as uncovered, dropping PR #945's
 * new-code coverage to 71.6 % under the 80 % gate. This test drives the bound from within
 * `:core:jvmTest` so the coverage lands where Sonar looks. It mirrors how
 * [MidJourneyNoPathErrorStopRegressionTest] anchors the mid-journey `NoTopologicalPath` bound in
 * core alongside its own dispatcher-agent counterpart, and how
 * [Issue943OwnershipConflictStallBoundTest] anchors the OwnershipConflict wait bound.
 *
 * ## The defect this pins
 *
 * Before Issue #905, `Train.Front.actions()` hit `if (where is DynamicInOut) break` at the origin
 * when no path existed, silently abandoning the journey. The fix removed that `break` and routed the
 * origin `NoTopologicalPath` case through [Train.Site.holdOrStopAtOriginWithoutPath]: a bounded retry
 * ([Train.MAX_ORIGIN_NO_PATH_RETRIES] attempts with a 5 s hold between each), then `env.errorStop`
 * with a message naming the misconfigured origin InOut.
 *
 * ## How the scenario is produced
 *
 * [originHidingNav] delegates `isPathReservedForTrain` to the real service (so the train is genuinely
 * admitted) but returns [PathResult.NoTopologicalPath] for every `findReservedPathForTrain` call at a
 * `DynamicInOut` — i.e. the train's `Front` sees a permanent dead-end at its entry InOut. The
 * `errorStop` call is captured through [NavigationDecoratingContext].
 *
 * If the `env.errorStop` call were removed from `holdOrStopAtOriginWithoutPath` (leaving the `return`
 * or the former `break`), the `Front` would never call `errorStop`, the captured list would stay
 * empty, and the assertion would fail — so this cannot pass vacuously.
 *
 * @since Issue #905 (AC2); core coverage anchor added for Issue #943's Sonar gate.
 */
@DisplayName("Issue #905 AC2: origin NoTopologicalPath → bounded retries → env.errorStop (core coverage anchor)")
@Tag("integration-test")
class Issue905OriginNoPathErrorStopRegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	/**
	 * Verifies that [Train.MAX_ORIGIN_NO_PATH_RETRIES] is defined and positive — the bounded-retry
	 * policy for origin `NoTopologicalPath` that this test exercises.
	 */
	@Test
	fun `MAX_ORIGIN_NO_PATH_RETRIES is defined and positive`() {
		assertThat(Train.MAX_ORIGIN_NO_PATH_RETRIES).isGreaterThan(0)
	}

	/**
	 * A train whose origin InOut has no topological continuation stops the run through
	 * `env.errorStop` after the bounded retry count, instead of polling silently to the end time.
	 *
	 * Asserts:
	 * 1. A train was admitted (so the scenario is genuinely at the entry InOut, not a no-op).
	 * 2. `env.errorStop` fired with the origin wording `"No topological path from origin InOut"` —
	 *    distinguishing it from the mid-journey bound's `"navigation reports no usable path"`.
	 * 3. The misconfigured origin InOut is named (quoted) in the message, not elided.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `origin NoTopologicalPath fires errorStop after the bounded retries`() {
		val context = loadVyhybnaContext()
		val inOutNames = context.getInOuts().map { it.name }
		assertThat(inOutNames).isNotEmpty()

		val realNav = context.getRoutingServices().getTrainNavigationService()

		// Report NoTopologicalPath at every entry InOut (origin), delegate every other query and the
		// admission gate to the real service — so the train is admitted and its Front then sees a
		// permanent dead-end at the entry InOut. holdOrStopAtOriginWithoutPath retries 5× (5 s hold
		// each) then calls env.errorStop.
		val hidingNav =
			object : TrainNavigationService {
				override fun findReservedPathForTrain(
					trainId: String,
					separator: PathSeparator
				): PathResult =
					if (separator is DynamicInOut) {
						PathResult.NoTopologicalPath
					} else {
						realNav.findReservedPathForTrain(trainId, separator)
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

		val capturedErrors = CopyOnWriteArrayList<Throwable>()
		val hidingContext = NavigationDecoratingContext(context, hidingNav) { capturedErrors.add(it) }

		val loop = ShuntingLoop(hidingContext, END_TIME)
		wireSynchronousDispatcher(hidingContext, loop)
		context.setMainProcess(loop)
		context.run()

		logger.info {
			"origin bound test complete: trainsEntered=${loop.getTrainsEntered()}, " +
				"trainsExited=${loop.getTrainsExited()}, errorStops=${capturedErrors.size}"
		}

		// (1) The scenario engaged at origin: a train was admitted.
		assertThat(loop.getTrainsEntered()).isGreaterThan(0)

		// (2) The origin bound fired with the origin wording, not the mid-journey wording.
		val originError =
			capturedErrors.firstOrNull { it.message?.contains(ORIGIN_NO_PATH_FRAGMENT) == true }
		assertThat(originError, name = "captured origin errorStop throwable").isNotNull()
		val message = originError!!.message ?: ""
		assertThat(message, name = "origin errorStop message").contains(ORIGIN_NO_PATH_FRAGMENT)

		// (3) The misconfigured origin InOut is named (quoted) in the message — not elided.
		val quotedNames = inOutNames.map { "'$it'" }
		assertThat(
			quotedNames.any { message.contains(it) },
			name = "errorStop message names a quoted InOut"
		).isTrue()
	}

	private companion object {
		/**
		 * Long enough for one train to be queued, admitted, and for its `Front` to exhaust the
		 * bounded retry cap (5 attempts × 5 s hold ≈ 20 sim seconds) and call `env.errorStop`.
		 * `errorStop` stops the sim immediately, so in the passing case `run()` returns well before
		 * this; the margin is for admission and control-tick scheduling slack only.
		 */
		const val END_TIME: Long = 60L

		/** Distinctive fragment of the origin `NoTopologicalPath` `errorStop` message. */
		const val ORIGIN_NO_PATH_FRAGMENT = "No topological path from origin InOut"
	}
}
