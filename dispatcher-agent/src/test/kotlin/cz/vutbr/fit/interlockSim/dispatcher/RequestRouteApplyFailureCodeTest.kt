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
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests proving [DispatchDecisionApplier]'s `RequestRoute` path reports the correct
 * [ApplyFailureCode] for every [RouteRequestResult] subtype, not just `Reserved`.
 *
 * Before this fix, `applyDecision`'s `RequestRoute` branch always returned `true` regardless of
 * outcome, so [ApplyFailureCode.ALL_PATHS_BLOCKED]/[ApplyFailureCode.CONFLICT]/
 * [ApplyFailureCode.NO_ROUTE_EXISTS] were never constructed by [ActionOutcomeSink.onActionOutcome]
 * — dead enum entries (SP2c.20 follow-up, Issue #843 acceptance criterion #10).
 *
 * `onDecisionApplied` firing behaviour must stay unchanged: it fires for all four outcomes,
 * since a `RequestRoute` attempt reaching the actuator is "applied" for GUI purposes regardless
 * of whether the route was actually reserved.
 */
@DisplayName("DispatchDecisionApplier — RequestRoute ApplyFailureCode wiring (SP2c.20 follow-up)")
class RequestRouteApplyFailureCodeTest {
	private fun applierWithSink(
		networkActuator: NetworkActuatorPort,
		outcomes: MutableList<ActionOutcome>
	): DispatchDecisionApplier {
		val queue = ActuatorCommandQueue()
		return DispatchDecisionApplier(
			queue = queue,
			networkActuator = networkActuator,
			onApproveTrain = {},
			actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
		)
	}

	@Test
	@DisplayName("Reserved -> phase APPLIED, applyFailure null, onDecisionApplied fires")
	fun reservedIsApplied() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.Reserved("T1", 2)
		val outcomes = mutableListOf<ActionOutcome>()
		var decisionAppliedCount = 0
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = {},
				onDecisionApplied = { decisionAppliedCount++ },
				actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			)

		queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
		applier.onControlStep()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED)
		assertThat(outcomes.first().applyFailure).isNull()
		assertThat(decisionAppliedCount).isEqualTo(1)
	}

	@Test
	@DisplayName(
		"AllPathsBlocked -> phase APPLIED_THEN_FAILED, applyFailure ALL_PATHS_BLOCKED, onDecisionApplied still fires"
	)
	fun allPathsBlockedIsAppliedThenFailed() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns RouteRequestResult.AllPathsBlocked(2)
		val outcomes = mutableListOf<ActionOutcome>()
		var decisionAppliedCount = 0
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = {},
				onDecisionApplied = { decisionAppliedCount++ },
				actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			)

		queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
		applier.onControlStep()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.ALL_PATHS_BLOCKED)
		assertThat(decisionAppliedCount).isEqualTo(1)
	}

	@Test
	@DisplayName("Conflict -> phase APPLIED_THEN_FAILED, applyFailure CONFLICT, onDecisionApplied still fires")
	fun conflictIsAppliedThenFailed() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns
			RouteRequestResult.Conflict("blockX", "T2")
		val outcomes = mutableListOf<ActionOutcome>()
		var decisionAppliedCount = 0
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = {},
				onDecisionApplied = { decisionAppliedCount++ },
				actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			)

		queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
		applier.onControlStep()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.CONFLICT)
		assertThat(decisionAppliedCount).isEqualTo(1)
	}

	@Test
	@DisplayName("NoRouteExists -> phase APPLIED_THEN_FAILED, applyFailure NO_ROUTE_EXISTS, onDecisionApplied still fires")
	fun noRouteExistsIsAppliedThenFailed() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns
			RouteRequestResult.NoRouteExists("zA", "doA1")
		val outcomes = mutableListOf<ActionOutcome>()
		var decisionAppliedCount = 0
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = {},
				onDecisionApplied = { decisionAppliedCount++ },
				actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			)

		queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
		applier.onControlStep()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.NO_ROUTE_EXISTS)
		assertThat(decisionAppliedCount).isEqualTo(1)
	}
}
