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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * Pins that the two consumers of [excludingOwnedBy] select the same candidates (Issue #1240,
 * follow-up of #1224): the Step 1.6 probe ([RouteCandidateScreen.screenDivergentCandidates]) and the
 * already-owned early return of [DefaultPathReservationService.reservePath] (Step 2a.5). The probe
 * predicts the merge, and its carve-out for a candidate with no forward blocks must be exactly the
 * candidates that take the early return. Both real code paths are driven on the same train, the same
 * candidate path and the same block ownership; nothing here calls [excludingOwnedBy] itself.
 *
 * ## How each side is observed
 *
 * - **Probe.** The candidate is passed alone to `screenDivergentCandidates`. The train's stored
 *   PathInfo is replaced by one that ends west of `zA` (target `A`), which no candidate of this
 *   test can continue. A candidate is therefore screened out exactly when the probe sees at least one
 *   forward block, and kept exactly when it carves the candidate out (no forward block).
 * - **Early return.** The candidate is passed alone to the internal `reservePath` overload (its
 *   `topologicalPaths` parameter), with the train's PathInfo removed so that Step 1.6 cannot pre-empt
 *   Step 2a.5. The candidate took the early return exactly when the call reports `Success` and the
 *   train's registered blocks did not change (nothing was newly reserved).
 *
 * Ownership is the same in both observations (the PathInfo is only the instrument that makes each
 * side visible), and every case also states the expected verdict so that two sides that were wrong in
 * the same way cannot pass.
 *
 * ## Topology (`vyhybna.xml`)
 *
 * `zA -> B` has two candidates over blocks (named by their two ends):
 * main `[zA-vA, vA-doA1, doA1-doB1, doB1-vB, zB-vB, B-zB]` and
 * branch `[zA-vA, doA2-vA, doA2-doB2, doB2-vB, zB-vB, B-zB]`.
 * `B -> zB` has the single candidate `[B-zB]`.
 *
 * ## Mutation checks run when this pin was written (each must make a case fail)
 *
 * - Step 2a.5 uses its own inline filter that also drops free blocks (not just the train's own).
 * - Step 1.6 skips the helper and uses every block of the candidate.
 * - One side tests ownership through the registry instead of `block.trainName`.
 *
 * @since Issue #1240
 */
@DisplayName("Step 1.6 probe and Step 2a.5 early return select the same candidates (Issue #1240)")
@Timeout(30, unit = TimeUnit.SECONDS)
class ProbeEarlyReturnSelectionPinTest : PathReservationServiceTestBase() {
	/** Who owns what before the candidate is judged. */
	enum class Ownership {
		/** The train holds the whole main leg `zA -> B`. */
		TRAIN_HOLDS_MAIN_LEG,

		/** The train holds `zA -> doB1`; the rest is free. */
		TRAIN_HOLDS_PREFIX_REST_FREE,

		/** The train holds `zA -> doB1`; another train holds `doB1 -> B`. */
		TRAIN_HOLDS_PREFIX_OTHER_HOLDS_TAIL,

		/** Another train holds the whole main leg; the train holds nothing. */
		OTHER_HOLDS_MAIN_LEG,

		/** Nothing is held by anyone. */
		ALL_FREE,

		/** The train stands on block `B-zB` without a registry entry (Issue #893's state). */
		TRAIN_STANDS_UNREGISTERED
	}

	private val trainId = "train_1240"
	private val otherId = "other_1240"

	private lateinit var screen: RouteCandidateScreen
	private lateinit var defaultService: DefaultPathReservationService
	private lateinit var zA: DynamicPathSeparator
	private lateinit var inOutA: DynamicPathSeparator
	private lateinit var inOutB: DynamicPathSeparator

	@BeforeEach
	fun createFixtures() {
		screen =
			RouteCandidateScreen(
				registry,
				environment,
				ReservationTargetDiscovery(navigator, environment),
				pathInfoBuilder
			)
		defaultService = service as DefaultPathReservationService
		zA = findSemaphoreByName("zA")
		inOutA = inOutNamed("A")
		inOutB = inOutNamed("B")
	}

	@ParameterizedTest(name = "{0}, candidate {1}: carved out = {2}")
	@MethodSource("cases")
	@DisplayName("the probe carves a candidate out exactly when the early return takes it")
	fun probeCarveOutEqualsEarlyReturn(
		ownership: Ownership,
		candidateIndex: Int,
		expectedCarvedOut: Boolean
	) {
		val (start, target) = endpointsOf(ownership)
		givenOwnership(ownership)
		val candidates = navigator.findAllTopologicalPaths(start, target)
		assertThat(candidates, "candidates of $ownership").hasSize(candidateCount(ownership))
		val candidate = candidates[candidateIndex]

		val probeCarvesOut = probeCarvesOut(start, target, candidateIndex, candidate)
		val earlyReturnTaken = earlyReturnTaken(start, target, candidate)

		assertThat(probeCarvesOut, "the probe carves the candidate out").isEqualTo(expectedCarvedOut)
		assertThat(earlyReturnTaken, "the early return takes the candidate").isEqualTo(expectedCarvedOut)
	}

	private fun endpointsOf(ownership: Ownership): Pair<DynamicPathSeparator, DynamicPathSeparator> =
		if (ownership == Ownership.TRAIN_STANDS_UNREGISTERED) {
			inOutB to findSemaphoreByName("zB")
		} else {
			zA to inOutB
		}

	private fun candidateCount(ownership: Ownership): Int = if (ownership == Ownership.TRAIN_STANDS_UNREGISTERED) 1 else 2

	private fun givenOwnership(ownership: Ownership) {
		val doB1 = findSemaphoreByName("doB1")
		when (ownership) {
			Ownership.TRAIN_HOLDS_MAIN_LEG -> reserve(trainId, zA, inOutB)
			Ownership.TRAIN_HOLDS_PREFIX_REST_FREE -> reserve(trainId, zA, doB1)
			Ownership.TRAIN_HOLDS_PREFIX_OTHER_HOLDS_TAIL -> {
				reserve(trainId, zA, doB1)
				reserve(otherId, doB1, inOutB)
			}
			Ownership.OTHER_HOLDS_MAIN_LEG -> reserve(otherId, zA, inOutB)
			Ownership.ALL_FREE -> Unit
			Ownership.TRAIN_STANDS_UNREGISTERED -> occupy(blockBetween("B", "zB"), trainId)
		}
	}

	private fun reserve(
		id: String,
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	) {
		assertThat(service.reservePath(id, start, target), "setup route of $id")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	/**
	 * The candidate survives Step 1.6 only if it has no forward block: the stored PathInfo ends at `A`,
	 * so every candidate with a forward block starts its merge candidate somewhere else and is screened.
	 */
	private fun probeCarvesOut(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidateIndex: Int,
		candidate: List<TrackSection>
	): Boolean {
		registry.restorePathInfo(trainId, unreachableEndPathInfo())
		val (failures, usable) =
			screen.screenDivergentCandidates(trainId, start, target, listOf(IndexedValue(candidateIndex, candidate)))
		assertThat(failures.count + usable.size, "the single candidate is either screened or kept").isEqualTo(1)
		return usable.isNotEmpty()
	}

	/** A PathInfo ending at `A`: no candidate of this test starts its merge candidate at `A`. */
	private fun unreachableEndPathInfo(): PathInfo =
		pathInfoBuilder.buildPathInfo(zA, inOutA, navigator.findAllTopologicalPaths(zA, inOutA).single())

	/**
	 * The candidate takes Step 2a.5's early return when the request succeeds without the train
	 * acquiring a single new block. The PathInfo is removed first so that Step 1.6 cannot pre-empt it.
	 */
	private fun earlyReturnTaken(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		candidate: List<TrackSection>
	): Boolean {
		registry.restorePathInfo(trainId, null)
		val blocksBefore = registry.getBlocks(trainId).toSet()
		val result =
			defaultService.reservePath(trainId, start, target, DEFAULT_MAX_PATH_DEPTH, lazyOf(listOf(candidate)))
		return result is PathReservationService.ReservationResult.Success &&
			registry.getBlocks(trainId).toSet() == blocksBefore
	}

	companion object {
		/** Ownership, candidate index, and whether the candidate has no forward block (carved out). */
		@JvmStatic
		fun cases(): Stream<Arguments> =
			Stream.of(
				Arguments.of(Ownership.TRAIN_HOLDS_MAIN_LEG, 0, true),
				Arguments.of(Ownership.TRAIN_HOLDS_MAIN_LEG, 1, false),
				Arguments.of(Ownership.TRAIN_HOLDS_PREFIX_REST_FREE, 0, false),
				Arguments.of(Ownership.TRAIN_HOLDS_PREFIX_REST_FREE, 1, false),
				Arguments.of(Ownership.TRAIN_HOLDS_PREFIX_OTHER_HOLDS_TAIL, 0, false),
				Arguments.of(Ownership.TRAIN_HOLDS_PREFIX_OTHER_HOLDS_TAIL, 1, false),
				Arguments.of(Ownership.OTHER_HOLDS_MAIN_LEG, 0, false),
				Arguments.of(Ownership.OTHER_HOLDS_MAIN_LEG, 1, false),
				Arguments.of(Ownership.ALL_FREE, 0, false),
				Arguments.of(Ownership.ALL_FREE, 1, false),
				Arguments.of(Ownership.TRAIN_STANDS_UNREGISTERED, 0, true)
			)
	}
}
