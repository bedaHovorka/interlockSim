package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetCandidate
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.FirstTrainOwnershipConflict
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
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
	private companion object {
		/** Long enough for the first train under way to stand awaiting the extension navigation refuses. */
		const val HELD_END_END_TIME: Long = 150L
	}

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
					val expected = query.findReservationTargetCandidates(start, owner).map { it.expectedCandidateTarget() }
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

	/**
	 * [BlockInputObservation.heldRouteEndName] (Issue #1244) is resolved only in the awaiting state,
	 * so the per-tick hot path reads nothing new: it is `null` for every input that is not awaiting
	 * a route extension, and for an awaiting one it names the separator the owner's stored route
	 * ends at. The awaiting state is produced as in `Issue943OwnershipConflictStallBoundTest`:
	 * navigation answers an ownership conflict for the first train under way.
	 */
	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `heldRouteEndName is resolved only for an awaiting input and names the stored route's end`() {
		val context = loadVyhybnaContext()
		context.getInOuts()
		val stall = FirstTrainOwnershipConflict(context.getRoutingServices().getTrainNavigationService())
		val conflictEnv = NavigationDecoratingContext(context, stall.navigation)
		val loop = prepareShuntingLoop(context, HELD_END_END_TIME, env = conflictEnv)
		val audit = HeldRouteEndAudit(context.scope.get<PathReservationRegistry>())
		val wired = loop.controlStepListener
		loop.controlStepListener =
			ControlStepListener {
				audit.check(loop.getInnerBlockInputs() + loop.getOuterBlockInputs())
				wired?.onControlStep()
			}

		context.run()

		audit.assertCoveredAndClean()
	}

	/** The shell's gate for resolving a forward target (Issues #749 and #1060). */
	private fun canReserveForward(input: BlockInputObservation): Boolean =
		(!input.pathAlreadyExtendedBeyond || input.awaitingRouteExtension) &&
			(input.isApproachingThisInput || input.pathSetUpTowardThisInput)

	/**
	 * The expected observation fact for a query result, computed independently of
	 * [cz.vutbr.fit.interlockSim.sim.toCandidateTarget] so this test is a real oracle for the
	 * production conversion rather than a restatement of it.
	 */
	private fun ReservationTargetCandidate.expectedCandidateTarget(): CandidateTarget =
		when (val sep = separator) {
			is DynamicInOut -> CandidateTarget(sep.name, SeparatorKind.IN_OUT, available)
			is DynamicRailSemaphore -> CandidateTarget(sep.name, SeparatorKind.SEMAPHORE, available)
			else -> throw IllegalStateException("Unexpected separator kind: $sep")
		}

	/**
	 * Checks every block input of a control step against the owner's stored route: an awaiting
	 * input must report the route's end ([routeEndpointName], pinned by `DispatchObservationTest`),
	 * every other input `null`. Counts what it saw so the run cannot pass vacuously.
	 */
	private class HeldRouteEndAudit(
		private val registry: PathReservationRegistry
	) {
		private var awaitingSeen = 0
		private var namedEndSeen = 0
		private var otherSeen = 0
		private val violations = mutableListOf<String>()

		fun check(inputs: List<BlockInputObservation>) {
			for (input in inputs) {
				val expected = expectedEnd(input)
				if (input.heldRouteEndName != expected) violations += describe(input, expected)
			}
		}

		fun assertCoveredAndClean() {
			assertThat(awaitingSeen, "awaiting inputs observed").isGreaterThan(0)
			assertThat(namedEndSeen, "awaiting inputs whose stored route ends at a named endpoint")
				.isGreaterThan(0)
			assertThat(otherSeen, "non-awaiting inputs observed").isGreaterThan(0)
			assertThat(violations.size, "violations: $violations").isEqualTo(0)
		}

		private fun expectedEnd(input: BlockInputObservation): String? {
			if (!input.awaitingRouteExtension) {
				otherSeen++
				return null
			}
			awaitingSeen++
			return registry
				.getPathInfo(requireNotNull(input.ownerTrainId))
				?.target
				?.routeEndpointName()
				?.also { namedEndSeen++ }
		}

		private fun describe(
			input: BlockInputObservation,
			expected: String?
		): String =
			"${input.blockId}->${input.towardSemaphoreName} awaiting=${input.awaitingRouteExtension}: " +
				"${input.heldRouteEndName} != $expected"
	}
}
