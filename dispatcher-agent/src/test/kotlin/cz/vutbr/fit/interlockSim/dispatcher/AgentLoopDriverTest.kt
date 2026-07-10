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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [AgentLoopDriver].
 *
 * Verifies:
 * - Correct cycle order: sense → decide → post → [SimulationController.awaitIfPaused]
 *   → [SimulationController.throttle]
 * - Decisions returned by [Dispatcher.decide] are posted to [ActuatorCommandQueue]
 * - Decisions are **posted** (not applied in-process) — the driver thread never
 *   mutates simulation state
 * - [SimulationController.throttle] receives the correct simulation-time delta
 *   (`snapshot.simTime - prevSimTime` from the previous cycle)
 * - [SimulationController] is only used in the driver; it is not accessible to the
 *   dispatcher or observation
 *
 * Uses a hand-written [RecordingSimulationController] to capture call order and
 * mockk for [NetworkPerceptionPort] and [Dispatcher].
 *
 * @since Issue #732 (SP0.10 — Goal 10)
 */
@DisplayName("AgentLoopDriver — paced sense-decide-act cycle")
@Timeout(30, unit = TimeUnit.SECONDS)
class AgentLoopDriverTest {
	private lateinit var perceptionPort: NetworkPerceptionPort
	private lateinit var dispatcher: Dispatcher
	private lateinit var commandQueue: ActuatorCommandQueue
	private lateinit var controller: RecordingSimulationController

	@BeforeEach
	fun setUp() {
		perceptionPort = mockk(relaxed = true)
		dispatcher = mockk(relaxed = true)
		commandQueue = ActuatorCommandQueue()
		controller = RecordingSimulationController()

		// Default: snapshot at simTime=0.0; dispatcher returns NoAction
		every { perceptionPort.snapshot() } returns emptySnapshot(0.0)
		every { dispatcher.decide(any()) } returns listOf(DispatchDecision.NoAction)
	}

	private fun makeDriver(): AgentLoopDriver = AgentLoopDriver(perceptionPort, dispatcher, commandQueue, controller)

	// ── Helpers ────────────────────────────────────────────────────────────────

	/** Creates a [SimulationSnapshot] with the given simTime and empty lists. */
	private fun emptySnapshot(simTime: Double): SimulationSnapshot =
		SimulationSnapshot(
			simTime = simTime,
			semaphores = emptyList(),
			blocks = emptyList(),
			trainPositions = emptyList(),
			timetables = emptyList()
		)

	// ── Cycle order ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Cycle order contract")
	inner class CycleOrder {
		/**
		 * The canonical invariant from SP0.10 (#732):
		 * sense → decide → post → awaitIfPaused → throttle.
		 *
		 * Verified by recording when each collaborator is invoked.
		 */
		@Test
		@DisplayName("sense → decide → post → awaitIfPaused → throttle")
		fun cycleOrderIsCorrect() {
			val callOrder = mutableListOf<String>()

			every { perceptionPort.snapshot() } answers {
				callOrder += "sense"
				emptySnapshot(1.0)
			}

			val dispatchObsSlot = slot<DispatchObservation>()
			every { dispatcher.decide(capture(dispatchObsSlot)) } answers {
				// Sense must have happened before decide
				assertThat(callOrder.last()).isEqualTo("sense")
				callOrder += "decide"
				listOf(DispatchDecision.ApproveTrain("T1"))
			}

			val controllerWithOrder =
				object : SimulationController {
					var awaitCalledAfterPost = false

					override suspend fun awaitIfPaused() {
						// Post must have happened before awaitIfPaused
						assertThat(commandQueue.approximateSize()).isEqualTo(1)
						awaitCalledAfterPost = true
						callOrder += "awaitIfPaused"
					}

					override fun throttle(simDeltaSeconds: Double) {
						// awaitIfPaused must have happened before throttle
						assertThat(awaitCalledAfterPost).isTrue()
						callOrder += "throttle"
					}

					override fun isPaused(): Boolean = false

					override fun pollStepEvent(): Boolean = false

					override fun pollStepTime(): Double? = null

					override fun requestPause() = Unit
				}

			val driver = AgentLoopDriver(perceptionPort, dispatcher, commandQueue, controllerWithOrder)

			runBlocking { driver.runCycle() }

			assertThat(callOrder).containsExactly("sense", "decide", "awaitIfPaused", "throttle")
		}

		@Test
		@DisplayName("awaitIfPaused is called before throttle within PACE step")
		fun awaitIfPausedBeforeThrottle() {
			val callOrder = mutableListOf<String>()
			val orderedController =
				object : SimulationController {
					override suspend fun awaitIfPaused() {
						callOrder += "awaitIfPaused"
					}

					override fun throttle(simDeltaSeconds: Double) {
						callOrder += "throttle"
					}

					override fun isPaused(): Boolean = false

					override fun pollStepEvent(): Boolean = false

					override fun pollStepTime(): Double? = null

					override fun requestPause() = Unit
				}

			val driver = AgentLoopDriver(perceptionPort, dispatcher, commandQueue, orderedController)

			runBlocking { driver.runCycle() }

			val awaitIndex = callOrder.indexOf("awaitIfPaused")
			val throttleIndex = callOrder.indexOf("throttle")
			assertThat(awaitIndex < throttleIndex).isTrue()
		}
	}

	// ── Decisions posted, not applied ─────────────────────────────────────────

	@Nested
	@DisplayName("Decisions posted to queue, not applied in-process")
	inner class DecisionsPosted {
		@Test
		@DisplayName("decisions returned by dispatcher are posted to commandQueue")
		fun decisionsArePosted() {
			val expected =
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ReservePath("T1", "zA", "doA1")
				)
			every { dispatcher.decide(any()) } returns expected

			runBlocking { makeDriver().runCycle() }

			val posted = commandQueue.drain()
			assertThat(posted).containsExactly(*expected.toTypedArray())
		}

		@Test
		@DisplayName("driver does not apply decisions itself — queue holds them until drained")
		fun driverDoesNotApplyDecisions() {
			every { dispatcher.decide(any()) } returns listOf(DispatchDecision.ApproveTrain("T1"))

			// No actuator ports or approval callbacks are involved — this driver only posts.
			// Verify that the queue grows after runCycle and that the driver returned normally.
			runBlocking { makeDriver().runCycle() }

			// The decision is still in the queue (no applier draining it in this test)
			assertThat(commandQueue.approximateSize()).isEqualTo(1)
		}

		@Test
		@DisplayName("NoAction is posted to queue (dispatcher always returns at least NoAction)")
		fun noActionIsPosted() {
			every { dispatcher.decide(any()) } returns listOf(DispatchDecision.NoAction)

			runBlocking { makeDriver().runCycle() }

			val posted = commandQueue.drain()
			assertThat(posted).containsExactly(DispatchDecision.NoAction)
		}

		@Test
		@DisplayName("empty decision list results in nothing posted")
		fun emptyDecisionListPostsNothing() {
			// Dispatcher may return an empty list (though RuleBasedDispatcher returns at least NoAction)
			every { dispatcher.decide(any()) } returns emptyList()

			runBlocking { makeDriver().runCycle() }

			assertThat(commandQueue.drain()).isEmpty()
		}
	}

	// ── Throttle delta calculation ─────────────────────────────────────────────

	@Nested
	@DisplayName("Throttle delta = snapshot.simTime - prevSimTime")
	inner class ThrottleDelta {
		@Test
		@DisplayName("first cycle: delta = snapshot.simTime (prevSimTime starts at 0.0)")
		fun firstCycleDeltaEqualsSnapshotSimTime() {
			every { perceptionPort.snapshot() } returns emptySnapshot(5.0)

			runBlocking { makeDriver().runCycle() }

			assertThat(controller.lastThrottleDelta).isEqualTo(5.0)
		}

		@Test
		@DisplayName("second cycle: delta = simTime(2) - simTime(1)")
		fun secondCycleDeltaIsIncremental() {
			every { perceptionPort.snapshot() } returnsMany
				listOf(emptySnapshot(3.0), emptySnapshot(7.0))

			val driver = makeDriver()
			runBlocking {
				driver.runCycle() // prevSimTime = 0 → delta = 3.0
				driver.runCycle() // prevSimTime = 3.0 → delta = 4.0
			}

			// The second throttle call should receive delta = 7.0 - 3.0 = 4.0
			assertThat(controller.lastThrottleDelta).isEqualTo(4.0)
		}

		@Test
		@DisplayName("zero delta when simTime does not advance between cycles")
		fun zeroDeltaWhenSimTimeStagnant() {
			every { perceptionPort.snapshot() } returns emptySnapshot(10.0)

			val driver = makeDriver()
			runBlocking {
				driver.runCycle() // prevSimTime = 0 → delta = 10.0
				driver.runCycle() // prevSimTime = 10.0, snapshot still 10.0 → delta = 0.0
			}

			assertThat(controller.lastThrottleDelta).isEqualTo(0.0)
		}
	}

	// ── Dispatcher receives correct observation ────────────────────────────────

	@Nested
	@DisplayName("Dispatcher receives observation built from snapshot")
	inner class ObservationContent {
		@Test
		@DisplayName("observation snapshot matches the sensed snapshot")
		fun observationSnapshotMatchesSensed() {
			val expectedSnapshot = emptySnapshot(42.0)
			every { perceptionPort.snapshot() } returns expectedSnapshot

			val capturedObs = slot<DispatchObservation>()
			every { dispatcher.decide(capture(capturedObs)) } returns listOf(DispatchDecision.NoAction)

			runBlocking { makeDriver().runCycle() }

			assertThat(capturedObs.captured.snapshot).isEqualTo(expectedSnapshot)
		}

		@Test
		@DisplayName("dispatcher is called exactly once per runCycle call")
		fun dispatcherCalledOncePerCycle() {
			runBlocking { makeDriver().runCycle() }

			verify(exactly = 1) { dispatcher.decide(any()) }
		}

		@Test
		@DisplayName("perception port is called exactly once per runCycle call")
		fun perceptionPortCalledOncePerCycle() {
			runBlocking { makeDriver().runCycle() }

			verify(exactly = 1) { perceptionPort.snapshot() }
		}
	}

	// ── Controller pacing ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("Controller pacing calls")
	inner class ControllerPacing {
		@Test
		@DisplayName("awaitIfPaused is called once per cycle")
		fun awaitIfPausedCalledOncePerCycle() {
			runBlocking { makeDriver().runCycle() }

			assertThat(controller.awaitCalls).isEqualTo(1)
		}

		@Test
		@DisplayName("throttle is called once per cycle")
		fun throttleCalledOncePerCycle() {
			runBlocking { makeDriver().runCycle() }

			assertThat(controller.throttleCalls).isEqualTo(1)
		}

		@Test
		@DisplayName("multiple cycles each call awaitIfPaused and throttle once")
		fun multiCycleCallsEachCallControllerOnce() {
			val driver = makeDriver()
			val cycleCount = 3

			runBlocking {
				repeat(cycleCount) { driver.runCycle() }
			}

			assertThat(controller.awaitCalls).isEqualTo(cycleCount)
			assertThat(controller.throttleCalls).isEqualTo(cycleCount)
		}
	}

	// ── Helper: RecordingSimulationController ──────────────────────────────────

	/**
	 * Hand-written test double for [SimulationController] that records
	 * [awaitIfPaused] and [throttle] call counts and the last delta value.
	 */
	private class RecordingSimulationController : SimulationController {
		var awaitCalls: Int = 0
			private set

		var throttleCalls: Int = 0
			private set

		var lastThrottleDelta: Double = Double.NaN
			private set

		override suspend fun awaitIfPaused() {
			awaitCalls++
		}

		override fun throttle(simDeltaSeconds: Double) {
			throttleCalls++
			lastThrottleDelta = simDeltaSeconds
		}

		override fun isPaused(): Boolean = false

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun requestPause() = Unit
	}
}
