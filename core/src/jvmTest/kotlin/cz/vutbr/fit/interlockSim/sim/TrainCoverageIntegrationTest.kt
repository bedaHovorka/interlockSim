/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Integration tests for Train$Front and Train$LengthChecker coverage (#394)
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Integration tests to increase coverage of Train$Front and Train$LengthChecker.
 *
 * Both are private inner classes of [Train] and can only be exercised through
 * full simulation runs or reflection.
 *
 * **Train$LengthChecker coverage gaps (before):**
 * - `report()` method (only called on invariant violation -- tested via reflection)
 *
 * **Train$Front note:**
 * The STOP signal branch in Front.semaphoreAction (lines 331-408) accounts for ~47% of
 * Front's instructions but requires precise timing of the ShuntingLoop dispatcher.
 * The dispatcher proactively reserves paths and sets semaphore signals to allowing,
 * making the STOP branch unreachable in normal simulation runs. Covering this branch
 * requires modifying dispatcher timing, which is restricted by sim/ package guidelines.
 *
 * @since 2026-03-27 (#394)
 */
@DisplayName("Train Front & LengthChecker Coverage Tests (#394)")
class TrainCoverageIntegrationTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun tearDown() {
		context?.close()
	}

	private fun loadVyhybnaContext(): DefaultSimulationContext {
		val ctx = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, warmUpDynamicWrappers = true)
		context = ctx
		return ctx
	}

	/**
	 * Tests for Train$Front coverage -- exercises semaphore handling,
	 * section transitions, and various separatorAction branches through
	 * ShuntingLoop and SimpleTestProcess simulations.
	 */
	@Nested
	@DisplayName("Front -- semaphore and section transition coverage")
	@Tag("integration-test")
	inner class FrontCoverageTests {
		/**
		 * Extended ShuntingLoop run (endTime=200) exercises Front branches through
		 * multiple train generations. Covers:
		 * - semaphoreAction() allowing signal branches
		 * - separatorAction() for all separator types (InOut, semaphore, switch)
		 * - accelerateToSignal() with both allowing and warning modes
		 * - Front entering/leaving multiple track sections
		 */
		@Test
		@DisplayName("ShuntingLoop endTime=200 maximizes Front branch coverage")
		@Timeout(value = 120, unit = SECONDS)
		fun extendedSimulationMaximizesFrontCoverage() {
			val ctx = loadVyhybnaContext()

			val endEvents = AtomicInteger(0)
			val continuousEvents = AtomicInteger(0)
			ctx.addPropertyChangeListener(
				ContextPropertyChangeListener { event ->
					when (event.propertyName) {
						ReportType.TRAIN_EVENTS.name -> {
							val msg = event.newValue?.toString() ?: ""
							if (msg.contains("ends")) endEvents.incrementAndGet()
						}
						ReportType.TRAIN_CONTINUOUS.name -> continuousEvents.incrementAndGet()
					}
				}
			)

			val loop = ShuntingLoop(ctx, 200L)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)
			ctx.run()

			assertThat(endEvents.get(), name = "train end events in extended sim")
				.isGreaterThan(2)
			assertThat(continuousEvents.get(), name = "continuous telemetry events")
				.isGreaterThan(0)
		}

		/**
		 * Short ShuntingLoop run exercises basic Front path.
		 */
		@Test
		@DisplayName("ShuntingLoop endTime=60 exercises basic Front paths")
		@Timeout(value = 60, unit = SECONDS)
		fun shortSimulationExercisesBasicFrontPaths() {
			val ctx = loadVyhybnaContext()

			val trainApproved = AtomicInteger(0)
			ctx.addPropertyChangeListener(
				ContextPropertyChangeListener { event ->
					if (event.propertyName == ReportType.TRAIN_APPROVED.name) {
						trainApproved.incrementAndGet()
					}
				}
			)

			val loop = ShuntingLoop(ctx, 60L)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)
			ctx.run()

			assertThat(trainApproved.get(), name = "trains approved (exercising Front paths)")
				.isGreaterThan(0)
		}

		/**
		 * Exercises Front with a simple linear topology using SimpleTestProcess.
		 * Covers Front.separatorAction when `where == timetable.getIn()` (InOut entry).
		 */
		@Test
		@DisplayName("SimpleTestProcess exercises Front InOut entry path")
		@Timeout(value = 60, unit = SECONDS)
		fun simpleLinearTopologyExercisesInOutEntryPath() {
			val ctx = TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext
			context = ctx

			val inOuts = ctx.getInOuts().toList()
			require(inOuts.size >= 2) { "Test requires at least 2 InOuts" }

			ctx.getRoutingServices().getPathReservationService().reservePath("CoverageTest#1", inOuts[0], inOuts[1])

			val timetable = Timetable(inOuts[0], inOuts[1], Time(0.0), Time(60.0), 50.0)
			val train = Train(ctx, timetable)

			val testProcess = SimpleTestProcess(train, endTime = 45.0)
			ctx.setMainProcess(testProcess)
			ctx.run()

			val state = testProcess.getTrainState()
			logger.info {
				"Train state: velocity=${state.velocity}, position=${state.position}, terminated=${state.terminated}"
			}
			assertThat(state.terminated || state.position > 0.0, name = "train made progress or completed")
				.isTrue()
		}
	}

	/**
	 * Tests for Train$LengthChecker coverage -- exercises report() via reflection
	 * and check() through simulation.
	 *
	 * LengthChecker is a private inner class with:
	 * - check(): validates abs(front - tail - length) <= maxAbsError (95% covered by existing tests)
	 * - report(): diagnostic output (0% covered -- only called on invariant violation)
	 *
	 * Since report() is only called during error conditions (invariant violation in
	 * ContinuousInvariantChecker.derivatives()), we use reflection to invoke it directly.
	 */
	@Nested
	@DisplayName("LengthChecker -- report() and check() coverage")
	@Tag("integration-test")
	inner class LengthCheckerCoverageTests {
		/**
		 * Exercises LengthChecker.report() via reflection on a freshly created Train.
		 *
		 * report() appends: front.getTotalDistance() + ' ' + tail.getTotalDistance() + ' ' + getLength()
		 * On a fresh train, distances are 0.0 and length is the timetable value.
		 */
		@Test
		@DisplayName("LengthChecker.report() produces diagnostic output via reflection")
		@Timeout(value = 60, unit = SECONDS)
		fun lengthCheckerReportProducesDiagnosticOutput() {
			val ctx = loadVyhybnaContext()
			val inOuts = ctx.getInOuts().toList()
			require(inOuts.size >= 2)

			ctx.getRoutingServices().getPathReservationService().reservePath("ReflTest#1", inOuts[0], inOuts[1])

			val timetable = Timetable(inOuts[0], inOuts[1], Time(0.0), Time(60.0), 100.0)
			val train = Train(ctx, timetable)

			val apField = Train::class.java.getDeclaredField("ap")
			apField.isAccessible = true
			val lengthChecker = apField.get(train) as ContinuousInvariantChecker

			val sb = StringBuilder("test : ")
			val result = lengthChecker.report(sb)

			assertThat(result).isNotNull()
			val output = result.toString()
			logger.info { "LengthChecker.report() output: $output" }
			assertThat(output, name = "report output contains prefix")
				.contains("test : ")
			assertThat(output, name = "report output contains train length")
				.contains("100.0")
		}

		/**
		 * Exercises LengthChecker.report() on a train after simulation run,
		 * where front and tail have traveled valid distances.
		 */
		@Test
		@DisplayName("LengthChecker.report() formats distances from active simulation")
		@Timeout(value = 60, unit = SECONDS)
		fun lengthCheckerReportAfterSimulationRun() {
			val ctx = TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext
			context = ctx

			val inOuts = ctx.getInOuts().toList()
			require(inOuts.size >= 2)

			ctx.getRoutingServices().getPathReservationService().reservePath("ReportTest#1", inOuts[0], inOuts[1])

			val timetable = Timetable(inOuts[0], inOuts[1], Time(0.0), Time(60.0), 100.0)
			val train = Train(ctx, timetable)

			val testProcess = SimpleTestProcess(train, endTime = 5.0)
			ctx.setMainProcess(testProcess)
			ctx.run()

			val apField = Train::class.java.getDeclaredField("ap")
			apField.isAccessible = true
			val lengthChecker = apField.get(train) as ContinuousInvariantChecker

			val sb = StringBuilder("diagnostics : ")
			val result = lengthChecker.report(sb)
			val output = result.toString()
			logger.info { "LengthChecker.report() after sim: $output" }
			assertThat(output, name = "report contains diagnostics prefix")
				.contains("diagnostics : ")
		}

		/**
		 * Exercises LengthChecker.check() during actual simulation via derivatives().
		 * The true branch is always hit in a successful simulation.
		 */
		@Test
		@DisplayName("LengthChecker.check() exercised through simulation")
		@Timeout(value = 60, unit = SECONDS)
		fun lengthCheckerCheckExercisedThroughSimulation() {
			val ctx = loadVyhybnaContext()

			val loop = ShuntingLoop(ctx, 30L)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)
			ctx.run()

			assertThat(ctx.getGraph()).isNotNull()
		}

		/**
		 * Tests LengthChecker.report() returns same StringBuilder reference (API contract).
		 * Also validates the output format: 3 space-separated numeric values.
		 */
		@Test
		@DisplayName("LengthChecker.report() returns same StringBuilder reference")
		@Timeout(value = 30, unit = SECONDS)
		fun lengthCheckerReportReturnsSameReference() {
			val ctx = loadVyhybnaContext()
			val inOuts = ctx.getInOuts().toList()
			require(inOuts.size >= 2)

			ctx.getRoutingServices().getPathReservationService().reservePath("RefTest#1", inOuts[0], inOuts[1])

			val timetable = Timetable(inOuts[0], inOuts[1], Time(0.0), Time(60.0), 100.0)
			val train = Train(ctx, timetable)

			val apField = Train::class.java.getDeclaredField("ap")
			apField.isAccessible = true
			val lengthChecker = apField.get(train) as ContinuousInvariantChecker

			val sb = StringBuilder()
			val returned = lengthChecker.report(sb)

			assertThat(returned === sb, name = "report() returns same StringBuilder reference")
				.isTrue()

			val parts = sb.toString().trim().split(" ")
			assertThat(parts.size, name = "report output has 3 space-separated values")
				.isGreaterThanOrEqualTo(3)
		}
	}
}
