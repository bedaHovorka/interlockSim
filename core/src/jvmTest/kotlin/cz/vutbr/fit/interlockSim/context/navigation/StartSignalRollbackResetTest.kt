/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * G3 (Issue #893, phase alpha, task A5): rollback must reset partial aspect writes after a
 * signal-config failure.
 *
 * ## Defect
 *
 * `reservePath` Step 2g: when `configureStartSignal` returns `false` (config exception
 * swallowed, or -- since task A1 -- a rear-facing START), `rollbackUnconfigurableCandidate`
 * unwinds the registry and switches but never touches a semaphore. Yet a PARTIAL aspect write
 * is possible: `DefaultSimulationContext.configureSemaphoreSignal` can set the physical aspect
 * and a LATER step inside that same call still fail. The result is a lit semaphore for a route
 * that was never granted -- exactly the evergreen-aspect defect class #847 fixed on every other
 * release path, left open on this one.
 *
 * ## Fault injection
 *
 * `DefaultPathReservationService` is constructed directly (constructor injection precedent:
 * [cz.vutbr.fit.interlockSim.testutil.CoreTestModule] `PathReservationService` scope factory)
 * with a delegating [SimulationEnvironment] wrapper that first forwards
 * `configureSemaphoreSignal` to the real context -- so the aspect actually gets written -- and
 * THEN throws, reproducing exactly what a config call that mutates state before failing looks
 * like from `configureStartSignal`'s point of view.
 *
 * ## Topology
 *
 * `doA1` faces B->A (see [DefaultPathReservationService.facesDirectionOfTravel] via task A1's
 * `StartDirectionTests`), so `doA1 -> zA` is a legitimately-facing START -- task
 * A1's rear-facing-START guard passes and the request reaches signal configuration, which is
 * this test's actual concern. The train id is fresh with an empty footprint, so task A-R1's
 * contiguity predicate passes vacuously.
 */
@Tag("integration-test")
class StartSignalRollbackResetTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var realEnvironment: SimulationContext
	private lateinit var navigator: TopologyNavigator
	private lateinit var registry: PathReservationRegistry
	private lateinit var pathInfoBuilder: PathInfoBuilder
	private lateinit var routeFinder: RouteFinder

	@BeforeEach
	fun setUp() {
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()

		realEnvironment = simulationContext
		navigator = simulationContext.scope.get()
		registry = simulationContext.scope.get()
		pathInfoBuilder = simulationContext.scope.get()
		routeFinder = simulationContext.scope.get()
	}

	private fun findSemaphoreByName(name: String): DynamicRailSemaphore {
		val grid = simulationContext.getRailWayNetGrid()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid[Point(x, y)]
				if (cell is DynamicRailSemaphore && cell.name == name) {
					return cell
				}
			}
		}
		throw IllegalStateException("Semaphore $name not found in grid")
	}

	private fun inOutNamed(name: String): DynamicPathSeparator =
		simulationContext
			.getInOuts()
			.map { simulationContext.toDynamic(it) }
			.filterIsInstance<DynamicInOut>()
			.single { it.name == name }

	/**
	 * Delegates every [SimulationEnvironment] call to [real] except [configureSemaphoreSignal]:
	 * when [armed] and [semaphore] is [faultOn], it delegates FIRST -- so the physical aspect is
	 * genuinely written -- and THEN throws. This reproduces, from `configureStartSignal`'s
	 * try/catch, the exact partial-write shape `DefaultSimulationContext.configureSemaphoreSignal`
	 * can produce on a config call that mutates the aspect before a later internal step fails.
	 *
	 * [armed] defaults to `true` (fires on every call to [faultOn]) but is a `var` so a test can
	 * disarm it for an initial, legitimately-successful call and re-arm it before a later attempt
	 * that reuses the same START -- the review-fix regression test below needs exactly that shape:
	 * the FIRST call must succeed and record the semaphore for real, and only the SECOND call may
	 * fail.
	 *
	 * Delegates [SimulationContext], not just [SimulationEnvironment]: switch configuration
	 * (`configureSwitchesInPath`) hard-requires `environment as? SimulationContext` for
	 * `getSegment()` access and throws `IllegalStateException` otherwise, and the reserved
	 * `doA1 -> zA` route below traverses switch `vA`. A plain `SimulationEnvironment` wrapper
	 * would fail before reaching this test's actual concern.
	 */
	private class PartialWriteFaultEnvironment(
		private val real: SimulationContext,
		private val faultOn: DynamicRailSemaphore,
		var armed: Boolean = true
	) : SimulationContext by real {
		override fun configureSemaphoreSignal(
			semaphore: DynamicRailSemaphore,
			firstBlock: DynamicTrackBlock,
			allowedSpeed: Double?
		) {
			real.configureSemaphoreSignal(semaphore, firstBlock, allowedSpeed)
			if (armed && semaphore === faultOn) {
				throw SimulationException("injected signal-config failure for ${semaphore.name}")
			}
		}
	}

	@Test
	fun `rollback resets a partially-written START aspect after a signal-config failure`() {
		val doA1 = findSemaphoreByName("doA1")
		val zA = findSemaphoreByName("zA")
		assertThat(doA1.signal).isEqualTo(Signal.STOP)

		val faultyEnvironment = PartialWriteFaultEnvironment(realEnvironment, faultOn = doA1)
		val service =
			DefaultPathReservationService(navigator, faultyEnvironment, registry, pathInfoBuilder, routeFinder)

		// maxDepth = 3 restricts the topological search to the single direct doA1 -> zA route,
		// the same technique the MergeAbortResourceRelease sibling test (PathReservationServiceTest)
		// and StartDirectionTests use to exclude vyhybna's longer sibling-branch alternate --
		// otherwise an unconfigurable switch on that alternate candidate would surface as a
		// geometric failure and mask what THIS test actually verifies: the START-aspect rollback
		// after this direct candidate's injected signal-config fault (ConfigFailed, not a geometric
		// rejection). Under Issue #937's AND-gate a mixed candidate set (one geometric, one
		// ConfigFailed) would classify as AllPathsBlocked rather than GeometricallyImpossible, but
		// the masking effect is the same, so excluding the alternate keeps this test on the direct
		// candidate's behaviour alone.
		val result = service.reservePath("faultTrain", doA1, zA, maxDepth = 3)

		// With only the direct candidate, the injected ConfigFailed is ordinary contention (no
		// geometric rejection is reached), so the exhausted-attempt classifier returns
		// AllPathsBlocked(attemptedPaths = 1) -- NOT GeometricallyImpossible, which would come from
		// the excluded alternate candidate's unconfigurable switch and is decoupled from this
		// test's purpose (pinning the START-aspect rollback).
		assertThat(result)
			.withMessage("a signal-config failure must fail the reservation as AllPathsBlocked")
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat((result as PathReservationService.ReservationResult.AllPathsBlocked).attemptedPaths)
			.withMessage("maxDepth=3 yields exactly the one direct candidate")
			.isEqualTo(1)

		assertThat(registry.getBlocks("faultTrain"))
			.withMessage("a rolled-back candidate must hold no blocks")
			.isEmpty()
		assertThat(registry.getSwitches("faultTrain"))
			.withMessage("a rolled-back candidate must lock no switches")
			.isEmpty()
		assertThat(doA1.signal)
			.withMessage("a partial aspect write must be undone by rollback, not left lit")
			.isEqualTo(Signal.STOP)
	}

	/**
	 * Review-fix regression (Issue #893 task A5, fix round 1): a route EXTENSION reusing its
	 * ORIGINAL start must not have that start's still-governing signal reset just because THIS
	 * attempt's own re-configuration of it failed.
	 *
	 * `doA1` is legitimately cleared and recorded for `extendTrain` by a first, genuinely
	 * successful `reservePath` call. A second call from the SAME train and the SAME start
	 * (`doA1`) extends the route further in the same direction; the injected fault fires on
	 * THIS attempt's own re-config of `doA1` (a route extension always re-invokes
	 * `configureStartSignal` on the original start, even though it was already cleared -- see
	 * [DefaultPathReservationService.configureStartSignal]). Before the fix,
	 * `resetUnrecordedStartSignal` could not tell "written by an earlier, still-valid
	 * reservation" apart from "written by this failed attempt" and reset `doA1` regardless,
	 * stranding the train behind its own signal while its earlier blocks stayed registered.
	 */
	@Test
	fun `a failed extension does not reset the START signal cleared by an earlier successful reservation`() {
		val doA1 = findSemaphoreByName("doA1")
		val zA = findSemaphoreByName("zA")
		val inOutA = inOutNamed("A")

		// armed=false: the FIRST call below must succeed for real, exactly like the liveness
		// twin, so doA1 ends up genuinely cleared and recorded for extendTrain.
		val faultyEnvironment = PartialWriteFaultEnvironment(realEnvironment, faultOn = doA1, armed = false)
		val service =
			DefaultPathReservationService(navigator, faultyEnvironment, registry, pathInfoBuilder, routeFinder)

		val initial = service.reservePath("extendTrain", doA1, zA, maxDepth = 3)
		assertThat(initial)
			.withMessage("the setup reservation must succeed so doA1 is legitimately cleared first")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(doA1.signal.isAllowing())
			.withMessage("the earlier reservation must have cleared the START")
			.isTrue()
		val blocksBefore = registry.getBlocks("extendTrain").toSet()
		assertThat(blocksBefore).isNotEmpty()

		// Re-arm: only THIS second attempt's own re-config of doA1 must fail.
		faultyEnvironment.armed = true
		// maxDepth = 4 restricts the extension's topological search to the single direct
		// doA1 -> A route (doA1 -> vA -> zA -> A, three sections), excluding vyhybna's longer
		// sibling-branch alternate whose unconfigurable switch would otherwise be classified
		// GeometricallyImpossible (Issue #903) and mask this test's actual concern: the
		// START-aspect rollback after the injected ConfigFailed on doA1's re-config.
		val extension = service.reservePath("extendTrain", doA1, inOutA, maxDepth = 4)

		// With only the direct candidate, the injected ConfigFailed is ordinary contention, so
		// the exhausted-attempt classifier returns AllPathsBlocked(1) -- NOT GeometricallyImpossible
		// (which would come from the excluded alternate and is decoupled from this test's purpose).
		assertThat(extension)
			.withMessage("the injected fault must fail the extension attempt as AllPathsBlocked")
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat((extension as PathReservationService.ReservationResult.AllPathsBlocked).attemptedPaths)
			.withMessage("maxDepth=4 yields exactly the one direct candidate")
			.isEqualTo(1)
		assertThat(registry.getBlocks("extendTrain").toSet())
			.withMessage("a failed extension must not touch the train's earlier, still-valid blocks")
			.isEqualTo(blocksBefore)
		assertThat(doA1.signal.isAllowing())
			.withMessage(
				"a failed EXTENSION must not strand the train behind its own still-governing START -- " +
					"doA1 was cleared by an EARLIER successful reservation, not written by this failed attempt"
			).isTrue()

		// The bookkeeping must have survived intact too, not just the physical aspect: releasePath
		// is the only thing allowed to reset doA1 now, proving it is still correctly recorded.
		service.releasePath("extendTrain")
		assertThat(doA1.signal)
			.withMessage("releasePath must still find and reset doA1 -- the bookkeeping was never purged")
			.isEqualTo(Signal.STOP)
	}

	/**
	 * Liveness twin (anti-#566): the identical route through the identical service
	 * construction, but with the real environment (no fault injection), must still succeed
	 * and light the START -- proving the wrapper itself does not break the happy path.
	 */
	@Test
	fun `liveness twin - the same route succeeds and lights the START without fault injection`() {
		val doA1 = findSemaphoreByName("doA1")
		val zA = findSemaphoreByName("zA")

		val service =
			DefaultPathReservationService(navigator, realEnvironment, registry, pathInfoBuilder, routeFinder)

		val result = service.reservePath("liveTrain", doA1, zA)

		assertThat(result)
			.withMessage("without fault injection the route must succeed")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(doA1.signal.isAllowing())
			.withMessage("a successful reservation must clear the START")
			.isTrue()
	}
}
