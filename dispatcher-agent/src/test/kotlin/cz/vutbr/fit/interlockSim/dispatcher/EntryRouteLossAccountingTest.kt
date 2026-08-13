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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
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
 * A [TrainNavigationService] that hides the reserved route from callers that ask for it by
 * `findReservedPathForTrain`, while leaving every other query delegating to the real service.
 *
 * This models — deterministically, and without having to win a race — the state
 * `docs/INTERLOCKING_SCOPE_LIMITATIONS.md` §B1 warns about: a train whose entry reservation is
 * gone by the time its `Front` first asks for it.
 *
 * Note that `DefaultTrainNavigationService.isPathReservedForTrain` is implemented *on top of*
 * `findReservedPathForTrain`, so delegating it here (rather than overriding it) keeps
 * `Train.actions()`'s admission gate answering from the real, un-hidden state. That is precisely
 * the asymmetry the scenario needs: the train is cleared to start, and only then does the route
 * appear to be gone.
 */
private class RouteHidingNavigationService(
	private val delegate: TrainNavigationService
) : TrainNavigationService by delegate {
	override fun findReservedPathForTrain(
		trainId: String,
		separator: PathSeparator
	): PathResult = PathResult.OwnershipConflict
}

/** Serves [nav] in place of the real train-navigation service; everything else is the real context. */
private class RouteHidingContext(
	private val delegate: SimulationContext,
	nav: TrainNavigationService
) : SimulationContext by delegate {
	private val routing =
		object : RoutingServices by delegate.getRoutingServices() {
			override fun getTrainNavigationService(): TrainNavigationService = nav
		}

	override fun getRoutingServices(): RoutingServices = routing
}

/**
 * Pins what actually happens when a train's entry reservation is missing at the moment its `Front`
 * first looks for it (Issue #834, SP2c.11 — task 9, part B).
 *
 * ## The claim under investigation, and the measured answer
 *
 * The concern raised against `Train.kt`'s
 * `if (path == null || next == null) { if (where is DynamicInOut) break }` was that a train could
 * "terminate having never moved, and still count as exited" — which would corrupt the
 * journeys-completed / trains-admitted counters #834 and #895 depend on.
 *
 * The second half of that is **not what happens**, and this test pins the real behaviour:
 *
 * 1. The `Front` does break out on its first loop iteration, having produced zero movement.
 * 2. `Train.actions()` does **not** follow it. After `Process.activate(front)` it parks in
 *    `waitUntilCrossing { (getLength() - dtMin) - front.getTotalDistance() }`, a level-triggered
 *    wait on a distance the train will never cover. The `Train` process therefore never
 *    terminates, and nothing ever re-activates it.
 * 3. `ShuntingLoop.iteration()` increments `trainsExitedCount` only for approved trains whose
 *    `terminated()` is true. A never-terminating train is never counted — so the exited counter
 *    **under**-reports here, it does not over-report.
 *
 * The real defect in this state is a different and quieter one, recorded here rather than fixed:
 * the stalled train holds its admission slot forever and stays at the head of its `InOutWorker`
 * queue, so the station jams. `Front.separatorAction` also throws a FATAL `SimulationException`
 * ("Path to semaphore first element must match current position: null") on the way out, on the
 * `Front`'s own coroutine, where it is reported but does not stop the run. Both are `core/`
 * behaviour questions that need a traffic-simulation-expert ruling (TEAM.md), so #834 task 9
 * deliberately changed no `core/` behaviour.
 *
 * ## Reachability in production wiring (why this needs an injected navigation service)
 *
 * The state cannot currently be reached by cancelling a route from outside. Every releaser —
 * `DispatchDecisionApplier` applying a `cancel_route`/`ReleaseRoute`, and
 * [OrphanReservationSweeper.sweep] — runs synchronously inside `ShuntingLoop.iteration()`, before
 * the loop's `hold()`. The whole admission chain (`InOutWorker` reserving → `Train.actions()`
 * resuming from its `waitUntil` → `Process.activate(front)` → the `Front`'s first query) runs
 * strictly *after* that, in the same simulated instant, on the single kDisco scheduler thread,
 * with no releaser interleaved. `CancelRouteTool` only emits a `DispatchAction` to be applied on
 * that same sim thread, so the agent thread cannot short-circuit it either. Hence the injected
 * [RouteHidingNavigationService]: it produces the state directly instead of asserting a race that
 * does not exist.
 *
 * @since Issue #834 (SP2c.11 — Goal 10)
 */
@DisplayName("A train that never entered a block is not counted as exited (#834)")
@Tag("integration-test")
class EntryRouteLossAccountingTest {
	private val fixture = LiftedStackFixture()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("front breaks at the entry InOut: zero movement, zero exits, no termination")
	fun trainThatNeverEnteredIsNeverCountedAsExited() {
		val context = fixture.loadShuntingLoopContext()
		// Initialize the dynamic wrapper map (required before ShuntingLoop construction).
		context.getInOuts()

		val hidingContext =
			RouteHidingContext(
				context,
				RouteHidingNavigationService(context.getRoutingServices().getTrainNavigationService())
			)

		// The loop is handed the route-hiding view so the Trains its generator builds resolve their
		// navigation service through it; the run itself is driven by the real context.
		val loop = ShuntingLoop(hidingContext, endTime = END_TIME)

		// Anything observed to be terminated, or to have moved, while the run is in progress.
		val terminatedDuringRun = mutableListOf<String>()
		val movedDuringRun = mutableListOf<String>()
		var exitedDuringRun = 0

		loop.controlStepListener =
			ControlStepListener {
				// Admit whatever is queued — no dispatcher needed: the trains never move, so no
				// forward reservations are ever required.
				loop.getQueuedTrains().firstOrNull()?.let { loop.approveQueuedTrain(it.trainId) }

				loop.getApprovedTrains().forEach { train ->
					if (train.terminated()) terminatedDuringRun.add(train.name)
					if (train.totalDistance > 0.0) movedDuringRun.add(train.name)
				}
				exitedDuringRun = maxOf(exitedDuringRun, loop.getTrainsExited())
			}

		context.setMainProcess(loop)
		context.run()

		// The scenario actually engaged: at least one train was admitted.
		assertThat(loop.getTrainsEntered(), name = "trains entered").isGreaterThanOrEqualTo(1)

		// …and none of them ever moved.
		assertThat(movedDuringRun, name = "trains that moved").isEmpty()

		// The load-bearing assertions. A train whose Front broke at the entry InOut is parked
		// forever in Train.actions(), so it is never observed terminated and never counted as
		// exited. If either of these ever starts failing, the counters #834/#895 rely on have begun
		// crediting a journey that never happened, and the "make the counting honest" fix that
		// task 9 found unnecessary becomes necessary.
		assertThat(terminatedDuringRun, name = "trains observed terminated").isEmpty()
		assertThat(exitedDuringRun, name = "trains exited (peak during run)").isEqualTo(0)
		assertThat(loop.getTrainsExited(), name = "trains exited (final)").isEqualTo(0)
	}

	private companion object {
		/** Long enough for the generator to queue trains and for several control steps to observe them. */
		const val END_TIME: Long = 60L
	}
}
