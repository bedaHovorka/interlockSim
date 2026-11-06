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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.inOutCandidate
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidates
import io.mockk.coEvery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Issue #1343 wiring: [AgentLoopDriver] installs a reader over its own two ports as its
 * `plannerTickSource`'s [KoogAgentPlanAdapter.latestObservationReader], so in production the
 * post-failure fallback decides on the latest state published through the very ports the
 * driver senses from.
 *
 * The [NetworkPerceptionPort] is a strict mock answering only `snapshot()`: a sim-thread capture
 * request would throw, and [confirmVerified] proves none was made on the whole cycle (C3).
 */
@DisplayName("AgentLoopDriver wires the latest published state into the LLM fallback (#1343)")
class AgentLoopDriverLatestStateFallbackTest {
	private val train = "Train #4"

	private val trainOnKb =
		BlockInputObservation(
			blockId = "kB",
			towardSemaphoreName = "zB",
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = train,
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false,
			candidateTargets = semaphoreCandidates("doA1")
		)

	private val trainOnK1 =
		trainOnKb.copy(
			blockId = "k1",
			towardSemaphoreName = "doA1",
			candidateTargets = listOf(inOutCandidate("A", available = true))
		)

	private val kbFree =
		trainOnKb.copy(
			state = TrackFacility.State.FREE,
			ownerTrainId = null,
			isApproachingThisInput = false,
			candidateTargets = emptyList()
		)

	private fun snapshotAt(simTime: Double): SimulationSnapshot =
		SimulationSnapshot.EMPTY.copy(
			simTime = simTime,
			trainPositions = listOf(TrainPositionReading(train, 5.0, 0.0, 0.0, null))
		)

	@Volatile
	private var publishedSnapshot: SimulationSnapshot = snapshotAt(100.0)

	@Volatile
	private var publishedTick: ShuntingLoop.TickObservation =
		ShuntingLoop.TickObservation(emptyList(), emptyList(), listOf(trainOnKb))

	private val perceptionPort =
		mockk<NetworkPerceptionPort>().also { port -> every { port.snapshot() } answers { publishedSnapshot } }

	private fun adapter(koogAgent: KoogDispatchAgent): KoogAgentPlanAdapter {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns koogAgent
		return KoogAgentPlanAdapter(
			agentFactory = agentFactory,
			context = mockk<DefaultSimulationContext>(),
			fallbackDispatcher = RuleBasedDispatcher(),
			inferenceTimeout = Duration.ofMillis(50),
			commandQueue = ActuatorCommandQueue(),
			sinkHolder = SinkHolder()
		)
	}

	private fun driver(
		koogAdapter: KoogAgentPlanAdapter,
		commandQueue: ActuatorCommandQueue = ActuatorCommandQueue()
	): AgentLoopDriver =
		AgentLoopDriver(
			perceptionPort = perceptionPort,
			planner = koogAdapter,
			commandQueue = commandQueue,
			controller = mockk<SimulationController>(relaxed = true),
			dispatchLoopSensorPort = DefaultDispatchLoopSensorPort { publishedTick },
			plannerTickSource = koogAdapter
		)

	@Test
	@DisplayName("the driver installs a reader over its own two ports on its plannerTickSource")
	fun driverInstallsReaderOverItsPorts() {
		val koogAdapter = adapter(mockk())

		driver(koogAdapter)

		assertThat(koogAdapter.latestObservationReader?.read()).isNotNull().isEqualTo(
			DispatchObservation(
				snapshot = publishedSnapshot,
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = listOf(trainOnKb)
			)
		)
	}

	@Test
	@DisplayName("a timed-out cycle posts the fallback request from the train's new position, with no capture")
	fun timedOutCyclePostsTheFreshRequest() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			// The simulation keeps running during the inference: Train #4 moves from kB onto k1.
			publishedSnapshot = snapshotAt(130.0)
			publishedTick = ShuntingLoop.TickObservation(emptyList(), listOf(trainOnK1), listOf(kbFree))
			delay(500)
			emptyList()
		}
		val commandQueue = ActuatorCommandQueue()

		runBlocking { driver(adapter(koogAgent), commandQueue).runCycle() }

		assertThat(commandQueue.drain()).containsExactly(DispatchDecision.ReservePath(train, "doA1", "A"))
		verify(exactly = 2) { perceptionPort.snapshot() }
		confirmVerified(perceptionPort)
	}
}
