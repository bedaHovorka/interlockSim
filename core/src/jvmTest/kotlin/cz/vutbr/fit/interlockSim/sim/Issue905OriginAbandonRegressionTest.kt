/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Regression tests for Issue #905
 * 2026
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Regression tests for Issue #905: A train whose origin route is missing must wait
 * for the dispatcher instead of silently abandoning its journey.
 *
 * **Defect fixed:**
 * `Train.kt` had `if (where is DynamicInOut) break` inside the `if (path == null || next == null)`
 * block. Because the destination check (`where is DynamicInOut && current != null`) at the top of
 * the loop always fires first, line 199 was only reachable at the origin (`current == null`).
 * When the origin path query returned non-Available (OwnershipConflict or NoTopologicalPath), the
 * `Front.actions()` loop exited silently, leaving the train stranded with:
 * - The InOutWorker queue head never advancing (admission queue blocked).
 * - The `approwedTrains` slot held forever (ShuntingLoop.kt:365-372).
 * - A FATAL `SimulationException` at `Train.kt:644` (pathToSemaphore invariant, because
 *   `fireStart` was never called).
 *
 * **Fix (Issue #905, AC1):**
 * The `if (where is DynamicInOut) break` at the former line 199 is removed. The origin case
 * (`current == null`) now falls through to the dispatcher-wait branch:
 * - `OwnershipConflict` → `waitUntil(env.createPathAvailableCondition(...))` (event-driven).
 * - `NoTopologicalPath` at origin → bounded retry then `env.errorStop` (AC2).
 *
 * **Reachability (AC5 standing assumption):**
 * Reproducing the failure state directly — without a `TrainNavigationService` decorator —
 * is not possible on current wiring. kDisco's single simulation thread ensures that every
 * path releaser runs synchronously inside the `ControlStepListener` before the Front
 * process's first wake-up. Therefore the admission gate (`waitUntil { isPathReservedForTrain }`)
 * and the `Front`'s first `findReservedPathForTrain` always observe the same reservation state
 * in `shuntingLoop` and `shuntingLoopAI` as currently wired.
 *
 * **Re-check trigger:** If any path releaser moves off the control step, or a second
 * sim-thread mutation point is introduced, attempt the direct-reproduction test again and
 * update this note.
 *
 * Tests in this class use a `TrainNavigationService` decorator injected through
 * [NavigationDecoratingContext] to simulate the theoretical race as required by AC5. That wrapper
 * replaced a MockK `spyk` in Issue #943 — see its KDoc for why a spy cannot carry a scenario in
 * which a train stays suspended.
 */
@DisplayName("Issue #905: Origin-abandon regression (decorator approach)")
@Tag("integration-test")
class Issue905OriginAbandonRegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	/**
	 * Verifies that [Train.MAX_ORIGIN_NO_PATH_RETRIES] is defined and has a positive value.
	 *
	 * This constant governs the bounded-retry policy for `NoTopologicalPath` at origin (AC2):
	 * a misconfigured network is detected and reported via `env.errorStop` rather than looping
	 * silently forever.
	 */
	@Test
	fun `MAX_ORIGIN_NO_PATH_RETRIES is defined and positive`() {
		assertThat(Train.MAX_ORIGIN_NO_PATH_RETRIES).isGreaterThan(0)
	}

	/**
	 * Verifies that a train encountering an `OwnershipConflict` at its **origin** InOut does
	 * NOT abandon its journey silently.  With the fix applied, the `Front.actions()` loop
	 * falls through to `waitUntil(pathAvailableCondition)` and resumes when the dispatcher
	 * reserves the path on the next control step, after which the train completes its journey
	 * and exits the system.
	 *
	 * **Without the fix:** `Front.actions()` executed `if (where is DynamicInOut) break`,
	 * silently exiting the loop. The train never entered any block, so:
	 * - `waitUntilCrossing { (getLength() - dtMin) - front.getTotalDistance() }` never fired,
	 * - `out()` never ran, leaving the InOutWorker queue head stuck,
	 * - the `approwedTrains` slot was held permanently,
	 * - and a FATAL `requireSimulation` exception was thrown at `pathToSemaphore?.getFirst() == where`.
	 *
	 * **With the fix:** The train waits at origin, resumes on the next tick when
	 * `env.createPathAvailableCondition` becomes true, and exits normally.
	 *
	 * **Decorator reachability (AC5):** The first `findReservedPathForTrain` call at a
	 * `DynamicInOut` is intercepted per-train via a `NavigationDecoratingContext`-injected
	 * decorator that returns `PathResult.OwnershipConflict`. All subsequent calls delegate to the
	 * real service, so on the event-driven `waitUntil` retry the path is found and the train
	 * proceeds.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `train recovers from origin OwnershipConflict and completes journey`() {
		val context = loadVyhybnaContext()
		assertThat(context.getInOuts()).isNotEmpty()

		val realNav = context.getRoutingServices().getTrainNavigationService()

		// Decorator: return OwnershipConflict for the FIRST findReservedPathForTrain at a
		// DynamicInOut (i.e. the origin query) for each train, then delegate to the real service.
		val conflictCount = AtomicInteger(0)
		val firstOriginQuerySeen = ConcurrentHashMap<String, Boolean>()
		val decoratedNav =
			object : TrainNavigationService {
				override fun findReservedPathForTrain(
					trainId: String,
					separator: PathSeparator
				): PathResult {
					if (separator is DynamicInOut) {
						val key = "$trainId@${separator.name}"
						if (firstOriginQuerySeen.putIfAbsent(key, true) == null) {
							conflictCount.incrementAndGet()
							logger.debug {
								"Issue905 decorator: injecting OwnershipConflict for $trainId at ${separator.name}"
							}
							return PathResult.OwnershipConflict
						}
					}
					return realNav.findReservedPathForTrain(trainId, separator)
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

		// Inject the decorator through a delegating wrapper: only the navigation service is
		// replaced; everything else (Koin scope access, kDisco internals) goes to the real
		// DefaultSimulationContext, which still owns the run.
		val decoratedContext = NavigationDecoratingContext(context, decoratedNav)

		val loop = ShuntingLoop(decoratedContext, 120L)
		wireSynchronousDispatcher(decoratedContext, loop)
		context.setMainProcess(loop)
		context.run()

		logger.info {
			"Issue905 test complete: conflictCount=${conflictCount.get()}, " +
				"trainsEntered=${loop.getTrainsEntered()}, trainsExited=${loop.getTrainsExited()}"
		}

		// The decorator must have fired at least once (origin conflict was actually injected).
		assertThat(conflictCount.get()).isGreaterThan(0)
		// Trains must still have exited — origin OwnershipConflict does not cause abandonment.
		assertThat(loop.getTrainsExited()).isGreaterThan(0)
		// Trains entered >= trains exited (trains cannot exit without entering).
		assertThat(loop.getTrainsEntered()).isGreaterThanOrEqualTo(loop.getTrainsExited())
	}
}
