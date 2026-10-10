package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetCandidate
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Locks in the sensor-port contract that keeps the forward-reservation graph search
 * off the per-tick hot path.
 *
 * [ShuntingLoop.toBlockInputObservation] resolves [BlockInputObservation.candidateTargets]
 * via [ReservationTargetQuery.findReservationTargetCandidates][cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetQuery.findReservationTargetCandidates],
 * which is a BFS plus a per-candidate topological-path enumeration (Issue #970).
 * Running the query for every
 * block input on every tick — including inputs that provably cannot take a forward
 * reservation — accounted for ~9% of `fast-sim example shuntingLoop 300` wall time.
 *
 * The contract: `candidateTargets` is populated **only** for inputs that could actually
 * yield a [DispatchDecision.ReservePath] — a train occupying the block and approaching this
 * input, or a path already set up toward it — and only when the path is not already extended
 * beyond it. For every other input it is empty.
 *
 * [RuleBasedDispatcher.checkInput] returns `null` for exactly those non-eligible cases, so
 * narrowing the contract is behaviour-preserving; the golden `shuntingLoop` output is
 * byte-for-byte unchanged.
 *
 * Reverting the gate (resolving the candidates unconditionally) makes this test fail:
 * FREE inputs with clear track ahead resolve to a non-empty candidate list.
 *
 * Since Issue #970 the list the dispatcher chooses from is the only target the shell reports
 * (Issue #1152 removed the single-name projection). The last two
 * tests pin the list's content against
 * [ReservationTargetQuery.findReservationTargetCandidates][cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetQuery.findReservationTargetCandidates].
 */
@DisplayName("ShuntingLoop forward-reservation-target laziness contract")
@Tag("integration-test")
class ShuntingLoopReservationTargetLazinessTest : KoinTestBase() {
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `candidateTargets is empty for every input that cannot take a forward reservation`() {
		val context = loadVyhybnaContext()
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 120L)
		wireSynchronousDispatcher(context, loop)

		// Observations are published before the control-step listener fires, so reading them
		// from a listener installed *after* the wiring sees the current tick's data.
		val wired = loop.controlStepListener
		var inputsSeen = 0
		var freeInputsSeen = 0
		var eligibleInputsSeen = 0
		val violations = mutableListOf<String>()

		loop.controlStepListener =
			ControlStepListener {
				val inputs = loop.getInnerBlockInputs() + loop.getOuterBlockInputs()
				for (input in inputs) {
					inputsSeen++
					if (input.state == TrackFacility.State.FREE) freeInputsSeen++

					val canReserveForward =
						!input.pathAlreadyExtendedBeyond &&
							(input.isApproachingThisInput || input.pathSetUpTowardThisInput)
					if (canReserveForward) {
						eligibleInputsSeen++
					} else if (input.candidateTargets.isNotEmpty()) {
						violations +=
							"block=${input.blockId} toward=${input.towardSemaphoreName} state=${input.state} " +
							"approaching=${input.isApproachingThisInput} " +
							"setUpToward=${input.pathSetUpTowardThisInput} " +
							"extendedBeyond=${input.pathAlreadyExtendedBeyond} " +
							"candidateTargets=${input.candidateTargets}"
					}
				}
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		// The run must have exercised the interesting states, otherwise the assertion is vacuous.
		assertThat(inputsSeen, "block inputs observed").isGreaterThan(0)
		assertThat(freeInputsSeen, "FREE inputs observed").isGreaterThan(0)
		assertThat(eligibleInputsSeen, "reservation-eligible inputs observed").isGreaterThan(0)

		assertThat(violations.size, "non-eligible inputs carrying candidate targets: $violations").isEqualTo(0)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `a FREE block input never carries a forward-reservation target`() {
		val context = loadVyhybnaContext()
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 120L)
		wireSynchronousDispatcher(context, loop)
		val wired = loop.controlStepListener

		val freeWithTarget = mutableListOf<String>()
		loop.controlStepListener =
			ControlStepListener {
				(loop.getInnerBlockInputs() + loop.getOuterBlockInputs())
					.filter { it.state == TrackFacility.State.FREE }
					.forEach { input ->
						if (input.candidateTargets.isNotEmpty()) {
							freeWithTarget += "${input.blockId}->${input.towardSemaphoreName}=${input.candidateTargets}"
						}
					}
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(freeWithTarget.firstOrNull(), "FREE input with a forward-reservation target").isNull()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `candidateTargets is empty for every non-eligible input`() {
		val context = loadVyhybnaContext()
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 120L)
		wireSynchronousDispatcher(context, loop)
		val wired = loop.controlStepListener

		var inputsWithCandidates = 0
		val violations = mutableListOf<String>()
		loop.controlStepListener =
			ControlStepListener {
				for (input in loop.getInnerBlockInputs() + loop.getOuterBlockInputs()) {
					if (input.candidateTargets.isNotEmpty()) inputsWithCandidates++
					if (!canReserveForward(input) && input.candidateTargets.isNotEmpty()) {
						violations += "non-eligible ${input.blockId}->${input.towardSemaphoreName} lists ${input.candidateTargets}"
					}
				}
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(inputsWithCandidates, "inputs carrying candidates").isGreaterThan(0)
		assertThat(violations.size, "violations: $violations").isEqualTo(0)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `every candidate's kind matches its separator class and the list equals the interlocking's query`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val query = context.getRoutingServices().getReservationTargetQuery()
		val semaphores = context.cellsOfType<DynamicRailSemaphore>()

		val loop = ShuntingLoop(context, endTime = 120L)
		wireSynchronousDispatcher(context, loop)
		val wired = loop.controlStepListener

		var listsCompared = 0
		val violations = mutableListOf<String>()
		loop.controlStepListener =
			ControlStepListener {
				for (input in loop.getInnerBlockInputs() + loop.getOuterBlockInputs()) {
					if (!canReserveForward(input)) continue
					val start = semaphores.first { it.name == input.towardSemaphoreName } as OrientedPathSeparator
					val owner = if (input.awaitingRouteExtension) input.ownerTrainId else null
					val expected = query.findReservationTargetCandidates(start, owner).map { it.toCandidateTarget() }
					listsCompared++
					if (input.candidateTargets != expected) {
						violations += "${input.blockId}->${input.towardSemaphoreName}: ${input.candidateTargets} != $expected"
					}
				}
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(listsCompared, "eligible inputs compared").isGreaterThan(0)
		assertThat(violations.size, "violations: $violations").isEqualTo(0)
	}

	/** The shell's gate for resolving a forward target (Issues #749 and #1060). */
	private fun canReserveForward(input: BlockInputObservation): Boolean =
		(!input.pathAlreadyExtendedBeyond || input.awaitingRouteExtension) &&
			(input.isApproachingThisInput || input.pathSetUpTowardThisInput)

	/** The expected observation fact for a query result: the kind follows the separator's class. */
	private fun ReservationTargetCandidate.toCandidateTarget(): CandidateTarget =
		when (val sep = separator) {
			is DynamicInOut -> CandidateTarget(sep.name, SeparatorKind.IN_OUT, available)
			is DynamicRailSemaphore -> CandidateTarget(sep.name, SeparatorKind.SEMAPHORE, available)
			else -> throw IllegalStateException("Unexpected separator kind: $sep")
		}
}
