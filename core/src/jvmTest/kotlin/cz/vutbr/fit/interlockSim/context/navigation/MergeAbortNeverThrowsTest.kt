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
import cz.vutbr.fit.interlockSim.context.EditingContext
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
 * ## What "abort" must look like from the caller's side
 *
 * `Success`, no exception, blocks still RESERVED to this train, and `getPathInfo(train).target`
 * still the **old** target — the stored PathInfo is never truncated or partially updated
 * (invariant I2, the #316 rule). The newly reserved blocks that the aborted merge left out of
 * the PathInfo become an orphaned RESERVED tail; that tail is reclaimable by
 * `OrphanReservationSweeper` → `RegistryPartialRouteReleaser.releaseUntravelledTail`, which also
 * drives its semaphores back to STOP. See `dispatcher-agent`'s `MergeAbortSimSurvivalTest`.
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
		val editingContext =
			TestFixtures.loadShuntingXml().use { stream ->
				editingContextFactory.createContext(stream) as EditingContext
			}
		simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
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
	@DisplayName("Step 2i: a non-contiguous merge aborts, reservePath still returns Success")
	fun step2iMergeAbortDoesNotThrow() {
		// Given: the train holds zA → doA1
		assertThat(reserve(zA, doA1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val storedBefore = registry.getPathInfo(trainId)
		assertThat(storedBefore).isNotNull()
		assertThat(storedBefore!!.target).isEqualTo(doA1)

		// When: it requests zA → doB1. Step 0 passes (zA still bounds a held block), the extra
		// block doA1–doB1 is genuinely new, so the request reaches Step 2i — where the merge is
		// non-contiguous, because new.start (zA) != old.target (doA1).
		val result = reserve(zA, doB1)

		// Then: no exception escaped reservePath, and it still reports Success.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// The blocks really are reserved to this train — the abort is a PathInfo-only decision
		// and must not touch block ownership (invariant I1).
		val held = registry.getBlocks(trainId)
		assertThat(held.isNotEmpty()).isTrue()
		held.forEach { block ->
			assertThat(block.trainName).isEqualTo(trainId)
			assertThat(
				block.getState() == TrackFacility.State.RESERVED ||
					block.getState() == TrackFacility.State.OCCUPIED
			).isTrue()
		}

		// And the stored PathInfo is byte-for-byte the pre-merge object: same instance, old target.
		assertThat(registry.getPathInfo(trainId)!!.target).isEqualTo(doA1)
		assertThat(registry.getPathInfo(trainId) === storedBefore).isTrue()
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
