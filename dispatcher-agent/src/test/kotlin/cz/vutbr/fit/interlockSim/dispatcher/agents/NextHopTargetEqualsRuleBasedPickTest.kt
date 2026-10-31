/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.chosenTargetName
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.FirstTrainOwnershipConflict
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tripwire for Issue #970: the LLM arm and the rule-based oracle must name the same hop target.
 *
 * [RuleBasedDispatcher] chooses its [DispatchDecision.ReservePath] target through
 * `ReservationTargetPolicy` over [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.candidateTargets];
 * since Issue #1152 [NextHopResolver] reads the very same list through the very same policy.
 * This test pins that the two arms agree on every tick of a live `vyhybna.xml` run — in both
 * directions: every
 * [NextHopOutcome.Hop] has a `ReservePath` with the same target for the same train, and every
 * `ReservePath` has a `Hop` with the same target. Should a reader ever stop going through the
 * policy, this fails before the prompt and the oracle disagree in production.
 *
 * Same wiring idea as `RuleBasedDispatcherDeterminismRunner`: a real `ShuntingLoop` drives the
 * simulation, and the oracle (`RuleBasedDispatcher().decide`) is evaluated on the exact
 * per-tick observation the synchronous production wiring sees — [RuleBasedDispatcher] is a pure
 * decision function, so a fresh instance queried from the control step is a faithful oracle.
 *
 * The conflict-injected second method (@since Issue #1244) runs the same parity pin on the
 * #1060 awaiting-extension shape (produced the way the flap-test in `:core`'s jvmTest does it)
 * and adds the model-facing denial: on no control step does the hint offer a train awaiting an
 * extension a hop to the end of the route it already holds.
 *
 * @since Issue #970; the conflict-injected method added Issue #1255 (part 2 of #1244)
 */
@DisplayName("NextHopResolver hop target equals the rule-based ReservePath target (Issue #970)")
@Tag("integration-test")
class NextHopTargetEqualsRuleBasedPickTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `every Hop target equals the ReservePath target for the same train on every tick`() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		context.getInOuts()

		val loop = ShuntingLoop(context, endTime = 300L)
		wireSynchronousDispatcher(context, loop)
		val wired = loop.controlStepListener

		// The oracle needs a SimulationSnapshot for the admission count; capture one on the sim
		// thread inside the control step, exactly where the synchronous wiring reads its own.
		val perception = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val oracle = RuleBasedDispatcher()

		var hopsCompared = 0
		val violations = mutableListOf<String>()
		loop.controlStepListener =
			ControlStepListener {
				val observation = captureObservation(perception, loop)
				val reserveTargets = reserveTargetsOf(oracle, observation)
				val hops = hopsOf(observation)

				hopsCompared += compareHopTargets(observation, hops, reserveTargets, violations)
				expectHopForEveryReserve(observation, hops, reserveTargets, violations)
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(hopsCompared, "hops compared").isGreaterThan(0)
		assertThat(violations.size, "violations: $violations").isEqualTo(0)
	}

	/**
	 * The both-arms parity of this class, checked on the #1060 awaiting-extension shape (Issue
	 * #1244, part of #1255): [FirstTrainOwnershipConflict] holds the first train that queries past
	 * a non-entry separator in an ownership-conflict wait — the way the flap test in `:core`'s
	 * jvmTest produces the shape — so the run really reaches a train standing at a signal, awaiting
	 * an extension, whose pick is the end of the route it already holds. Both arms must skip that
	 * held end: the oracle through the guard the parity pin exists for, and [NextHopResolver]
	 * through the guard its hint rendering applies — nothing in the model's face names the held end.
	 *
	 * Non-vacuity: the guard's condition is reached on at least one control step (measured there:
	 * the stall runs through several expiry cycles), and the free-running trains still produce
	 * hop comparisons.
	 */
	@Test
	@Timeout(value = 90, unit = TimeUnit.SECONDS)
	fun `with the conflict injection, no Hop names an awaiting train's held route end`() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		val stall = FirstTrainOwnershipConflict(context.getRoutingServices().getTrainNavigationService())
		// Navigation errors are recorded, not thrown (the #943 errorStop path); pass a no-op hook
		// the way the flap test passes its collector.
		val conflictEnv = NavigationDecoratingContext(context, stall.navigation) { }

		val loop = prepareShuntingLoop(context, endTime = 400L, env = conflictEnv)
		val wired = loop.controlStepListener
		val perception = DefaultNetworkPerceptionPort(env = conflictEnv, activeTrains = loop::getApprovedTrains)
		val oracle = RuleBasedDispatcher()

		var hopsCompared = 0
		var awaitingHeldEndSteps = 0
		val violations = mutableListOf<String>()
		loop.controlStepListener =
			ControlStepListener {
				val observation = captureObservation(perception, loop)
				val reserveTargets = reserveTargetsOf(oracle, observation)
				val hops = hopsOf(observation)

				hopsCompared += compareHopTargets(observation, hops, reserveTargets, violations)
				expectHopForEveryReserve(observation, hops, reserveTargets, violations)
				denyHeldEndHops(observation, hops, violations)
				awaitingHeldEndSteps += reachedGuardInputs(observation)
				wired?.onControlStep()
			}

		context.run()

		val trainId = requireNotNull(stall.heldTrainId.get()) { "no train was stalled by the injection" }
		assertThat(hopsCompared, "hops compared").isGreaterThan(0)
		assertThat(
			awaitingHeldEndSteps,
			"control steps $trainId awaited with pick == held route end"
		).isGreaterThanOrEqualTo(1)
		assertThat(violations.size, "violations: $violations").isEqualTo(0)
	}

	/** Builds the per-tick observation from the live shell. Runs on the sim thread inside the control step. */
	private fun captureObservation(
		perception: DefaultNetworkPerceptionPort,
		loop: ShuntingLoop
	): DispatchObservation {
		perception.captureSnapshot()
		return DispatchObservation(
			snapshot = perception.snapshot(),
			unapprovedTrains = loop.getQueuedTrains(),
			innerBlockInputs = loop.getInnerBlockInputs(),
			outerBlockInputs = loop.getOuterBlockInputs()
		)
	}

	/** The rule-based oracle's `ReservePath` targets per train for [observation]. */
	private fun reserveTargetsOf(
		oracle: RuleBasedDispatcher,
		observation: DispatchObservation
	) = oracle
		.decide(observation)
		.filterIsInstance<DispatchDecision.ReservePath>()
		.groupBy({ it.trainId }, { it.toTargetName })

	/** The LLM arm's next hops per train for [observation]. */
	private fun hopsOf(observation: DispatchObservation): Map<String, NextHopOutcome.Hop> {
		val trainIds =
			(observation.innerBlockInputs + observation.outerBlockInputs)
				.mapNotNull { it.ownerTrainId }
				.distinct()
		return NextHopResolver
			.resolveAll(trainIds, observation)
			.mapNotNull { (trainId, outcome) -> (outcome as? NextHopOutcome.Hop)?.let { trainId to it } }
			.toMap()
	}

	/** Forward check: every hop carries the same target the oracle's ReservePath names. */
	private fun compareHopTargets(
		observation: DispatchObservation,
		hops: Map<String, NextHopOutcome.Hop>,
		reserveTargets: Map<String, List<String>>,
		violations: MutableList<String>
	): Int {
		var compared = 0
		for ((trainId, hop) in hops) {
			compared++
			val targets = reserveTargets[trainId]
			if (targets != listOf(hop.toTargetName)) {
				violations +=
					"t=${observation.snapshot.simTime} $trainId: Hop to ${hop.toTargetName} but ReservePath to $targets"
			}
		}
		return compared
	}

	/** Reverse check: every oracle ReservePath has a hop for the same train. */
	private fun expectHopForEveryReserve(
		observation: DispatchObservation,
		hops: Map<String, NextHopOutcome.Hop>,
		reserveTargets: Map<String, List<String>>,
		violations: MutableList<String>
	) {
		for ((trainId, targets) in reserveTargets) {
			if (trainId !in hops) {
				violations += "t=${observation.snapshot.simTime} $trainId: ReservePath to $targets but no Hop"
			}
		}
	}

	/**
	 * The inputs whose owner is awaiting a route extension with a live held route end — the
	 * guard's antecedent, single-sourced here so the two checks below cannot drift apart from it.
	 */
	private fun awaitingInputs(observation: DispatchObservation): List<BlockInputObservation> =
		(observation.innerBlockInputs + observation.outerBlockInputs)
			.filter {
				!it.ownerTrainId.isNullOrEmpty() &&
					it.awaitingRouteExtension &&
					it.heldRouteEndName != null
			}

	/**
	 * Appends a violation for any [NextHopOutcome.Hop] whose target equals an awaiting train's
	 * held route end — the suppression the resolver guard exists for (Issue #1244). Real data
	 * gives a train at most one awaiting input per tick, so `associate` cannot lose anything.
	 */
	private fun denyHeldEndHops(
		observation: DispatchObservation,
		hops: Map<String, NextHopOutcome.Hop>,
		violations: MutableList<String>
	) {
		if (hops.isEmpty()) return
		val awaiting = awaitingInputs(observation)
		if (awaiting.isEmpty()) return
		val heldEndsByTrain = awaiting.associate { requireNotNull(it.ownerTrainId) to it.heldRouteEndName!! }
		for ((trainId, hop) in hops) {
			val heldEnd = heldEndsByTrain[trainId] ?: continue
			if (hop.toTargetName == heldEnd) {
				val simTime = observation.snapshot.simTime
				violations +=
					"t=$simTime $trainId: Hop to $heldEnd, but that is the held route end (Issue #1244)"
			}
		}
	}

	/**
	 * The count of inputs the #1244 guard's full condition is reached on — the non-vacuity meter:
	 * the pick equality is deliberately re-encoded here even though the guard is in the shared
	 * seam, because this pin exists to prove the scenario reaches the condition it pins.
	 */
	private fun reachedGuardInputs(observation: DispatchObservation): Int =
		awaitingInputs(observation).count { it.chosenTargetName() == it.heldRouteEndName }
}
