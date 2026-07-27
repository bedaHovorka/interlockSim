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
 * semantics fix: an empty decision list from a successful LLM cycle must be returned as-is, not
 * treated as a failure that triggers [Dispatcher] fallback (which would double-dispatch alongside
 * the LLM's own tool-driven actuation — see the class KDoc).
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
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
	fun `empty decisions with actuator-tool side effects are returned as-is, fallback is not invoked`() {
		// The LLM returned an empty decision list BUT invoked at least one actuator tool during the
		// cycle (detected by the command queue growing). That is the normal successful outcome once
		// real Koog tool-calling is wired: actuation already happened as a tool side effect, so an
		// empty returned list does NOT mean "the LLM did nothing" — and falling back would
		// double-dispatch. The adapter must therefore NOT invoke the fallback here.
		val commandQueue = ActuatorCommandQueue()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			commandQueue.postAll(listOf(DispatchDecision.NoAction)) // simulate an actuator tool call
			emptyList()
		}
		val fallback = mockk<Dispatcher>()

		val result = runBlocking { adapter(koogAgent, fallback, commandQueue = commandQueue).plan(observation) }

		assertThat(result).isEmpty()
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `a concurrent sim-thread drain mid-cycle does not erase the actuator-tool signal (PR #811 Fix F)`() {
		// PR #811 agent-architect review: the queue-approximateSize-delta detection had a
		// false-negative window — the kDisco sim thread drains the queue during a slow
		// decideAsync, so the after-sample read "no change" and the adapter fell back on top of
		// the LLM's already-acted tool calls (double-dispatch). The per-cycle actuator-CALL counter
		// (ActuatorCommandQueue.actedViaToolsThisCycle) counts postAll CALLS, not queue contents,
		// so draining cannot erase it. This test locks that property: the mock posts a decision
		// (one actuator-tool call), a concurrent drain empties the queue mid-cycle, then the mock
		// returns empty — the adapter must still see "acted" and NOT fall back.
		val commandQueue = ActuatorCommandQueue()
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } coAnswers {
			commandQueue.postAll(listOf(DispatchDecision.NoAction)) // LLM actuator-tool post
			commandQueue.drain() // sim thread drains the queue mid-cycle
			emptyList()
		}
		val fallback = mockk<Dispatcher>()

		val result = runBlocking { adapter(koogAgent, fallback, commandQueue = commandQueue).plan(observation) }

		assertThat(result).isEmpty()
		coVerify(exactly = 0) { fallback.decide(any()) }
	}

	@Test
	fun `empty decisions with no actuator-tool side effects invoke the rule-based fallback`() {
		// The LLM completed a cycle but neither returned decisions nor invoked any actuator tool —
		// it truly did nothing. There is no double-dispatch risk (nothing was posted), and not
		// falling back would leave queued trains un-routed indefinitely (the LLM is stateless across
		// cycles). The adapter must therefore fall back to the rule-based dispatcher.
		val koogAgent = mockk<KoogDispatchAgent>()
		coEvery { koogAgent.decideAsync(any()) } returns emptyList() // no queue side effect
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
		 * A mock [KoogDispatchAgent] that simulates the LLM having acted via at least one actuator
		 * tool during the cycle: it posts one [DispatchDecision.NoAction] to [commandQueue] as a
		 * side effect, then returns an empty decision list. This is the shape under which the
		 * admission safety net is designed to fire — the LLM routed/admitted via tools but left
		 * free admission capacity unused, so the net force-approves the oldest queued train(s).
		 * (Without a queue side effect, an empty cycle now correctly falls back instead — see
		 * `empty decisions with no actuator-tool side effects invoke the rule-based fallback`.)
		 */
		private fun agentThatActedViaTools(commandQueue: ActuatorCommandQueue): KoogDispatchAgent =
			mockk {
				coEvery { decideAsync(any()) } coAnswers {
					commandQueue.postAll(listOf(DispatchDecision.NoAction))
					emptyList()
				}
			}

		/** ApproveTrain decisions the safety net posted this cycle (filters out the LLM's own side effects). */
		private fun approveTrainPosts(queue: ActuatorCommandQueue): List<DispatchDecision.ApproveTrain> =
			queue.drain().filterIsInstance<DispatchDecision.ApproveTrain>()

		@Test
		fun `force-approves the oldest queued train when free capacity remains after a completed LLM cycle`() {
			val commandQueue = ActuatorCommandQueue()
			val koogAgent = agentThatActedViaTools(commandQueue)
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
			val koogAgent = agentThatActedViaTools(commandQueue)
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
			val koogAgent = agentThatActedViaTools(commandQueue)
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
			val koogAgent = agentThatActedViaTools(commandQueue)
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
