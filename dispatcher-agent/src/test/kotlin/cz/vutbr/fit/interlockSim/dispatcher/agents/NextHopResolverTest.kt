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
import org.junit.jupiter.api.Nested
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

	// ── resolveAll: same-tick same-target dedup (Issue #834, SP2c.11, task 8) ──────────────────

	/**
	 * Coverage for [NextHopResolver.resolveAll], the cycle-scoped entry point that gives the
	 * per-cycle rendering the same same-tick `claimedSeparators` dedup
	 * [cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher.checkAllInputs] applies across trains —
	 * see that class's KDoc for the race [resolve] alone cannot see (each train resolved in
	 * isolation, no notion of another train's target this same cycle).
	 */
	@DisplayName("resolveAll (Issue #834, SP2c.11, task 8)")
	@Nested
	inner class ResolveAll {
		@Test
		@DisplayName("two trains whose owned inputs target the same separator: exactly one keeps the Hop")
		fun exactlyOneTrainKeepsTheHopWhenTargetsCollide() {
			val t1Input =
				input(
					towardSemaphoreName = "doB1",
					toSeparatorName = "sharedSep",
					ownerTrainId = "T1",
					isApproachingThisInput = true
				)
			val t2Input =
				input(
					towardSemaphoreName = "doC1",
					toSeparatorName = "sharedSep",
					ownerTrainId = "T2",
					isApproachingThisInput = true
				)
			val obs = observation(innerBlockInputs = listOf(t1Input, t2Input))

			val outcomes = NextHopResolver.resolveAll(listOf("T1", "T2"), obs)

			assertThat(outcomes["T1"]).isEqualTo(NextHopOutcome.Hop(fromSignalName = "doB1", toSeparatorName = "sharedSep"))
			assertThat(outcomes["T2"]).isEqualTo(NextHopOutcome.ClaimedByAnotherTrain(toSeparatorName = "sharedSep"))
		}

		@Test
		@DisplayName("the winner is the train whose eligible input is first in innerBlockInputs+outerBlockInputs order")
		fun winnerIsEarlierInInputListOrderRegardlessOfTrainIdsArgumentOrder() {
			// T2's eligible input is listed first (innerBlockInputs before outerBlockInputs), so T2
			// wins even though "T1" is requested first in the trainIds argument -- the tie-break
			// mirrors RuleBasedDispatcher.checkAllInputs's fixed evaluation order, not caller order.
			val t2Input =
				input(
					towardSemaphoreName = "inner",
					toSeparatorName = "sharedSep",
					ownerTrainId = "T2",
					isApproachingThisInput = true
				)
			val t1Input =
				input(
					towardSemaphoreName = "outer",
					toSeparatorName = "sharedSep",
					ownerTrainId = "T1",
					isApproachingThisInput = true
				)
			val obs = observation(innerBlockInputs = listOf(t2Input), outerBlockInputs = listOf(t1Input))

			val outcomes = NextHopResolver.resolveAll(listOf("T1", "T2"), obs)

			assertThat(outcomes["T2"]).isEqualTo(NextHopOutcome.Hop(fromSignalName = "inner", toSeparatorName = "sharedSep"))
			assertThat(outcomes["T1"]).isEqualTo(NextHopOutcome.ClaimedByAnotherTrain(toSeparatorName = "sharedSep"))
		}

		@Test
		@DisplayName("two trains with different targets: both keep their Hop (dedup must not over-reach)")
		fun bothTrainsKeepTheirHopWhenTargetsDiffer() {
			val t1Input =
				input(
					towardSemaphoreName = "doB1",
					toSeparatorName = "sepA",
					ownerTrainId = "T1",
					isApproachingThisInput = true
				)
			val t2Input =
				input(
					towardSemaphoreName = "doC1",
					toSeparatorName = "sepB",
					ownerTrainId = "T2",
					isApproachingThisInput = true
				)
			val obs = observation(innerBlockInputs = listOf(t1Input, t2Input))

			val outcomes = NextHopResolver.resolveAll(listOf("T1", "T2"), obs)

			assertThat(outcomes["T1"]).isEqualTo(NextHopOutcome.Hop(fromSignalName = "doB1", toSeparatorName = "sepA"))
			assertThat(outcomes["T2"]).isEqualTo(NextHopOutcome.Hop(fromSignalName = "doC1", toSeparatorName = "sepB"))
		}

		@Test
		@DisplayName(
			"synthetic multi-eligible-input data: resolveAll takes the train's FIRST eligible input and " +
				"does not fall back to a later free one — the accepted divergence from checkAllInputs"
		)
		fun resolveAllShortCircuitsAtFirstEligibleInputPerTrainDivergingFromCheckAllInputsOnSyntheticData() {
			// Real-domain invariant: a train owns at most ONE eligible input per tick
			// (nextSemaphore is single-valued, isSetUpPath is directional — see NextHopResolver's
			// KDoc). This test deliberately violates it: T2 owns TWO both-eligible inputs. T1's
			// earlier input claims sepX first, so T2's first eligible input (→sepX) is already
			// claimed. resolveAll decides T2 at that first input and reports ClaimedByAnotherTrain,
			// never falling back to T2's second, still-free input (→sepY) — where
			// RuleBasedDispatcher.checkAllInputs (per-input, no per-train short-circuit) would skip
			// the claimed sepX and grant T2 a route to sepY. This divergence is accepted design (it
			// keeps resolve pure and the per-train outcome a single hop) and never arises on the
			// real topologies this dispatcher runs on.
			val t1Input =
				input(
					towardSemaphoreName = "doA1",
					toSeparatorName = "sepX",
					ownerTrainId = "T1",
					isApproachingThisInput = true
				)
			val t2FirstInput =
				input(
					towardSemaphoreName = "doB1",
					toSeparatorName = "sepX",
					ownerTrainId = "T2",
					isApproachingThisInput = true
				)
			val t2SecondInput =
				input(
					towardSemaphoreName = "doB2",
					toSeparatorName = "sepY",
					ownerTrainId = "T2",
					isApproachingThisInput = true
				)
			val obs = observation(innerBlockInputs = listOf(t1Input, t2FirstInput, t2SecondInput))

			val outcomes = NextHopResolver.resolveAll(listOf("T1", "T2"), obs)

			assertThat(outcomes["T1"]).isEqualTo(NextHopOutcome.Hop(fromSignalName = "doA1", toSeparatorName = "sepX"))
			// T2 is decided at its first eligible input (doB1→sepX, already claimed); it does NOT
			// fall back to doB2→sepY — this is the point where resolveAll diverges from checkAllInputs.
			assertThat(outcomes["T2"]).isEqualTo(NextHopOutcome.ClaimedByAnotherTrain(toSeparatorName = "sepX"))
		}

		@Test
		@DisplayName("a single train is unaffected: resolveAll agrees with resolve for every existing outcome shape")
		fun singleTrainResolveAllAgreesWithResolve() {
			val hopInput =
				input(towardSemaphoreName = "doB1", toSeparatorName = "doB2", isApproachingThisInput = true)
			val extendedInput =
				input(
					ownerTrainId = "T1",
					isApproachingThisInput = true,
					toSeparatorName = "target",
					pathAlreadyExtendedBeyond = true
				)
			val noHopInput = input(ownerTrainId = "T1", toSeparatorName = "target")

			listOf(
				observation(innerBlockInputs = listOf(hopInput)),
				observation(innerBlockInputs = listOf(extendedInput)),
				observation(innerBlockInputs = listOf(noHopInput)),
				observation()
			).forEach { obs ->
				val single = NextHopResolver.resolve("T1", obs)
				val fromResolveAll = NextHopResolver.resolveAll(listOf("T1"), obs)["T1"]

				assertThat(fromResolveAll).isEqualTo(single)
			}
		}

		@Test
		@DisplayName("resolveAll on the same observation twice yields an equal map both times (determinism)")
		fun determinism() {
			val t1Input =
				input(
					towardSemaphoreName = "doB1",
					toSeparatorName = "sharedSep",
					ownerTrainId = "T1",
					isApproachingThisInput = true
				)
			val t2Input =
				input(
					towardSemaphoreName = "doC1",
					toSeparatorName = "sharedSep",
					ownerTrainId = "T2",
					isApproachingThisInput = true
				)
			val obs = observation(innerBlockInputs = listOf(t1Input, t2Input))

			val first = NextHopResolver.resolveAll(listOf("T1", "T2"), obs)
			val second = NextHopResolver.resolveAll(listOf("T1", "T2"), obs)

			assertThat(first).isEqualTo(second)
		}
	}
}
