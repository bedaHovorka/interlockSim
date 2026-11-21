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
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1390: candidate discovery and the G8 end screen must agree about which way a signal faces.
 *
 * On the Praha station asset a train at `S14` was offered `S15` as a next signal facing it
 * ([ReservationTargetQuery.findReservationTargetCandidates]), while `reservePath` refused every
 * `S14 -> S15` candidate at G8 ([RouteCandidateScreen.rearFacingEndReason]): the route end "faces
 * away from the direction of travel". An LLM dispatcher that followed the offer stalled at the #943
 * bound.
 *
 * Both tests replay `reservePath`'s own candidate pipeline read-only: the topological candidates of
 * the request, then the Step 1.4 start screen and the Step 1.5 (G8) end screen. Nothing is reserved.
 */
@Tag("integration-test")
class PrahaDiscoveryG8AgreementTest : CommonKoinTestBase() {
	private lateinit var ctx: DefaultSimulationContext
	private lateinit var screen: RouteCandidateScreen
	private lateinit var navigator: TopologyNavigator
	private lateinit var query: ReservationTargetQuery

	@BeforeEach
	fun setUp() {
		ctx = CommonTestFixtures.parseSimulationContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, get()).tracked()
		val routing = ctx.getRoutingServices()
		screen = (routing.getPathReservationService() as DefaultPathReservationService).candidateScreen
		navigator = routing.getTopologyNavigator()
		query = routing.getReservationTargetQuery()
	}

	@Test
	fun `every signal offered from S14 has a candidate that passes the start and end screens`() {
		val s14 = semaphore(S14)

		assertThat(offeredSignalsWithoutUsableCandidate(s14), name = "signals offered from $S14 that G8 refuses")
			.isEmpty()
	}

	@Test
	fun `every signal offered from any Praha signal has a candidate that passes the start and end screens`() {
		val refused =
			ctx.cellsOfType<DynamicRailSemaphore>().sortedBy { it.name }.flatMap { start ->
				offeredSignalsWithoutUsableCandidate(start).map { "${start.name} -> $it" }
			}

		assertThat(refused, name = "offered signals that G8 refuses").isEmpty()
	}

	@Test
	fun `a G8 refusal from S14 never lists the refused end as a signal facing the train`() {
		val s14 = semaphore(S14)
		val contradictions = mutableListOf<String>()
		var refusals = 0
		for (target in ctx.cellsOfType<DynamicRailSemaphore>()) {
			if (target == s14) continue
			val (_, startUsable) = screen.screenRearFacingStarts(s14, candidatesOf(s14, target))
			for ((index, path) in startUsable) {
				val reason = screen.rearFacingEndReason(s14, target, path, index) ?: continue
				refusals++
				val facingList = reason.substringAfter("are: ").removeSuffix(".").split(", ")
				if (target.name in facingList) contradictions += "${target.name} (candidate $index): $reason"
			}
		}

		assertThat(refusals, name = "G8 refusals from $S14 (the check must not be vacuous)").isGreaterThan(0)
		assertThat(contradictions, name = "G8 refusals that list the refused end as facing").isEmpty()
	}

	/** Names of the Signal targets offered from [start] whose every candidate the start or end screen refuses. */
	private fun offeredSignalsWithoutUsableCandidate(start: DynamicRailSemaphore): List<String> =
		query
			.findReservationTargetCandidates(start, null)
			.map { it.separator }
			.filterIsInstance<DynamicRailSemaphore>()
			.filter { target ->
				val (_, startUsable) = screen.screenRearFacingStarts(start, candidatesOf(start, target))
				val (_, endUsable) = screen.screenRearFacingEnds(start, target, startUsable)
				endUsable.isEmpty()
			}.map { it.name }

	/** The candidate paths `reservePath` enumerates for a signal-to-signal request. */
	private fun candidatesOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	) = navigator.findAllTopologicalPaths(start, target, DEFAULT_MAX_PATH_DEPTH)

	private fun semaphore(name: String): DynamicRailSemaphore =
		ctx.cellsOfType<DynamicRailSemaphore>().single {
			it.name ==
				name
		}

	private companion object {
		const val S14 = "S14"
	}
}
