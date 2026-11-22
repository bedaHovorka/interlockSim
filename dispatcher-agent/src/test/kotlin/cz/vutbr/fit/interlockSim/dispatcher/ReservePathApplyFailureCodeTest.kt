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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickRecord
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Issue #1365 review fix: [DispatchDecisionApplier]'s `ReservePath` branch must report the
 * [RouteRequestResult] refusal it received the same way its `RequestRoute` branch does, so the
 * run recorder can count an [ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS] refusal of a
 * `ReservePath` fallback decision (the shape the rule-based fallback posts on a silent LLM
 * cycle's observation — see [cz.vutbr.fit.interlockSim.dispatcher.planner.StaleOriginRefusalAfterSilentCycleTest]).
 *
 * Before this fix the `ReservePath` branch discarded the refusal: `applyReservePath` returned
 * `Unit`, so [ActionPhase] stayed `APPLIED` with a `null` [ApplyFailureCode] and
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot.staleOriginRefusalsAfterSilentCycle]
 * never counted a `ReservePath` refusal.
 *
 * The pinned `decisionApplied` convention (see `RequestRouteApplyFailureCodeTest`) holds: a
 * refusal that reached the actuator is still "applied" for GUI purposes, so
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecisionListener] fires exactly once.
 */
@DisplayName("DispatchDecisionApplier — a ReservePath refusal is reported and counted (#1365)")
class ReservePathApplyFailureCodeTest {
	companion object {
		private const val TRAIN = "Train #4"
	}

	@Test
	@DisplayName(
		"OriginNotContiguous through ReservePath -> APPLIED_THEN_FAILED with ORIGIN_NOT_CONTIGUOUS, " +
			"counted after the silent cycle, decisionApplied still fires"
	)
	fun reservePathRefusalIsAppliedThenFailedAndCounted() {
		val correlationMap = CommandCorrelationMap()
		val queue = ActuatorCommandQueue(correlationMap = correlationMap)
		val outcomes = mutableListOf<ActionOutcome>()
		var decisionAppliedCount = 0
		val recorder =
			DefaultDispatcherRunRecorder(
				runId = "reserve-path-refusal-run",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				params =
					RunParameters(
						tickPeriodMs = 500L,
						historyN = 10,
						temperature = 0.0,
						maxActionsPerTick = 3,
						model = "",
						seed = null
					)
			)
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns
			RouteRequestResult.OriginNotContiguous("zA", "$TRAIN holds no block bounded by 'zA'")
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = {},
				onDecisionApplied = { decisionAppliedCount++ },
				correlationMap = correlationMap,
				actionOutcomeSink =
					ActionOutcomeSink { outcome ->
						outcomes.add(outcome)
						recorder.onActionOutcome(outcome)
					}
			)

		// Production order: the silent non-idle cycle's tick is reported inside plan() before
		// AgentLoopDriver posts the cycle's decisions, so the cycle index in the recorder's
		// silent set is the same index postAll stamps on the posted decisions.
		recorder.onTick(
			TickRecord(TickOutcome.RULE_FALLBACK, simTime = 100.0, silentNonIdleCycleIndex = correlationMap.currentCycle())
		)
		queue.postAll(listOf(DispatchDecision.ReservePath(TRAIN, "zA", "doA1")), ActionAuthor.RULE_FALLBACK, "fallback")
		applier.onControlStep()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)
		assertThat(decisionAppliedCount).isEqualTo(1)
		assertThat(recorder.snapshot().staleOriginRefusalsAfterSilentCycle).isEqualTo(1L)
	}
}
