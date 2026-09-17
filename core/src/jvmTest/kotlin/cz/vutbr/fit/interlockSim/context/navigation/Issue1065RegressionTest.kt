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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Regression tests for Issue #1065: a merge-abort rollback left a live route's switch
 * silently re-thrown to the other position, because [DynamicRailSwitch.setUpPath] never
 * checked [DynamicRailSwitch.locked] the way [DynamicRailSwitch.changeConf] does.
 *
 * ## Root cause (captured from Issue #1031 run A, t=160)
 *
 * A train holds `A → doA2` with switch `vA` locked in BRANCH. The dispatcher requests
 * `zA → doB1`, whose candidate needs `vA` in MAIN. `configureSwitchesInPath` called
 * `vA.setUpPath(...)`, which silently overwrote `conf` to MAIN and re-locked -- with no check
 * that `vA` was already locked, in a DIFFERENT position, by the train's own live route. The
 * Step 2i merge then aborted (the candidate does not extend the train's registered `PathInfo`
 * contiguously), and the rollback released only the candidate's OWN switches
 * (`priorSwitches.filterNot`), so `vA` stayed at MAIN -- wrong for the surviving route, which
 * still needs BRANCH.
 *
 * ## Fix (Issue #1065)
 *
 * `DynamicRailSwitch.setUpPath` now refuses (throws [SwitchLockedException]) when the switch
 * is locked in a different position than the candidate needs. The candidate is rejected as
 * TRANSIENT contention ([PathReservationService.ReservationResult.AllPathsBlocked]), never as
 * [PathReservationService.ReservationResult.GeometricallyImpossible] -- that classification
 * would make [cz.vutbr.fit.interlockSim.sim.InOutWorker] throw instead of letting the train
 * wait for the switch to free. A companion stale-lock reclamation
 * ([DefaultPathReservationService.dropFreedBlock]) keeps the guard from turning a train that
 * has merely PASSED a switch into a permanent block for every other train.
 */
@DisplayName("Issue #1065 Regression: a locked switch must not be re-thrown")
class Issue1065RegressionTest : KoinTestBase() {
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
	@DisplayName("a merge-abort candidate needing the OTHER position of a live switch is refused, not stolen")
	fun mergeAbortCandidateDoesNotStealASwitchFromTheSurvivingRoute() {
		val trainId = "train_1065_locked_switch"

		// Given: Train holds zA -> doB2, locking vA in BRANCH (the k2 leg).
		val phase1 = service.reservePath(trainId, semaphoreZA, semaphoreDoB2)
		assertThat(phase1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(switchVA.conf).isEqualTo(Conf.BRANCH)
		assertThat(switchVA.locked).isTrue()

		// When: the dispatcher asks for zA -> doB1 -- the #1031 shape. This candidate needs vA
		// in MAIN, but the train already holds it locked in BRANCH for its live route, and the
		// candidate does not extend that route contiguously (new.start == old.start == zA, not
		// old.target), so Step 2i's merge would abort even if the switch write had succeeded.
		val phase2 = service.reservePath(trainId, semaphoreZA, semaphoreDoB1)

		// Then: refused as ordinary, transient contention -- NEVER as a permanent geometric
		// impossibility (that would make InOutWorker throw instead of letting the train wait).
		assertThat(phase2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// And: vA is untouched -- still BRANCH, still locked, still owned by this train. This is
		// the #1065 assertion: before the fix, vA ended up at MAIN here.
		assertThat(switchVA.conf).isEqualTo(Conf.BRANCH)
		assertThat(switchVA.locked).isTrue()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(trainId)

		// And: the train's original reservation survives completely -- blocks, switches and
		// PathInfo are exactly what phase 1 left them.
		assertThat(registry.getPathInfo(trainId)!!.target).isEqualTo(semaphoreDoB2)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a switch a train has PASSED (but not yet fully completed its journey) is reclaimed")
	fun staleSwitchLockIsReclaimedOnceTheOwnerHasPassedItWithoutCompletingTheJourney() {
		val trainId1 = "train_1065_passer"
		val trainId2 = "train_1065_follower"
		val inOutB = simulationContext.getInOuts().first { it.name == "B" } as DynamicPathSeparator

		// Given: train 1 holds the full route zA -> B (via k1, through vA in MAIN, then vB),
		// several blocks long.
		val phase1 = service.reservePath(trainId1, semaphoreZA, inOutB)
		assertThat(phase1).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(switchVA.conf).isEqualTo(Conf.MAIN)
		assertThat(switchVA.locked).isTrue()

		val (blocksNearVA, blocksFarFromVA) = registry.getBlocks(trainId1).partition { switchVA in it.ends() }
		assertThat(blocksNearVA.size).isEqualTo(2) // vA sits between exactly two blocks
		assertThat(blocksFarFromVA).isNotEmpty() // the train still holds track further on

		// When: train 1's tail clears the FIRST block adjacent to vA (physical passage:
		// enter() then leave(), then the production per-block release path
		// unregisterBlock -> dropFreedBlock). Train 1 still holds the OTHER block touching vA,
		// so vA's lock must survive this first release.
		val occupant = FakeTrackOccupant(trainId1)
		val firstNearVA = blocksNearVA[0]
		firstNearVA.enter(occupant)
		firstNearVA.leave(occupant)
		val firstReleased = service.unregisterBlock(trainId1, firstNearVA)
		assertThat(firstReleased).isTrue()
		// vA must stay locked -- train 1 still holds the other block adjacent to it.
		assertThat(switchVA.locked).isTrue()

		// When: the SECOND (and last) block touching vA is released the same way. Train 1 is
		// now physically past vA but its journey is far from over (blocksFarFromVA are still
		// held) -- unlike unregister()'s unconditional unlock on FULL completion, this is the
		// mid-journey case unregister() never reaches.
		val secondNearVA = blocksNearVA[1]
		secondNearVA.enter(occupant)
		secondNearVA.leave(occupant)
		val secondReleased = service.unregisterBlock(trainId1, secondNearVA)
		assertThat(secondReleased).isTrue()

		// Then: vA is reclaimed -- unlocked and unowned -- even though train 1's journey is not
		// complete (it still holds blocksFarFromVA). This is the #1065 assertion: vA must be
		// reclaimed once its owner holds no block adjacent to it, not held forever.
		assertThat(switchVA.locked).isFalse()
		assertThat(registry.getSwitchOwner(switchVA)).isNull()
		assertThat(registry.getBlocks(trainId1).toSet()).isEqualTo(blocksFarFromVA.toSet())

		// And: a second train needing the OTHER position is granted, not refused -- the whole
		// point of the reclamation.
		val phase2 = service.reservePath(trainId2, semaphoreZA, semaphoreDoB2)
		assertThat(phase2).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(switchVA.conf).isEqualTo(Conf.BRANCH)
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(trainId2)
	}
}
