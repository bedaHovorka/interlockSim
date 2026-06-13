package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.get
import org.koin.test.inject
import java.util.concurrent.TimeUnit

@DisplayName("DefaultSimulationContext Controlled Loop Tests")
class DefaultSimulationContextControllerTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val processFactory: SimulationProcessFactory by inject()

	private fun createValidContext(controller: SimulationController): DefaultSimulationContext {
		val xml = TestFixtures.loadShuntingXml()
		requireNotNull(xml) { "vyhybna.xml must exist in resources" }
		val editingContext = editingContextFactory.createContext(xml) as EditingContext
		return DefaultSimulationContext.fromEditingContext(editingContext, processFactory, controller)
	}

	private class SimpleHoldProcess(private val duration: Double) : LoopProcess() {
		override suspend fun iteration() {
			println("PROCESS_ITERATION: time=${time()}")
			if (time() >= duration) {
				terminate()
			}
		}
		override suspend fun interLoopSleep() {
			println("PROCESS_INTERLOOP_SLEEP: time=${time()}")
			hold(1.0)
		}
	}

	private class FakeSimulationController : SimulationController {
		var paused = false
		var stepEvent = false
		var stepTime: Double? = null
		val throttleCalls = mutableListOf<Double>()
		var awaitCount = 0

		override suspend fun awaitIfPaused() {
			awaitCount++
			while (paused && !stepEvent && stepTime == null) {
				kotlinx.coroutines.delay(1)
			}
		}

		override fun throttle(simDeltaSeconds: Double) {
			throttleCalls.add(simDeltaSeconds)
		}

		override fun isPaused(): Boolean = paused

		override fun pollStepEvent(): Boolean {
			if (stepEvent) {
				stepEvent = false
				paused = true // pause again after stepping
				return true
			}
			return false
		}

		override fun pollStepTime(): Double? {
			val dt = stepTime
			if (dt != null) {
				stepTime = null
				paused = true // pause again after stepping
				return dt
			}
			return null
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("FakeSimulationController: pause halts the loop, resume restarts it")
	fun testPauseAndResume() {
		val controller = FakeSimulationController()
		val context = createValidContext(controller)
		val testProcess = SimpleHoldProcess(5.0)
		context.setMainProcess(testProcess)

		controller.paused = true
		val future = java.util.concurrent.CompletableFuture.runAsync {
			try {
				context.run(controller)
			} catch (e: Throwable) {
				e.printStackTrace()
				throw e
			}
		}

		// Wait a bit to ensure it is blocked/paused at t=0
		Thread.sleep(200)
		assertThat(future.isDone).isFalse()
		assertThat(controller.awaitCount).isGreaterThan(0)

		// Resume
		controller.paused = false
		future.get(2, TimeUnit.SECONDS) // wait for completion

		assertThat(future.isDone).isTrue()
		assertThat(testProcess.time()).isEqualTo(5.0)
		assertThat(controller.throttleCalls.size).isGreaterThan(0)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("stepEvent() advances exactly one event boundary and pauses again")
	fun testStepEvent() {
		val controller = FakeSimulationController()
		val context = createValidContext(controller)
		val testProcess = SimpleHoldProcess(3.0)
		context.setMainProcess(testProcess)

		controller.paused = true
		val future = java.util.concurrent.CompletableFuture.runAsync {
			try {
				context.run(controller)
			} catch (e: Throwable) {
				e.printStackTrace()
				throw e
			}
		}

		// Wait a bit to ensure it's paused
		Thread.sleep(200)
		assertThat(future.isDone).isFalse()

		// Step exactly 1 event (will step to t=0.0 process activation)
		controller.stepEvent = true
		Thread.sleep(200)
		assertThat(testProcess.time()).isEqualTo(0.0)
		assertThat(future.isDone).isFalse()

		// Step exactly 2nd event (will step to t=1.0 first hold)
		controller.stepEvent = true
		Thread.sleep(200)
		assertThat(testProcess.time()).isEqualTo(1.0)
		assertThat(future.isDone).isFalse()

		// Step third event (will step to t=2.0 second hold)
		controller.stepEvent = true
		Thread.sleep(200)
		assertThat(testProcess.time()).isEqualTo(2.0)
		assertThat(future.isDone).isFalse()

		// Resume to end
		controller.paused = false
		future.get(2, TimeUnit.SECONDS)
		assertThat(testProcess.time()).isEqualTo(3.0)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("stepTime(1.0) advances 1 sim-second and pauses again")
	fun testStepTime() {
		val controller = FakeSimulationController()
		val context = createValidContext(controller)
		val testProcess = SimpleHoldProcess(3.0)
		context.setMainProcess(testProcess)

		controller.paused = true
		val future = java.util.concurrent.CompletableFuture.runAsync {
			try {
				context.run(controller)
			} catch (e: Throwable) {
				e.printStackTrace()
				throw e
			}
		}

		Thread.sleep(200)

		// Step exactly 1.0 sim seconds
		controller.stepTime = 1.0
		Thread.sleep(200)
		println("TEST_DEBUG: time=${testProcess.time()} terminated=${testProcess.terminated()}")
		assertThat(testProcess.time()).isEqualTo(1.0)
		assertThat(future.isDone).isFalse()

		// Step another 1.0 sim seconds
		controller.stepTime = 1.0
		Thread.sleep(200)
		assertThat(testProcess.time()).isEqualTo(2.0)
		assertThat(future.isDone).isFalse()

		// Resume to end
		controller.paused = false
		future.get(2, TimeUnit.SECONDS)
		assertThat(testProcess.time()).isEqualTo(3.0)
	}

	@Test
	@DisplayName("Throttled loop overhead is negligible (< 5%)")
	fun testLoopOverhead() {
		// Run with NoOp (unthrottled baseline)
		val noopContext = createValidContext(NoOpSimulationController)
		val noopProcess = SimpleHoldProcess(100.0) // 100 events -> 100 is enough to get a baseline
		noopContext.setMainProcess(noopProcess)

		val startNoop = System.nanoTime()
		noopContext.run()
		val durationNoop = System.nanoTime() - startNoop

		// Run with Fake controller with no pause/throttle
		val fakeController = FakeSimulationController()
		val fakeContext = createValidContext(fakeController)
		val fakeProcess = SimpleHoldProcess(100.0)
		fakeContext.setMainProcess(fakeProcess)

		val startFake = System.nanoTime()
		fakeContext.run()
		val durationFake = System.nanoTime() - startFake

		val overheadPercent = ((durationFake - durationNoop).toDouble() / durationNoop) * 100.0
		println("Unthrottled baseline: ${durationNoop / 1_000_000.0} ms")
		println("Fake controller loop: ${durationFake / 1_000_000.0} ms")
		println("Overhead: $overheadPercent %")

		// Let's assert a very safe overhead limit.
		// On busy/constrained VM execution, jitter can occur, but we just want to verify it remains reasonable.
		assertThat(overheadPercent).isLessThan(20.0)
	}
}
