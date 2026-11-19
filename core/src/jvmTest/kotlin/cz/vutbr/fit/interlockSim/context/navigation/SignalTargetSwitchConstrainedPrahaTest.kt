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

import assertk.all
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isSameInstanceAs
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.warnMessages
import cz.vutbr.fit.interlockSim.testutil.withLogOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #1385 (traffic-simulation-expert ruling): a request whose target is not an InOut enumerates its
 * candidates with the bounded, cached, switch-legal [TopologyNavigator.findAllSwitchConstrainedPaths], as
 * the InOut branch and [ReservationTargetDiscovery] already do, instead of the unbounded switch-blind
 * [TopologyNavigator.findAllTopologicalPaths].
 *
 * Runs on the Praha station asset (`praha-hlavni-nadrazi-station.xml`), where the switch-blind listing
 * was measured at 20 480 paths for the behind-the-train request `S2 -> S11`.
 */
@Tag("integration-test")
class SignalTargetSwitchConstrainedPrahaTest : PrahaPathReservationTestBase() {
	private fun serviceOver(
		navigator: TopologyNavigator,
		nextTargets: ((DynamicPathSeparator, TrackSection) -> List<DynamicPathSeparator>)? = null
	): DefaultPathReservationService = serviceOf(navigator, nextTargets)

	@Test
	fun `a signal-target request never runs the switch-blind enumeration`() {
		occupy(blockBetween(S2, S5), TRAIN)
		val recording = RecordingNavigator(navigator)
		val s2 = findSemaphoreByName(S2)
		val s11 = findSemaphoreByName(S11)

		val result = serviceOver(recording).reservePath(TRAIN, s2, s11)

		// N = 0: no switch-legal route leads from S2 to S11 (the switch-blind listing had 20 480 phantom
		// paths, every one refused by Step 1.4, G8 or the #742 reversal check), so the request ends
		// before any screen with NoPathExists instead of GeometricallyImpossible.
		assertThat(recording.topologicalCalls, name = "findAllTopologicalPaths calls").isEqualTo(0)
		assertThat(navigator.findAllSwitchConstrainedPaths(s2, s11), name = "candidates S2 -> S11").isEmpty()
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NoPathExists>()
	}

	@Test
	fun `a pair whose only topological paths are switch-illegal is refused once as NoPathExists`() {
		val s14 = findSemaphoreByName(S14)
		val s15 = findSemaphoreByName(S15)
		// The precondition that makes S14 -> S15 phantom-only: eight switch-blind paths, no legal one.
		assertThat(navigator.findAllTopologicalPaths(s14, s15), name = "switch-blind paths").isNotEmpty()
		assertThat(navigator.findAllSwitchConstrainedPaths(s14, s15), name = "switch-legal paths").isEmpty()

		var warnings: List<String> = emptyList()
		lateinit var result: PathReservationService.ReservationResult
		withLogOf(DefaultPathReservationService::class.java.name) { serviceLog ->
			result = service.reservePath(TRAIN, s14, s15)
			warnings = serviceLog.warnMessages()
		}

		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NoPathExists>()
		val refusals = warnings.filter { it.startsWith(REFUSED) }
		assertThat(refusals, name = "refusal lines").hasSize(1)
		assertThat(refusals.single(), name = "the refusal line").startsWith("$REFUSED'$TRAIN' $S14 -> $S15: ")
	}

	@Test
	fun `every semaphore-to-semaphore and semaphore-to-InOut search completes within the budget`() {
		val topology = navigator as DefaultTopologyNavigator
		val discovery = ReservationTargetDiscovery(navigator, simulationContext)
		val inOuts = simulationContext.getInOuts().map { simulationContext.toDynamic(it) as DynamicInOut }
		val semaphores = simulationContext.cellsOfType<DynamicRailSemaphore>()
		var maxExpanded = 0
		for (semaphore in semaphores) {
			// An InOut the offer walk (Issue #1390) offers is reachable through `next` by a simple
			// switch-legal route, so the enumeration must find at least one route to it.
			val offered =
				discovery.resolveForwardSection(semaphore, "test")?.let { (start, next) ->
					discovery.findNextSemaphoresVia(start, next).filterIsInstance<DynamicInOut>()
				} ?: emptyList()
			// InOut targets first, then semaphores: the two former inner loops in one pass, in their
			// original order, so the searched pair set and sequence — and the measured numbers below —
			// stay exactly what they were.
			for (target in inOuts + semaphores) {
				val search =
					topology.searchSwitchConstrainedPaths(
						semaphore,
						target,
						DEFAULT_MAX_PATH_DEPTH,
						DefaultTopologyNavigator.ROUTE_SEARCH_NODE_BUDGET
					)
				assertThat(search, name = "search ${semaphore.name} -> ${separatorNameOf(target)}")
					.isInstanceOf<RouteSearch.Completed>()
				if (target is DynamicInOut && target in offered) {
					assertThat(
						(search as RouteSearch.Completed).paths,
						name = "${semaphore.name} -> ${separatorNameOf(target)}"
					).isNotEmpty()
				}
				maxExpanded = maxOf(maxExpanded, search.expandedNodes)
			}
		}
		// Measured 2026-11-19: 203 expanded nodes at most across all 1160 pairs (worst pair
		// S_60_16 -> S_4_4, a semaphore target; the 319 semaphore-to-InOut pairs peaked at the same 203,
		// S_60_16 -> N-Lib-1), all finished in under a second. The bound is a tripwire with headroom,
		// far below the 300 000 budget.
		assertThat(maxExpanded, name = "most expanded nodes of one pair").isLessThanOrEqualTo(MAX_EXPANDED_TRIPWIRE)
	}

	@Test
	fun `reservePath consumes the cached candidate list without changing it`() {
		val start = findSemaphoreByName(S_4_4)
		val target = findSemaphoreByName(S2)
		val cached = navigator.findAllSwitchConstrainedPaths(start, target, DEFAULT_MAX_PATH_DEPTH)
		val snapshot = cached.map { it.toList() }
		assertThat(cached.size, name = "candidates $S_4_4 -> $S2").isEqualTo(3)
		val recording = RecordingNavigator(navigator)
		val service = serviceOver(recording)

		service.reservePath(TRAIN, start, target)
		service.reservePath(TRAIN, start, target)

		val answers = recording.switchConstrainedAnswers.filter { it.start == start && it.target == target }
		assertThat(answers.size, name = "enumerations handed to reservePath").isEqualTo(2)
		answers.forEach { search -> assertThat(search.answer).isSameInstanceAs(cached) }
		assertThat(navigator.findAllSwitchConstrainedPaths(start, target, DEFAULT_MAX_PATH_DEPTH)).all {
			isSameInstanceAs(cached)
			isEqualTo(snapshot)
		}
	}

	@Test
	fun `a NoPathExists target before a geometric one keeps the next-semaphore verdict permanent`() {
		val k6 = blockBetween(S9, S15)
		occupy(k6, TRAIN)
		val s9 = findSemaphoreByName(S9)
		val unreachable = findSemaphoreByName(S11)
		val rearFacing = findSemaphoreByName(S15)
		// One candidate whose first block is k6, the platform track behind S9: Step 1.4 refuses it.
		val behindS9 = listOf(listOf(checkNotNull(k6.getNextTrackSection(s9, null))))
		val recording =
			RecordingNavigator(
				navigator,
				noRouteTo = setOf(unreachable),
				stubbedSwitchConstrained = mapOf(rearFacing to behindS9)
			)
		val discovery = ReservationTargetDiscovery(navigator, simulationContext)
		val (start, next) = checkNotNull(discovery.resolveForwardSection(s9, "test"))
		val service = serviceOver(recording) { _, _ -> listOf(unreachable, rearFacing) }

		var warnings: List<String> = emptyList()
		lateinit var result: PathReservationService.ReservationResult
		withLogOf(DefaultPathReservationService::class.java.name) { serviceLog ->
			result = service.reservePathToAnyNextSemaphore(TRAIN, start, next)
			warnings = serviceLog.warnMessages()
		}

		// Both targets are permanent refusals, so the verdict must not fall through to AllPathsBlocked,
		// on which InOutWorker would wait forever.
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		// The per-target NoPathExists arm logs at TRACE, like the geometric arm: reservePath's own refusal
		// line is the one WARN, and InOutWorker retries would repeat a per-target WARN.
		assertThat(warnings.filter { it.startsWith("reservePathToAnyNextSemaphore:") }, name = "per-target WARN lines")
			.isEmpty()
	}

	@Test
	fun `a last NoPathExists stands only when every next-semaphore attempt was permanent`() {
		val impl = service as DefaultPathReservationService
		val noPath = PathReservationService.ReservationResult.NoPathExists

		assertThat(impl.classifyExhaustedSemaphores(noPath, permanentAttempts = 2, attemptCount = 2))
			.isSameInstanceAs(noPath)
		// A merely blocked target before it can free up: contention, not a permanent refusal.
		assertThat(impl.classifyExhaustedSemaphores(noPath, permanentAttempts = 1, attemptCount = 2))
			.isEqualTo(PathReservationService.ReservationResult.AllPathsBlocked(2))
	}

	private companion object {
		const val MAX_EXPANDED_TRIPWIRE = 1_000
		const val REFUSED = "reservePath: refused "
	}
}
