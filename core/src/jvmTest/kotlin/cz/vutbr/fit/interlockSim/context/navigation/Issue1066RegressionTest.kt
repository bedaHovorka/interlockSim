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
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
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
 * The precondition now runs at Step 2a.5, before any mutation, and a candidate that fails it is
 * reported as [PathReservationService.ReservationResult.DivergesFromHeldRoute].
 *
 * Topology (vyhybna.xml): `zA(14,8) - vA(15,8) - doA1(16,8) ... doB1(25,8)` on the main leg and
 * `vA - doA2(17,9) ... doB2(24,9)` on the branch leg.
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

		// Then: a distinct, non-contention denial naming the held target.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.DivergesFromHeldRoute>()
		val denial = result as PathReservationService.ReservationResult.DivergesFromHeldRoute
		assertThat(denial.reason).contains("doB2")

		// And: no block, switch, signal or PathInfo changed.
		assertThat(registry.getBlocks(trainId).map { it to it.getState() }).isEqualTo(blocksBefore)
		assertThat(switchVA.conf).isEqualTo(Conf.BRANCH)
		assertThat(registry.getSwitchOwner(switchVA)).isEqualTo(trainId)
		assertThat(signalAspects()).isEqualTo(signalsBefore)
		assertThat(registry.getPathInfo(trainId)).isSameInstanceAs(infoBefore)
	}
}
