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
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit coverage for [RuleBasedEmissionStrategy] (SP2c.5, Issue #828).
 *
 * The strategy is the adapter that lets the existing rule-based [Dispatcher] drive
 * [DispatchTickLoop] through the same four-action vocabulary and validator the LLM agent uses.
 * Two things must hold for the P10 determinism gate to keep working:
 *
 * 1. every emitted action carries [ActionAuthor.RULE_BASED] (never `RULE_FALLBACK`, which
 *    [cz.vutbr.fit.interlockSim.dispatcher.agents.TerminalFallbackGuard] treats as terminal);
 * 2. `NoAction` maps to an **empty list**, never to `null` — `null` would be rewritten by the
 *    loop into a `TIMEOUT_NOOP`, misreporting a deliberate "do nothing" as a deadline miss.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
@DisplayName("SP2c.5 — RuleBasedEmissionStrategy (#828)")
@Timeout(30, unit = TimeUnit.SECONDS)
class RuleBasedEmissionStrategyTest {
	/** Returns a fixed decision list and captures the observation it was handed. */
	private class ScriptedDispatcher(
		private val decisions: List<DispatchDecision>
	) : Dispatcher {
		var lastObservation: DispatchObservation? = null
			private set
		var decideCallCount: Int = 0
			private set

		override fun decide(observed: DispatchObservation): List<DispatchDecision> {
			lastObservation = observed
			decideCallCount++
			return decisions
		}
	}

	private fun train(
		trainId: String,
		phase: TrainPhase,
		frontSection: String? = "kA",
		destination: String = "B"
	) = TrainView(
		trainId = trainId,
		phase = phase,
		frontSectionName = frontSection,
		velocityMps = if (phase == TrainPhase.RUNNING) 6.0 else 0.0,
		accelerationMps2 = if (phase == TrainPhase.RUNNING) 0.5 else 0.0,
		destinationInOutName = destination,
		signalAheadName = null,
		signalAheadAspect = null,
		distanceToSignalAheadMetres = 0.0,
		waitingSinceSimTime = null,
		waitSeconds = 0.0
	)

	private fun blockInput(
		blockId: String,
		toward: String
	) = BlockInputObservation(
		blockId = blockId,
		towardSemaphoreName = toward,
		toSeparatorName = "B",
		state = TrackFacility.State.FREE,
		ownerTrainId = null,
		isApproachingThisInput = false,
		pathSetUpTowardThisInput = false,
		pathAlreadyExtendedBeyond = false
	)

	private val observation =
		DispatcherObservation.EMPTY.copy(
			tick = 12L,
			simTime = 120.0,
			trains = listOf(train("T-2", TrainPhase.RUNNING), train("T-1", TrainPhase.QUEUED, frontSection = null)),
			queued = listOf(QueuedTrainView("T-1", "B", 100.0)),
			activeCount = 1,
			capacity = 3,
			innerBlockInputs = listOf(blockInput("kA", "doA1")),
			outerBlockInputs = listOf(blockInput("kZ", "zA"))
		)

	// ── Decision → action mapping ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Decision mapping into the sealed DispatchAction vocabulary")
	inner class DecisionMapping {
		@Test
		@DisplayName("ApproveTrain maps to DispatchAction.ApproveTrain")
		fun approveTrainMapping() {
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(listOf(DispatchDecision.ApproveTrain("T-1"))))

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted).isNotNull()
			assertThat(emitted.map { it.action }).containsExactly(DispatchAction.ApproveTrain("T-1"))
		}

		/**
		 * The rule-based dispatcher speaks `ReservePath(fromSemaphoreName, toSeparatorName)`; the
		 * agent vocabulary speaks `RequestRoute(fromEndpointName, toEndpointName)`. Would fail if
		 * the two endpoint fields were swapped in `toAttributedAction`.
		 */
		@Test
		@DisplayName("ReservePath maps to RequestRoute with from/to in the right order")
		fun reservePathMapsToRequestRoute() {
			val decision = DispatchDecision.ReservePath("T-2", fromSemaphoreName = "doA1", toSeparatorName = "B")
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(listOf(decision)))

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted.map { it.action }).containsExactly(
				DispatchAction.RequestRoute(
					trainId = "T-2",
					fromEndpointName = "doA1",
					toEndpointName = "B",
					scope = RouteScope.Section
				)
			)
		}

		/**
		 * The distinction the loop relies on: `emptyList()` means "nothing to do", `null` means
		 * "deadline missed". Would fail if `NoAction` were mapped to a `NoOp` action or if the
		 * strategy's return type were relaxed to nullable.
		 */
		@Test
		@DisplayName("NoAction produces an empty list, not null and not a NoOp action")
		fun noActionProducesEmptyList() {
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(listOf(DispatchDecision.NoAction)))

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted).isNotNull()
			assertThat(emitted).isEmpty()
		}

		@Test
		@DisplayName("an empty decision list produces an empty action list")
		fun emptyDecisionsProduceEmptyActions() {
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(emptyList()))

			assertThat(runBlocking { strategy.emit("", observation) }).isEmpty()
		}

		@Test
		@DisplayName("decisions outside the rule-based vocabulary are dropped")
		fun outOfVocabularyDecisionsAreDropped() {
			val strategy =
				RuleBasedEmissionStrategy(
					ScriptedDispatcher(
						listOf(
							DispatchDecision.HoldTrain("T-2", holdDurationSeconds = 5.0),
							DispatchDecision.ApproveTrain("T-1")
						)
					)
				)

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted).hasSize(1)
			assertThat(emitted[0].action).isEqualTo(DispatchAction.ApproveTrain("T-1"))
		}

		@Test
		@DisplayName("mixed decisions keep their relative order")
		fun mixedDecisionsKeepOrder() {
			val strategy =
				RuleBasedEmissionStrategy(
					ScriptedDispatcher(
						listOf(
							DispatchDecision.ApproveTrain("T-1"),
							DispatchDecision.ReservePath("T-2", "doA1", "B")
						)
					)
				)

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted.map { it.action.kind }).containsExactly("approve_train", "request_route")
		}
	}

	// ── Authorship ────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Authorship: every emitted action is RULE_BASED")
	inner class Authorship {
		/**
		 * Would fail if any branch of `toAttributedAction` emitted `RULE_FALLBACK` (or left the
		 * `AttributedAction` default of `LLM`) — the guard would then fail every P10 gate run.
		 */
		@Test
		@DisplayName("all emitted actions carry ActionAuthor.RULE_BASED")
		fun allActionsAreRuleBased() {
			val strategy =
				RuleBasedEmissionStrategy(
					ScriptedDispatcher(
						listOf(
							DispatchDecision.ApproveTrain("T-1"),
							DispatchDecision.ReservePath("T-2", "doA1", "B")
						)
					)
				)

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted.all { it.author == ActionAuthor.RULE_BASED }).isTrue()
			assertThat(emitted.any { it.author == ActionAuthor.RULE_FALLBACK }).isFalse()
		}

		/**
		 * The strategy's [cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy.author] is
		 * [ActionAuthor.RULE_BASED] — the author [DispatchTickLoop] attributes an idle-substituted
		 * `no_op` to when this strategy returns an empty list (a deliberate rule-engine idle tick,
		 * not a [ActionAuthor.TIMEOUT_NOOP] degraded timeout). Would fail if the override were
		 * dropped/changed to any other author (the inherited default happens to match, but the
		 * override documents the rule engine's intent).
		 */
		@Test
		@DisplayName("the strategy's author property is ActionAuthor.RULE_BASED")
		fun authorPropertyIsRuleBased() {
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(emptyList()))

			assertThat(strategy.author).isEqualTo(ActionAuthor.RULE_BASED)
		}

		@Test
		@DisplayName("emitted actions carry the observation's tick")
		fun actionsCarryObservationTick() {
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(listOf(DispatchDecision.ApproveTrain("T-1"))))

			val emitted = runBlocking { strategy.emit("", observation) }

			assertThat(emitted[0].tick).isEqualTo(12L)
		}

		@Test
		@DisplayName("command ids are unique across successive emissions")
		fun commandIdsAreUnique() {
			val strategy = RuleBasedEmissionStrategy(ScriptedDispatcher(listOf(DispatchDecision.ApproveTrain("T-1"))))

			val first = runBlocking { strategy.emit("", observation) }
			val second = runBlocking { strategy.emit("", observation) }

			assertThat(second[0].commandId.value).isGreaterThan(first[0].commandId.value)
		}
	}

	// ── Observation reconstruction ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Reconstruction: DispatcherObservation → DispatchObservation")
	inner class ObservationReconstruction {
		/**
		 * The whole reason #828 extends [DispatcherObservation] with `innerBlockInputs` /
		 * `outerBlockInputs`: without them the reconstructed [DispatchObservation] would carry
		 * empty lists and the rule-based dispatcher would never find a path to reserve.
		 */
		@Test
		@DisplayName("inner and outer block inputs are carried through verbatim")
		fun blockInputsAreCarriedThrough() {
			val dispatcher = ScriptedDispatcher(emptyList())

			runBlocking { RuleBasedEmissionStrategy(dispatcher).emit("", observation) }

			val reconstructed = dispatcher.lastObservation!!
			assertThat(reconstructed.innerBlockInputs).isEqualTo(observation.innerBlockInputs)
			assertThat(reconstructed.outerBlockInputs).isEqualTo(observation.outerBlockInputs)
		}

		@Test
		@DisplayName("queued trains become unapprovedTrains with their destination preserved")
		fun queuedTrainsBecomeUnapprovedTrains() {
			val dispatcher = ScriptedDispatcher(emptyList())

			runBlocking { RuleBasedEmissionStrategy(dispatcher).emit("", observation) }

			val unapproved = dispatcher.lastObservation!!.unapprovedTrains
			assertThat(unapproved).hasSize(1)
			assertThat(unapproved[0].trainId).isEqualTo("T-1")
			assertThat(unapproved[0].destinationInOutName).isEqualTo("B")
		}

		/**
		 * Would fail if the QUEUED/EXITED filter were dropped: a queued train has no position, so
		 * feeding it to the dispatcher as an active train position would misreport the network.
		 */
		@Test
		@DisplayName("only non-queued, non-exited trains become train positions")
		fun onlyActiveTrainsBecomePositions() {
			val obs =
				observation.copy(
					trains =
						listOf(
							train("T-2", TrainPhase.RUNNING),
							train("T-1", TrainPhase.QUEUED, frontSection = null),
							train("T-0", TrainPhase.EXITED, frontSection = null)
						)
				)
			val dispatcher = ScriptedDispatcher(emptyList())

			runBlocking { RuleBasedEmissionStrategy(dispatcher).emit("", obs) }

			val positions = dispatcher.lastObservation!!.snapshot.trainPositions
			assertThat(positions.map { it.trainId }).containsExactly("T-2")
		}

		@Test
		@DisplayName("HELD and DWELLING trains still count as active positions")
		fun heldAndDwellingTrainsAreActive() {
			val obs =
				observation.copy(
					trains = listOf(train("T-2", TrainPhase.HELD), train("T-3", TrainPhase.DWELLING))
				)
			val dispatcher = ScriptedDispatcher(emptyList())

			runBlocking { RuleBasedEmissionStrategy(dispatcher).emit("", obs) }

			assertThat(
				dispatcher.lastObservation!!
					.snapshot.trainPositions
					.map { it.trainId }
			).containsExactly("T-2", "T-3")
		}

		@Test
		@DisplayName("velocity, acceleration and front section survive the round trip")
		fun kinematicsSurviveRoundTrip() {
			val dispatcher = ScriptedDispatcher(emptyList())

			runBlocking { RuleBasedEmissionStrategy(dispatcher).emit("", observation) }

			val reading =
				dispatcher.lastObservation!!
					.snapshot.trainPositions
					.single { it.trainId == "T-2" }
			assertThat(reading.velocity).isEqualTo(6.0)
			assertThat(reading.acceleration).isEqualTo(0.5)
			assertThat(reading.frontSectionName).isEqualTo("kA")
		}

		@Test
		@DisplayName("simTime is carried into the reconstructed snapshot")
		fun simTimeIsCarried() {
			val dispatcher = ScriptedDispatcher(emptyList())

			runBlocking { RuleBasedEmissionStrategy(dispatcher).emit("", observation) }

			assertThat(dispatcher.lastObservation!!.snapshot.simTime).isEqualTo(120.0)
		}

		@Test
		@DisplayName("the wrapped dispatcher is consulted exactly once per emit")
		fun dispatcherConsultedOncePerEmit() {
			val dispatcher = ScriptedDispatcher(emptyList())
			val strategy = RuleBasedEmissionStrategy(dispatcher)

			runBlocking {
				strategy.emit("", observation)
				strategy.emit("", observation)
			}

			assertThat(dispatcher.decideCallCount).isEqualTo(2)
		}

		/**
		 * The rendered prompt is meaningless to a rule-based dispatcher; it must not leak into the
		 * reconstructed observation or change the decision.
		 */
		@Test
		@DisplayName("the prompt argument is ignored")
		fun promptIsIgnored() {
			val dispatcher = ScriptedDispatcher(listOf(DispatchDecision.ApproveTrain("T-1")))
			val strategy = RuleBasedEmissionStrategy(dispatcher)

			val withPrompt = runBlocking { strategy.emit("a long rendered prompt", observation) }
			val withoutPrompt = runBlocking { strategy.emit("", observation) }

			assertThat(withPrompt.map { it.action }).isEqualTo(withoutPrompt.map { it.action })
		}
	}
}
