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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Regression tests for Issue #742: a physically impossible sibling-branch "diversion"
 * (e.g. `doB1 → doB2`) could be reserved as [PathReservationService.ReservationResult.Success]
 * even though no configuration of switch `vB` joins the k1 leg to the k2 leg.
 *
 * ## Root Cause (captured from a failing `RuleBasedDispatcherDeterminismTest` run)
 *
 * 1. A train on branch k1 stands at exit semaphore `doB1` while its through-exit toward `B`
 *    is momentarily blocked by an opposing train.
 * 2. `findNextReservationTarget(doB1)` offers the first FREE target `doB2` — the sibling
 *    parallel branch's semaphore. That route requires switch `vB` to join its two branch
 *    legs, which no switch configuration allows.
 * 3. `reservePath(train, doB1, doB2)` reserved the blocks, locked `vB`, then
 *    `configureSwitchesInPath` threw `PathSeparatorChangeException` ("switch doesn't join
 *    this segments") — which was swallowed — and the reservation still returned Success
 *    with the start signal set to proceed.
 * 4. The train was committed to an untraversable route; `isPathExtendedBeyond(doB1)`
 *    then suppressed the corrective `doB1 → B` decision forever — a permanent stall that
 *    gridlocked the network (`trainsExited` 1 or 3 instead of 5).
 *
 * ## Fix (Issue #742)
 *
 * `reservePath()` must treat a genuinely traversed switch that cannot be configured as a
 * failed candidate path: roll the candidate back (blocks, switch locks) and try the next
 * candidate / return AllPathsBlocked. The train then simply WAITS at its current semaphore
 * and the through route is reserved at a future simulation time once it frees — partial
 * paths plus wait-then-extend are the correct railway semantics.
 */
@DisplayName("Issue #742 Regression: impossible sibling-branch diversion must not reserve")
class Issue742RegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService

	// Elements from vyhybna.xml (parallel branches k1 at row 8, k2 at row 9)
	private lateinit var inOutB: DynamicPathSeparator // InOut B at (30,8)
	private lateinit var semaphoreZA: DynamicPathSeparator // zA at (14,8)
	private lateinit var semaphoreZB: DynamicPathSeparator // zB at (27,8)
	private lateinit var semaphoreDoA1: DynamicPathSeparator // doA1 at (16,8), guards k1
	private lateinit var semaphoreDoA2: DynamicPathSeparator // doA2 at (17,9), guards k2
	private lateinit var semaphoreDoB1: DynamicPathSeparator // doB1 at (25,8), guards k1
	private lateinit var semaphoreDoB2: DynamicPathSeparator // doB2 at (24,9), guards k2
	private lateinit var switchVB: DynamicRailSwitch // vB at (26,8)

	@BeforeEach
	fun setUp() {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

		registry = simulationContext.scope.get()
		service = simulationContext.getRoutingServices().getPathReservationService()

		val grid = simulationContext.getRailWayNetGrid()
		val inOuts = simulationContext.getInOuts().toList()

		inOutB = inOuts.find { it.name == "B" } as? DynamicPathSeparator
			?: throw IllegalStateException("InOut 'B' not found in vyhybna.xml")

		semaphoreZA = separatorAt(grid.getCellAt(14, 8), "zA")
		semaphoreZB = separatorAt(grid.getCellAt(27, 8), "zB")
		semaphoreDoA1 = separatorAt(grid.getCellAt(16, 8), "doA1")
		semaphoreDoA2 = separatorAt(grid.getCellAt(17, 9), "doA2")
		semaphoreDoB1 = separatorAt(grid.getCellAt(25, 8), "doB1")
		semaphoreDoB2 = separatorAt(grid.getCellAt(24, 9), "doB2")
		switchVB = separatorAt(grid.getCellAt(26, 8), "vB") as? DynamicRailSwitch
			?: throw IllegalStateException("Switch 'vB' is not a DynamicRailSwitch")
	}

	private fun separatorAt(
		cell: Any?,
		name: String
	): DynamicPathSeparator =
		(cell as? PathSeparator)
			?.let { simulationContext.toDynamic(it) }
			?: throw IllegalStateException("Separator '$name' not found in vyhybna.xml")

	@AfterEach
	fun tearDown() {
		simulationContext.close()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("impossible diversion doB1→doB2 is rejected without leaking blocks, locks or PathInfo")
	fun impossibleDiversionIsRejectedCleanly() {
		val trainId = "train_742"

		// Train enters its real branch: zA → doB1 (via vA onto k1).
		val entry = service.reservePath(trainId, semaphoreZA, semaphoreDoB1)
		assertThat(entry).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val blocksAfterEntry = registry.getBlocks(trainId)
		val pathInfoAfterEntry = registry.getPathInfo(trainId)
		assertThat(pathInfoAfterEntry).isNotNull()

		// The dispatcher's impossible diversion decision (issued when doB1 → B is blocked):
		// no configuration of switch vB joins branch k1 to branch k2.
		val diversion = service.reservePath(trainId, semaphoreDoB1, semaphoreDoB2)

		// The reservation must FAIL — the train has to wait for its through route instead.
		assertThat(diversion)
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// No blocks may leak from the failed candidate (k2 / junction blocks stay free).
		assertThat(registry.getBlocks(trainId)).isEqualTo(blocksAfterEntry)

		// Switch vB must not stay locked by the failed attempt.
		assertThat(switchVB.locked).isFalse()

		// PathInfo must be unchanged: still ending at doB1, same shape.
		val pathInfoAfterDiversion = registry.getPathInfo(trainId)
		assertThat(pathInfoAfterDiversion).isNotNull()
		assertThat(pathInfoAfterDiversion!!.target).isEqualTo(pathInfoAfterEntry!!.target)
		assertThat(pathInfoAfterDiversion.reservedPath.size)
			.isEqualTo(pathInfoAfterEntry.reservedPath.size)
		assertNoAdjacentSeparators(pathInfoAfterDiversion)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("train waits at doB1 and extends to B at a future simulation time (partial path)")
	fun trainWaitsAndExtendsWhenThroughRouteFrees() {
		val trainId = "train_742_waiter"
		val blockerId = "train_742_blocker"

		// Train on k1 with a partial path ending at doB1.
		val entry = service.reservePath(trainId, semaphoreZA, semaphoreDoB1)
		assertThat(entry).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Opposing train holds the B-side entry (B → zB), blocking the through route.
		val blocker = service.reservePath(blockerId, inOutB, semaphoreZB)
		assertThat(blocker).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Continuation doB1 → B is blocked: the train must WAIT (state untouched).
		val attempt = service.reservePath(trainId, semaphoreDoB1, inOutB)
		assertThat(attempt)
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		val pathInfoWhileWaiting = registry.getPathInfo(trainId)
		assertThat(pathInfoWhileWaiting).isNotNull()
		assertThat(pathInfoWhileWaiting!!.target).isEqualTo(semaphoreDoB1)

		// Future simulation time: the blocker clears the B side.
		service.releasePath(blockerId)

		// The same extension now succeeds and merges as a clean continuation.
		val extension = service.reservePath(trainId, semaphoreDoB1, inOutB)
		assertThat(extension).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val mergedPathInfo = registry.getPathInfo(trainId)
		assertThat(mergedPathInfo).isNotNull()
		assertThat(mergedPathInfo!!.target).isEqualTo(inOutB)
		assertThat(mergedPathInfo.reservedPath.count { it == semaphoreDoB1 }).isEqualTo(1)
		assertNoAdjacentSeparators(mergedPathInfo)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("legitimate parallel-branch alternate zB→doA2 still succeeds when k1 is taken")
	fun legitimateParallelAlternateStillSucceeds() {
		val opposingId = "train_742_opposing"
		val trainId = "train_742_entering"

		// Opposing train occupies branch k1 (traveling A → B direction).
		val opposing = service.reservePath(opposingId, semaphoreZA, semaphoreDoB1)
		assertThat(opposing).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Entering train from the B side: k1 (via doA1) is taken by the opposing train...
		val viaK1 = service.reservePath(trainId, semaphoreZB, semaphoreDoA1)
		assertThat(viaK1)
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// ...but the legitimate same-direction alternate via k2 (doA2) must still work:
		// switch vB CAN join the zB side to the k2 leg. This is the healthy crossing
		// pattern of the shunting loop and must survive the Issue #742 fix.
		val viaK2 = service.reservePath(trainId, semaphoreZB, semaphoreDoA2)
		assertThat(viaK2).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val pathInfo = registry.getPathInfo(trainId)
		assertThat(pathInfo).isNotNull()
		assertThat(pathInfo!!.target).isEqualTo(semaphoreDoA2)
		assertNoAdjacentSeparators(pathInfo)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("unconfigurable switch rejection causes no orphaned locks (rollback coverage)")
	fun unconfigurableSwitchRollbackCleansUpLocks() {
		val trainId = "train_742_rollback_test"

		// Train enters via k1: zA → doA1
		val entry = service.reservePath(trainId, semaphoreZA, semaphoreDoA1)
		assertThat(entry).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Snapshot initial state
		val blocksBeforeAttempt = registry.getBlocks(trainId).toSet()
		val switchesBeforeAttempt = registry.getSwitches(trainId).toSet()

		// Attempt impossible diversion that would lock switchVB in configureSwitchesInPath
		// (but fail after setting up the lock, then rollback)
		val impossible = service.reservePath(trainId, semaphoreDoA1, semaphoreDoB2)

		// Must fail cleanly
		assertThat(impossible)
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// Verify NO NEW blocks are registered
		assertThat(registry.getBlocks(trainId).toSet()).isEqualTo(blocksBeforeAttempt)

		// Verify NO NEW switches are registered (switchVB must not stay locked)
		assertThat(registry.getSwitches(trainId).toSet()).isEqualTo(switchesBeforeAttempt)

		// Verify switchVB is unlocked (not held by failed attempt)
		assertThat(switchVB.locked).isFalse()
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("null-segment lenient-skip preserves switch unlocked and blocks free (Issue #300)")
	fun nullSegmentLenientSkipDoesNotCorruptState() {
		val trainId = "train_742_null_segment"

		// Train takes a partial path: zA → doB1
		val partial = service.reservePath(trainId, semaphoreZA, semaphoreDoB1)
		assertThat(partial).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val blocksAfterPartial = registry.getBlocks(trainId).toSet()

		// Now attempt to extend to B: doB1 → inOutB. This path's geometry through switchVB
		// may result in null segments (path doesn't geometrically traverse the switch in
		// both directions), triggering the lenient-skip branch at line 1653.
		// The train must not corrupt vB or reserve extra blocks.
		val extension = service.reservePath(trainId, semaphoreDoB1, inOutB)

		// Extension succeeds cleanly (or fails if B is already blocked, but not corrupted)
		val finalBlocks = registry.getBlocks(trainId).toSet()
		if (extension is PathReservationService.ReservationResult.Success) {
			// If extension succeeded, blocks should only increase (no corruption)
			assertThat(finalBlocks).isNotEmpty()
			// All original blocks must be preserved
			blocksAfterPartial.forEach { block ->
				assertThat(finalBlocks).contains(block)
			}
		}

		// In either case, switchVB must not be locked if it wasn't before the extension
		val switchesBeforeExtension = registry.getSwitches(trainId)
		if (!switchesBeforeExtension.contains(switchVB)) {
			assertThat(switchVB.locked).isFalse()
		}
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("partial-lock rollback filters out pre-existing switches (train path extension)")
	fun partialLockRollbackDoesNotUnlockPriorSwitches() {
		val trainId = "train_742_prior_switches"

		// Phase 1: Initial reservation via vA (registers vA as a switch owned by this train)
		val phase1 = service.reservePath(trainId, semaphoreZA, semaphoreDoB1)
		assertThat(phase1).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val switchesAfterPhase1 = registry.getSwitches(trainId).toSet()
		// Verify vA (or some switch) is now registered
		assertThat(switchesAfterPhase1.size).isGreaterThanOrEqualTo(1)

		// Snapshot phase1 blocks
		val blocksPhase1 = registry.getBlocks(trainId).toSet()

		// Phase 2: Attempt impossible extension that would lock vB
		// (rollbackUnconfigurableCandidate should filter vB out, not unlocking vA)
		val phase2 = service.reservePath(trainId, semaphoreDoB1, semaphoreDoB2)
		assertThat(phase2)
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// After rollback, Phase 1 switches must still be registered (not double-unlocked)
		val switchesAfterPhase2 = registry.getSwitches(trainId).toSet()
		assertThat(switchesAfterPhase2).isEqualTo(switchesAfterPhase1)

		// After rollback, Phase 1 blocks must be preserved (no corruption)
		assertThat(registry.getBlocks(trainId).toSet()).isEqualTo(blocksPhase1)
	}

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("block-unregister rollback cancels and unregisters all forwardBlocks")
	fun blockUnregisterRollbackCancelsAllForwardBlocks() {
		val trainId = "train_742_block_unregister"

		// Reserve initial path: zA → doA1 to establish baseline
		val initial = service.reservePath(trainId, semaphoreZA, semaphoreDoA1)
		assertThat(initial).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val initialBlocks = registry.getBlocks(trainId).toSet()
		assertThat(initialBlocks.size).isGreaterThanOrEqualTo(1)

		// Attempt impossible extension: doA1 → doB2
		// This triggers reservation of forwardBlocks, then rollback when switch cannot
		// be configured. rollbackUnconfigurableCandidate must cancel each block before
		// unregistering it (line 1950: block.cancelPathSetup(reservedFrom))
		val impossible = service.reservePath(trainId, semaphoreDoA1, semaphoreDoB2)
		assertThat(impossible)
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()

		// After rollback, all forwardBlocks must be unregistered
		val remainingBlocks = registry.getBlocks(trainId).toSet()
		assertThat(remainingBlocks).isEqualTo(initialBlocks)

		// Verify all remaining blocks have reservedFrom set (they are reserved by this train
		// from their entry point). This ensures the rollback properly restored the state.
		remainingBlocks.forEach { block ->
			// Each block should have reservedFrom pointing to a valid path separator
			assertThat(block.reservedFrom).isNotNull()
		}
	}

	// Helper: a structurally valid reserved path never has two adjacent separators
	// (a separator directly followed by another separator means a disconnected splice).
	private fun assertNoAdjacentSeparators(pathInfo: PathInfo) {
		val elements = pathInfo.reservedPath.toList()
		for (i in 1 until elements.size) {
			val previous = elements[i - 1]
			val current = elements[i]
			val adjacentSeparators =
				previous is DynamicPathSeparator && current is DynamicPathSeparator
			assertThat(adjacentSeparators).isFalse()
		}
	}
}
