/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Performance Benchmarks
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.benchmarks

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Measures the cost of the controlled simulation loop in [DefaultSimulationContext.run].
 *
 * `run(controller)` invokes [SimulationController.throttle] and
 * [SimulationController.awaitIfPaused] from the `beforeEvent` hook on every simulation
 * event.  The question this benchmark answers is: how much does that hook cost when the
 * controller does nothing at all?
 *
 * Two arms, reported independently rather than as a ratio:
 *
 * - [baselineNoOpController] passes [NoOpSimulationController], the `object` singleton.
 *   Being a singleton it is monomorphic at the call site, so the JIT can devirtualize and
 *   inline the empty bodies away.
 * - [withBenchmarkController] passes a fresh [BenchmarkController], a distinct class whose
 *   method bodies are equally empty.
 *
 * The delta between the two arms is therefore the real dispatch/bookkeeping cost of the
 * hook, and it is exactly the asymmetry that the JUnit test which used to live in
 * `DefaultSimulationContextControllerTest` was silently measuring.
 *
 * ## Why this is a benchmark and not a test
 *
 * This measurement used to be a JUnit assertion (`controlledLoopOverheadIsNegligible`),
 * timing one run of each arm with [System.nanoTime] and asserting the wall-clock ratio was
 * below 5%.  That cannot work on a shared CI runner: a single sample per arm, no JIT
 * warmup — the baseline arm ran first and absorbed it, biasing the ratio negative — and
 * runner CPU-steal variance far exceeding the 5% threshold.  It failed CI at 5.33%.
 * JMH supplies the forks, warmup and measurement iterations that make the number mean
 * something.  There is no threshold assertion here; read the numbers.
 *
 * ## Why SingleShotTime
 *
 * The payload is one whole simulation run against a single-use context, so each invocation
 * needs a freshly built [DefaultSimulationContext]. Under [Mode.AverageTime] JMH drives tens
 * of thousands of invocations per iteration, and that context churn hits a hard limit:
 * [DefaultSimulationContext] derives its Koin scope id from an identity hash code
 * (`platformIdentityCode(this)`), which is not unique, so at that volume ids collide and Koin
 * throws `ScopeAlreadyCreatedException`. [Mode.SingleShotTime] measures one full run per
 * invocation and keeps the number of contexts in the hundreds, which is both the honest shape
 * for a millisecond-scale payload and well clear of the collision threshold.
 *
 * Run with:
 * ```
 * ./gradlew :desktop-ui:jmh -Pjmh.includes='ControlledLoopOverhead'
 * ```
 *
 * @see DefaultSimulationContext.run
 * @see NoOpSimulationController
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10)
@Measurement(iterations = 20)
@Fork(value = 3)
open class ControlledLoopOverheadBenchmark {
	/**
	 * Simulation end time, in simulated seconds.
	 *
	 * The retired JUnit test used 300s.  60s is used here to keep total benchmark
	 * wall-time reasonable across 3 forks x (3 warmup + 5 measurement) iterations; the
	 * per-event hook cost this benchmark isolates does not depend on the horizon.
	 */
	private val simulationEndTime: Long = 60L

	@Setup(Level.Trial)
	fun startDi() {
		stopKoin() // Clean slate — a previous trial in this JVM may have left Koin running.
		startKoin { modules(interlockSimModule) }
	}

	@TearDown(Level.Trial)
	fun stopDi() {
		stopKoin()
	}

	/**
	 * Railway network XML stream. Uses vyhybna.xml (shunting loop), the standard test network.
	 */
	private fun railwayNetworkXml(): InputStream =
		javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			?: error("Railway network XML not found on the benchmark classpath")

	/**
	 * The context under measurement, rebuilt before every invocation.
	 *
	 * A [DefaultSimulationContext] is single-use — [DefaultSimulationContext.run] consumes it —
	 * so each invocation needs a fresh one. It is built in [prepareContext] rather than inside
	 * the `@Benchmark` body so that XML parsing and context construction, which cost far more
	 * than the per-event hook under test, stay *outside* the timed region. JMH excludes
	 * [Level.Invocation] fixture time from the measurement.
	 */
	private var context: DefaultSimulationContext? = null

	@Setup(Level.Invocation)
	fun prepareContext() {
		val factory =
			KoinJavaComponent.get<SimulationContextFactory>(SimulationContextFactory::class.java)
		val ctx = railwayNetworkXml().use { factory.createContext(it) } as DefaultSimulationContext
		ctx.getInOuts()
		ctx.setMainProcess(ShuntingLoop(ctx, simulationEndTime))
		context = ctx
	}

	@TearDown(Level.Invocation)
	fun releaseContext() {
		context?.close()
		context = null
	}

	/**
	 * A [SimulationController] that does nothing, as a distinct class rather than a singleton.
	 *
	 * Deliberately not an `object`: the point of the second arm is to present the call site
	 * with an implementation the JIT cannot assume is the only one.
	 */
	private class BenchmarkController : SimulationController {
		override suspend fun awaitIfPaused() {
			// No-op: never paused.
		}

		override fun throttle(simDeltaSeconds: Double) {
			// No-op: no wall-clock pacing.
		}

		override fun isPaused(): Boolean = false

		override fun pollStepEvent(): Boolean = false

		override fun pollStepTime(): Double? = null

		override fun requestPause() {
			// No-op: benchmark runs headless.
		}

		override fun requestResume() {
			// No-op: benchmark runs headless.
		}

		override fun currentSpeedMultiplier(): Double = 1.0
	}

	/**
	 * Baseline: the controlled loop driven by the [NoOpSimulationController] singleton.
	 */
	@Benchmark
	fun baselineNoOpController(blackhole: Blackhole) {
		val ctx = requireNotNull(context) { "prepareContext() must run before the benchmark body" }
		ctx.run(NoOpSimulationController)
		blackhole.consume(ctx)
	}

	/**
	 * The controlled loop driven by a non-singleton no-op controller.
	 *
	 * The difference against [baselineNoOpController] is the hook's dispatch and bookkeeping
	 * cost per simulation event.
	 */
	@Benchmark
	fun withBenchmarkController(blackhole: Blackhole) {
		val ctx = requireNotNull(context) { "prepareContext() must run before the benchmark body" }
		ctx.run(BenchmarkController())
		blackhole.consume(ctx)
	}
}
