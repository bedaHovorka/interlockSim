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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Issue #834 (SP2c.11): `reservePath` must survive an aborted PathInfo merge at **both** of its
 * `registerPathInfo` call sites.
 *
 * ## Why this is at service level and not registry level
 *
 * The registry-level behaviour is pinned by [PathReservationRegistryMergingTest]. What that
 * suite cannot show is the thing that actually mattered: `registerPathInfo` is called from
 * `DefaultPathReservationService.reservePath` **after** blocks have been reserved
 * (`registerAtomic`), switches configured and locked, and the START signal cleared. Anything
 * thrown there escapes `reservePath` with the interlocking already half-committed and no
 * rollback — and `DispatchDecisionApplier.onControlStep` catches only `IllegalArgumentException`,
 * so an `IllegalStateException` propagated out onto the kDisco simulation thread and killed it
 * while the run still wrote a well-formed result file.
 *
 * There are two such call sites, and only one of them was documented:
 *
 * 1. **Step 2i** — the normal commit path, after `registerAtomic` / `configureAndRegisterSwitches`
 *    / `configureStartSignal` / `configureIntermediateSemaphores`.
 * 2. **The already-owned early return** — reached when every block of the requested candidate is
 *    already held by this train, *after* `configureAlreadyOwnedStartSignal` has already lit a
 *    signal.
 *
 * Both are exercised here against real `vyhybna.xml` track objects, because the whole point is
 * the state left behind in the interlocking, not the call sequence.
 *
 * ## What "abort" must look like from the caller's side (updated by Issue #904)
 *
 * No exception either way, and a genuine abort never truncates or partially updates the stored
 * PathInfo (invariant I2, the #316 rule). Per the traffic-simulation-expert Issue #904 ruling,
 * plus a root-cause fix discovered while implementing it:
 *
 * - **Step 2i** used to build its merge candidate from the caller-supplied `start`, which for a
 *   route EXTENSION reusing its ORIGINAL start (the shape Issue #911 already established happens
 *   in production) made `new.start != old.target` on *every* such call -- not a rare shape, the
 *   routine one -- so the merge spuriously aborted for legitimate extensions. The fix builds the
 *   merge candidate from the FORWARD-ONLY segment instead (starting where the new blocks
 *   actually begin), so `new.start` agrees with `old.target` and the merge genuinely SUCCEEDS
 *   for this shape (see [step2iMergeAbortDoesNotThrow], which now pins a real merge, not an
 *   abort). Step 2i's rollback (transactionally complete, matching the #901 standard) now fires
 *   only for genuinely pathological aborts -- duplicated new-start, the 3rd-occurrence cycle
 *   guard -- where it releases exactly what the rejected candidate acquired and tries the next
 *   candidate; an exhausted attempt with no other failure mode reports `AllPathsBlocked`. There
 *   is no orphaned RESERVED tail left for `OrphanReservationSweeper` to reclaim either way.
 * - **The already-owned early return** acquires no new blocks/switches -- the train already owns
 *   everything -- so an abort there has nothing to roll back; it still reports `Success` (the
 *   train's actual block ownership is correct and unchanged), only resetting the signal it may
 *   have just re-cleared. See `dispatcher-agent`'s `MergeAbortSimSurvivalTest`.
 *
 * ## Topology (vyhybna.xml)
 *
 * ```
 * A(11,8) ── zA(14,8) ── vA(15,8) ── doA1(16,8) ──────── doB1(25,8) ── vB(26,8) ── zB(27,8) ── B(30,8)
 *                          └──────── doA2(17,9) ──────── doB2(24,9) ────┘
 * ```
 *
 * `zA → doA1` spans two blocks (zA–vA, vA–doA1); `zA → doB1` extends that by one more
 * (doA1–doB1). Both routes start at `zA`, so `rejectNonContiguousStart` (Step 0, footprint-based)
 * passes for the second request while the *PathInfo* merge is still non-contiguous — which is
 * precisely the shape that reaches the merge abort.
 */
@DisplayName("reservePath survives an aborted PathInfo merge at both registerPathInfo call sites")
@Tag("integration-test")
class MergeAbortNeverThrowsTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService

	private lateinit var zA: DynamicPathSeparator
	private lateinit var doA1: DynamicPathSeparator
	private lateinit var doB1: DynamicPathSeparator
	private lateinit var inOutA: DynamicPathSeparator

	private val trainId = "Train #1"

	@BeforeEach
	fun setUp() {
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
		testContext = simulationContext

		registry = simulationContext.scope.get()
		service = simulationContext.getRoutingServices().getPathReservationService()

		zA = separatorAt(14, 8)
		doA1 = separatorAt(16, 8)
		doB1 = separatorAt(25, 8)
		inOutA = separatorAt(11, 8)
	}

	/** Canonical dynamic wrapper for the grid cell at ([x], [y]) — the identity the registry keys on. */
	private fun separatorAt(
		x: Int,
		y: Int
	): DynamicPathSeparator {
		val cell = simulationContext.getRailWayNetGrid()[Point(x, y)]
		val separator = cell as? PathSeparator ?: throw IllegalStateException("No separator at ($x, $y): $cell")
		return simulationContext.toDynamic(separator)
	}

	private fun reserve(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): PathReservationService.ReservationResult = service.reservePath(trainId, start, target)

	@Test
	@DisplayName("Step 2i: a route extension reusing its original start now MERGES instead of aborting (Issue #904)")
	fun step2iMergeAbortDoesNotThrow() {
		// Given: the train holds zA → doA1
		assertThat(reserve(zA, doA1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val storedBefore = registry.getPathInfo(trainId)
		assertThat(storedBefore).isNotNull()
		assertThat(storedBefore!!.target).isEqualTo(doA1)

		// When: it requests zA → doB1, reusing the ORIGINAL start zA (not the current front
		// doA1) -- exactly the route-extension shape Issue #911 already established happens in
		// production. Step 0 passes (zA still bounds a held block); the extra block doA1–doB1 is
		// genuinely new.
		val result = reserve(zA, doB1)

		// Then (Issue #904 root-cause fix): reservePath now builds the Step 2i merge candidate
		// from the FORWARD-ONLY segment (starting at doA1, where the new block actually begins),
		// not from the caller-supplied zA -- so new.start (doA1) equals old.target (doA1) and the
		// merge SUCCEEDS, properly extending PathInfo, instead of spuriously aborting on every
		// such extension. This is a real, correct extension, not an orphaned tail.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val held = registry.getBlocks(trainId)
		assertThat(held.isNotEmpty()).isTrue()
		held.forEach { block ->
			assertThat(block.trainName).isEqualTo(trainId)
			assertThat(
				block.getState() == TrackFacility.State.RESERVED ||
					block.getState() == TrackFacility.State.OCCUPIED
			).isTrue()
		}

		// The PathInfo genuinely advanced: front moved from doA1 to doB1, tail stayed at zA.
		val merged = registry.getPathInfo(trainId)
		assertThat(merged).isNotNull()
		assertThat(merged!!.start).isEqualTo(zA)
		assertThat(merged.target).isEqualTo(doB1)
		assertThat(merged !== storedBefore).isTrue()
	}

	@Test
	@DisplayName("already-owned early return: a non-contiguous merge aborts, reservePath still returns Success")
	fun alreadyOwnedMergeAbortDoesNotThrow() {
		// Given: the train holds the LONGER route zA → doB1, which subsumes zA → doA1.
		assertThat(reserve(zA, doB1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val storedBefore = registry.getPathInfo(trainId)
		assertThat(storedBefore).isNotNull()
		assertThat(storedBefore!!.target).isEqualTo(doB1)

		// Precondition for the branch under test: every block of the shorter zA → doA1 candidate
		// must already be owned, otherwise the request would take the Step 2i path instead and
		// this test would silently stop covering the early return.
		val heldEnds = registry.getBlocks(trainId).flatMap { it.ends().toList() }.toSet()
		assertThat(heldEnds.contains(doA1)).isTrue()

		// When: it re-requests the shorter zA → doA1. All blocks are already owned, so
		// forwardBlocks is empty and control reaches the already-owned early return — after
		// configureAlreadyOwnedStartSignal has already lit zA. The requested target (doA1)
		// differs from the held target (doB1), so the no-op short circuit does not apply and
		// registerPathInfo is called with a non-contiguous new.start.
		val result = reserve(zA, doA1)

		// Then: no exception escaped, Success is still reported.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// The longer route's PathInfo is untouched — in particular it was NOT shortened to doA1,
		// which would have stranded the train's already-reserved doA1–doB1 block.
		assertThat(registry.getPathInfo(trainId)!!.target).isEqualTo(doB1)
		assertThat(registry.getPathInfo(trainId) === storedBefore).isTrue()

		val held = registry.getBlocks(trainId)
		assertThat(held.isNotEmpty()).isTrue()
		held.forEach { block -> assertThat(block.trainName).isEqualTo(trainId) }
	}

	@Test
	@DisplayName("golden equivalence: a contiguous extension still merges and advances the front")
	fun contiguousExtensionStillMerges() {
		// The happy path must be untouched by the abort guards (invariant I4).
		// A → zA first, so that the second request can start exactly at the stored front.
		assertThat(reserve(inOutA, zA))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val lengthBefore = registry.getPathInfo(trainId)!!.reservedPath.size

		// zA IS the current front, so this extension is contiguous and must merge normally.
		assertThat(reserve(zA, doA1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		val merged = registry.getPathInfo(trainId)
		assertThat(merged).isNotNull()
		assertThat(merged!!.start).isEqualTo(inOutA) // tail preserved
		assertThat(merged.target).isEqualTo(doA1) // front advanced
		assertThat(merged.reservedPath.size > lengthBefore).isTrue()
	}
}
