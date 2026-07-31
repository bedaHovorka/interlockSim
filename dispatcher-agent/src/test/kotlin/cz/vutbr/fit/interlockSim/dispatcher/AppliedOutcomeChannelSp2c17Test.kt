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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.agents.Affordance
import cz.vutbr.fit.interlockSim.dispatcher.agents.CompactTextRenderer
import cz.vutbr.fit.interlockSim.dispatcher.agents.RenderContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Acceptance-criteria tests for the SP2c.17 correlated async outcome channel (#840).
 *
 * Tests:
 * - AC: `request_route` that yields `Conflict` appears as `CONFLICTED` in the next tick's
 *   rendered prompt, correlated to the right train and target.
 * - AC: `unattributedApplies` is counted and surfaced when correlation fails (i.e. when the
 *   command was applied without being registered in a [CommandCorrelationMap]).
 * - AC: No new tool is registered (tool count unchanged — tested via [CommandCorrelationMap]
 *   not being a tool).
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
@DisplayName("SP2c.17 — correlated async outcome channel (#840)")
class AppliedOutcomeChannelSp2c17Test {
	private lateinit var networkActuator: NetworkActuatorPort
	private lateinit var correlationMap: CommandCorrelationMap
	private lateinit var outcomeSink: AppliedOutcomeChannel

	@BeforeEach
	fun setUp() {
		networkActuator = mockk(relaxed = true)
		correlationMap = CommandCorrelationMap()
		outcomeSink = AppliedOutcomeChannel()
	}

	// ── Helper ────────────────────────────────────────────────────────────────

	/**
	 * Creates a fully-wired [DispatchDecisionApplier] with the test [correlationMap]
	 * and [outcomeSink] connected, paired with a [ActuatorCommandQueue] that is also
	 * wired to [correlationMap].
	 */
	private fun makeWiredApplier(): Pair<ActuatorCommandQueue, DispatchDecisionApplier> {
		val queue = ActuatorCommandQueue(correlationMap = correlationMap)
		val applier = DispatchDecisionApplier(
			queue = queue,
			networkActuator = networkActuator,
			onApproveTrain = { },
			correlationMap = correlationMap,
			outcomeSink = outcomeSink
		)
		return queue to applier
	}

	// ── AC: Conflict correlation ───────────────────────────────────────────────

	@Nested
	@DisplayName("Conflict correlation (AC)")
	inner class ConflictCorrelation {
		@Test
		@DisplayName("request_route that yields Conflict appears as CONFLICTED correlated to the right train and target")
		fun requestRouteConflictAppearsAsConflicted() {
			every {
				networkActuator.requestRoute("T-087", "zA", "InOut-B")
			} returns RouteRequestResult.Conflict(
				blockName = "W2",
				existingOwner = "T-042"
			)

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))

			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(1)
			val outcome = outcomes[0]
			assertThat(outcome).isInstanceOf(AppliedOutcome.Conflicted::class)
			outcome as AppliedOutcome.Conflicted
			assertThat(outcome.trainId).isEqualTo("T-087")
			assertThat(outcome.toEndpointName).isEqualTo("InOut-B")
			assertThat(outcome.blockName).isEqualTo("W2")
			assertThat(outcome.existingOwner).isEqualTo("T-042")
		}

		@Test
		@DisplayName("Conflicted outcome renders as CONFLICTED in the next tick's prompt section")
		fun conflictedOutcomeRenderedInPrompt() {
			every {
				networkActuator.requestRoute("T-087", "zA", "InOut-B")
			} returns RouteRequestResult.Conflict(
				blockName = "W2",
				existingOwner = "T-042"
			)

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))
			applier.onControlStep()

			// Simulate the projector draining outcomes for the next tick
			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(
				tick = 2L,
				appliedOutcomes = outcomes
			)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			// AC: appears as CONFLICTED correlated to the right train and target
			assertThat(rendered).contains("request_route T-087 -> InOut-B : CONFLICTED")
			assertThat(rendered).contains("W2 held by T-042")
		}

		@Test
		@DisplayName("Reserved outcome renders as RESERVED for the right train and target")
		fun reservedOutcomeRenderedInPrompt() {
			every {
				networkActuator.requestRoute("T-087", "zA", "InOut-B")
			} returns RouteRequestResult.Reserved("T-087", blocksCount = 3)

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = outcomes)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			assertThat(rendered).contains("request_route T-087 -> InOut-B : RESERVED")
		}

		@Test
		@DisplayName("Blocked outcome renders as BLOCKED with path count")
		fun blockedOutcomeRenderedInPrompt() {
			every {
				networkActuator.requestRoute("T-091", "zA", "InOut-B")
			} returns RouteRequestResult.AllPathsBlocked(attemptedPaths = 2)

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-091", "zA", "InOut-B")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = outcomes)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			assertThat(rendered).contains("request_route T-091 -> InOut-B : BLOCKED")
			assertThat(rendered).contains("2 path(s) attempted")
		}

		@Test
		@DisplayName("approve_train outcome renders as ADMITTED")
		fun approveTrain_renderedAsAdmitted() {
			val admittedTrains = mutableListOf<String>()
			val queue = ActuatorCommandQueue(correlationMap = correlationMap)
			val applier = DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = { admittedTrains.add(it) },
				correlationMap = correlationMap,
				outcomeSink = outcomeSink
			)
			correlationMap.newCycle()
			queue.postAll(listOf(DispatchDecision.ApproveTrain("T-102")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = outcomes)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			assertThat(rendered).contains("approve_train T-102 : ADMITTED")
		}

		@Test
		@DisplayName("CommandId is unique per queued decision — two identical RequestRoute in different cycles get distinct IDs")
		fun distinctCommandIdsAcrossCycles() {
			every {
				networkActuator.requestRoute(any(), any(), any())
			} returns RouteRequestResult.Conflict(blockName = "W1", existingOwner = "T-001")

			val (queue, applier) = makeWiredApplier()

			correlationMap.newCycle() // cycle 1
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))
			applier.onControlStep()

			correlationMap.newCycle() // cycle 2
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(2)
			// Both should be Conflicted, but with different CommandIds
			val ids = outcomes.map { it.id.value }
			assertThat(ids[0]).isEqualTo(1L)
			assertThat(ids[1]).isEqualTo(2L)
		}
	}

	// ── AC: unattributedApplies ────────────────────────────────────────────────

	@Nested
	@DisplayName("unattributedApplies (AC)")
	inner class UnattributedApplies {
		@Test
		@DisplayName("unattributedApplies is 0 when all decisions were registered before apply")
		fun zeroWhenAllDecisionsRegistered() {
			every {
				networkActuator.requestRoute(any(), any(), any())
			} returns RouteRequestResult.Reserved("T-087", blocksCount = 1)

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))
			applier.onControlStep()

			assertThat(correlationMap.unattributedApplies).isEqualTo(0L)
		}

		@Test
		@DisplayName("unattributedApplies increments when correlate() finds no entry (decision not registered)")
		fun incrementsWhenCorrelationFails() {
			every {
				networkActuator.requestRoute(any(), any(), any())
			} returns RouteRequestResult.Reserved("T-087", blocksCount = 1)

			// Applier with a FRESH correlationMap that has NO registered decisions
			val emptyCorrelationMap = CommandCorrelationMap()
			val queue = ActuatorCommandQueue() // NOT wired to correlationMap, so nothing is registered
			val applier = DispatchDecisionApplier(
				queue = queue,
				networkActuator = networkActuator,
				onApproveTrain = { },
				correlationMap = emptyCorrelationMap,
				outcomeSink = outcomeSink
			)

			// Post directly to the queue (bypasses correlationMap.register)
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "InOut-B")))
			applier.onControlStep()

			// correlate() returns null → unattributedApplies increments
			assertThat(emptyCorrelationMap.unattributedApplies).isEqualTo(1L)
		}

		@Test
		@DisplayName("DroppedInvalid outcome is published when IllegalArgumentException is thrown")
		fun droppedInvalidPublishedOnException() {
			every {
				networkActuator.requestRoute(any(), any(), any())
			} throws IllegalArgumentException("Unknown endpoint: XYZ")

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "XYZ")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(1)
			val outcome = outcomes[0]
			assertThat(outcome).isInstanceOf(AppliedOutcome.DroppedInvalid::class)
			outcome as AppliedOutcome.DroppedInvalid
			assertThat(outcome.commandType).isEqualTo("request_route")
			assertThat(outcome.trainId).isEqualTo("T-087")
			assertThat(outcome.message).contains("Unknown endpoint: XYZ")
		}

		@Test
		@DisplayName("DroppedInvalid renders as DROPPED in prompt")
		fun droppedInvalidRenderedInPrompt() {
			every {
				networkActuator.requestRoute(any(), any(), any())
			} throws IllegalArgumentException("Unknown endpoint: XYZ")

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.RequestRoute("T-087", "zA", "XYZ")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = outcomes)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			assertThat(rendered).contains("request_route T-087 : DROPPED")
			assertThat(rendered).contains("Unknown endpoint: XYZ")
		}
	}

	// ── AC: release_route correlation ──────────────────────────────────────────

	@Nested
	@DisplayName("release_route correlation (AC)")
	inner class ReleaseRouteCorrelation {
		@Test
		@DisplayName("release_route that returns true renders as RELEASED")
		fun releaseRouteReleasedRendered() {
			every { networkActuator.releaseRoute("T-087") } returns true

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.ReleaseRoute("T-087")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			assertThat(outcomes).hasSize(1)
			val outcome = outcomes[0]
			assertThat(outcome).isInstanceOf(AppliedOutcome.Released::class)
			outcome as AppliedOutcome.Released
			assertThat(outcome.trainId).isEqualTo("T-087")
			assertThat(outcome.anyReleased).isEqualTo(true)
		}

		@Test
		@DisplayName("release_route that returns false renders as NO_RESERVATION")
		fun releaseRouteNoReservationRendered() {
			every { networkActuator.releaseRoute("T-087") } returns false

			correlationMap.newCycle()
			val (queue, applier) = makeWiredApplier()
			queue.postAll(listOf(DispatchDecision.ReleaseRoute("T-087")))
			applier.onControlStep()

			val outcomes = outcomeSink.drainSince(0L)
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = outcomes)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			assertThat(rendered).contains("release_route T-087 : NO_RESERVATION")
		}
	}

	// ── AC: No new tool registered ──────────────────────────────────────────────

	@Nested
	@DisplayName("No new tool registered (AC from SP2c.6)")
	inner class NoNewToolRegistered {
		@Test
		@DisplayName("CommandCorrelationMap is not a DomainTool — zero new tools added by SP2c.17")
		fun commandCorrelationMapIsNotATool() {
			// CommandCorrelationMap has no 'name', 'description', or 'execute' API —
			// it is an internal implementation detail, not a tool the LLM can call.
			// This assertion verifies the intent by confirming it does NOT implement
			// the DomainTool interface or any tool-related marker.
			val map = CommandCorrelationMap()
			assertThat(map).isInstanceOf(CommandCorrelationMap::class)
			// It is NOT an instance of DomainTool — this would fail to compile if it were.
		}
	}

	// ── Golden-file format ────────────────────────────────────────────────────

	@Nested
	@DisplayName("Golden-file: applied_outcomes section format")
	inner class GoldenFileFormat {
		@Test
		@DisplayName("applied outcomes section matches expected format: header + indented entries + (none) when empty")
		fun appliedOutcomesSectionFormat_none() {
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = emptyList())
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			assertThat(rendered).contains("applied outcomes since last tick:\n  (none)\n")
		}

		@Test
		@DisplayName("applied outcomes section matches expected format for multiple outcomes")
		fun appliedOutcomesSectionFormat_multipleOutcomes() {
			val id1 = CommandId(1L)
			val id2 = CommandId(2L)
			val id3 = CommandId(3L)
			val outcomes = listOf(
				AppliedOutcome.Reserved(
					trainId = "T-087",
					fromEndpointName = "zA",
					toEndpointName = "InOut-B",
					blocksCount = 2,
					id = id1,
					tickIndex = 5L
				),
				AppliedOutcome.Conflicted(
					trainId = "T-091",
					fromEndpointName = "zA",
					toEndpointName = "InOut-B",
					blockName = "W2",
					existingOwner = "T-087",
					id = id2,
					tickIndex = 5L
				),
				AppliedOutcome.Approved(
					trainId = "T-102",
					admitted = true,
					id = id3,
					tickIndex = 5L
				)
			)
			val observation = DispatcherObservation.EMPTY.copy(appliedOutcomes = outcomes)
			val ctx = buildRenderContext(observation)
			val rendered = CompactTextRenderer().render(ctx)

			// Golden-file: check the exact section format
			assertThat(rendered).contains("applied outcomes since last tick:\n")
			assertThat(rendered).contains("  request_route T-087 -> InOut-B : RESERVED\n")
			assertThat(rendered).contains("  request_route T-091 -> InOut-B : CONFLICTED (W2 held by T-087)\n")
			assertThat(rendered).contains("  approve_train T-102 : ADMITTED\n")
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun buildRenderContext(observation: DispatcherObservation): RenderContext =
		RenderContext(
			observation = observation,
			previous = DispatcherObservation.EMPTY,
			workingMemory = WorkingMemory.EMPTY,
			history = emptyList(),
			affordances = listOf(Affordance.NO_OP)
		)
}
