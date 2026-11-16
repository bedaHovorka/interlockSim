/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.CandidateTarget
import cz.vutbr.fit.interlockSim.sim.SeparatorKind
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The pure next-section rule shared by `RequestRouteTool` and [ActionValidator] (Issue #1378).
 *
 * A Signal target is legal for a train standing at (or approaching) `from` only when it is one of
 * the candidates the interlocking lists one section ahead of `from` for that train — the same list
 * the NEXT SECTION line and the rule-based dispatcher read. The rule is inert whenever that list is
 * not there to compare against.
 */
@DisplayName("NextSectionTargets — a Signal target must be one section ahead of the train (Issue #1378)")
class NextSectionTargetsTest {
	private val train = "Train #2"

	private fun input(
		towardSemaphoreName: String = "S9",
		ownerTrainId: String? = train,
		candidates: List<CandidateTarget> = emptyList()
	): BlockInputObservation =
		BlockInputObservation(
			blockId = "k6",
			towardSemaphoreName = towardSemaphoreName,
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = ownerTrainId,
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false,
			candidateTargets = candidates
		)

	private fun inOut(
		name: String,
		available: Boolean
	) = CandidateTarget(name, SeparatorKind.IN_OUT, available)

	private fun signal(
		name: String,
		available: Boolean
	) = CandidateTarget(name, SeparatorKind.SEMAPHORE, available)

	@Test
	@DisplayName("no block inputs at all: inert")
	fun emptyInputsAreInert() {
		assertThat(NextSectionTargets.refusal(emptyList(), emptyList(), train, "S9", "S15")).isNull()
	}

	@Test
	@DisplayName("outer block inputs take part in the membership check")
	fun outerInputsParticipate() {
		val outer = listOf(input(candidates = listOf(inOut("N-Vys-1", true), signal("S3", false))))

		assertThat(NextSectionTargets.refusal(emptyList(), outer, train, "S9", "N-Vys-1")).isNull()
	}

	@Test
	@DisplayName("a target listed one section ahead and available passes")
	fun availableCandidatePasses() {
		val inputs = listOf(input(candidates = listOf(inOut("N-Vys-1", true), signal("S3", false))))

		assertThat(NextSectionTargets.refusal(inputs, emptyList(), train, "S9", "N-Vys-1")).isNull()
	}

	@Test
	@DisplayName("a target listed one section ahead but unavailable still passes (the engine answers it)")
	fun unavailableCandidatePasses() {
		val inputs = listOf(input(candidates = listOf(inOut("N-Vys-1", true), signal("S3", false))))

		assertThat(NextSectionTargets.refusal(inputs, emptyList(), train, "S9", "S3")).isNull()
	}

	@Test
	@DisplayName("a Signal target not listed ahead is refused with the exact pick guidance")
	fun refusalWithAPickIsPinnedVerbatim() {
		val inputs = listOf(input(candidates = listOf(inOut("N-Vys-1", true), signal("S3", false))))

		assertThat(NextSectionTargets.refusal(inputs, emptyList(), train, "S9", "S15")).isEqualTo(
			"Train 'Train #2' cannot go from 'S9' to 'S15': 'S15' is not the next section ahead of the " +
				"train at 'S9'. Its NEXT SECTION is from 'S9' to 'N-Vys-1'. Request exactly " +
				"fromEndpointName='S9', toEndpointName='N-Vys-1'. Do not retry 'S15'."
		)
	}

	@Test
	@DisplayName("a refused target with no available pick pins the no-request reply, sections ahead sorted")
	fun refusalWithoutAPickIsPinnedVerbatim() {
		val inputs = listOf(input(candidates = listOf(signal("S3", false), inOut("N-Vys-1", false))))

		assertThat(NextSectionTargets.refusal(inputs, emptyList(), train, "S9", "S15")).isEqualTo(
			"Train 'Train #2' cannot go from 'S9' to 'S15': 'S15' is not the next section ahead of the " +
				"train at 'S9'. No section ahead of 'S9' can be reserved right now (sections ahead: " +
				"'N-Vys-1', 'S3'). Make no route request for this train this tick."
		)
	}

	/**
	 * Issue #1244 in this rule's pick (review of #1378): a train awaiting a route extension whose
	 * policy pick is the end of the route it already holds is not eligible for a NEXT SECTION — the
	 * prompt shows NoSectionReservable and the rule-based dispatcher skips it. Naming that end in the
	 * rejection would re-grant the held route, the no-op that fights the reservation expiry.
	 */
	@Test
	@DisplayName("an awaiting train whose pick is its held route end gets no pick (Issue #1244)")
	fun awaitingPickEqualToHeldRouteEndIsNoPick() {
		val awaiting =
			input(candidates = listOf(inOut("N-Vys-1", true))).copy(
				pathAlreadyExtendedBeyond = true,
				awaitingRouteExtension = true,
				heldRouteEndName = "N-Vys-1"
			)

		assertThat(NextSectionTargets.refusal(listOf(awaiting), emptyList(), train, "S9", "S15")).isEqualTo(
			"Train 'Train #2' cannot go from 'S9' to 'S15': 'S15' is not the next section ahead of the " +
				"train at 'S9'. No section ahead of 'S9' can be reserved right now (sections ahead: " +
				"'N-Vys-1'). Make no route request for this train this tick."
		)
	}

	@Test
	@DisplayName("inputs of another train, toward another signal, or with no candidates leave the rule inert")
	fun unrelatedInputsAreInert() {
		val inputs =
			listOf(
				input(ownerTrainId = "Train #9", candidates = listOf(inOut("N-Vys-1", true))),
				input(towardSemaphoreName = "S15", candidates = listOf(inOut("N-Vys-1", true))),
				input(ownerTrainId = null, candidates = listOf(inOut("N-Vys-1", true))),
				input(candidates = emptyList())
			)

		assertThat(NextSectionTargets.refusal(inputs, emptyList(), train, "S9", "S15")).isNull()
	}
}
