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
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isSameAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
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
 * - Decisions returned by [DispatcherPlanner.plan] are posted to [ActuatorCommandQueue]
 * - Decisions are **posted** (not applied in-process) — the driver thread never
 *   mutates simulation state
 * - [SimulationController.throttle] receives the correct simulation-time delta
 *   (`snapshot.simTime - prevSimTime` from the previous cycle)
 * - [SimulationController] is only used in the driver; it is not accessible to the
 *   planner or observation
 *
 * Uses a hand-written [RecordingSimulationController] to capture call order and
 * mockk for [NetworkPerceptionPort] and [DispatcherPlanner].
 *
 * @since Issue #732 (SP0.10 — Goal 10)
 */
@DisplayName("AgentLoopDriver — paced sense-decide-act cycle")
@Timeout(30, unit = TimeUnit.SECONDS)
class AgentLoopDriverTest {
	private lateinit var perceptionPort: NetworkPerceptionPort
	private lateinit var planner: DispatcherPlanner
	private lateinit var commandQueue: ActuatorCommandQueue
	private lateinit var controller: RecordingSimulationController

	@BeforeEach
	fun setUp() {
		perceptionPort = mockk(relaxed = true)
		planner = mockk(relaxed = true)
		commandQueue = ActuatorCommandQueue()
		controller = RecordingSimulationController()

		// Default: snapshot at simTime=0.0; planner returns NoAction
		every { perceptionPort.snapshot() } returns emptySnapshot(0.0)
		coEvery { planner.plan(any()) } returns listOf(DispatchDecision.NoAction)
	}

	private fun makeDriver(): AgentLoopDriver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller)

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
			coEvery { planner.plan(capture(dispatchObsSlot)) } answers {
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

					override fun requestResume() = Unit

					override fun currentSpeedMultiplier(): Double = 1.0
				}

			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controllerWithOrder)

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

					override fun requestResume() = Unit

					override fun currentSpeedMultiplier(): Double = 1.0
				}

			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, orderedController)

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
			coEvery { planner.plan(any()) } returns expected

			runBlocking { makeDriver().runCycle() }

			val posted = commandQueue.drain()
			assertThat(posted).containsExactly(*expected.toTypedArray())
		}

		@Test
		@DisplayName("driver does not apply decisions itself — queue holds them until drained")
		fun driverDoesNotApplyDecisions() {
			coEvery { planner.plan(any()) } returns listOf(DispatchDecision.ApproveTrain("T1"))

			// No actuator ports or approval callbacks are involved — this driver only posts.
			// Verify that the queue grows after runCycle and that the driver returned normally.
			runBlocking { makeDriver().runCycle() }

			// The decision is still in the queue (no applier draining it in this test)
			assertThat(commandQueue.approximateSize()).isEqualTo(1)
		}

		@Test
		@DisplayName("NoAction is posted to queue (dispatcher always returns at least NoAction)")
		fun noActionIsPosted() {
			coEvery { planner.plan(any()) } returns listOf(DispatchDecision.NoAction)

			runBlocking { makeDriver().runCycle() }

			val posted = commandQueue.drain()
			assertThat(posted).containsExactly(DispatchDecision.NoAction)
		}

		@Test
		@DisplayName("empty decision list results in nothing posted")
		fun emptyDecisionListPostsNothing() {
			// Planner may return an empty list (though RuleBasedDispatcher returns at least NoAction)
			coEvery { planner.plan(any()) } returns emptyList()

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

			// #926: the delta is now the raw simDelta minus the (real, monotonic-clock-measured)
			// wall time spent inside planner.plan() — a handful of microseconds even for an
			// instant mock call — so it is bounded rather than exactly 5.0. See
			// PlannerWallTimeThrottleAdjustment for the dedicated subtraction/clamp tests.
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(5.0)
			assertThat(controller.lastThrottleDelta).isGreaterThan(4.9)
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

			// The second throttle call should receive delta = 7.0 - 3.0 = 4.0, minus a negligible
			// wall-clock adjustment (#926 — see firstCycleDeltaEqualsSnapshotSimTime above).
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(4.0)
			assertThat(controller.lastThrottleDelta).isGreaterThan(3.9)
		}

		@Test
		@DisplayName("stagnant simTime: cycle is skipped — no re-decide, no throttle, pause still honoured (SP0.11)")
		fun stagnantSimTimeSkipsCycle() {
			// SP0.11: a snapshot whose simTime equals the previous cycle's is a re-read of the
			// same sim tick. runCycle returns early — the dispatcher must NOT re-decide on
			// identical state (duplicate-ReservePath source, Issue #742 investigation) and
			// throttle is not called again; only awaitIfPaused still runs so pause is honoured.
			every { perceptionPort.snapshot() } returns emptySnapshot(10.0)

			val driver = makeDriver()
			runBlocking {
				driver.runCycle() // prevSimTime = 0 → processed, delta = 10.0
				driver.runCycle() // snapshot still 10.0 → skipped (early return)
			}

			// #926: negligible wall-clock adjustment (see firstCycleDeltaEqualsSnapshotSimTime above).
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(10.0)
			assertThat(controller.lastThrottleDelta).isGreaterThan(9.9)
			assertThat(controller.throttleCalls).isEqualTo(1)
			assertThat(controller.awaitCalls).isEqualTo(2)
			coVerify(exactly = 1) { planner.plan(any()) }
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
			coEvery { planner.plan(capture(capturedObs)) } returns listOf(DispatchDecision.NoAction)

			runBlocking { makeDriver().runCycle() }

			assertThat(capturedObs.captured.snapshot).isEqualTo(expectedSnapshot)
		}

		@Test
		@DisplayName("dispatcher is called exactly once per runCycle call")
		fun dispatcherCalledOncePerCycle() {
			runBlocking { makeDriver().runCycle() }

			coVerify(exactly = 1) { planner.plan(any()) }
		}

		@Test
		@DisplayName("perception port is called exactly once per runCycle call")
		fun perceptionPortCalledOncePerCycle() {
			runBlocking { makeDriver().runCycle() }

			verify(exactly = 1) { perceptionPort.snapshot() }
		}
	}

	// ── Sensor-port atomicity (tearing fix) ───────────────────────────────────

	@Nested
	@DisplayName("dispatchLoopSensorPort is read atomically, once per cycle")
	inner class ObservationProviderAtomicity {
		/**
		 * Regression test for the tearing bug: [AgentLoopDriver] used to call three
		 * separate providers (unapproved trains, inner/outer block inputs), each of
		 * which could observe a different sim tick if the sim thread republished
		 * [ShuntingLoop.TickObservation] in between. Now there is a single
		 * [cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort.snapshot] call per
		 * cycle (SP4.2, Issue #564), so this asserts it is invoked exactly once and
		 * that all three [DispatchObservation] fields come from that single
		 * returned bundle.
		 */
		@Test
		@DisplayName("sensor port snapshot is taken exactly once per runCycle call")
		fun observationProviderCalledOncePerCycle() {
			var callCount = 0
			val sensorPort =
				DefaultDispatchLoopSensorPort {
					callCount++
					ShuntingLoop.TickObservation(emptyList(), emptyList(), emptyList())
				}
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, sensorPort)

			runBlocking { driver.runCycle() }

			assertThat(callCount).isEqualTo(1)
		}

		@Test
		@DisplayName("all three DispatchObservation fields come from the same TickObservation instance")
		fun allThreeFieldsComeFromSameTick() {
			val tickA =
				ShuntingLoop.TickObservation(
					queuedTrains = listOf(QueuedTrainObservation("T1", "OUT1")),
					innerBlockInputs =
						listOf(
							BlockInputObservation(
								blockId = "innerA",
								towardSemaphoreName = "semA",
								state = TrackFacility.State.FREE,
								ownerTrainId = null,
								isApproachingThisInput = false,
								pathSetUpTowardThisInput = false,
								pathAlreadyExtendedBeyond = false
							)
						),
					outerBlockInputs =
						listOf(
							BlockInputObservation(
								blockId = "outerA",
								towardSemaphoreName = "semB",
								state = TrackFacility.State.FREE,
								ownerTrainId = null,
								isApproachingThisInput = false,
								pathSetUpTowardThisInput = false,
								pathAlreadyExtendedBeyond = false
							)
						)
				)
			// A second, distinguishable tick — if the driver ever split its reads across two
			// provider calls, some fields would come from tickB instead of tickA.
			val tickB =
				ShuntingLoop.TickObservation(
					queuedTrains = listOf(QueuedTrainObservation("T2", "OUT2")),
					innerBlockInputs = emptyList(),
					outerBlockInputs = emptyList()
				)
			var callCount = 0
			val sensorPort =
				DefaultDispatchLoopSensorPort {
					callCount++
					if (callCount == 1) tickA else tickB
				}
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, sensorPort)

			val capturedObs = slot<DispatchObservation>()
			coEvery { planner.plan(capture(capturedObs)) } returns listOf(DispatchDecision.NoAction)

			runBlocking { driver.runCycle() }

			assertThat(capturedObs.captured.unapprovedTrains).isSameAs(tickA.queuedTrains)
			assertThat(capturedObs.captured.innerBlockInputs).isSameAs(tickA.innerBlockInputs)
			assertThat(capturedObs.captured.outerBlockInputs).isSameAs(tickA.outerBlockInputs)
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
			// SP0.11: simTime must advance between cycles, otherwise runCycle treats the
			// snapshot as a stale re-read and skips the PACE throttle (see
			// stagnantSimTimeSkipsCycle for that contract).
			every { perceptionPort.snapshot() } returnsMany
				listOf(emptySnapshot(1.0), emptySnapshot(2.0), emptySnapshot(3.0))

			val driver = makeDriver()
			val cycleCount = 3

			runBlocking {
				repeat(cycleCount) { driver.runCycle() }
			}

			assertThat(controller.awaitCalls).isEqualTo(cycleCount)
			assertThat(controller.throttleCalls).isEqualTo(cycleCount)
		}
	}

	// ── Throttle double-count fix (Issue #926) ───────────────────────────────────

	@Nested
	@DisplayName("Throttle delta accounts for wall time spent inside planner.plan()")
	inner class PlannerWallTimeThrottleAdjustment {
		/**
		 * Regression test for the throttle double-count defect (#926): before the fix,
		 * `runCycle()` passed the full `simDelta` to [SimulationController.throttle]
		 * regardless of how much wall-clock time `planner.plan()` had already consumed
		 * (e.g. blocking on LLM inference) — so the sim-pacing sleep on top of that
		 * already-elapsed wall time slowed every cycle far beyond the intended pace.
		 *
		 * The fix subtracts the wall time actually spent inside `planner.plan()`,
		 * converted to sim-time units via the controller's active speed multiplier,
		 * before calling [SimulationController.throttle]. A sleep can only ever run
		 * *long* (scheduler jitter), never short, so the resulting throttle delta must
		 * be strictly less than the raw `simDelta` and can be bounded below the
		 * pre-fix value with a comfortable jitter margin.
		 */
		@Test
		@DisplayName("throttle delta is reduced by planner wall time * speed multiplier")
		fun throttleDeltaSubtractsPlannerWallTime() {
			controller.speedMultiplier = 2.0
			every { perceptionPort.snapshot() } returns emptySnapshot(10.0)
			val sleepMs = 100L
			coEvery { planner.plan(any()) } coAnswers {
				delay(sleepMs)
				listOf(DispatchDecision.NoAction)
			}

			runBlocking { makeDriver().runCycle() }

			assertThat(controller.throttleCalls).isEqualTo(1)
			// Pre-fix (buggy) behaviour would have passed exactly 10.0 — assert strictly less.
			assertThat(controller.lastThrottleDelta).isLessThan(10.0)
			// wallElapsedInSimUnits >= 0.1s * 2.0x = 0.2s (delay() never returns early), so the
			// adjusted delta must be at most 9.8; a generous upper margin absorbs scheduler jitter.
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(9.85)
			// Sanity floor: the adjustment should not be wildly larger than expected either.
			assertThat(controller.lastThrottleDelta).isGreaterThan(9.0)
		}

		/**
		 * Clamp test: when the wall time spent inside `planner.plan()` (scaled by the speed
		 * multiplier) exceeds the raw `simDelta`, the adjusted delta must clamp at `0.0` rather
		 * than go negative — a negative duration passed to `throttle()` would be nonsensical.
		 */
		@Test
		@DisplayName("throttle delta clamps at 0.0 when planner wall time exceeds the sim-time budget")
		fun throttleDeltaClampsAtZeroWhenPlannerExceedsBudget() {
			controller.speedMultiplier = 1.0
			every { perceptionPort.snapshot() } returns emptySnapshot(0.01)
			coEvery { planner.plan(any()) } coAnswers {
				delay(50L)
				listOf(DispatchDecision.NoAction)
			}

			runBlocking { makeDriver().runCycle() }

			assertThat(controller.throttleCalls).isEqualTo(1)
			assertThat(controller.lastThrottleDelta).isEqualTo(0.0)
		}
	}

	// ── Signal-based pacing (SP0.11c, Issue #746) ────────────────────────────────

	@Nested
	@DisplayName("Signal-based pacing (snapshotSignal non-null)")
	inner class SignalBasedPacing {
		/** [runCycle] must not read anything before the signal returns. */
		@Test
		@DisplayName("runCycle awaits the signal before sensing")
		fun runCycleAwaitsSignalBeforeSensing() {
			val callOrder = mutableListOf<String>()
			val signal =
				mockk<SnapshotSignal> {
					every { await() } answers {
						callOrder += "await"
						true
					}
				}
			every { perceptionPort.snapshot() } answers {
				callOrder += "sense"
				emptySnapshot(1.0)
			}
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, snapshotSignal = signal)

			runBlocking { driver.runCycle() }

			assertThat(callOrder).containsExactly("await", "sense")
		}

		@Test
		@DisplayName("runCycle returns true and posts decisions when the signal is delivered")
		fun runCycleProcessesNormallyWhenSignalled() {
			val signal = mockk<SnapshotSignal> { every { await() } returns true }
			coEvery { planner.plan(any()) } returns listOf(DispatchDecision.ApproveTrain("T1"))
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, snapshotSignal = signal)

			val processed = runBlocking { driver.runCycle() }

			assertThat(processed).isTrue()
			assertThat(commandQueue.drain()).containsExactly(DispatchDecision.ApproveTrain("T1"))
			coVerify(exactly = 1) { planner.plan(any()) }
		}

		/**
		 * The stale-tick guard ([stagnantSimTimeSkipsCycle]'s polling-mode contract) does
		 * not apply once a real [SnapshotSignal] is delivering a fresh snapshot per call —
		 * see [AgentLoopDriver]'s KDoc for why keeping that guard in signal mode is the
		 * exact SP0.11c root cause, not just redundant.
		 */
		@Test
		@DisplayName("unchanged simTime still decides again when re-signalled (guard bypassed)")
		fun unchangedSimTimeStillDecidesWhenSignalled() {
			val signal = mockk<SnapshotSignal> { every { await() } returns true }
			every { perceptionPort.snapshot() } returns emptySnapshot(10.0)
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, snapshotSignal = signal)

			val results =
				runBlocking {
					listOf(driver.runCycle(), driver.runCycle())
				}

			assertThat(results).containsExactly(true, true)
			coVerify(exactly = 2) { planner.plan(any()) }
			assertThat(controller.throttleCalls).isEqualTo(2)
		}

		@Test
		@DisplayName("runCycle returns false and does not sense/decide when the signal times out")
		fun runCycleShortCircuitsOnSignalTimeout() {
			val signal = mockk<SnapshotSignal> { every { await() } returns false }
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, snapshotSignal = signal)

			val processed = runBlocking { driver.runCycle() }

			assertThat(processed).isFalse()
			verify(exactly = 0) { perceptionPort.snapshot() }
			coVerify(exactly = 0) { planner.plan(any()) }
			assertThat(commandQueue.drain()).isEmpty()
		}

		@Test
		@DisplayName("a signal timeout still calls awaitIfPaused so pause/step is honoured")
		fun signalTimeoutStillHonoursPause() {
			val signal = mockk<SnapshotSignal> { every { await() } returns false }
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, snapshotSignal = signal)

			runBlocking { driver.runCycle() }

			assertThat(controller.awaitCalls).isEqualTo(1)
			assertThat(controller.throttleCalls).isEqualTo(0)
		}

		@Test
		@DisplayName("polling-mode runCycle (no signal) returns true on a normally-processed cycle")
		fun pollingModeReturnsTrueOnNormalCycle() {
			val processed = runBlocking { makeDriver().runCycle() }

			assertThat(processed).isTrue()
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

		/** Speed multiplier reported by [currentSpeedMultiplier]; test-configurable. Defaults to 1.0x. */
		var speedMultiplier: Double = 1.0

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

		override fun requestResume() = Unit

		override fun currentSpeedMultiplier(): Double = speedMultiplier
	}
}
