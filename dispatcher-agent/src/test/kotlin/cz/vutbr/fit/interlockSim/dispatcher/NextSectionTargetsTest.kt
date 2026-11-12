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
import assertk.assertions.contains
import assertk.assertions.doesNotContain
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
		assertThat(NextSectionTargets.mismatch(emptyList(), train, "S9", "S15")).isNull()
	}

	@Test
	@DisplayName("a target listed one section ahead and available passes")
	fun availableCandidatePasses() {
		val inputs = listOf(input(candidates = listOf(inOut("N-Vys-1", true), signal("S3", false))))

		assertThat(NextSectionTargets.mismatch(inputs, train, "S9", "N-Vys-1")).isNull()
	}

	@Test
	@DisplayName("a target listed one section ahead but unavailable still passes (the engine answers it)")
	fun unavailableCandidatePasses() {
		val inputs = listOf(input(candidates = listOf(inOut("N-Vys-1", true), signal("S3", false))))

		assertThat(NextSectionTargets.mismatch(inputs, train, "S9", "S3")).isNull()
	}

	@Test
	@DisplayName("a target that is not listed ahead is a mismatch naming the pick and the sorted sections ahead")
	fun unlistedTargetIsMismatch() {
		val inputs = listOf(input(candidates = listOf(signal("S3", false), inOut("N-Vys-1", true))))

		val mismatch = NextSectionTargets.mismatch(inputs, train, "S9", "S15")

		assertThat(mismatch).isEqualTo(
			NextSectionTargets.Mismatch(from = "S9", pick = "N-Vys-1", ahead = listOf("N-Vys-1", "S3"))
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

		assertThat(NextSectionTargets.mismatch(inputs, train, "S9", "S15")).isNull()
	}

	@Test
	@DisplayName("with no available candidate the mismatch has no pick and the message says to make no request")
	fun noAvailableCandidateHasNoPick() {
		val inputs = listOf(input(candidates = listOf(inOut("N-Vys-1", false))))

		val mismatch = NextSectionTargets.mismatch(inputs, train, "S9", "S15")

		assertThat(mismatch).isEqualTo(NextSectionTargets.Mismatch(from = "S9", pick = null, ahead = listOf("N-Vys-1")))
		val message = NextSectionTargets.rejectionMessage(train, "S15", mismatch!!)
		assertThat(message).contains("No section ahead of 'S9' can be reserved right now")
		assertThat(message).contains("Make no route request for this train this tick.")
		assertThat(message).doesNotContain("NEXT SECTION is")
		for (word in listOf("occupied", "blocked", "option", "choose", "select")) {
			assertThat(message.lowercase()).doesNotContain(word)
		}
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

		val mismatch = NextSectionTargets.mismatch(listOf(awaiting), train, "S9", "S15")

		assertThat(mismatch).isEqualTo(NextSectionTargets.Mismatch(from = "S9", pick = null, ahead = listOf("N-Vys-1")))
	}

	@Test
	@DisplayName("the message with a pick names the exact pair to request and forbids retrying the target")
	fun messageWithPickNamesExactPair() {
		val message =
			NextSectionTargets.rejectionMessage(
				train,
				"S15",
				NextSectionTargets.Mismatch(from = "S9", pick = "N-Vys-1", ahead = listOf("N-Vys-1", "S3"))
			)

		assertThat(message).contains("'S15' is not the next section ahead of the train at 'S9'")
		// Covers behind, sideways and too far alike — no claim about which of them it is.
		assertThat(message).doesNotContain("more than one section away")
		assertThat(message).contains("Its NEXT SECTION is from 'S9' to 'N-Vys-1'")
		assertThat(message).contains("fromEndpointName='S9', toEndpointName='N-Vys-1'")
		assertThat(message).contains("Do not retry 'S15'.")
		for (word in listOf("occupied", "blocked", "option", "choose", "select")) {
			assertThat(message.lowercase()).doesNotContain(word)
		}
	}
}
