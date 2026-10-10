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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * [ReservationTargetQuery.findReservationTargetCandidates] on `vyhybna.xml` (Issue #970): the
 * interlocking reports every settable next separator with its evaluated availability, in the
 * same order and with the same verdicts [PathReservationService.findNextReservationTarget] used
 * to collapse into a single pick. Mirrors the k1-occupied setup of [SwitchBlindTargetSelectionTest].
 *
 * @since Issue #970
 */
@DisplayName("findReservationTargetCandidates (Issue #970)")
@Timeout(10, unit = TimeUnit.SECONDS)
class ReservationTargetCandidatesTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var query: ReservationTargetQuery

	/** Same instance as [query]; the pick oracle the candidate list is cross-checked against. */
	private lateinit var service: PathReservationService

	private lateinit var semaphoreZA: OrientedPathSeparator // zA at (14,8), faces vA
	private lateinit var semaphoreDoA1: DynamicPathSeparator // doA1 at (16,8), k1 A-side
	private lateinit var semaphoreDoB1: DynamicPathSeparator // doB1 at (25,8), k1 B-side
	private lateinit var semaphoreDoB2: DynamicPathSeparator // doB2 at (24,9), k2 B-side

	@BeforeEach
	fun setUp() {
		simulationContext =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		query = simulationContext.getRoutingServices().getReservationTargetQuery()
		service = simulationContext.getRoutingServices().getPathReservationService()

		semaphoreZA = simulationContext.separatorAt(14, 8) as OrientedPathSeparator
		semaphoreDoA1 = simulationContext.separatorAt(16, 8)
		semaphoreDoB1 = simulationContext.separatorAt(25, 8)
		semaphoreDoB2 = simulationContext.separatorAt(24, 9)
	}

	/** The single block whose two ends are exactly [a] and [b]. */
	private fun blockBetween(
		a: DynamicPathSeparator,
		b: DynamicPathSeparator
	): DynamicTrackBlock =
		simulationContext
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.firstOrNull { block -> block.ends().toSet() == setOf(a, b) }
			?: throw IllegalStateException("No block found between $a and $b")

	/** Reserves k1 (doA1 - doB1) for [trainId] directly at block level, as [SwitchBlindTargetSelectionTest] does. */
	private fun reserveK1For(trainId: String) {
		val k1 = blockBetween(semaphoreDoA1, semaphoreDoB1)
		k1.setUpPath(semaphoreDoA1, trainId)
		assertThat(k1.getState(), name = "reserve k1").isEqualTo(TrackFacility.State.RESERVED)
	}

	/** Every oriented separator of the loaded grid: all signals and both station exits. */
	private fun allOrientedSeparators(): List<OrientedPathSeparator> =
		simulationContext.cellsOfType<DynamicRailSemaphore>().map { it as OrientedPathSeparator } +
			simulationContext.cellsOfType<DynamicInOut>().map { it as OrientedPathSeparator }

	@Test
	@DisplayName("with k1 occupied, zA reports doB1 unavailable and doB2 available, in search order")
	fun occupiedK1IsReportedUnavailableAndFreeK2Available() {
		reserveK1For("blocker_k1")

		val candidates = query.findReservationTargetCandidates(semaphoreZA, null)

		assertThat(candidates).containsExactly(
			ReservationTargetCandidate(semaphoreDoB1, available = false),
			ReservationTargetCandidate(semaphoreDoB2, available = true)
		)
	}

	@Test
	@DisplayName("the first available candidate is exactly findNextReservationTarget's pick, from every separator")
	fun firstAvailableCandidateEqualsFindNextReservationTarget() {
		reserveK1For("blocker_k1")
		val starts = allOrientedSeparators()
		assertThat(starts, name = "separators under test").isNotEmpty()

		for (start in starts) {
			val candidates = query.findReservationTargetCandidates(start, null)
			assertThat(candidates.firstOrNull { it.available }?.separator, name = "pick from $start")
				.isEqualTo(service.findNextReservationTarget(start))
		}
	}

	@Test
	@DisplayName("owner-aware query (Issue #1060): the owner's own reserved k1 counts as available")
	fun ownerAwareQueryCountsOwnBlocksAsAvailable() {
		reserveK1For("T1")

		val forOwner = query.findReservationTargetCandidates(semaphoreZA, "T1")
		val forStranger = query.findReservationTargetCandidates(semaphoreZA, "T2")

		assertThat(forOwner).containsExactly(
			ReservationTargetCandidate(semaphoreDoB1, available = true),
			ReservationTargetCandidate(semaphoreDoB2, available = true)
		)
		assertThat(forStranger).containsExactly(
			ReservationTargetCandidate(semaphoreDoB1, available = false),
			ReservationTargetCandidate(semaphoreDoB2, available = true)
		)
		assertThat(service.findNextReservationTarget(semaphoreZA, "T1"), name = "owner-aware pick")
			.isEqualTo(semaphoreDoB1)
	}
}
