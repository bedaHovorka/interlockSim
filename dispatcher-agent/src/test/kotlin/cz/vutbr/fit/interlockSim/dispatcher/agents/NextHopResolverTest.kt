/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NextHopResolver] (Issue #893, phase beta, task B1).
 *
 * Pure-function coverage of the underlying eligibility rule, independent of prompt rendering
 * (which [KoogDispatchAgentImplTest] covers): byte-for-byte the same precondition
 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.reserveOrDefer] applies, so a resolver bug
 * here would mean the model is pointed at a route the rule-based dispatcher itself would refuse.
 *
 * @since Issue #893 (phase beta, task B1)
 */
@DisplayName("NextHopResolver (Issue #893 phase beta, task B1)")
class NextHopResolverTest {
	private fun observation(
		innerBlockInputs: List<BlockInputObservation> = emptyList(),
		outerBlockInputs: List<BlockInputObservation> = emptyList()
	): DispatchObservation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = innerBlockInputs,
			outerBlockInputs = outerBlockInputs
		)

	private fun input(
		towardSemaphoreName: String = "sem",
		toSeparatorName: String? = "nextSep",
		ownerTrainId: String? = "T1",
		isApproachingThisInput: Boolean = false,
		pathSetUpTowardThisInput: Boolean = false,
		pathAlreadyExtendedBeyond: Boolean = false,
		blockId: String = "block"
	): BlockInputObservation =
		BlockInputObservation(
			blockId = blockId,
			towardSemaphoreName = towardSemaphoreName,
			toSeparatorName = toSeparatorName,
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = ownerTrainId,
			isApproachingThisInput = isApproachingThisInput,
			pathSetUpTowardThisInput = pathSetUpTowardThisInput,
			pathAlreadyExtendedBeyond = pathAlreadyExtendedBeyond
		)

	// ── Hop selection ────────────────────────────────────────────────────────

	@Test
	@DisplayName("returns Hop with the block's toward-signal and next-separator names when the input qualifies")
	fun resolvesHopFromQualifyingInput() {
		val qualifying =
			input(towardSemaphoreName = "doB1", toSeparatorName = "doB2", isApproachingThisInput = true)

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(qualifying)))

		assertThat(outcome).isEqualTo(NextHopOutcome.Hop(fromSignalName = "doB1", toSeparatorName = "doB2"))
	}

	@Test
	@DisplayName("a RESERVED input qualifies via pathSetUpTowardThisInput, not just isApproachingThisInput")
	fun resolvesHopFromReservedPathSetUpInput() {
		val qualifying =
			input(towardSemaphoreName = "zA", toSeparatorName = "zB", pathSetUpTowardThisInput = true)

		val outcome = NextHopResolver.resolve("T1", observation(outerBlockInputs = listOf(qualifying)))

		assertThat(outcome).isEqualTo(NextHopOutcome.Hop(fromSignalName = "zA", toSeparatorName = "zB"))
	}

	@Test
	@DisplayName("selection precedence: an earlier qualifying innerBlockInputs entry wins over a later outer one")
	fun innerListPrecedesOuterList() {
		val innerHop =
			input(towardSemaphoreName = "inner", toSeparatorName = "innerTarget", isApproachingThisInput = true)
		val outerHop =
			input(towardSemaphoreName = "outer", toSeparatorName = "outerTarget", isApproachingThisInput = true)

		val outcome =
			NextHopResolver.resolve(
				"T1",
				observation(innerBlockInputs = listOf(innerHop), outerBlockInputs = listOf(outerHop))
			)

		assertThat(outcome).isEqualTo(NextHopOutcome.Hop(fromSignalName = "inner", toSeparatorName = "innerTarget"))
	}

	@Test
	@DisplayName("selection precedence: list order within innerBlockInputs is preserved")
	fun listOrderWithinInnerIsPreserved() {
		val first =
			input(towardSemaphoreName = "first", toSeparatorName = "firstTarget", isApproachingThisInput = true)
		val second =
			input(towardSemaphoreName = "second", toSeparatorName = "secondTarget", isApproachingThisInput = true)

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(first, second)))

		assertThat(outcome).isEqualTo(NextHopOutcome.Hop(fromSignalName = "first", toSeparatorName = "firstTarget"))
	}

	// ── Non-qualifying inputs ───────────────────────────────────────────────

	@Test
	@DisplayName("owner mismatch: an otherwise-qualifying input owned by a different train yields no hop")
	fun ownerMismatchYieldsNoHop() {
		val someoneElses =
			input(ownerTrainId = "OTHER", isApproachingThisInput = true, toSeparatorName = "target")

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(someoneElses)))

		assertThat(outcome).isEqualTo(NextHopOutcome.NoSectionReservable)
	}

	@Test
	@DisplayName("neither approaching nor path-set-up: a FREE-shaped input owned by the train yields no hop")
	fun neitherApproachingNorPathSetUpYieldsNoHop() {
		val neither =
			input(
				ownerTrainId = "T1",
				isApproachingThisInput = false,
				pathSetUpTowardThisInput = false,
				toSeparatorName = "target"
			)

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(neither)))

		assertThat(outcome).isEqualTo(NextHopOutcome.NoSectionReservable)
	}

	@Test
	@DisplayName(
		"null toSeparatorName: an approaching input with no computed FREE target yields NoSectionReservable, not Hop"
	)
	fun nullTargetYieldsNoSectionReservable() {
		val noTarget = input(ownerTrainId = "T1", isApproachingThisInput = true, toSeparatorName = null)

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(noTarget)))

		assertThat(outcome).isEqualTo(NextHopOutcome.NoSectionReservable)
	}

	@Test
	@DisplayName("pathAlreadyExtendedBeyond on every owned input yields RouteAlreadySet")
	fun allExtendedYieldsRouteAlreadySet() {
		val extended =
			input(
				ownerTrainId = "T1",
				isApproachingThisInput = true,
				toSeparatorName = "target",
				pathAlreadyExtendedBeyond = true
			)

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(extended)))

		assertThat(outcome).isEqualTo(NextHopOutcome.RouteAlreadySet)
	}

	@Test
	@DisplayName("a mix of an extended and a qualifying owned input yields the qualifying input's Hop")
	fun mixedExtendedAndQualifyingYieldsHopNotRouteAlreadySet() {
		val extended =
			input(
				ownerTrainId = "T1",
				isApproachingThisInput = true,
				toSeparatorName = "doneTarget",
				pathAlreadyExtendedBeyond = true,
				blockId = "b1"
			)
		val qualifying =
			input(
				ownerTrainId = "T1",
				pathSetUpTowardThisInput = true,
				toSeparatorName = "nextTarget",
				towardSemaphoreName = "nextSem",
				blockId = "b2"
			)

		val outcome = NextHopResolver.resolve("T1", observation(innerBlockInputs = listOf(extended, qualifying)))

		assertThat(outcome).isEqualTo(NextHopOutcome.Hop(fromSignalName = "nextSem", toSeparatorName = "nextTarget"))
	}

	@Test
	@DisplayName("a train with no owned input at all yields NoSectionReservable")
	fun noOwnedInputYieldsNoSectionReservable() {
		val outcome = NextHopResolver.resolve("T1", observation())

		assertThat(outcome).isEqualTo(NextHopOutcome.NoSectionReservable)
	}

	// ── Determinism ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("resolving the same observation twice yields an equal outcome both times (determinism)")
	fun determinism() {
		val obs =
			observation(
				innerBlockInputs =
					listOf(input(towardSemaphoreName = "doB1", toSeparatorName = "doB2", isApproachingThisInput = true))
			)

		val first = NextHopResolver.resolve("T1", obs)
		val second = NextHopResolver.resolve("T1", obs)

		assertThat(first).isEqualTo(second)
	}
}
