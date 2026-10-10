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
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
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
 * [NextHopResolver] still reads the compatibility projection
 * [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.toSeparatorName], which the shell fills
 * from the same policy over the same list. While the two read sites exist, this test pins that
 * they agree on every tick of a live `vyhybna.xml` run — in both directions: every
 * [NextHopOutcome.Hop] has a `ReservePath` with the same target for the same train, and every
 * `ReservePath` has a `Hop` with the same target. Should the projection ever drift from the
 * policy (or a reader stop going through it), this fails before the prompt and the oracle
 * disagree in production.
 *
 * Same wiring idea as `RuleBasedDispatcherDeterminismRunner`: a real `ShuntingLoop` drives the
 * simulation, and the oracle (`RuleBasedDispatcher().decide`) is evaluated on the exact
 * per-tick observation the synchronous production wiring sees — [RuleBasedDispatcher] is a pure
 * decision function, so a fresh instance queried from the control step is a faithful oracle.
 *
 * @since Issue #970
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
		.groupBy({ it.trainId }, { it.toSeparatorName })

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
			if (targets != listOf(hop.toSeparatorName)) {
				violations +=
					"t=${observation.snapshot.simTime} $trainId: Hop to ${hop.toSeparatorName} but ReservePath to $targets"
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
}
