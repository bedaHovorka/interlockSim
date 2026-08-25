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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Issue #944: a route that reverses a train over the section it just travelled must be refused,
 * because the resulting reserved path is one no train can follow — and a train that cannot follow
 * its own path halts forever holding its blocks, deadlocking the layout.
 *
 * ## The measured production sequence this reproduces
 *
 * From `exampleGui shuntingLoopAI 333` on `df83cd18`, Train #4 running B → A:
 *
 * ```
 * DispatchDecisionApplier: applying RequestRoute trainName=Train #4, from=zB, to=doA2
 * DispatchDecisionApplier: applying RequestRoute trainName=Train #4, from=doA2, to=zB   <-- reversal
 * mergePathInfo: LEGITIMATE CIRCULAR ROUTE - separator Dynamic[doB2] appears 2x in path
 * mergePathInfo: LEGITIMATE CIRCULAR ROUTE - separator Dynamic[vB]   appears 2x in path
 * mergePathInfo: LEGITIMATE CIRCULAR ROUTE - separator Dynamic[zB]   appears 2x in path
 * ```
 *
 * The occurrence-counting cycle guard only rejects a *3rd* occurrence, so the out-and-back path
 * merged. `DefaultTrainNavigationService.buildPathWithDirection` then walked it, revisited a
 * `(separator, previous)` pair, found the cycle exit rear-facing and returned `null` — 168,288
 * such failures in one run, since the wait condition re-evaluates after every event. Train #4
 * never moved again, holding the blocks Train #5 needed, and the run ended 3/7 journeys instead
 * of 5/7.
 *
 * ## Why this test is at service level and not only registry level
 *
 * [PathReservationRegistryMergingTest.DirectionReversalGuard] pins the merge decision in
 * isolation. What it cannot show is the consequence that actually mattered: whether the train can
 * still *navigate* afterwards. This test asserts exactly that — after the reversal is refused,
 * `findReservedPathForTrain` still returns [PathResult.Available] from the train's position. With
 * the reversal merged it returned [PathResult.OwnershipConflict] on every evaluation, forever,
 * which is the deadlock itself.
 *
 * ## Topology (vyhybna.xml)
 *
 * ```
 * A(11,8) ── zA(14,8) ── vA(15,8) ── doA1(16,8) ──────── doB1(25,8) ── vB(26,8) ── zB(27,8) ── B(30,8)
 *                          └──────── doA2(17,9) ──────── doB2(24,9) ────┘
 * ```
 */
@DisplayName("Issue #944: a reversing route is refused and the train stays navigable")
@Tag("integration-test")
class Issue944ReversalDeadlockRegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService
	private lateinit var navigation: TrainNavigationService

	private lateinit var zB: DynamicPathSeparator
	private lateinit var doA2: DynamicPathSeparator

	private val trainId = "Train #4"

	@BeforeEach
	fun setUp() {
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
		testContext = simulationContext

		registry = simulationContext.scope.get()
		service = simulationContext.getRoutingServices().getPathReservationService()
		navigation = simulationContext.getRoutingServices().getTrainNavigationService()

		zB = separatorAt(27, 8)
		doA2 = separatorAt(17, 9)
	}

	private fun separatorAt(
		x: Int,
		y: Int
	): DynamicPathSeparator {
		val cell = simulationContext.getRailWayNetGrid()[Point(x, y)]
		val separator = cell as? PathSeparator ?: throw IllegalStateException("No separator at ($x, $y): $cell")
		return simulationContext.toDynamic(separator)
	}

	@Test
	@DisplayName("the reversing second route is refused and leaves the stored route untouched")
	fun reversingRouteIsRefused() {
		// Given: the train holds zB → doA2, exactly as granted in the measured run.
		assertThat(service.reservePath(trainId, zB, doA2))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val storedBefore = registry.getPathInfo(trainId)
		assertThat(storedBefore).isNotNull()
		assertThat(storedBefore!!.target).isEqualTo(doA2)

		// When: the dispatcher requests the reverse of the segment the train is on.
		service.reservePath(trainId, doA2, zB)

		// Then: the stored route is the SAME object, still ending at doA2. Before the guard this
		// became an out-and-back path targeting zB that retraced section k2 backwards.
		val storedAfter = registry.getPathInfo(trainId)
		assertThat(storedAfter).isNotNull()
		assertThat(storedAfter!!).isSameInstanceAs(storedBefore)
		assertThat(storedAfter.target).isEqualTo(doA2)
	}

	@Test
	@DisplayName("after the refusal the train can still navigate forward — this is the deadlock assertion")
	fun trainRemainsNavigableAfterRefusal() {
		assertThat(service.reservePath(trainId, zB, doA2))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Sanity: the train is navigable from its position before the bad request.
		assertThat(navigation.findReservedPathForTrain(trainId, zB))
			.isInstanceOf<PathResult.Available>()

		service.reservePath(trainId, doA2, zB)

		// The whole point: navigation must still yield a usable path. With the reversal merged
		// this returned OwnershipConflict on every evaluation for the rest of the run, so the
		// train waited forever on createPathAvailableCondition while holding its blocks.
		assertThat(navigation.findReservedPathForTrain(trainId, zB))
			.isInstanceOf<PathResult.Available>()
	}
}
