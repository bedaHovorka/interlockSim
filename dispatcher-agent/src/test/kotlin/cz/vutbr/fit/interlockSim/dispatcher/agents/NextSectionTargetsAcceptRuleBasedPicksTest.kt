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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.NextSectionTargets
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.loadNetworkContext
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * No-false-positive pin for the Issue #1378 next-section rule: on every control step of a live
 * `vyhybna.xml` run, every target the rule-based oracle reserves ([DispatchDecision.ReservePath]) and
 * every hop the prompt's NEXT SECTION line offers ([NextHopOutcome.Hop]) passes [NextSectionTargets].
 *
 * The rule refuses a Signal target that is not listed one section ahead of the train. Should it ever
 * refuse a target the dispatcher's own policy picks — the request the prompt tells the model to copy —
 * the LLM arm would be locked out of the very route it is told to request. Same wiring as
 * [NextHopTargetEqualsRuleBasedPickTest]: a real `ShuntingLoop`, with the oracle and the resolver
 * evaluated on the exact per-tick observation the synchronous production wiring sees. The vyhybna
 * run is a unit test (under a second); the Praha station run is tagged `integration-test`.
 *
 * @since Issue #1378
 */
@DisplayName("NextSectionTargets accepts every rule-based ReservePath and every NEXT SECTION hop (Issue #1378)")
class NextSectionTargetsAcceptRuleBasedPicksTest : DispatcherKoinTestBase() {
	private companion object {
		/** The `prahaHlavniNadrazi` rule-based twin completes all 8 journeys by 345 s (Issue #1378). */
		const val PRAHA_END_TIME = 400L
		const val PRAHA_MAX_CONCURRENT_TRAINS = 4
		const val PRAHA_MAX_TRAINS = 8
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `no ReservePath target and no Hop target is refused on any tick of a vyhybna run`() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		context.getInOuts()

		assertNoRuleBasedRequestRefused(context, ShuntingLoop(context, endTime = 300L))
	}

	/**
	 * The Praha station variant, with the traffic of the `prahaHlavniNadrazi` example (4 concurrent
	 * of 8 trains, seed 0): the station whose double-ended platform tracks exposed Issue #1378.
	 */
	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `no ReservePath target and no Hop target is refused on any tick of a Praha station run`() {
		val context = loadNetworkContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML).tracked()
		context.getInOuts()

		val loop =
			ShuntingLoop(
				context,
				endTime = PRAHA_END_TIME,
				maxConcurrentTrains = PRAHA_MAX_CONCURRENT_TRAINS,
				seed = 0L,
				maxTrains = PRAHA_MAX_TRAINS
			)
		assertNoRuleBasedRequestRefused(context, loop)
	}

	private fun assertNoRuleBasedRequestRefused(
		context: DefaultSimulationContext,
		loop: ShuntingLoop
	) {
		wireSynchronousDispatcher(context, loop)
		val wired = loop.controlStepListener

		val perception = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val oracle = RuleBasedDispatcher()

		var requestsChecked = 0
		val violations = mutableListOf<String>()
		loop.controlStepListener =
			ControlStepListener {
				val observation = captureObservation(perception, loop)
				val inputs = observation.innerBlockInputs + observation.outerBlockInputs
				val requests =
					oracle
						.decide(observation)
						.filterIsInstance<DispatchDecision.ReservePath>()
						.map { Triple(it.trainId, it.fromSemaphoreName, it.toTargetName) } +
						hopsOf(observation).map { (trainId, hop) -> Triple(trainId, hop.fromSignalName, hop.toTargetName) }
				for ((trainId, from, to) in requests) {
					requestsChecked++
					NextSectionTargets.mismatch(inputs, trainId, from, to)?.let {
						violations += "t=${observation.snapshot.simTime} $trainId $from -> $to refused: $it"
					}
				}
				wired?.onControlStep()
			}

		context.setMainProcess(loop)
		context.run()

		assertThat(requestsChecked, "rule-based requests checked").isGreaterThan(0)
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

	/** The NEXT SECTION hops the prompt renders, per train, for [observation]. */
	private fun hopsOf(observation: DispatchObservation): List<Pair<String, NextHopOutcome.Hop>> {
		val trainIds =
			(observation.innerBlockInputs + observation.outerBlockInputs)
				.mapNotNull { it.ownerTrainId }
				.distinct()
		return NextHopResolver
			.resolveAll(trainIds, observation)
			.mapNotNull { (trainId, outcome) -> (outcome as? NextHopOutcome.Hop)?.let { trainId to it } }
	}
}
