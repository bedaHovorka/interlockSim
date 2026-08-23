/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Regression test for the OwnershipConflict wait horizon (Issue #943)
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
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
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Regression test for Issue #943: a train the dispatcher never extends must not wait silently
 * forever on `waitUntil(env.createPathAvailableCondition(...))`.
 *
 * ## The defect this pins
 *
 * PR #940 reclassified "train waiting for its route to be extended" from
 * [PathResult.NoTopologicalPath] (a misreport) to [PathResult.OwnershipConflict], which routes the
 * train onto the event-driven wait in `Train.Site.actions()` instead of the polled
 * `hold(5.0)` + `continue` loop. That is correct while the dispatcher eventually extends the
 * route. When it never does — a rear-facing route terminus, the measured Issue #944 shape — the
 * wait had no horizon, no counter and no log: the train held its reserved block against every
 * train behind it until `END_TIME`, with nothing in the logs or metrics distinguishing the
 * livelock from a healthy momentary wait.
 *
 * The bound added for #943 gives that wait two horizons: a one-shot WARN at
 * [Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS], then [SimulationEnvironment.errorStop] at
 * [Train.OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS]. This test drives the second one; the WARN
 * itself is asserted in `:dispatcher-agent`'s `OwnershipConflictStallWarningTest`, whose test
 * source set has logback on the compile classpath (`:core`'s has it only as `runtimeOnly`).
 *
 * ## How the scenario is produced
 *
 * The stalling `TrainNavigationService` decorator picks the first train to query from a
 * non-`DynamicInOut` separator — i.e. the first train that is under way and holding track — and
 * returns [PathResult.OwnershipConflict] for that one train from then on. Every other train, and
 * that train's own queries at its entry `InOut`, get the real service, so the layout keeps
 * working and simulated time keeps advancing around the stalled train. The decorator is injected
 * through [NavigationDecoratingContext], which also overrides `createPathAvailableCondition` so the
 * stalled train's wait condition consults the decorated service and never becomes true — the
 * "dispatcher never extends" case.
 *
 * Without the bound no `errorStop` fires and the captured list stays empty, so this cannot pass
 * vacuously.
 *
 * ## Why a hand-written wrapper and not MockK `spyk`
 *
 * This scenario keeps a train suspended for a few hundred simulated seconds, and a suspended train
 * queries the context after every event and every integration step. A `spyk` records every one of
 * those calls, which exhausted a 512 MB test worker before the horizon could fire. See
 * [NavigationDecoratingContext] for the measurement and the full argument.
 */
@DisplayName("Issue #943: never-extended OwnershipConflict wait → bounded → env.errorStop")
@Tag("integration-test")
class Issue943OwnershipConflictStallBoundTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	/**
	 * Pins the escalation invariant the two-stage design rests on:
	 *
	 * ```
	 * 0 < OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS < OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS
	 * ```
	 *
	 * Reverse the two and the `errorStop` fires before the WARN can ever be emitted: the run still
	 * stops, the test suite still passes, and the diagnostic Issue #943 was filed for silently
	 * disappears. That is why the ordering is asserted here rather than left to the constants.
	 */
	@Test
	fun `ownership-conflict horizons are positive and the WARN precedes the errorStop`() {
		assertThat(Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS, name = "WARN horizon").isGreaterThan(0.0)
		assertThat(Train.OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS, name = "errorStop horizon")
			.isGreaterThan(Train.OWNERSHIP_CONFLICT_WARN_HORIZON_SECONDS)
	}

	/**
	 * A train whose route the dispatcher never extends stops the run through `env.errorStop`
	 * after the wait horizon, instead of holding its block silently to `END_TIME`.
	 *
	 * Asserts:
	 * 1. A train was admitted and therefore left its origin `InOut` — the stall is genuinely
	 *    mid-journey (the train holds track), not the origin case.
	 * 2. `env.errorStop` fired with the route-extension wording, distinguishing it from the
	 *    mid-journey `NoTopologicalPath` bound ("navigation reports no usable path") and from the
	 *    origin bound ("No topological path from origin InOut").
	 */
	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	fun `never-extended route fires errorStop after the wait horizon`() {
		val context = loadVyhybnaContext()
		assertThat(context.getInOuts()).isNotEmpty()

		val realNav = context.getRoutingServices().getTrainNavigationService()

		// Stall exactly ONE train, the first to query from a non-InOut separator — i.e. the first
		// train that is under way and holding track. Every other train keeps the real service, so
		// the rest of the layout goes on working and simulated time keeps advancing around the
		// stalled one. That is the faithful shape of #943: one train's route terminus is
		// rear-facing and never extended, while the dispatcher serves everyone else normally.
		val stalledTrainId = AtomicReference<String?>(null)
		val stallingNav =
			object : TrainNavigationService {
				override fun findReservedPathForTrain(
					trainId: String,
					separator: PathSeparator
				): PathResult {
					if (separator !is DynamicInOut) {
						stalledTrainId.compareAndSet(null, trainId)
					}
					return if (trainId == stalledTrainId.get() && separator !is DynamicInOut) {
						PathResult.OwnershipConflict
					} else {
						realNav.findReservedPathForTrain(trainId, separator)
					}
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
		val stallingContext = NavigationDecoratingContext(context, stallingNav) { capturedErrors.add(it) }

		val loop = ShuntingLoop(stallingContext, END_TIME)
		wireSynchronousDispatcher(stallingContext, loop)
		context.setMainProcess(loop)
		context.run()

		logger.info {
			"Issue943 bound test complete: trainsEntered=${loop.getTrainsEntered()}, " +
				"trainsExited=${loop.getTrainsExited()}, errorStops=${capturedErrors.size}"
		}

		// (1) The stall is mid-journey: a train was admitted and left its origin InOut.
		assertThat(loop.getTrainsEntered()).isGreaterThan(0)

		// (2) The horizon fired rather than the train waiting silently to END_TIME.
		val stallError =
			capturedErrors.firstOrNull { it.message?.contains(ROUTE_EXTENSION_FRAGMENT) == true }
		assertThat(stallError, name = "captured route-extension errorStop throwable").isNotNull()
		assertThat(stallError!!.message ?: "", name = "errorStop message")
			.contains(ROUTE_EXTENSION_FRAGMENT)
	}

	/**
	 * The origin counterpart of [`never-extended route fires errorStop after the wait horizon`]:
	 * a train the dispatcher never admits — it stalls at its **entry** `InOut` (`current == null`,
	 * the `atOrigin == true` branch of `waitForPathOrReportStall`) — stops the run through
	 * `env.errorStop` with the origin wording, instead of waiting silently to `END_TIME`.
	 *
	 * ## Why a second :core test (coverage anchor)
	 *
	 * The `atOrigin == true` branches of `waitForPathOrReportStall` (the WARN at `Train.kt:290-292`
	 * and the `errorStop` at `303-304`) are exercised at runtime by `:dispatcher-agent`'s
	 * `OwnershipConflictStallWarningTest.originStallWarningNamesTheTrainOnce`, but that coverage is
	 * attributed to `:dispatcher-agent`'s JaCoCo report, whose `classDirectories` are scoped to its
	 * own classes. `Train.kt` lives in `:core`, and Sonar reads only `:core`'s JaCoCo report for it,
	 * so those origin lines showed as uncovered and the PR's new-code coverage stayed under the 80 %
	 * gate. This test drives the origin branch from within `:core:jvmTest` so the coverage lands
	 * where Sonar looks — the same reason [MidJourneyNoPathErrorStopRegressionTest] anchors the
	 * mid-journey `NoTopologicalPath` bound alongside its dispatcher-agent twin.
	 *
	 * The WARN code (`290-292`) executes for coverage even though this test does not assert the log
	 * line: `:core`'s test source set has logback as `runtimeOnly` only, which is why the WARN
	 * wording itself is pinned in `:dispatcher-agent`.
	 *
	 * Asserts:
	 * 1. A train was admitted (so the stall is genuinely at the entry InOut, not a no-admission no-op).
	 * 2. `env.errorStop` fired with the **origin** wording (`"no entry route reserved"`), distinct
	 *    from the mid-journey `"no route extension"` wording the test above asserts.
	 */
	@Test
	@Timeout(value = 180, unit = TimeUnit.SECONDS)
	fun `never-admitted origin route fires errorStop after the wait horizon`() {
		val context = loadVyhybnaContext()
		assertThat(context.getInOuts()).isNotEmpty()

		val realNav = context.getRoutingServices().getTrainNavigationService()

		// Stall exactly ONE train, the first to query from its entry InOut — `separator is
		// DynamicInOut` — so it never leaves origin (`current` stays null, the `atOrigin` branch).
		// isPathReservedForTrain still delegates to the real service, so the train IS admitted; its
		// Front then sees OwnershipConflict at the entry InOut and parks in waitForPathOrReportStall.
		val stalledTrainId = AtomicReference<String?>(null)
		val stallingNav =
			object : TrainNavigationService {
				override fun findReservedPathForTrain(
					trainId: String,
					separator: PathSeparator
				): PathResult {
					if (separator is DynamicInOut) {
						stalledTrainId.compareAndSet(null, trainId)
					}
					return if (trainId == stalledTrainId.get() && separator is DynamicInOut) {
						PathResult.OwnershipConflict
					} else {
						realNav.findReservedPathForTrain(trainId, separator)
					}
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
		val stallingContext = NavigationDecoratingContext(context, stallingNav) { capturedErrors.add(it) }

		val loop = ShuntingLoop(stallingContext, END_TIME)
		wireSynchronousDispatcher(stallingContext, loop)
		context.setMainProcess(loop)
		context.run()

		logger.info {
			"Issue943 origin bound test complete: trainsEntered=${loop.getTrainsEntered()}, " +
				"trainsExited=${loop.getTrainsExited()}, errorStops=${capturedErrors.size}"
		}

		// (1) The stall is at origin: a train was admitted (it holds no track, so the rest of the
		// layout can still work around it).
		assertThat(loop.getTrainsEntered()).isGreaterThan(0)

		// (2) The origin horizon fired with the origin wording, not the mid-journey wording.
		val originError =
			capturedErrors.firstOrNull { it.message?.contains(ORIGIN_ROUTE_EXTENSION_FRAGMENT) == true }
		assertThat(originError, name = "captured origin errorStop throwable").isNotNull()
		assertThat(originError!!.message ?: "", name = "origin errorStop message")
			.contains(ORIGIN_ROUTE_EXTENSION_FRAGMENT)
	}

	private companion object {
		/**
		 * Ample room for admission, the train to reach its first semaphore, and the
		 * [Train.OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS] wait horizon to elapse. `errorStop` ends
		 * the run as soon as the bound trips, so a passing run returns well before this; a run
		 * without the bound burns the whole span, which is what the RED state looks like.
		 */
		const val END_TIME: Long = 200L

		/** Distinctive fragment of the never-extended-route `errorStop` message. */
		const val ROUTE_EXTENSION_FRAGMENT = "no route extension"

		/** Distinctive fragment of the never-admitted-origin `errorStop` message (distinct from mid-journey). */
		const val ORIGIN_ROUTE_EXTENSION_FRAGMENT = "no entry route reserved"
	}
}
