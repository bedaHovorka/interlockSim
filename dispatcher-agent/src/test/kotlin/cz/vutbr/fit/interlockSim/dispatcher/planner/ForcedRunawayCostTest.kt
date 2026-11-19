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

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.executor.HoldingOllamaServer
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.dispatcher.testutil.SelfPacedSimClock
import cz.vutbr.fit.interlockSim.dispatcher.testutil.SleepingPacingRecorder
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.testutil.emptySnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Issue #1340, acceptance 1: one runaway Ollama generation costs at most one dispatcher tick.
 *
 * The forced runaway: [HoldingOllamaServer] in its one-slot mode (like `llama-server -np 1`) answers
 * request 1, never ends request 2, and answers every later request only after the client has closed
 * the held connection and the slot-release delay has passed — the way Ollama queues requests behind
 * a generation that holds its only slot. Cycle 1 is therefore answered, cycle 2 runs into the cycle
 * timeout, and cycle 3 onwards can only be answered if the cycle timeout really freed the slot
 * (Issue #1341). The chain is the production one: [KoogAgentPlanAdapter] → [DefaultAgentService] →
 * [OllamaSimpleExecutor] over the CIO client. Only the agent factory, the simulation context it
 * would receive, and the fallback dispatcher (always `NoAction`) are mocked; the agent, the executor
 * and the HTTP client are real. Mocking the factory also skips the pre-warm request, which would
 * shift the held-request index.
 *
 * Measured, per run:
 * - **fallback cycles**: [TickRecord]s with outcome [TickOutcome.RULE_FALLBACK], read through
 *   [KoogAgentPlanAdapter.addTickListener] (not `fallback.decide` calls: a silent cycle consults the
 *   fallback too);
 * - **breaker openings**: [LlmCircuitBreaker.openCount] (default threshold 3);
 * - **first answered cycle after the runaway**: the first cycle after cycle 2 whose tick is not a
 *   fallback.
 *
 * Expected and pinned: 1 fallback cycle, 0 breaker openings, cycle 3 answered. The pins are cycle
 * counts, never wall-clock bounds (the ruling in [OllamaTimeoutAbortTest]'s KDoc). The cycle
 * timeout and the slot-release delay keep at least the production ratio (30 s / 1 s, the 1 s being
 * the #1341 live check of 2026-11-03): [CYCLE_TIMEOUT] / [SLOT_RELEASE_DELAY_MILLIS] is about 30
 * (5,000 ms / 167 ms).
 *
 * ## Driver level (the pacing found by the #1301 campaign)
 *
 * The adapter-level test above calls `plan()` back to back, so it cannot see what the cycle after a
 * runaway costs in pacing. The two driver-level tests run the same chain under the real
 * [AgentLoopDriver] against a sim that paces itself on the wall clock ([SelfPacedSimClock], speed
 * [SIM_SPEED], so the 5 s timeout is 20 sim s) with a controller that sleeps like
 * `ThrottlingSimulationController` ([SleepingPacingRecorder]): once in polling mode, once with the
 * production [DefaultSnapshotSignal] fired once per control step, as `ShuntingLoop` fires it. Before
 * the #1340 pacing fix in [AgentLoopDriver.runCycle], PACE slept again for the sim time that passed
 * during the timed-out cycle, and every later cycle re-slept it: the period stayed at the timeout's
 * length for the rest of the run (the Červený Újezd campaign run r02: actions 58 sim s apart after
 * one 30 s timeout).
 *
 * Besides the same 1 / 0 counts, they pin every throttle argument after the runaway, and their sum,
 * as sim time no machine load can move: at most one control step per cycle in polling mode; in
 * signal mode one more step for the driver's wake-up lag after a signal (see
 * [AgentLoopDriver]'s `awaitFreshSnapshot`), which at speed [SIM_SPEED] is half a second of wall
 * clock. The old code gives about 20 per cycle. The sim-time periods between cycles hold real wall
 * time (HTTP, scheduling), so they are printed, never asserted. This part of the measurement
 * changed production code (the driver's PACE step); the adapter and its fallback did not change.
 */
@DisplayName("A forced runaway Ollama generation costs one dispatcher tick (Issue #1340)")
class ForcedRunawayCostTest {
	private companion object {
		val CYCLE_TIMEOUT: Duration = Duration.ofSeconds(5)
		const val SLOT_RELEASE_DELAY_MILLIS = 167L

		/** The request that never ends: cycle 2's. */
		const val HELD_REQUEST = 2
		const val CYCLES = 5
		const val RUNAWAY_CYCLE = 2

		/**
		 * The tick of an answered cycle here: the reply has no tool call, and the observation is the
		 * [SimulationSnapshot.EMPTY] sentinel, which is never an idle station, so the adapter consults the
		 * fallback as an oracle; it finds only [DispatchDecision.NoAction], so the cycle is not a fallback.
		 */
		val ANSWERED = TickOutcome.LLM_SILENT_NONACTIONABLE

		/**
		 * Driver-level tests: the sim runs 4 sim s per wall second, so the timeout is 20 sim s and a
		 * control step is half a second of wall clock.
		 */
		const val SIM_SPEED = 4.0

		/** `ShuntingLoop`'s control step: one tick per 2 sim s. */
		const val CONTROL_STEP = 2.0

		/** Signal mode: one control step of the driver's wake-up lag on top of the polling bound. */
		const val SIGNAL_LAG_STEPS = 1

		/** Upper bound on [AgentLoopDriver.runCycle] calls, short-circuited ones included. */
		const val MAX_RUN_CYCLE_CALLS = 100_000
	}

	private val observation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@Test
	fun `one runaway generation costs one fallback cycle and no breaker opening`() {
		HoldingOllamaServer(
			heldRequest = HELD_REQUEST,
			slotReleaseDelayMillis = SLOT_RELEASE_DELAY_MILLIS
		).use { server ->
			val config = OllamaExecutorConfig(ollamaEndpoint = server.baseUrl)
			val executor = OllamaSimpleExecutor(config)
			try {
				val adapter = adapterOver(executor, config)
				val ticks = CopyOnWriteArrayList<TickRecord>()
				adapter.addTickListener { ticks.add(it) }

				repeat(CYCLES) { runBlocking { adapter.plan(observation) } }

				val outcomes = ticks.map { it.outcome }
				val fallbackCycles = outcomes.count { it == TickOutcome.RULE_FALLBACK }
				val breakerOpenings = adapter.circuitBreaker.openCount
				val firstAnsweredAfterRunaway =
					outcomes
						.withIndex()
						.firstOrNull { (i, outcome) -> i + 1 > RUNAWAY_CYCLE && outcome != TickOutcome.RULE_FALLBACK }
						?.let { it.index + 1 }
				println(
					"Issue #1340: forced runaway on cycle $RUNAWAY_CYCLE of $CYCLES " +
						"(cycle timeout ${CYCLE_TIMEOUT.toMillis()} ms, slot release ${SLOT_RELEASE_DELAY_MILLIS} ms): " +
						"fallbackCycles=$fallbackCycles, breakerOpenings=$breakerOpenings, " +
						"firstAnsweredCycleAfterRunaway=$firstAnsweredAfterRunaway, outcomes=$outcomes"
				)

				assertThat(outcomes, "tick outcome per cycle").containsExactly(
					ANSWERED,
					TickOutcome.RULE_FALLBACK,
					ANSWERED,
					ANSWERED,
					ANSWERED
				)
				assertThat(fallbackCycles, "fallback cycles").isEqualTo(1)
				assertThat(breakerOpenings, "circuit-breaker openings").isEqualTo(0L)
				assertThat(firstAnsweredAfterRunaway, "first answered cycle after the runaway").isEqualTo(3)
			} finally {
				executor.close()
			}
		}
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `under the polling driver, the decision rate recovers one cycle after the runaway`() {
		runawayUnderDriver(signalled = false)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `under the signalled driver, the decision rate recovers one cycle after the runaway`() {
		runawayUnderDriver(signalled = true)
	}

	/**
	 * Runs [CYCLES] processed cycles of the real [AgentLoopDriver] over the real adapter chain with
	 * request [HELD_REQUEST] held, against the self-paced sim; in [signalled] mode a publisher thread
	 * publishes the sim time and fires a [DefaultSnapshotSignal] once per control step.
	 */
	private fun runawayUnderDriver(signalled: Boolean) {
		HoldingOllamaServer(
			heldRequest = HELD_REQUEST,
			slotReleaseDelayMillis = SLOT_RELEASE_DELAY_MILLIS
		).use { server ->
			val config = OllamaExecutorConfig(ollamaEndpoint = server.baseUrl)
			val executor = OllamaSimpleExecutor(config)
			val clock = SelfPacedSimClock(speedMultiplier = SIM_SPEED, controlStepSeconds = CONTROL_STEP)
			val publisher = if (signalled) StepPublisher(clock) else null
			try {
				val adapter = adapterOver(executor, config)
				val perceptionPort = mockk<NetworkPerceptionPort>()
				every { perceptionPort.snapshot() } answers {
					emptySnapshot(publisher?.publishedSimTime ?: clock.simTime())
				}
				val ticks = CopyOnWriteArrayList<TickRecord>()
				val pacing = SleepingPacingRecorder(SIM_SPEED)
				val driver =
					AgentLoopDriver(
						perceptionPort,
						adapter,
						ActuatorCommandQueue(),
						pacing,
						snapshotSignal = publisher?.signal,
						plannerTickSource = adapter,
						onTickRecord = { ticks.add(it) }
					)
				publisher?.start()

				runBlocking {
					var calls = 0
					while (ticks.size < CYCLES && calls < MAX_RUN_CYCLE_CALLS) {
						calls++
						driver.runCycle()
					}
				}

				val outcomes = ticks.map { it.outcome }
				val periods = ticks.map { it.simTime }.zipWithNext { a, b -> b - a }
				val fallbackCycles = outcomes.count { it == TickOutcome.RULE_FALLBACK }
				val breakerOpenings = adapter.circuitBreaker.openCount
				val afterRunaway = pacing.throttleDeltas.drop(1)
				val perCycleBound = CONTROL_STEP * (1 + if (signalled) SIGNAL_LAG_STEPS else 0)
				println(
					"Issue #1340 driver level (${if (signalled) "signal" else "polling"} mode): forced runaway " +
						"on cycle $RUNAWAY_CYCLE of $CYCLES (cycle timeout ${CYCLE_TIMEOUT.toMillis()} ms, " +
						"slot release ${SLOT_RELEASE_DELAY_MILLIS} ms, sim speed $SIM_SPEED): " +
						"fallbackCycles=$fallbackCycles, breakerOpenings=$breakerOpenings, outcomes=$outcomes, " +
						"simTimes=${ticks.map { it.simTime }}, periods=$periods, throttle=${pacing.throttleDeltas}"
				)

				assertThat(outcomes.size, "cycles").isEqualTo(CYCLES)
				assertThat(outcomes[RUNAWAY_CYCLE - 1], "the runaway cycle").isEqualTo(TickOutcome.RULE_FALLBACK)
				assertThat(fallbackCycles, "fallback cycles").isEqualTo(1)
				assertThat(breakerOpenings, "circuit-breaker openings").isEqualTo(0L)
				// PACE never sleeps again for the sim time the runaway already spent on the wall clock.
				afterRunaway.forEachIndexed { i, delta ->
					assertThat(delta, "throttle argument of cycle ${i + 2}").isLessThanOrEqualTo(perCycleBound)
				}
				assertThat(afterRunaway.sum(), "throttle arguments of cycles 2..$CYCLES, summed")
					.isLessThanOrEqualTo((CYCLES - 1) * perCycleBound)
			} finally {
				publisher?.close()
				executor.close()
			}
		}
	}

	/**
	 * The sim side of signal mode: publishes [clock]'s sim time and then fires [signal] once per
	 * control step — the order `ShuntingLoop` uses (publish the tick observation, then signal).
	 */
	private class StepPublisher(
		private val clock: SelfPacedSimClock
	) : AutoCloseable {
		val signal = DefaultSnapshotSignal()

		@Volatile
		var publishedSimTime: Double = 0.0
			private set

		@Volatile
		private var running = true

		private val thread =
			thread(start = false, isDaemon = true, name = "issue-1340-step-publisher") {
				var lastPublished = -1.0
				while (running) {
					val now = clock.simTime()
					if (now != lastPublished) {
						lastPublished = now
						publishedSimTime = now
						signal.signal()
					}
					Thread.sleep(1)
				}
			}

		fun start() = thread.start()

		override fun close() {
			running = false
			thread.join(JOIN_MILLIS)
		}

		private companion object {
			const val JOIN_MILLIS = 1_000L
		}
	}

	/**
	 * The production planner wired to a real Koog agent built by [DefaultAgentService] over
	 * [executor]; only the agent factory (which would also pre-warm a real Ollama) is mocked. The
	 * fallback returns [DispatchDecision.NoAction], so a fallback cycle is visible only in its tick.
	 */
	private fun adapterOver(
		executor: OllamaSimpleExecutor,
		config: OllamaExecutorConfig
	): KoogAgentPlanAdapter {
		val agent =
			runBlocking {
				DefaultAgentService(executor, config).createDispatchAgent(
					modelName = config.modelName,
					tools = emptyList(),
					systemPrompt = "You are a test dispatcher."
				)
			}
		val agentFactory = mockk<KoogAgentFactory>()
		coEvery { agentFactory.createAgent(any()) } returns agent
		val fallback = mockk<Dispatcher>()
		every { fallback.decide(any()) } returns listOf(DispatchDecision.NoAction)
		return KoogAgentPlanAdapter(
			agentFactory,
			mockk<DefaultSimulationContext>(),
			fallback,
			CYCLE_TIMEOUT,
			ActuatorCommandQueue(),
			SinkHolder()
		)
	}
}
