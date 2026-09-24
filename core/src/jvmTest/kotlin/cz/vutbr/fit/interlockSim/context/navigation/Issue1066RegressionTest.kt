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
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
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
 * Regression tests for Issue #1066: a candidate that fails `mergePathInfo`'s Step 0a precondition
 * (`new.start == old.target`) used to be detected only at Step 2i, after blocks were reserved,
 * switches thrown and signals configured, and then reported as `AllPathsBlocked` ("track busy").
 *
 * The precondition now runs at Step 1.6, before any mutation, and a candidate that fails it is
 * reported as [PathReservationService.ReservationResult.DivergesFromHeldRoute].
 *
 * PR #1082 review round adds two guards around that gate:
 *  - a request STARTING at the held target (`doB1 -> B` while holding `zA -> doB1`) is a normal
 *    extension and must still reserve and MERGE (the gate must not be over-eager), and
 *  - the outer semaphore scan (`reservePathToAnyNextSemaphore`) reports the divergent verdict —
 *    not a retryable "all paths blocked" — when every enumerated semaphore diverges
 *    (review thread lABQY).
 *
 * Topology (vyhybna.xml): `zA(14,8) - vA(15,8) - doA1(16,8) ... doB1(25,8)` on the main leg and
 * `vA - doA2(17,9) ... doB2(24,9)` on the branch leg. InOuts: `A(11,8)`, `B(30,8)`.
 */
@DisplayName("Issue #1066 Regression: a candidate diverging from the held route is refused before any mutation")
class Issue1066RegressionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService

	private lateinit var zA: DynamicPathSeparator
	private lateinit var doB2: DynamicPathSeparator
	private lateinit var doB1: DynamicPathSeparator
	private lateinit var switchVA: DynamicRailSwitch

	private val trainId = "train_1066"

	@BeforeEach
	fun setUp() {
		simulationContext = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
		registry = simulationContext.scope.get()
		service = simulationContext.getRoutingServices().getPathReservationService()

		zA = simulationContext.separatorAt(14, 8)
		doB2 = simulationContext.separatorAt(24, 9)
		doB1 = simulationContext.separatorAt(25, 8)
		switchVA = simulationContext.separatorAt(15, 8) as? DynamicRailSwitch
			?: throw IllegalStateException("Switch 'vA' is not a DynamicRailSwitch")
	}

	@AfterEach
	fun tearDown() {
		simulationContext.close()
	}

	/** Signal aspect of every semaphore on the two legs, keyed by grid position. */
	private fun signalAspects(): Map<String, Signal> =
		listOf(14 to 8, 16 to 8, 17 to 9, 24 to 9, 25 to 8, 27 to 8).associate { (x, y) ->
			val semaphore = simulationContext.separatorAt(x, y) as DynamicRailSemaphore
			"$x,$y" to semaphore.signal
		}

	/** First track section on the shortest topological path [from] -> [to]. */
	private fun sectionBetween(
		from: DynamicPathSeparator,
		to: DynamicPathSeparator
	): TrackSection =
		simulationContext
			.getRoutingServices()
			.getTopologyNavigator()
			.findAllTopologicalPaths(from, to)
			.firstOrNull()
			?.filterIsInstance<TrackSection>()
			?.firstOrNull()
			?: throw IllegalStateException("No track section between $from and $to")

	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("zA -> doB1 while the stored path ends at doB2: refused with DivergesFromHeldRoute, nothing mutated")
	fun divergentCandidateIsRefusedWithoutMutation() {
		// Given: the train stands at zA holding zA -> doB2 (stored PathInfo target = doB2).
		assertThat(service.reservePath(trainId, zA, doB2))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val infoBefore = registry.getPathInfo(trainId)
		assertThat(infoBefore!!.target).isEqualTo(doB2)
		val blocksBefore = registry.getBlocks(trainId).map { it to it.getState() }
		val signalsBefore = signalAspects()
		assertThat(switchVA.conf).isEqualTo(Conf.BRANCH)

		// When: it requests zA -> doB1 -- zA still bounds a held block, but the candidate diverges
		// at vA, so its merge candidate would start at zA, not at old.target (doB2).
		val result = service.reservePath(trainId, zA, doB1)

		// Then: a distinct, non-contention denial naming the held target. The target must be
		// the plain endpoint name, not a debug rendering like "Dynamic[doB2, signal=STOP]"
		// (review thread on PR #1082): the LLM is told to extend from this exact name.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.DivergesFromHeldRoute>()
		val denial = result as PathReservationService.ReservationResult.DivergesFromHeldRoute
		assertThat(denial.heldTarget).isEqualTo("doB2")
		assertThat(denial.reason).contains("doB2")

		// And: no block, switch, signal or PathInfo changed.
		assertThat(registry.getBlocks(trainId).map { it to it.getState() }).isEqualTo(blocksBefore)
		assertThat(switchVA.conf).isEqualTo(Conf.BRANCH)
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(trainId)
		assertThat(signalAspects()).isEqualTo(signalsBefore)
		assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(infoBefore)
	}

	/**
	 * Reviewer recommendation (PR #1082): the screening must not be over-eager. A request that
	 * STARTS at the held route's target is the normal forward extension (Issue #911's shape) —
	 * `new.start == old.target` — so it passes Step 1.6 and must reserve and MERGE, not diverge.
	 * This is the guard that keeps the #1066 gate from eating legitimate dispatching.
	 *
	 * The extension target is InOut B, not zB: zB faces away from an eastbound arrival from doB1
	 * (G8, Issue #1064), so `doB1 -> zB` is geometrically impossible and no screening is involved.
	 * `doB1 -> B` is the real production shape — a train held at doB1 extending to the exit.
	 */
	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("doB1 -> B while holding zA -> doB1: legitimate extension, reserved and merged")
	fun legitimateExtensionFromHeldTargetIsNotRefused() {
		// Given: the train holds the real main-leg route zA -> doB1 (old.target = doB1).
		assertThat(service.reservePath(trainId, zA, doB1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val infoBefore = registry.getPathInfo(trainId)
		assertThat(infoBefore!!.target).isEqualTo(doB1)
		val blocksBefore = registry.getBlocks(trainId).size

		// When: it extends from doB1 -- the start equals the stored target, exactly the merge
		// shape Step 1.6 lets through.
		val result = service.reservePath(trainId, doB1, simulationContext.separatorAt(30, 8))

		// Then: success, and the stored PathInfo is a MERGED one ending at B -- a new object,
		// not the pre-extension instance.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val merged = registry.getPathInfo(trainId)
		assertThat(merged).isNotSameInstanceAs(infoBefore)
		assertThat(merged!!.target).isEqualTo(simulationContext.separatorAt(30, 8))
		assertThat(registry.getBlocks(trainId).size).isGreaterThan(blocksBefore)
	}

	/**
	 * Pins the outer aggregation (review thread lABQY on PR #1082):
	 * [PathReservationService.reservePathToAnyNextSemaphore] counts a divergent attempt in
	 * `divergentAttempts`. When EVERY enumerated semaphore diverges, the gate in
	 * `classifyExhaustedSemaphores` holds (`geometricAttempts + divergentAttempts == attemptCount`)
	 * and the caller sees the distinct [PathReservationService.ReservationResult.DivergesFromHeldRoute]
	 * verdict, not a retryable "all paths blocked".
	 */
	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("reservePathToAnyNextSemaphore with every target divergent reports DivergesFromHeldRoute")
	fun outerScanReportsDivergentWhenEveryTargetDiverges() {
		// Given: the train stands at zA holding zA -> doB2 (branch), so old.target = doB2.
		assertThat(service.reservePath(trainId, zA, doB2))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val infoBefore = registry.getPathInfo(trainId)
		val blocksBefore = registry.getBlocks(trainId).map { it.getState() }

		// When: the semaphore scan starts at vA along the main leg. The only forward semaphore
		// it finds is doB1; every candidate for vA -> doB1 either starts elsewhere than old.target
		// (divergent, Step 1.6) or arrives at doB1 rear-facing (geometric, Step 1.5).
		val result =
			service.reservePathToAnyNextSemaphore(
				trainId,
				switchVA,
				sectionBetween(switchVA, simulationContext.separatorAt(16, 8))
			)

		// Then: the outer verdict is the distinct divergent refusal, naming the held target.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.DivergesFromHeldRoute>()
		val denial = result as PathReservationService.ReservationResult.DivergesFromHeldRoute
		assertThat(denial.heldTarget).isEqualTo("doB2")
		assertThat(denial.reason).contains("vA")
		// And nothing was reserved or merged behind it.
		assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(infoBefore)
		assertThat(registry.getBlocks(trainId).map { it.getState() }).isEqualTo(blocksBefore)
	}
}
