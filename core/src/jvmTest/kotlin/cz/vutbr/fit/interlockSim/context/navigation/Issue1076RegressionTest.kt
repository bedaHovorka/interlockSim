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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
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
import cz.vutbr.fit.interlockSim.testutil.passAndRelease
import cz.vutbr.fit.interlockSim.testutil.routeBlocksOf
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import cz.vutbr.fit.interlockSim.testutil.topologicalPathBlocks
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

		// vA starts in the position a zA -> doB1 candidate needs (MAIN, the k1 leg), so every
		// same-position scenario below needs no reposition and only ownership can refuse it.
		if (switchVA.conf != Conf.MAIN) {
			switchVA.changeConf()
		}
	}

	@AfterEach
	fun tearDown() {
		simulationContext.close()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a same-position candidate over a STALE foreign switch succeeds by reclamation, never by stealing")
	fun staleForeignOwnershipIsReclaimedInsteadOfStolen() {
		// Given: vA is registered to a train that holds NO block bounded by it -- the exact stale
		// window from the issue (a scoped rollback released the owner's adjacent blocks via
		// registry.unregisterBlock, which bypasses dropFreedBlock's reclamation).
		registry.registerSwitches(OTHER_OWNER, listOf(switchVA))
		assertThat(switchVA.locked).isTrue()

		// When: the candidate reserves zA -> doB1, which traverses vA in the SAME position the
		// stale owner holds. Before the fix, registerSwitches silently overwrote switchToTrain
		// while the stale owner's trainToSwitches list kept vA -- the two maps disagreed.
		val result = service.reservePath(CANDIDATE, semaphoreZA, semaphoreDoB1)

		// Then: the reservation succeeds by LEGITIMATE reclamation (Step 2e.5), so both maps
		// agree: the candidate owns vA and the stale owner's switch list is empty.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(CANDIDATE)
		assertThat(registry.getSwitches(CANDIDATE)).contains(switchVA)
		assertThat(registry.getSwitches(OTHER_OWNER)).isEmpty()

		// And: the stale owner's release path can no longer unlock the candidate's live switch
		// -- the corruption consequence the issue describes.
		assertThat(registry.unregisterSwitches(OTHER_OWNER)).isEmpty()
		assertThat(switchVA.locked).isTrue()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(CANDIDATE)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a same-position candidate over a LIVE foreign switch is refused as transient contention")
	fun liveForeignOwnershipRefusesTheSamePositionCandidate() {
		// Given: vA is registered to a train that still holds a block bounded by vA on the OTHER
		// leg (k2 side) -- a live route the candidate's blocks do not conflict with, so only the
		// ownership check can refuse the traversal. A real route over k2 would need vA in BRANCH;
		// this arrangement is deliberately not physically consistent, because no consistent live
		// same-position owner exists without a block conflict that would refuse the candidate
		// first. It isolates the ownership guard -- do not "fix" it into a consistent route.
		assertThat(registry.registerAtomic(OTHER_OWNER, listOf(blockNearVAOffCandidatePath())))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		registry.registerSwitches(OTHER_OWNER, listOf(switchVA))

		// When / Then: before the fix this traversed vA and stole the ownership entry.
		assertCandidateRefusedAndOwnerIntact()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a flank-protected foreign switch is never reclaimed, even with no adjacent block")
	fun flankProtectedForeignSwitchIsNeverReclaimed() {
		// Given: vA is a FLANK grant (a facade `route.flank` lock, Issue #1076 review). The flank
		// owner holds NO block bounded by vA -- a flank switch protects a route it is not adjacent
		// to -- so without the flank marker Step 2e.5 would release this LIVE protection as stale.
		registry.registerFlankSwitches(OTHER_OWNER, listOf(switchVA))
		assertThat(registry.getBlocks(OTHER_OWNER)).isEmpty()

		// When / Then: the flank protection survives and refuses the candidate.
		assertCandidateRefusedAndOwnerIntact()
		assertThat(registry.isFlankProtected(switchVA)).isTrue()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a foreign owner on a switch with NO valid configuration keeps the candidate GeometricallyImpossible")
	fun foreignOwnerDoesNotMaskAGeometricImpossibility() {
		// Given: vA is a flank grant of another train -- the only foreign ownership Step 2e.5
		// never reclaims, so the ownership guard in configureSwitchesInPath really sees it.
		registry.registerFlankSwitches(OTHER_OWNER, listOf(switchVA))

		// When: the candidate asks for the impossible diversion doB1 -> doB2 (the Issue #742
		// shape). The route runs back over k1 to vA and on to k2, and no configuration of vA
		// joins branch k1 to branch k2, so the required configuration is null.
		val diversion = service.reservePath(CANDIDATE, semaphoreDoB1, semaphoreDoB2)

		// Then: the permanent geometric impossibility (#742) wins. The ownership guard must not
		// turn it into retryable contention that a live foreign owner would never resolve, so the
		// train would retry forever (Issue #1076 review).
		assertThat(diversion)
			.isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()

		// And: the foreign owner's grant is untouched by the candidate's rollback.
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(OTHER_OWNER)
		assertThat(switchVA.locked).isTrue()
		assertThat(registry.getBlocks(CANDIDATE)).isEmpty()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("a block release next to a flank-protected switch does not reclaim it (Issue #1065 path)")
	fun blockReleaseDoesNotReclaimAFlankProtectedSwitch() {
		registry.registerFlankSwitches(OTHER_OWNER, listOf(switchVA))

		passAndReleaseNeighbourBlockOfVA()

		// The flank grant survives the per-block reclamation.
		assertThat(switchVA.locked).isTrue()
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(OTHER_OWNER)
		assertThat(registry.isFlankProtected(switchVA)).isTrue()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("control: the same block release DOES reclaim a plain (non-flank) ownership")
	fun blockReleaseReclaimsAPlainStaleOwnership() {
		// The same arrangement with a plain registration proves the release really reaches the
		// reclamation, so the flank test above is not vacuous.
		registry.registerSwitches(OTHER_OWNER, listOf(switchVA))

		passAndReleaseNeighbourBlockOfVA()

		assertThat(switchVA.locked).isFalse()
		assertThat(registry.getSwitchOwner(switchVA)).isNull()
		assertThat(registry.getSwitches(OTHER_OWNER)).isEmpty()
	}

	/**
	 * The candidate asks for zA -> doB1 over vA in the SAME position [OTHER_OWNER] holds; it must
	 * be refused as TRANSIENT contention (never GeometricallyImpossible, which would make
	 * InOutWorker throw), and the owner's switch must be completely intact.
	 */
	private fun assertCandidateRefusedAndOwnerIntact() {
		val result = service.reservePath(CANDIDATE, semaphoreZA, semaphoreDoB1)

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat(switchVA.locked).isTrue()
		assertThat(switchVA.conf).isEqualTo(Conf.MAIN)
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(OTHER_OWNER)
		assertThat(registry.getSwitches(OTHER_OWNER)).contains(switchVA)
		assertThat(registry.getSwitches(CANDIDATE)).isEmpty()
		assertThat(registry.getBlocks(CANDIDATE)).isEmpty()
	}

	/**
	 * A neighbour train reserves a block bounded by vA and passes it, then releases it through
	 * the production per-block path (unregisterBlock -> dropFreedBlock -> the Issue #1065
	 * stale-lock reclamation).
	 */
	private fun passAndReleaseNeighbourBlockOfVA() {
		val block = blockNearVAOffCandidatePath()
		assertThat(registry.registerAtomic(NEIGHBOUR, listOf(block)))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		block.setUpPath(block.ends().first { it != switchVA } as DynamicPathSeparator, NEIGHBOUR)
		assertThat(service.passAndRelease(NEIGHBOUR, block)).isTrue()
	}

	/**
	 * The block bounded by vA on the k2 leg -- adjacent to the switch, but NOT on the candidate's
	 * zA -> doB1 route, so a reservation of it cannot conflict with the candidate.
	 */
	private fun blockNearVAOffCandidatePath(): DynamicTrackBlock {
		val candidateBlocks = simulationContext.routeBlocksOf(semaphoreZA, semaphoreDoB1).toSet()
		return simulationContext
			.topologicalPathBlocks(semaphoreZA, semaphoreDoB2)
			.flatten()
			.first { switchVA in it.ends() && it !in candidateBlocks }
	}

	private companion object {
		const val CANDIDATE = "train_1076_candidate"
		const val OTHER_OWNER = "train_1076_other_owner"
		const val NEIGHBOUR = "train_1076_neighbour"
	}
}
