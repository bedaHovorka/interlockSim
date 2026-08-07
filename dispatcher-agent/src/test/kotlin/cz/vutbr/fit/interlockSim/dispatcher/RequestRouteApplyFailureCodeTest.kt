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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionPhase
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
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
 *
 * ## End-to-end reachability (Issue #893, task A-R1b)
 *
 * `originNotContiguousIsAppliedThenFailed` above stubs [NetworkActuatorPort] directly, proving
 * only the applier's *mapping*. `originNotContiguousThroughFacadeWiredPortIsAppliedThenFailed`
 * below proves the same outcome through a real, facade-wired `DefaultNetworkActuatorPort` --
 * exactly how `DispatcherAgentModule` wires `NetworkActuatorPort` in production -- so the
 * discriminant is now live on both the facade and the legacy/no-facade actuator path.
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

	// ── Task A-R1b helpers (Issue #893) ─────────────────────────────────────

	private fun mockInOut(name: String): DynamicInOut {
		val staticRef = mockk<InOut>(relaxed = true)
		every { staticRef.getName() } returns name
		return mockk<DynamicInOut>(relaxed = true).also {
			every { it.name } returns name
			every { it.staticRef } returns staticRef
		}
	}

	/**
	 * Builds a real [DefaultNetworkActuatorPort] wired with [facade] -- exactly how
	 * `DispatcherAgentModule` wires `NetworkActuatorPort` in production (Issue #573, SP3.5).
	 * "zA"/"doA1" are registered as InOuts so `requireEndpoint` accepts them, matching the
	 * endpoint names every other test in this file already uses.
	 *
	 * @since Issue #893 (phase alpha, task A-R1b)
	 */
	private fun facadeWiredPort(facade: InterlockingFacade): NetworkActuatorPort {
		val grid = mockk<RailwayNetGrid<Cell>>(relaxed = true)
		every { grid.cols } returns 0
		every { grid.rows } returns 0
		val routingServices = mockk<RoutingServices>(relaxed = true)
		val env = mockk<SimulationEnvironment>(relaxed = true)
		every { env.getInOuts() } returns listOf(mockInOut("zA"), mockInOut("doA1"))
		every { env.getRailWayNetGrid() } returns grid
		every { env.getRoutingServices() } returns routingServices
		return DefaultNetworkActuatorPort(env = env, interlockingFacade = facade)
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

	/**
	 * The contiguity rejection (Issue #893, task A-R1) must be countable on its own.
	 *
	 * `DefaultPathReservationService.reservePath` now refuses a route whose start is not
	 * contiguous with the requesting train's footprint. That is an **LLM output error** — the
	 * dispatcher asked for track somewhere the train is not — whereas
	 * [ApplyFailureCode.ALL_PATHS_BLOCKED] is ordinary network contention that a correctly
	 * cautious agent produces all the time and that SP2c.20's gate deliberately excludes from
	 * the invalid-output rate. Folding the two together would make the defect this task fixes
	 * invisible in exactly the metric built to find it.
	 */
	@Test
	@DisplayName("OriginNotContiguous -> phase APPLIED_THEN_FAILED, applyFailure ORIGIN_NOT_CONTIGUOUS")
	fun originNotContiguousIsAppliedThenFailed() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns
			RouteRequestResult.OriginNotContiguous("zA", "T1 holds no block bounded by 'zA'")
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
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)
		assertThat(decisionAppliedCount).isEqualTo(1)
	}

	/**
	 * Task A-R1b (Issue #893): the discriminant must survive the FACADE branch too, not just a
	 * directly-mocked [NetworkActuatorPort] as in [originNotContiguousIsAppliedThenFailed] above.
	 * [DefaultNetworkActuatorPort] wired with an [InterlockingFacade] is exactly how
	 * `DispatcherAgentModule` wires `NetworkActuatorPort` in production; before this fix every
	 * denial reaching that facade-wired port collapsed to `RouteRequestResult.AllPathsBlocked(0)`
	 * regardless of the kernel's actual reason, so this scenario reached the applier as
	 * `ALL_PATHS_BLOCKED` / [cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome.Blocked]
	 * instead of `ORIGIN_NOT_CONTIGUOUS` / [AppliedOutcome.OriginNotContiguous].
	 */
	@Test
	@DisplayName(
		"OriginNotContiguous through a facade-wired NetworkActuatorPort -> ORIGIN_NOT_CONTIGUOUS " +
			"+ AppliedOutcome.OriginNotContiguous carrying the reason"
	)
	fun originNotContiguousThroughFacadeWiredPortIsAppliedThenFailed() {
		val reason = "T1 holds no block bounded by 'zA'; legal origins: doA1"
		val facade = mockk<InterlockingFacade>()
		every { facade.requestRouteByEndpoints("T1", "zA", "doA1") } returns
			InterlockingFacade.RouteResponse.Denied(reason, originNotContiguous = true)
		val networkActuator = facadeWiredPort(facade)

		val correlationMap = CommandCorrelationMap()
		val outcomeChannel = AppliedOutcomeChannel()
		val queue = ActuatorCommandQueue(correlationMap = correlationMap)
		val outcomes = mutableListOf<ActionOutcome>()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = {},
				correlationMap = correlationMap,
				outcomeSink = outcomeChannel,
				actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			)

		queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
		applier.onControlStep()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS)

		val published = outcomeChannel.drainSince(0L)
		assertThat(published).hasSize(1)
		assertThat(published.first()).isInstanceOf(AppliedOutcome.OriginNotContiguous::class)
		val originOutcome = published.first() as AppliedOutcome.OriginNotContiguous
		assertThat(originOutcome.trainId).isEqualTo("T1")
		assertThat(originOutcome.fromEndpointName).isEqualTo("zA")
		assertThat(originOutcome.toEndpointName).isEqualTo("doA1")
		assertThat(originOutcome.reason).isEqualTo(reason)
	}
}
