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

import ai.koog.agents.core.agent.AIAgent
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl
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
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
 * ## End-to-end reachability (Issue #893 task A-R1b; Issue #834 task alpha-7a)
 *
 * The `…IsAppliedThenFailed` tests stub [NetworkActuatorPort] directly, proving only the applier's
 * *mapping*. The `…ThroughFacadeWiredPort…` tests prove the same outcomes through a real,
 * facade-wired `DefaultNetworkActuatorPort` -- exactly how `DispatcherAgentModule` wires
 * `NetworkActuatorPort` in production.
 *
 * Issue #893 made that true for the contiguity discriminant only. Until Issue #834 task alpha-7a
 * every *other* facade denial still collapsed to `RouteRequestResult.AllPathsBlocked(0)`, so the
 * `CONFLICT` and `NO_ROUTE_EXISTS` branches of [DispatchDecisionApplier] were green here yet
 * production-unreachable, and a permanently impossible request was reported to the model as
 * retryable contention -- inflating the bucket the sweep's invalid-output rate excludes while
 * deflating the one it counts. `noPathThroughFacadeWiredPortIsNoRouteExists`,
 * `conflictThroughFacadeWiredPortIsConflict` and
 * `allPathsBlockedThroughFacadeWiredPortKeepsAttemptedPaths` close that gap for every cause.
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

	/**
	 * Review finding #2 (Issue #834): a four-condition interlocking refusal (route freedom / C1,
	 * switch / C2, atomic lock / C3-C4, signal un-clearable) is its own denial cause, distinct from
	 * the endpoint-resolution residual [RouteRequestResult.NoRouteExists]. The new
	 * [RouteRequestResult.ConditionFailed] carries a `retryable` flag so transient contention is
	 * not mislabelled as a permanent defect. This test pins only the applier's mapping; the
	 * facade-wired sibling below proves reachability through the production wiring.
	 */
	@Test
	@DisplayName("ConditionFailed -> APPLIED_THEN_FAILED, applyFailure CONDITION_FAILED, onDecisionApplied fires")
	fun conditionFailedIsAppliedThenFailed() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns
			RouteRequestResult.ConditionFailed("Block U1 occupied by train T2", retryable = true)
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
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.CONDITION_FAILED)
		assertThat(decisionAppliedCount).isEqualTo(1)
	}

	/**
	 * Issue #903: a candidate that is permanently geometrically impossible (rear-facing START or
	 * an unconfigurable switch) must reach the applier as its own [ApplyFailureCode], distinct
	 * from ordinary contention ([ApplyFailureCode.ALL_PATHS_BLOCKED]) — otherwise the dispatcher
	 * would keep retrying an identical request that can never succeed. This test pins only the
	 * applier's mapping (mirrors [conditionFailedIsAppliedThenFailed] above).
	 */
	@Test
	@DisplayName(
		"GeometricallyImpossible -> APPLIED_THEN_FAILED, applyFailure GEOMETRICALLY_IMPOSSIBLE, onDecisionApplied fires"
	)
	fun geometricallyImpossibleIsAppliedThenFailed() {
		val networkActuator = mockk<NetworkActuatorPort>(relaxed = true)
		every { networkActuator.requestRoute(any(), any(), any()) } returns
			RouteRequestResult.GeometricallyImpossible(
				"Switch along candidate 0 could not be configured for the requested route"
			)
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
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.GEOMETRICALLY_IMPOSSIBLE)
		assertThat(decisionAppliedCount).isEqualTo(1)
	}

	// ── Issue #834 task alpha-7a: every denial cause survives the facade branch ─────

	/**
	 * Drives one `RequestRoute` decision through a **real, facade-wired**
	 * [DefaultNetworkActuatorPort] whose kernel answers [denial], and returns the resulting
	 * `(ActionOutcome list, AppliedOutcome list)`.
	 *
	 * This is the wiring [DispatcherAgentModule] builds in production, so a code that only the
	 * applier's own `when` can produce (as the three sibling tests above stub it) is not evidence
	 * that the code is reachable at all — that is exactly the dead-path problem Issue #834 task
	 * alpha-7a fixes.
	 */
	private fun applyThroughFacadeWiredPort(
		denial: InterlockingFacade.RouteResponse.Denied
	): Pair<List<ActionOutcome>, List<AppliedOutcome>> {
		val facade = mockk<InterlockingFacade>()
		every { facade.requestRouteByEndpoints("T1", "zA", "doA1") } returns denial

		val correlationMap = CommandCorrelationMap()
		val outcomeChannel = AppliedOutcomeChannel()
		val queue = ActuatorCommandQueue(correlationMap = correlationMap)
		val outcomes = mutableListOf<ActionOutcome>()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = facadeWiredPort(facade),
				onApproveTrain = {},
				correlationMap = correlationMap,
				outcomeSink = outcomeChannel,
				actionOutcomeSink = ActionOutcomeSink { outcome -> outcomes.add(outcome) }
			)

		queue.postAll(listOf(DispatchDecision.RequestRoute("T1", "zA", "doA1")))
		applier.onControlStep()

		return outcomes to outcomeChannel.drainSince(0L)
	}

	/**
	 * Test F-a (Issue #834): `ReservationResult.NoPathExists` reaches the applier as
	 * [ApplyFailureCode.NO_ROUTE_EXISTS] through the facade-wired port.
	 *
	 * Before this fix, [DispatchDecisionApplier]'s `NoRouteExists` branch was
	 * production-unreachable: every facade denial except the contiguity one collapsed to
	 * `RouteRequestResult.AllPathsBlocked(0)`, so a permanently impossible request was reported to
	 * the model as retryable contention **and** excluded from the invalid-output rate that Issue
	 * #834's sweep ranks parameter cells on.
	 */
	@Test
	@DisplayName("NoPath denial through a facade-wired port -> NO_ROUTE_EXISTS + AppliedOutcome.NoRoute")
	fun noPathThroughFacadeWiredPortIsNoRouteExists() {
		val (outcomes, published) =
			applyThroughFacadeWiredPort(
				InterlockingFacade.RouteResponse.Denied(
					"No path exists: zA → doA1",
					InterlockingFacade.RouteResponse.DenialCause.NoPath
				)
			)

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.NO_ROUTE_EXISTS)

		assertThat(published).hasSize(1)
		assertThat(published.first()).isInstanceOf(AppliedOutcome.NoRoute::class)
		val noRoute = published.first() as AppliedOutcome.NoRoute
		assertThat(noRoute.trainId).isEqualTo("T1")
		assertThat(noRoute.fromEndpointName).isEqualTo("zA")
		assertThat(noRoute.toEndpointName).isEqualTo("doA1")
	}

	/**
	 * Test F-b (Issue #834): `ReservationResult.Conflict` reaches the applier as
	 * [ApplyFailureCode.CONFLICT] through the facade-wired port, with the conflicting block and
	 * its owner intact — so the dispatcher can wait for that specific train instead of retrying
	 * blindly. Both payloads were discarded before this fix.
	 */
	@Test
	@DisplayName("Conflict denial through a facade-wired port -> CONFLICT + AppliedOutcome.Conflicted")
	fun conflictThroughFacadeWiredPortIsConflict() {
		val (outcomes, published) =
			applyThroughFacadeWiredPort(
				InterlockingFacade.RouteResponse.Denied(
					"Block U7 occupied by train T2",
					InterlockingFacade.RouteResponse.DenialCause.Conflict("U7", "T2")
				)
			)

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.CONFLICT)

		assertThat(published).hasSize(1)
		assertThat(published.first()).isInstanceOf(AppliedOutcome.Conflicted::class)
		val conflicted = published.first() as AppliedOutcome.Conflicted
		assertThat(conflicted.blockName).isEqualTo("U7")
		assertThat(conflicted.existingOwner).isEqualTo("T2")
	}

	/**
	 * Test F-c (Issue #834): the real candidate-path count survives all the way into the prompt
	 * the LLM dispatcher actually reads.
	 *
	 * The old collapse rendered *"REFUSED — all paths blocked (0 path(s) attempted)"* — a number
	 * that contradicts [cz.vutbr.fit.interlockSim.ports.RouteRequestResult.AllPathsBlocked]'s own
	 * contract ("number of topological candidate paths that were checked") and tells the model
	 * nothing about how contended the route actually is. `n != 0` is asserted explicitly.
	 */
	@Test
	@DisplayName("AllPathsBlocked(n) through a facade-wired port keeps n != 0, into the rendered prompt line")
	fun allPathsBlockedThroughFacadeWiredPortKeepsAttemptedPaths() {
		val (outcomes, published) =
			applyThroughFacadeWiredPort(
				InterlockingFacade.RouteResponse.Denied(
					"All paths blocked (zA → doA1, attempts: 3)",
					InterlockingFacade.RouteResponse.DenialCause.AllPathsBlocked(3)
				)
			)

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.ALL_PATHS_BLOCKED)

		assertThat(published).hasSize(1)
		assertThat(published.first()).isInstanceOf(AppliedOutcome.Blocked::class)
		val blocked = published.first() as AppliedOutcome.Blocked
		assertThat(blocked.attemptedPaths).isEqualTo(3)
		assertThat(blocked.attemptedPaths).isNotEqualTo(0)

		// ... and the same count reaches the model, replacing the "(0 path(s) attempted)" line.
		val prompt = renderPrompt(blocked)
		assertThat(prompt).contains("3 path(s) attempted")
		assertThat(prompt).doesNotContain("0 path(s) attempted")
	}

	/**
	 * Review finding #2 (Issue #834): a four-condition `Denied` with cause
	 * [InterlockingFacade.RouteResponse.DenialCause.ConditionFailed] reaches the applier as
	 * [ApplyFailureCode.CONDITION_FAILED] through the facade-wired port, with the `retryable` flag
	 * and the reason prose intact in [AppliedOutcome.ConditionFailed]. This is the wiring
	 * `DispatcherAgentModule` builds in production; the four-condition `requestRoute` is not the
	 * production path (production goes through `requestRouteByEndpoints`, where `Other` is the
	 * genuine endpoint-resolution residual), but the branch is total so a future caller routing
	 * four-condition denials through the port gets the right code instead of a collapsed
	 * `ALL_PATHS_BLOCKED`.
	 */
	@Test
	@DisplayName(
		"ConditionFailed through a facade-wired port -> CONDITION_FAILED + AppliedOutcome.ConditionFailed carrying retryable and reason"
	)
	fun conditionFailedThroughFacadeWiredPortIsConditionFailed() {
		val reason = "Block U1 occupied by train T2"
		val (outcomes, published) =
			applyThroughFacadeWiredPort(
				InterlockingFacade.RouteResponse.Denied(
					reason,
					InterlockingFacade.RouteResponse.DenialCause.ConditionFailed(retryable = true)
				)
			)

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.first().phase).isEqualTo(ActionPhase.APPLIED_THEN_FAILED)
		assertThat(outcomes.first().applyFailure).isEqualTo(ApplyFailureCode.CONDITION_FAILED)

		assertThat(published).hasSize(1)
		assertThat(published.first()).isInstanceOf(AppliedOutcome.ConditionFailed::class)
		val failed = published.first() as AppliedOutcome.ConditionFailed
		assertThat(failed.trainId).isEqualTo("T1")
		assertThat(failed.fromEndpointName).isEqualTo("zA")
		assertThat(failed.toEndpointName).isEqualTo("doA1")
		assertThat(failed.retryable).isTrue()
		assertThat(failed.reason).isEqualTo(reason)
	}

	/**
	 * Renders [outcome] exactly the way the live path does: [KoogDispatchAgentImpl] drains its
	 * [AppliedOutcomeFeed] while building the user prompt. The `AIAgent` is mocked so no LLM is
	 * contacted; only the prompt text it would have received is captured.
	 */
	private fun renderPrompt(outcome: AppliedOutcome): String {
		val channel = AppliedOutcomeChannel()
		channel.publish(outcome)
		val aiAgent = mockk<AIAgent<String, String>>()
		val prompts = mutableListOf<String>()
		coEvery { aiAgent.run(any(), null) } answers {
			prompts.add(firstArg())
			"done"
		}
		val agent = KoogDispatchAgentImpl(aiAgent, outcomeFeed = channel)
		runBlocking {
			agent.decideAsync(
				DispatchObservation(
					snapshot = SimulationSnapshot.EMPTY,
					unapprovedTrains = emptyList(),
					innerBlockInputs = emptyList(),
					outerBlockInputs = emptyList()
				)
			)
		}
		return prompts.single()
	}
}
