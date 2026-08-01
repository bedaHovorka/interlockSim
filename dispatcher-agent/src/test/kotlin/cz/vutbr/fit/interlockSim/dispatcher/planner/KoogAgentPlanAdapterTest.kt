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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgent
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [KoogAgentPlanAdapter] (SP2b.9, Issue #566) — in particular the fallback
 * semantics: a **non-empty** decision list from [KoogDispatchAgent.decideAsync] is returned
 * as-is and triggers the admission safety net instead of [Dispatcher] fallback; an **empty**
 * decision list always falls back (SP2c.6, Issue #829, removed the queue-actuation-side-effect
 * detection that used to make an empty-but-acted-via-tools cycle skip the fallback — see the
 * class KDoc on [KoogAgentPlanAdapter]).
 *
 * @since Issue #566 (SP2b.9 — Goal 10); SP2c.6 (#829) simplifies the empty-decisions branch to
 *   always fall back
 */
class KoogAgentPlanAdapterTest {
	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	private fun adapter(
		koogAgent: KoogDispatchAgent,
		fallback: Dispatcher,
		inferenceTimeout: Duration = Duration.ofSeconds(30),
		commandQueue: ActuatorCommandQueue = ActuatorCommandQueue(),
		maxConcurrentTrains: Int = 2
	): KoogAgentPlanAdapter {
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns koogAgent
		val context = mockk<DefaultSimulationContext>()
		return KoogAgentPlanAdapter(agentFactory, context, fallback, inferenceTimeout, commandQueue, maxConcurrentTrains)
	}

	private fun observationWithQueue(
		unapprovedTrains: List<QueuedTrainObservation>,
		approvedTrainCount: Int
	): DispatchObservation =
		DispatchObservation(
			snapshot =
				SimulationSnapshot.EMPTY.copy(
					trainPositions =
						List(approvedTrainCount) { index ->
							TrainPositionReading(
								trainId = "active-$index",
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						}
				),
			unapprovedTrains = unapprovedTrains,
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@Test
	fun `non-empty decisions from the LLM are returned as-is and fallback is not invoked`() {
		val decisions = listOf(DispatchDecision.NoAction)
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns decisions
		val fallback = mockk<Dispatcher>()

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `empty decisions always invoke the rule-based fallback (SP2c6)`() {
		// SP2c.6 (#829): the queue-actuation-side-effect detection (actedViaToolsThisCycle) was
		// removed. Actuator tools now emit to the SinkHolder seam, not to a return value observed
		// here, so an empty decision list from decideAsync unconditionally falls back — regardless
		// of whether tools acted during the cycle.
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList()
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		coVerify(exactly = 1) { fallback.decide(any()) }
	}

	@Test
	fun `timeout invokes the fallback dispatcher and returns its result`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			delay(500)
			emptyList()
		}
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions

		val result =
			runBlocking { adapter(koogAgent, fallback, Duration.ofMillis(50)).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
		coVerify(exactly = 1) { koogAgent.decideAsync(any()) }
	}

	@Test
	fun `a thrown exception invokes the fallback dispatcher and returns its result`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws RuntimeException("boom")
		val fallbackDecisions = listOf(DispatchDecision.NoAction)
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns fallbackDecisions

		val result = runBlocking { adapter(koogAgent, fallback).plan(observation) }

		assertThat(result).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	fun `a thrown CancellationException propagates and fallback is not invoked`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } throws CancellationException("cancelled")
		val fallback = mockk<Dispatcher>()

		assertFailure { runBlocking { adapter(koogAgent, fallback).plan(observation) } }
			.isInstanceOf<CancellationException>()
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `TimeoutCancellationException from withTimeout is caught, not re-thrown as a bare CancellationException`() {
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			delay(500)
			emptyList()
		}
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)

		// Should NOT throw: TimeoutCancellationException (a CancellationException subtype) must
		// be handled by the earlier, more specific catch block, not re-thrown by the generic one.
		val result =
			runBlocking { adapter(koogAgent, fallback, Duration.ofMillis(50)).plan(observation) }
		assertThat(result).containsExactly(DispatchDecision.NoAction)
	}

	/**
	 * Admission safety net (Goal 10 SP2b.9 follow-up): the LLM's dispatch cycle is stateless
	 * (fresh Koog session every call, no memory of prior cycles — see the agent-architect
	 * analysis this fix is based on) and was observed to stop calling `approve_train` after
	 * the first train, even across many cycles where free capacity remained. Since
	 * `approve_train`/[DispatchDecision.ApproveTrain] is documented idempotent (a no-op for an
	 * already-active or nonexistent train), it's always safe for the driver to force-admit the
	 * oldest queued train(s) whenever a completed LLM cycle leaves free capacity unused.
	 */
	@Nested
	inner class AdmissionSafetyNet {
		/**
		 * A mock [KoogDispatchAgent] that returns a non-empty decision list — the shape under
		 * which [KoogAgentPlanAdapter.plan] does not fall back and instead runs the admission
		 * safety net (SP2c.6, Issue #829: the empty-branch queue-actuation-side-effect detection
		 * was removed, so only a non-empty [KoogDispatchAgent.decideAsync] result now avoids the
		 * fallback — see `empty decisions always invoke the rule-based fallback (SP2c6)`).
		 */
		private fun agentThatActedViaTools(): KoogDispatchAgent =
			mockk {
				coEvery { decideAsync(any()) } returns listOf(DispatchDecision.NoAction)
			}

		/** ApproveTrain decisions the safety net posted this cycle (filters out the LLM's own side effects). */
		private fun approveTrainPosts(queue: ActuatorCommandQueue): List<DispatchDecision.ApproveTrain> =
			queue.drain().filterIsInstance<DispatchDecision.ApproveTrain>()

		@Test
		fun `force-approves the oldest queued train when free capacity remains after a completed LLM cycle`() {
			val commandQueue = ActuatorCommandQueue()
			val koogAgent = agentThatActedViaTools()
			val fallback = mockk<Dispatcher>()
			val observation =
				observationWithQueue(
					unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
					approvedTrainCount = 0
				)

			runBlocking { adapter(koogAgent, fallback, commandQueue = commandQueue).plan(observation) }

			val approvals = approveTrainPosts(commandQueue)
			assertThat(approvals).hasSize(1)
			assertThat(approvals).contains(DispatchDecision.ApproveTrain("Train #1"))
			coVerify(exactly = 0) { fallback.decide(any()) }
		}

		@Test
		fun `force-approves multiple queued trains up to the free capacity, oldest first`() {
			val commandQueue = ActuatorCommandQueue()
			val koogAgent = agentThatActedViaTools()
			val fallback = mockk<Dispatcher>()
			val observation =
				observationWithQueue(
					unapprovedTrains =
						listOf(
							QueuedTrainObservation("Train #1", "A"),
							QueuedTrainObservation("Train #2", "B"),
							QueuedTrainObservation("Train #3", "A")
						),
					approvedTrainCount = 0
				)

			runBlocking {
				adapter(koogAgent, fallback, commandQueue = commandQueue, maxConcurrentTrains = 2)
					.plan(observation)
			}

			val approvals = approveTrainPosts(commandQueue)
			assertThat(approvals).hasSize(2)
			assertThat(approvals).contains(DispatchDecision.ApproveTrain("Train #1"))
			assertThat(approvals).contains(DispatchDecision.ApproveTrain("Train #2"))
		}

		@Test
		fun `does not force-approve when capacity is already full`() {
			val commandQueue = ActuatorCommandQueue()
			val koogAgent = agentThatActedViaTools()
			val fallback = mockk<Dispatcher>()
			val observation =
				observationWithQueue(
					unapprovedTrains = listOf(QueuedTrainObservation("Train #3", "A")),
					approvedTrainCount = 2
				)

			runBlocking {
				adapter(koogAgent, fallback, commandQueue = commandQueue, maxConcurrentTrains = 2)
					.plan(observation)
			}

			assertThat(approveTrainPosts(commandQueue)).isEmpty()
			coVerify(exactly = 0) { fallback.decide(any()) }
		}

		@Test
		fun `does not force-approve when there are no queued trains`() {
			val commandQueue = ActuatorCommandQueue()
			val koogAgent = agentThatActedViaTools()
			val fallback = mockk<Dispatcher>()
			val observation = observationWithQueue(unapprovedTrains = emptyList(), approvedTrainCount = 0)

			runBlocking { adapter(koogAgent, fallback, commandQueue = commandQueue).plan(observation) }

			assertThat(approveTrainPosts(commandQueue)).isEmpty()
		}

		@Test
		fun `does not force-approve on the timeout fallback path`() {
			// The fallback dispatcher (RuleBasedDispatcher in production) already handles
			// admission itself via its own returned decisions; the safety net must not also
			// fire here, since fallback.decide() is a stand-in for a real Dispatcher whose
			// own admission decisions this test does not model.
			val koogAgent = mockk<KoogDispatchAgent>()
			coEvery { koogAgent.decideAsync(any()) } coAnswers {
				delay(500)
				emptyList()
			}
			val fallback = mockk<Dispatcher>()
			every { fallback.decide(any()) } returns emptyList()
			val commandQueue = ActuatorCommandQueue()
			val observation =
				observationWithQueue(
					unapprovedTrains = listOf(QueuedTrainObservation("Train #1", "A")),
					approvedTrainCount = 0
				)

			runBlocking {
				adapter(koogAgent, fallback, Duration.ofMillis(50), commandQueue = commandQueue).plan(observation)
			}

			assertThat(commandQueue.drain()).isEmpty()
		}
	}
}
