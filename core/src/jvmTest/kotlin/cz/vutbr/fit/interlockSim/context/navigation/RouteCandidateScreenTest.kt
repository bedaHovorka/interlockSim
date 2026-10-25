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
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Pins [RouteCandidateScreen] directly (Issue #1210, SP12.9): the read-only candidate geometry
 * screening extracted from [DefaultPathReservationService]. The service-level suites
 * (`PathReservationContiguityTest`, `PathReservationNonOrientedOriginTest`,
 * `PathReservationNonOrientedEndTest`, `RouteEndDirectionTest`, `Issue1066RegressionTest`,
 * `PathReservationSwitchStartPathInfoRegressionTest`) pin the same rules through `reservePath`;
 * this class pins each function on its own, so a regression is located without going through the
 * whole reservation.
 *
 * Topology (`vyhybna.xml`): InOuts `A` and `B`; semaphores `zA`, `doA1`, `doA2`, `doB2`, `doB1`,
 * `zB`; switches `vA` and `vB`. `zA`, `doB1` and `doB2` face A to B; `doA1`, `doA2` and `zB` face
 * B to A. Block `kB` spans `B` and `zB`.
 *
 * @since Issue #1210
 */
@DisplayName("Route candidate screen (Issue #1210)")
@Timeout(30, unit = TimeUnit.SECONDS)
class RouteCandidateScreenTest : PathReservationServiceTestBase() {
	private lateinit var screen: RouteCandidateScreen

	@BeforeEach
	fun createScreen() {
		screen =
			RouteCandidateScreen(
				registry,
				environment,
				ReservationTargetDiscovery(navigator, environment),
				pathInfoBuilder
			)
	}

	private fun candidatePaths(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		maxDepth: Int = DEFAULT_MAX_PATH_DEPTH
	): List<List<TrackSection>> = navigator.findAllTopologicalPaths(start, target, maxDepth)

	private fun inOutB(): DynamicPathSeparator = inOutNamed("B")

	@Test
	fun `an unknown train has an empty footprint`() {
		assertThat(screen.footprintOf("nobody")).isEmpty()
	}

	@Test
	fun `footprint holds the registered blocks of the train`() {
		assertThat(service.reservePath("t1", findSemaphoreByName("zA"), findSemaphoreByName("doB1")))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		assertThat(screen.footprintOf("t1")).isEqualTo(registry.getBlocks("t1").toSet())
		assertThat(screen.footprintOf("t1")).isNotEmpty()
	}

	@Test
	fun `footprint holds a physically occupied block that has no registry entry`() {
		val kB = blockBetween("B", "zB")
		occupy(kB, "T-17")
		assertThat(registry.getBlocks("T-17")).isEmpty()

		assertThat(screen.footprintOf("T-17")).isEqualTo(setOf(kB))
	}

	@Test
	fun `footprint is the union of the registered and the occupied blocks`() {
		assertThat(service.reservePath("t1", findSemaphoreByName("zA"), findSemaphoreByName("doB1")))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val kB = blockBetween("B", "zB")
		occupy(kB, "t1")

		assertThat(screen.footprintOf("t1")).isEqualTo(registry.getBlocks("t1").toSet() + kB)
	}

	@Test
	fun `footprint of a train does not include a block another train holds`() {
		assertThat(service.reservePath("t1", findSemaphoreByName("zA"), findSemaphoreByName("doB1")))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()

		assertThat(screen.footprintOf("t2")).isEmpty()
	}

	@Test
	fun `a switch start is refused as geometrically impossible naming the separator and its type`() {
		val result = screen.rejectNonOrientedStart("train1", findSwitchByName("vA"), "reservePath")

		assertThat(result).isNotNull()
		val reason = result!!.reason
		assertThat(reason).startsWith("Route origin 'vA' is not an oriented separator (it is a DynamicRailSwitch)")
		assertThat(reason).contains("train 'train1'")
		assertThat(reason).contains("before any block, switch or signal was touched")
	}

	@Test
	fun `semaphore and InOut starts pass the oriented-start guard`() {
		assertThat(screen.rejectNonOrientedStart("train1", findSemaphoreByName("zA"), "reservePath")).isNull()
		assertThat(screen.rejectNonOrientedStart("train1", inOut1, "reservePath")).isNull()
	}

	/** Issue #1200: the END-side twin of the oriented-start guard. */
	@Test
	fun `a switch end is refused as geometrically impossible naming the separator and its type`() {
		val result = screen.rejectNonOrientedEnd("train1", findSwitchByName("vB"), "reservePath")

		assertThat(result).isNotNull()
		val reason = result!!.reason
		assertThat(reason).startsWith("Route end 'vB' is not an oriented separator (it is a DynamicRailSwitch)")
		assertThat(reason).contains("can only end at a semaphore or an InOut")
		assertThat(reason).contains("train 'train1'")
		assertThat(reason).contains("before any block, switch or signal was touched")
	}

	@Test
	fun `semaphore and InOut ends pass the oriented-end guard`() {
		assertThat(screen.rejectNonOrientedEnd("train1", findSemaphoreByName("doB1"), "reservePath")).isNull()
		assertThat(screen.rejectNonOrientedEnd("train1", inOutB(), "reservePath")).isNull()
	}

	@Test
	fun `a train with an empty footprint passes the contiguity guard from any start`() {
		assertThat(screen.rejectNonContiguousStart("phantom", findSemaphoreByName("doA1"))).isNull()
		assertThat(screen.rejectNonContiguousStart("phantom", findSemaphoreByName("doB1"))).isNull()
	}

	@Test
	fun `a start that bounds a block of the footprint passes the contiguity guard`() {
		occupy(blockBetween("B", "zB"), "T-17")

		assertThat(screen.rejectNonContiguousStart("T-17", findSemaphoreByName("zB"))).isNull()
		assertThat(screen.rejectNonContiguousStart("T-17", inOutB())).isNull()
	}

	@Test
	fun `a start that bounds no footprint block is refused and names the legal origins`() {
		occupy(blockBetween("B", "zB"), "T-17")

		val result = screen.rejectNonContiguousStart("T-17", findSemaphoreByName("doA1"))

		assertThat(result).isNotNull()
		assertThat(result!!.startName).isEqualTo("doA1")
		assertThat(result.reason).isEqualTo(
			"Route origin 'doA1' is not contiguous with train 'T-17': the train holds or occupies " +
				"1 block(s), none of which is bounded by 'doA1'. Legal origins for this train are: B, zB."
		)
	}

	@Test
	fun `the contiguity guard reserves and registers nothing`() {
		occupy(blockBetween("B", "zB"), "T-17")

		screen.rejectNonContiguousStart("T-17", findSemaphoreByName("doA1"))

		assertThat(registry.getBlocks("T-17")).isEmpty()
	}

	@Test
	fun `a candidate ending at a signal facing away from the train is screened out with a reason`() {
		val zA = findSemaphoreByName("zA")
		val doA1 = findSemaphoreByName("doA1")
		val candidates = candidatePaths(zA, doA1, maxDepth = 3)
		assertThat(candidates).hasSize(1)

		val (failures, usable) = screen.screenRearFacingEnds(zA, doA1, candidates)

		assertThat(usable).isEmpty()
		assertThat(failures.count).isEqualTo(1)
		assertThat(failures.reason).isEqualTo(
			"Route end 'doA1' faces away from the direction of travel on candidate 0; a route must end at " +
				"a signal facing the train. The next signals facing the train from 'zA' are: doB1, doB2."
		)
	}

	@Test
	fun `a candidate ending at a facing signal is kept with its original index`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val candidates = candidatePaths(zA, doB1)
		assertThat(candidates).hasSize(2)

		val (failures, usable) = screen.screenRearFacingEnds(zA, doB1, candidates)

		// Candidate 0 is the main leg and ends at doB1 facing the train. Candidate 1 loops through the
		// branch and reaches doB1 from the east, so doB1 faces away from that arrival: it is screened
		// out, and the surviving candidate keeps index 0.
		assertThat(usable.map { it.index }).containsExactly(0)
		assertThat(usable.single().value).isEqualTo(candidates[0])
		assertThat(failures.count).isEqualTo(1)
		assertThat(failures.reason).isNotNull().contains("on candidate 1")
	}

	@Test
	fun `a route with only facing candidates screens nothing out`() {
		val zA = findSemaphoreByName("zA")
		val candidates = candidatePaths(zA, inOutB())

		val (failures, usable) = screen.screenRearFacingEnds(zA, inOutB(), candidates)

		assertThat(failures).isEqualTo(GeometricFailures())
		assertThat(usable.map { it.index }).isEqualTo(candidates.indices.toList())
	}

	@Test
	fun `screening partitions the candidates into screened-out and kept ones`() {
		val zA = findSemaphoreByName("zA")
		val doA1 = findSemaphoreByName("doA1")
		val candidates = candidatePaths(zA, doA1)
		assertThat(candidates.size).isGreaterThan(1)

		val (failures, usable) = screen.screenRearFacingEnds(zA, doA1, candidates)

		// The direct candidate arrives from the west and is screened; the loop-around candidate
		// arrives from the east, so doA1 faces it and it is kept under its original index.
		assertThat(failures.count).isGreaterThan(0)
		assertThat(failures.count + usable.size).isEqualTo(candidates.size)
		usable.forEach { assertThat(it.value).isEqualTo(candidates[it.index]) }
	}

	@Test
	fun `an InOut target is never rear-facing`() {
		val zA = findSemaphoreByName("zA")
		val candidates = candidatePaths(zA, inOutB())
		assertThat(candidates).isNotEmpty()

		candidates.withIndex().forEach { (index, path) ->
			assertThat(screen.rearFacingEndReason(zA, inOutB(), path, index), "candidate $index").isNull()
		}
	}

	@Test
	fun `an empty path is never rear-facing`() {
		assertThat(
			screen.rearFacingEndReason(findSemaphoreByName("zA"), findSemaphoreByName("doA1"), emptyList(), 0)
		).isNull()
	}

	@Test
	fun `a switch target is never rear-facing`() {
		val zA = findSemaphoreByName("zA")
		val vB = findSwitchByName("vB")
		val candidates = candidatePaths(zA, vB)
		assertThat(candidates).isNotEmpty()

		candidates.withIndex().forEach { (index, path) ->
			assertThat(screen.rearFacingEndReason(zA, vB, path, index), "candidate $index").isNull()
		}
	}

	@Test
	fun `the arrival segment is found only for the section that reaches the target`() {
		val zA = findSemaphoreByName("zA")
		val doA1 = findSemaphoreByName("doA1")
		val path = candidatePaths(zA, doA1, maxDepth = 3).single()

		assertThat(screen.arrivalSegmentAt(doA1, path.last())).isNotNull()
		assertThat(screen.arrivalSegmentAt(doA1, path.first())).isNull()
	}

	@Test
	fun `a train with no stored route screens no candidate and builds no PathInfo`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val candidates = candidatePaths(zA, doB1).withIndex().toList()

		val (failures, usable) = screen.screenDivergentCandidates("fresh", zA, doB1, candidates)

		assertThat(failures).isEqualTo(GeometricFailures())
		assertThat(usable).isSameInstanceAs(candidates)
	}

	@Test
	fun `a candidate that diverges from the held route is screened out with the registry reason`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val doB2 = findSemaphoreByName("doB2")
		assertThat(service.reservePath("t1", zA, doB2))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val blocksBefore = registry.getBlocks("t1").toSet()
		val candidates = candidatePaths(zA, doB1).withIndex().toList()

		val (failures, usable) = screen.screenDivergentCandidates("t1", zA, doB1, candidates)

		// Candidate 0 is the main leg ending at doB1: it diverges from the branch route held to doB2.
		// Candidate 1 reaches doB1 through doB2, so it continues the held route and is kept.
		assertThat(candidates).hasSize(2)
		assertThat(usable.map { it.index }).containsExactly(1)
		assertThat(failures.count).isEqualTo(1)
		assertThat(failures.reason).isNotNull().contains("doB2")
		assertThat(registry.getBlocks("t1").toSet(), "screening is read-only").isEqualTo(blocksBefore)
	}

	@Test
	fun `a candidate extending from the held target is kept`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		assertThat(service.reservePath("t1", zA, doB1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val candidates = candidatePaths(doB1, inOutB()).withIndex().toList()
		assertThat(candidates).isNotEmpty()

		val (failures, usable) = screen.screenDivergentCandidates("t1", doB1, inOutB(), candidates)

		assertThat(failures).isEqualTo(GeometricFailures())
		assertThat(usable).isEqualTo(candidates)
	}

	@Test
	fun `a candidate whose blocks the train already owns is never screened`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		assertThat(service.reservePath("t1", zA, doB1))
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		val candidates = candidatePaths(zA, doB1).withIndex().toList()

		val (failures, usable) = screen.screenDivergentCandidates("t1", zA, doB1, candidates)

		// Candidate 0 is the held route itself (every block owned): never screened. Candidate 1 is the
		// loop through the branch, which does not continue the held main-leg route.
		assertThat(usable.map { it.index }).containsExactly(0)
		assertThat(failures.count).isEqualTo(1)
	}

	@Test
	fun `a forward-only PathInfo with nothing owned keeps the original start`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		val path = candidatePaths(zA, doB1).first()

		val info = screen.forwardOnlyPathInfo(zA, doB1, path, extractUniqueBlocks(path))

		assertThat(info.start).isEqualTo(zA)
		assertThat(info.target).isEqualTo(doB1)
	}

	@Test
	fun `a forward-only PathInfo starts where the first new block begins`() {
		val inOutA = inOutNamed("A")
		val path = candidatePaths(inOutA, inOutB()).first()
		val blocks = extractUniqueBlocks(path)
		val forwardBlocks = blocks.drop(1)
		// The first block (A to zA) is already owned, so the trimmed route starts at its far end.
		val expectedStart = blocks[0].ends().single { it in blocks[1].ends() }

		val info = screen.forwardOnlyPathInfo(inOutA, inOutB(), path, forwardBlocks)

		assertThat(info.start).isEqualTo(expectedStart)
		assertThat(info.start).isEqualTo(findSemaphoreByName("zA"))
		assertThat(info.target).isEqualTo(inOutB())
	}

	/** Issue #938: a switch is interior track and must never become the start of a PathInfo. */
	@Test
	fun `a forward-only PathInfo never starts at a switch`() {
		val doB2 = findSemaphoreByName("doB2")
		val path = candidatePaths(doB2, inOutB()).first()
		val vBzB = blockBetween("vB", "zB")
		val kB = blockBetween("zB", "B")
		val forwardBlocks = extractUniqueBlocks(path).filter { it == vBzB || it == kB }
		assertThat(forwardBlocks).containsExactlyInAnyOrder(vBzB, kB)

		val info = screen.forwardOnlyPathInfo(doB2, inOutB(), path, forwardBlocks)

		assertThat(info.start).isEqualTo(doB2)
		assertThat(info.start).isNotInstanceOf<DynamicRailSwitch>()
	}

	@Test
	fun `guards and screens leave the blocks of the network untouched`() {
		val zA = findSemaphoreByName("zA")
		val doA1 = findSemaphoreByName("doA1")
		val candidates = candidatePaths(zA, doA1)

		screen.rejectNonOrientedStart("t1", findSwitchByName("vA"), "reservePath")
		screen.rejectNonOrientedEnd("t1", findSwitchByName("vB"), "reservePath")
		screen.rejectNonContiguousStart("t1", zA)
		screen.screenRearFacingEnds(zA, doA1, candidates)
		screen.screenDivergentCandidates("t1", zA, doA1, candidates.withIndex().toList())

		val blocks = environment.getGraph().values().filterIsInstance<DynamicTrackBlock>()
		blocks.forEach { assertThat(registry.getOwner(it), "owner of $it").isNull() }
		assertThat(registry.getBlocks("t1")).isEmpty()
	}
}
