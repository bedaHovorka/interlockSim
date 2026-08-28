package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Locks in the sensor-port contract that keeps the forward-reservation graph search
 * off the per-tick hot path.
 *
 * [ShuntingLoop.toBlockInputObservation] resolves [BlockInputObservation.toSeparatorName]
 * via [PathReservationService.findNextReservationTarget][cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.findNextReservationTarget],
 * which is a BFS plus a per-candidate topological-path enumeration. Running it for every
 * block input on every tick — including inputs that provably cannot take a forward
 * reservation — accounted for ~9% of `fast-sim example shuntingLoop 300` wall time.
 *
 * The contract: `toSeparatorName` is populated **only** for inputs that could actually
 * yield a [DispatchDecision.ReservePath] — a train occupying the block and approaching this
 * input, or a path already set up toward it — and only when the path is not already extended
 * beyond it. For every other input it is `null`.
 *
 * [RuleBasedDispatcher.checkInput] returns `null` for exactly those non-eligible cases, so
 * narrowing the contract is behaviour-preserving; the golden `shuntingLoop` output is
 * byte-for-byte unchanged.
 *
 * Reverting the gate (computing `toSeparatorName` unconditionally) makes this test fail:
 * FREE inputs with clear track ahead resolve to a non-null target.
 */
@DisplayName("ShuntingLoop forward-reservation-target laziness contract")
@Tag("integration-test")
class ShuntingLoopReservationTargetLazinessTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `toSeparatorName is null for every input that cannot take a forward reservation`() {
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
					} else if (input.toSeparatorName != null) {
						violations +=
							"block=${input.blockId} toward=${input.towardSemaphoreName} state=${input.state} " +
							"approaching=${input.isApproachingThisInput} " +
							"setUpToward=${input.pathSetUpTowardThisInput} " +
							"extendedBeyond=${input.pathAlreadyExtendedBeyond} " +
							"toSeparatorName=${input.toSeparatorName}"
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

		assertThat(violations.size, "non-eligible inputs carrying a toSeparatorName: $violations").isEqualTo(0)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
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
						if (input.toSeparatorName != null) {
							freeWithTarget += "${input.blockId}->${input.towardSemaphoreName}=${input.toSeparatorName}"
						}
					}
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(freeWithTarget.firstOrNull(), "FREE input with a forward-reservation target").isNull()
	}
}
