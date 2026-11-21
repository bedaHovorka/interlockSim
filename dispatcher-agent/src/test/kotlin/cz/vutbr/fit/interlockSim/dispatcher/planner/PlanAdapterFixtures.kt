/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.CycleHistory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration

/**
 * Builds a [KoogAgentPlanAdapter] around a mocked [KoogAgentFactory] that serves [koogAgent],
 * with every test double overridable: the mocked fallback [fallback], the [sinkHolder] the
 * agent's emissions are counted on, and — for the wiring tests — [inferenceTimeout],
 * [commandQueue], [cycleHistory], [circuitBreaker] and the reporting-only offer [counter].
 *
 * Shared test double for the [KoogAgentPlanAdapter] planner tests, following the
 * `RunSnapshotFixtures.kt` pattern together with [trainPosition] and [trainPerception] below:
 * byte-for-byte the shapes [KoogAgentPlanAdapterTest] and [SkippedNextSectionOfferCountTest]
 * previously re-implemented field-for-field in their own private doubles (the PR #1408 simplify
 * round hoisted them here so the readings' default fields cannot drift apart between the
 * classes). [adapter]'s parameter order follows KoogAgentPlanAdapterTest's call sites, so its
 * `(koogAgent, fallback[, inferenceTimeout])` positional form keeps working there; everything
 * else is passed by name. Defaults mirror the production constructor defaults — a fresh
 * [LlmCircuitBreaker], the reporting-only [NextSectionOfferCounter.LIVE] counter, a 30 s
 * inference timeout.
 */
internal fun adapter(
	koogAgent: KoogDispatchAgent,
	fallback: Dispatcher = mockk(),
	inferenceTimeout: Duration = Duration.ofSeconds(30),
	commandQueue: ActuatorCommandQueue = ActuatorCommandQueue(),
	sinkHolder: SinkHolder = SinkHolder(),
	cycleHistory: CycleHistory = CycleHistory(capacity = 0),
	circuitBreaker: LlmCircuitBreaker = LlmCircuitBreaker(),
	counter: NextSectionOfferCounter = NextSectionOfferCounter.LIVE
): KoogAgentPlanAdapter {
	val agentFactory = mockk<KoogAgentFactory>()
	coEvery { agentFactory.createAgent(any()) } returns koogAgent
	val context = mockk<DefaultSimulationContext>()
	return KoogAgentPlanAdapter(
		agentFactory,
		context,
		fallback,
		inferenceTimeout,
		commandQueue,
		sinkHolder,
		cycleHistory,
		circuitBreaker = circuitBreaker,
		nextSectionOfferCounter = counter
	)
}

/**
 * One [TrainPositionReading] for [trainId] at [velocity] — shared by the observation builders so
 * the reading's default fields cannot drift apart.
 */
internal fun trainPosition(
	trainId: String,
	velocity: Double
): TrainPositionReading =
	TrainPositionReading(
		trainId = trainId,
		velocity = velocity,
		acceleration = 0.0,
		totalDistance = 0.0,
		frontSectionName = null
	)

/**
 * One [TrainPerceptionReading] for [trainId] at [velocity], with a signal ahead named
 * [signalAheadName] (or none). Shared with [trainPosition] like the double it replaces.
 */
internal fun trainPerception(
	trainId: String,
	velocity: Double,
	signalAheadName: String?
): TrainPerceptionReading =
	TrainPerceptionReading(
		trainId = trainId,
		signalAheadName = signalAheadName,
		signalAheadAspect = null,
		distanceToSignalAheadMetres = 100.0,
		currentSpeedLimitMps = 20.0,
		velocity = velocity,
		acceleration = 0.0,
		totalDistance = 0.0,
		frontSectionName = null,
		destinationInOutName = "B",
		scheduledArrivalTime = 0.0,
		isDwelling = false
	)
