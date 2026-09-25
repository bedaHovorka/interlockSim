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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Regression tests for Issue #1076: [PathReservationRegistry.registerSwitches] used to record
 * switch ownership with a plain `switchToTrain[switch] = trainId` overwrite, never checking
 * whether the switch was already registered to a DIFFERENT train. A candidate needing the
 * switch in the SAME position its current owner held could traverse it (the Issue #1065 SI-5
 * guard only refuses repositions) and silently steal the registry entry -- while the old
 * owner's `trainToSwitches` list still contained the switch, so the two maps disagreed and
 * the old owner's release path could later unlock a switch on the new owner's live route.
 *
 * ## Fix (Issue #1076)
 *
 * - [PathReservationRegistry.registerSwitches] now throws before mutating anything when a
 *   switch is registered to a different train (covered by the registry unit tests in
 *   [PathReservationRegistryTest]).
 * - `reservePath` reclaims STALE foreign ownership (owner holds no block bounded by the
 *   switch) through [PathReservationRegistry.unregisterSwitch] in Step 2e.5, keeping both
 *   maps consistent, so the same-position case that used to succeed by stealing now succeeds
 *   by legitimate reclamation.
 * - A LIVE foreign owner (holding an adjacent block) refuses the candidate as transient
 *   contention in `configureSwitchesInPath`, even in the same position.
 */
@Tag("integration-test")
@DisplayName("Issue #1076 Regression: registerSwitches must not steal a foreign train's switch")
class Issue1076RegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService

	// Elements from vyhybna.xml
	private lateinit var semaphoreZA: DynamicPathSeparator // zA at (14,8)
	private lateinit var semaphoreDoB1: DynamicPathSeparator // doB1 at (25,8), guards k1
	private lateinit var semaphoreDoB2: DynamicPathSeparator // doB2 at (24,9), guards k2
	private lateinit var switchVA: DynamicRailSwitch // vA at (15,8)

	@BeforeEach
	fun setUp() {
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

		registry = simulationContext.scope.get()
		service = simulationContext.getRoutingServices().getPathReservationService()

		semaphoreZA = simulationContext.separatorAt(14, 8)
		semaphoreDoB1 = simulationContext.separatorAt(25, 8)
		semaphoreDoB2 = simulationContext.separatorAt(24, 9)
		switchVA = simulationContext.separatorAt(15, 8) as? DynamicRailSwitch
			?: throw IllegalStateException("Switch 'vA' is not a DynamicRailSwitch")
	}

	@AfterEach
	fun tearDown() {
		simulationContext.close()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a same-position candidate over a STALE foreign switch succeeds by reclamation, never by stealing")
	fun staleForeignOwnershipIsReclaimedInsteadOfStolen() {
		val staleOwner = "train_1076_stale_owner"
		val candidate = "train_1076_candidate"

		// Given: vA is in the position the candidate will need (MAIN, the k1 leg towards doB1)
		// and is registered to a train that holds NO block bounded by it -- the exact stale
		// window from the issue (a scoped rollback released the owner's adjacent blocks via
		// registry.unregisterBlock, which bypasses dropFreedBlock's reclamation).
		if (switchVA.conf != Conf.MAIN) {
			switchVA.changeConf()
		}
		registry.registerSwitches(staleOwner, listOf(switchVA))
		assertThat(switchVA.locked).isTrue()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(staleOwner)

		// When: the candidate reserves zA -> doB1, which traverses vA in the SAME position the
		// stale owner holds. Before the fix, registerSwitches silently overwrote switchToTrain
		// while the stale owner's trainToSwitches list kept vA -- the two maps disagreed.
		val result = service.reservePath(candidate, semaphoreZA, semaphoreDoB1)

		// Then: the reservation succeeds by LEGITIMATE reclamation (Step 2e.5), so both maps
		// agree: the candidate owns vA and the stale owner's switch list is empty.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(candidate)
		assertThat(registry.getSwitches(candidate)).contains(switchVA)
		assertThat(registry.getSwitches(staleOwner)).isEmpty()

		// And: the stale owner's release path can no longer unlock the candidate's live switch
		// -- the corruption consequence the issue describes.
		assertThat(registry.unregisterSwitches(staleOwner)).isEmpty()
		assertThat(switchVA.locked).isTrue()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(candidate)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a same-position candidate over a LIVE foreign switch is refused as transient contention")
	fun liveForeignOwnershipRefusesTheSamePositionCandidate() {
		val liveOwner = "train_1076_live_owner"
		val candidate = "train_1076_candidate"

		// Given: vA is in the position the candidate will need (MAIN) but is registered to a
		// train that still holds a block bounded by vA on the OTHER leg (k2 side) -- a live
		// route the candidate's blocks do not conflict with, so only the ownership check can
		// refuse the traversal.
		if (switchVA.conf != Conf.MAIN) {
			switchVA.changeConf()
		}
		val k2SideBlock = blockNearSwitchOffCandidatePath()
		assertThat(registry.registerAtomic(liveOwner, listOf(k2SideBlock)))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		registry.registerSwitches(liveOwner, listOf(switchVA))
		assertThat(switchVA.locked).isTrue()

		// When: the candidate asks for zA -> doB1 (stem and k1 blocks are FREE, vA already in
		// MAIN -- before the fix this traversed vA and stole the ownership entry).
		val result = service.reservePath(candidate, semaphoreZA, semaphoreDoB1)

		// Then: refused as TRANSIENT contention (never GeometricallyImpossible, which would
		// make InOutWorker throw), and the live owner's switch is completely intact.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat(switchVA.locked).isTrue()
		assertThat(switchVA.conf).isEqualTo(Conf.MAIN)
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(liveOwner)
		assertThat(registry.getSwitches(liveOwner)).contains(switchVA)
		assertThat(registry.getSwitches(candidate)).isEmpty()
		assertThat(registry.getBlocks(candidate)).isEmpty()
	}

	/**
	 * The block bounded by vA on the k2 leg -- adjacent to the switch, but NOT on the
	 * candidate's zA -> doB1 route, so the candidate's block reservation cannot conflict
	 * with it and only the Issue #1076 ownership check stands between the candidate and
	 * the traversal.
	 */
	private fun blockNearSwitchOffCandidatePath(): DynamicTrackBlock {
		val candidateBlocks = routeBlocksOf(semaphoreZA, semaphoreDoB1).toSet()
		return allRouteBlocksOf(semaphoreZA, semaphoreDoB2)
			.first { switchVA in it.ends() && it !in candidateBlocks }
	}

	/** All blocks of the FIRST topological path from [start] to [target], in path order. */
	private fun routeBlocksOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): List<DynamicTrackBlock> =
		simulationContext
			.getRoutingServices()
			.getTopologyNavigator()
			.findAllTopologicalPaths(start, target)
			.first()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.distinct()

	/** The blocks of EVERY topological path from [start] to [target], flattened. */
	private fun allRouteBlocksOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): List<DynamicTrackBlock> =
		simulationContext
			.getRoutingServices()
			.getTopologyNavigator()
			.findAllTopologicalPaths(start, target)
			.flatten()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.distinct()
}
