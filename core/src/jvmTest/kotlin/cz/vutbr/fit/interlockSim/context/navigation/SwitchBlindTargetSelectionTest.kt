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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
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
 * Characterization test for the **switch-blind path-availability** defect that causes the
 * head-on gridlock observed in the `exampleGui shuntingLoop` (Goal 10 agent-dispatcher) path:
 * two opposing trains deadlock at red because the dispatcher keeps targeting the OCCUPIED
 * parallel track and never falls through to the FREE sibling track.
 *
 * ## Mechanism this test pins down
 *
 * With train `T` approaching switch `vA` from `zA`, and the k1 leg (`doA1 → doB1`) OCCUPIED by
 * an opposing train while the k2 leg (`doA2 → doB2`) is FREE:
 *
 * - [PathReservationService.findNextReservationTarget] enumerates BOTH exit semaphores
 *   `[doB1, doB2]` at the junction, then returns the *first* for which
 *   [PathReservationService.isPathAvailable] holds.
 * - `isPathAvailable` delegates to `TopologyNavigator.findAllTopologicalPaths`, which expands
 *   switches **without** honouring `RailSwitch.possibleFollowers` (it returns *all* joins). In
 *   `vyhybna.xml`, `doB1` sits on a separate 5-unit connector block between `k1` and switch
 *   `vB`, so there is an all-FREE **phantom** topological path to `doB1` that bypasses the
 *   occupied `k1` entirely:
 *
 *   `zA → vA → doA2 → k2(FREE) → doB2 → vB → [connector] → doB1`
 *
 *   That path reverses through `vB` from the k2 leg onto the k1 leg — physically impossible —
 *   yet its blocks are all free, so `isPathAvailable(zA, doB1)` returns a **false positive**.
 * - Because `doB1` is enumerated first and is (wrongly) deemed available, `firstOrNull` picks
 *   it and `doB2`/`k2` is never selected. `reservePath(zA, doB1)` then fails every candidate
 *   (real k1 path occupied; phantom path rejected by the #742 switch-config guard), so the
 *   train never diverts and the opposing pair gridlocks.
 *
 * ## What this test asserts (fixed behaviour — regression guard)
 *
 * Once availability is switch-aware (routing `isPathAvailable` through the switch-constrained
 * navigation that already exists as `getSwitchConstrainedNextTrackSections`), `doB1` is
 * reachable *only* via the occupied `k1`, so `isPathAvailable(zA, doB1)` is `false` and
 * `findNextReservationTarget(zA)` correctly falls through to the free `doB2`/`k2`. That is what
 * lets an opposing train divert and pass instead of deadlocking at red.
 *
 * @see Issue742RegressionTest for the reservation-time guard that rejects the phantom route.
 */
@DisplayName("Switch-blind isPathAvailable selects the occupied k1 target over the free k2")
class SwitchBlindTargetSelectionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var service: PathReservationService

	private lateinit var semaphoreZA: DynamicPathSeparator // zA at (14,8), faces vA
	private lateinit var semaphoreDoA1: DynamicPathSeparator // doA1 at (16,8), k1 A-side
	private lateinit var semaphoreDoB1: DynamicPathSeparator // doB1 at (25,8), k1 B-side
	private lateinit var semaphoreDoB2: DynamicPathSeparator // doB2 at (24,9), k2 B-side

	@BeforeEach
	fun setUp() {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		simulationContext =
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		service = simulationContext.getRoutingServices().getPathReservationService()

		val grid = simulationContext.getRailWayNetGrid()
		semaphoreZA = separatorAt(grid.getCellAt(14, 8), "zA")
		semaphoreDoA1 = separatorAt(grid.getCellAt(16, 8), "doA1")
		semaphoreDoB1 = separatorAt(grid.getCellAt(25, 8), "doB1")
		semaphoreDoB2 = separatorAt(grid.getCellAt(24, 9), "doB2")
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
	@DisplayName("occupied k1 does not steer the target to the free k2 — doB1 is falsely 'available'")
	fun occupiedK1IsFalselyAvailableSoFreeK2IsNeverSelected() {
		// Occupy ONLY the k1 block (doA1 → doB1, the single 100-unit track). This leaves the
		// shared zA→vA stub, the entire k2 leg (doA2/k2/doB2) and the doB1↔vB connector FREE —
		// exactly the state in which the opposing train #11 sits in k1 at the gridlock.
		val blocker = service.reservePath("blocker_k1", semaphoreDoA1, semaphoreDoB1)
		assertThat(blocker, name = "occupy k1")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Ground truth: the FREE parallel track k2 is genuinely reservable from zA.
		assertThat(service.isPathAvailable(semaphoreZA, semaphoreDoB2), name = "isPathAvailable(zA, doB2)")
			.isTrue()

		// The only physical path to doB1 traverses the OCCUPIED k1, so doB1 must NOT be
		// reservable. Switch-aware availability rejects the phantom k2→vB→doB1 reversal, so this
		// is false. (Before the fix, switch-blind `findAllTopologicalPaths` admitted the all-free
		// phantom route and returned a false positive here.)
		assertThat(service.isPathAvailable(semaphoreZA, semaphoreDoB1), name = "isPathAvailable(zA, doB1)")
			.isFalse()

		// Consequence: with doB1 correctly unavailable, findNextReservationTarget falls through
		// to doB2 — the free k2 track — which is what lets an opposing train divert and pass
		// instead of gridlocking at red. (Before the fix it committed to doB1 / occupied k1.)
		val target = service.findNextReservationTarget(semaphoreZA as OrientedPathSeparator)
		assertThat(target, name = "findNextReservationTarget(zA)")
			.isEqualTo(semaphoreDoB2)
	}
}
