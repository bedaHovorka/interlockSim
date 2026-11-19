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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherPlanner
import cz.vutbr.fit.interlockSim.dispatcher.testutil.SelfPacedSimClock
import cz.vutbr.fit.interlockSim.dispatcher.testutil.SleepingPacingRecorder
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.emptySnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
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
@Timeout(10, unit = TimeUnit.SECONDS)
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

			// #926: the delta is the raw simDelta minus the (real, monotonic-clock-measured) wall
			// time spent inside planner.plan(), clamped at 0.0 — so it lies in [0.0, simDelta].
			// Only assert that valid range here; a wall-clock-budget lower bound (e.g. > 4.9) would
			// be flaky under CI GC/scheduler pauses. See PlannerWallTimeThrottleAdjustment for the
			// dedicated subtraction/clamp tests.
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(5.0)
			assertThat(controller.lastThrottleDelta).isGreaterThanOrEqualTo(0.0)
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
			// wall-clock adjustment (#926 — see firstCycleDeltaEqualsSnapshotSimTime above). Assert
			// only the valid [0.0, 4.0] range to avoid CI wall-clock flakiness.
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(4.0)
			assertThat(controller.lastThrottleDelta).isGreaterThanOrEqualTo(0.0)
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
			// Assert only the valid [0.0, 10.0] range to avoid CI wall-clock flakiness.
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(10.0)
			assertThat(controller.lastThrottleDelta).isGreaterThanOrEqualTo(0.0)
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
					// A publishable record (non-EMPTY snapshot), like its sibling test — a record
					// whose snapshot is the EMPTY sentinel would short-circuit before DECIDE and
					// silently re-pin this test to the short-circuit path instead of a full cycle.
					ShuntingLoop.TickObservation(emptyList(), emptyList(), emptyList(), tick = 1L, snapshot = emptySnapshot(0.0))
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
						),
					tick = 1L,
					snapshot = emptySnapshot(0.0)
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

			assertThat(capturedObs.captured.unapprovedTrains).isSameInstanceAs(tickA.queuedTrains)
			assertThat(capturedObs.captured.innerBlockInputs).isSameInstanceAs(tickA.innerBlockInputs)
			assertThat(capturedObs.captured.outerBlockInputs).isSameInstanceAs(tickA.outerBlockInputs)
		}

		/**
		 * Issue #1354: the snapshot and the queue come from one tick-stamped record. The perception
		 * port already holds a newer snapshot (tick 6) than the record (tick 5) — the torn state a
		 * second read could pair with tick 5's queue. The planner must get tick 5's snapshot with
		 * tick 5's queue, and the perception port must not be touched at all.
		 */
		@Test
		@DisplayName("the planner gets the snapshot and the queue of one tick-stamped record")
		fun snapshotAndQueueComeFromOneRecord() {
			val snapshot5 = emptySnapshot(10.0)
			val queue5 = listOf(QueuedTrainObservation("T5", "OUT5"))
			val record5 =
				ShuntingLoop.TickObservation(
					queuedTrains = queue5,
					innerBlockInputs = emptyList(),
					outerBlockInputs = emptyList(),
					tick = 5L,
					snapshot = snapshot5
				)
			val strictPerceptionPort = mockk<NetworkPerceptionPort>()
			every { strictPerceptionPort.snapshot() } returns emptySnapshot(12.0)
			val driver =
				AgentLoopDriver(
					strictPerceptionPort,
					planner,
					commandQueue,
					controller,
					DefaultDispatchLoopSensorPort { record5 }
				)
			val capturedObs = slot<DispatchObservation>()
			coEvery { planner.plan(capture(capturedObs)) } returns listOf(DispatchDecision.NoAction)

			runBlocking { driver.runCycle() }

			assertThat(capturedObs.captured.snapshot).isSameInstanceAs(snapshot5)
			assertThat(capturedObs.captured.unapprovedTrains).isSameInstanceAs(queue5)
			confirmVerified(strictPerceptionPort)
		}

		/**
		 * Issue #1354, perception-only wiring: a driver built without an explicit sensor port
		 * senses the default port, whose provider pairs an empty dispatch bundle with
		 * [perceptionPort]'s published snapshot — the planner still gets the perception state of
		 * a run with no dispatch loop, and the port is read through the record, not alongside it.
		 */
		@Test
		@DisplayName("the default sensor port pairs the perception snapshot with empty dispatch inputs")
		fun defaultSensorPortPairsPerceptionSnapshotWithEmptyInputs() {
			val wired = emptySnapshot(3.0)
			every { perceptionPort.snapshot() } returns wired
			val driver = makeDriver()
			val capturedObs = slot<DispatchObservation>()
			coEvery { planner.plan(capture(capturedObs)) } returns listOf(DispatchDecision.NoAction)

			val ran = runBlocking { driver.runCycle() }

			assertThat(ran).isTrue()
			assertThat(capturedObs.captured.snapshot).isSameInstanceAs(wired)
			assertThat(capturedObs.captured.unapprovedTrains).isEmpty()
			assertThat(capturedObs.captured.innerBlockInputs).isEmpty()
			assertThat(capturedObs.captured.outerBlockInputs).isEmpty()
			verify(exactly = 1) { perceptionPort.snapshot() }
			confirmVerified(perceptionPort)
		}

		/** The EMPTY short-circuit is decided by the record's snapshot field, not a second read. */
		@Test
		@DisplayName("the default sensor port's EMPTY snapshot still short-circuits the cycle")
		fun defaultSensorPortEmptySnapshotShortCircuits() {
			every { perceptionPort.snapshot() } returns SimulationSnapshot.EMPTY
			val driver = makeDriver()

			val ran = runBlocking { driver.runCycle() }

			assertThat(ran).isFalse()
			coVerify(exactly = 0) { planner.plan(any()) }
			verify(exactly = 1) { perceptionPort.snapshot() }
			confirmVerified(perceptionPort)
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

	// ── Pacing after one long cycle (Issue #1340) ────────────────────────────────

	@Nested
	@DisplayName("Pacing recovers within one cycle after one long plan() (Issue #1340)")
	inner class PacingAfterLongCycle {
		/**
		 * The #1301 campaign shape: the sim thread paces itself on the same controller, so it keeps
		 * running while one `plan()` call is stuck (a 30 s LLM timeout at speed 2 = about 60 sim s).
		 * The next snapshot is then about 60 sim s ahead, but that sim time has already passed on the
		 * wall clock, so PACE must not sleep for it again. Before the fix it did: it subtracted only
		 * the wall time spent inside `plan()`, slept the rest, the sim ran on meanwhile, and every
		 * later cycle saw the same 60 sim s gap — the period ratcheted to the longest `plan()` for
		 * the rest of the run (Červený Újezd run r02: dispatcher actions exactly 58 sim s apart).
		 *
		 * Speed 20 instead of the production 2.0 keeps the test short (a 60 sim s gap is 3 s of wall
		 * clock, not 30 s); the defect does not depend on the speed. The pins are sim-time and cycle
		 * counts that no machine load can move: every throttle argument after the long cycle is at
		 * most one control step, and so their sum is at most one step per cycle (the old code gives
		 * about 60 per cycle). The sim-time period between cycles includes real wall time, so it is
		 * only printed, never asserted (the [cz.vutbr.fit.interlockSim.dispatcher.planner.OllamaTimeoutAbortTest]
		 * ruling: counts, never wall clock).
		 */
		@Test
		@Timeout(value = 60, unit = TimeUnit.SECONDS)
		@DisplayName("throttle never re-sleeps sim time that already passed during a long plan()")
		fun throttleDoesNotResleepElapsedSimTime() {
			val speed = 20.0
			val clock = SelfPacedSimClock(speedMultiplier = speed, controlStepSeconds = CONTROL_STEP)
			val pacing = SleepingPacingRecorder(speed)
			every { perceptionPort.snapshot() } answers { emptySnapshot(clock.simTime()) }
			val decidedAt = mutableListOf<Double>()
			coEvery { planner.plan(any()) } coAnswers {
				decidedAt += firstArg<DispatchObservation>().snapshot.simTime
				if (decidedAt.size == 1) Thread.sleep((LONG_CYCLE_SIM_SECONDS / speed * 1_000).toLong())
				listOf(DispatchDecision.NoAction)
			}
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, pacing)

			runProcessedCycles(driver, CYCLES)

			val periods = decidedAt.zipWithNext { a, b -> b - a }
			println(
				"Issue #1340 driver pacing: decidedAt=$decidedAt periods=$periods throttle=${pacing.throttleDeltas}"
			)
			assertThat(decidedAt.size, "processed cycles").isEqualTo(CYCLES)
			// Cycle 2 sees the long cycle's gap; that gap is the cost of the long cycle itself.
			assertThat(periods[0], "sim time the long cycle took").isGreaterThan(LONG_CYCLE_SIM_SECONDS / 2)
			val afterLongCycle = pacing.throttleDeltas.drop(1)
			afterLongCycle.forEachIndexed { i, delta ->
				assertThat(delta, "throttle argument of cycle ${i + 2}").isLessThanOrEqualTo(CONTROL_STEP)
			}
			assertThat(afterLongCycle.sum(), "throttle arguments of cycles 2..$CYCLES, summed")
				.isLessThanOrEqualTo((CYCLES - 1) * CONTROL_STEP)
		}

		/**
		 * The other side of the #1340 fix: sim time NOT yet matched on the wall clock is still slept
		 * for. A sim that ran 10 sim s ahead between two instant cycles (an unpaced sim thread) gets
		 * nearly the whole 10 s as the second throttle argument, so the fix is not "never throttle".
		 * At speed 0.1 the 10 sim s are 100 s of wall clock, so the `> 5.0` floor holds unless the
		 * two cycles are 50 s apart: load-proof.
		 */
		@Test
		@DisplayName("sim time not yet matched on the wall clock is still throttled on a later cycle")
		fun simTimeAheadOfWallClockIsStillThrottled() {
			controller.speedMultiplier = 0.1
			every { perceptionPort.snapshot() } returnsMany listOf(emptySnapshot(2.0), emptySnapshot(12.0))
			val driver = makeDriver()

			runBlocking {
				driver.runCycle()
				driver.runCycle()
			}

			assertThat(controller.throttleCalls).isEqualTo(2)
			assertThat(controller.lastThrottleDelta).isLessThanOrEqualTo(10.0)
			assertThat(controller.lastThrottleDelta).isGreaterThan(5.0)
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

	// ── Simulation stopping while a cycle is in flight (Issue #1032) ───────────

	@Nested
	@DisplayName("Simulation stops while plan() is in flight")
	inner class SimStoppedMidCycle {
		/**
		 * Reproduces the #1032 timeline: a cycle's `plan()` call is still running when the
		 * simulation stops (e.g. natural completion, or a deadlock resolution elsewhere).
		 * Once `plan()` finally returns — however late — the cycle must not act as if the
		 * run were still live: no decision may be posted, and PACE must not run [SimulationController.awaitIfPaused]/
		 * [SimulationController.throttle] against a controller that may itself be pacing a dead run.
		 */
		@Test
		@DisplayName("discards the decision and skips ACT/PACE when isSimActive() went false during plan()")
		fun discardsDecisionWhenSimStoppedMidCycle() {
			var simActive = true
			coEvery { planner.plan(any()) } answers {
				// Simulate the simulation stopping while this cycle's plan() call was in flight.
				simActive = false
				listOf(DispatchDecision.ApproveTrain("T1"))
			}
			val driver = AgentLoopDriver(perceptionPort, planner, commandQueue, controller, isSimActive = { simActive })

			val processed = runBlocking { driver.runCycle() }

			assertThat(processed).isFalse()
			assertThat(commandQueue.drain()).isEmpty()
			assertThat(controller.awaitCalls).isEqualTo(0)
			assertThat(controller.throttleCalls).isEqualTo(0)
		}

		@Test
		@DisplayName("isSimActive defaults to always-true, so existing callers are unaffected")
		fun defaultIsSimActiveDoesNotAffectNormalCycle() {
			coEvery { planner.plan(any()) } returns listOf(DispatchDecision.ApproveTrain("T1"))
			val driver = makeDriver()

			val processed = runBlocking { driver.runCycle() }

			assertThat(processed).isTrue()
			assertThat(commandQueue.drain()).containsExactly(DispatchDecision.ApproveTrain("T1"))
			assertThat(controller.awaitCalls).isEqualTo(1)
			assertThat(controller.throttleCalls).isEqualTo(1)
		}
	}

	private companion object {
		/** `ShuntingLoop`'s control step: one tick per 2 sim s. */
		const val CONTROL_STEP = 2.0

		/** The long cycle: a 30 s LLM timeout at the production speed 2.0. */
		const val LONG_CYCLE_SIM_SECONDS = 60.0
		const val CYCLES = 6

		/** Upper bound on [AgentLoopDriver.runCycle] calls, short-circuited ones included. */
		const val MAX_RUN_CYCLE_CALLS = 100_000
	}

	/** Calls [AgentLoopDriver.runCycle] until [cycles] of them have processed a snapshot. */
	private fun runProcessedCycles(
		driver: AgentLoopDriver,
		cycles: Int
	) {
		var processed = 0
		var calls = 0
		runBlocking {
			while (processed < cycles && calls < MAX_RUN_CYCLE_CALLS) {
				calls++
				if (driver.runCycle()) processed++
			}
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
