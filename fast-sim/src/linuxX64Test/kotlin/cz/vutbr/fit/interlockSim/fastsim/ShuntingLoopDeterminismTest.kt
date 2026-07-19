/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Native-side determinism check for ShuntingLoop at endTime=1024, mirroring the
 * exact `fast-sim example shuntingLoop 1024` CLI path (NativeExampleRegistry).
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Repeated-run determinism check for `NativeExampleRegistry`'s `shuntingLoop` construction
 * at endTime=1024, exercised via `:fast-sim:linuxX64Test`'s **debug** test binary
 * (`linkDebugTestLinuxX64` — the standard Kotlin/Native test task, not the release CLI
 * binary).
 *
 * kotlin.test on linuxX64 has no JUnit5-style `@Tag`/`@RepeatedTest`, so this stays a
 * modest, always-on part of `:fast-sim:linuxX64Test` (already run in CI) rather than a
 * 1000-repetition heavy test like [cz.vutbr.fit.interlockSim.sim.ShuntingLoopHeavyTest]
 * (JVM-only, `:core:heavyTest`, manual-run).
 *
 * **Expected counts (28 entered / 14 exited) are cross-platform identical** and must
 * match [cz.vutbr.fit.interlockSim.sim.ShuntingLoopHeavyTest] on the JVM as well as the
 * *release* `fast-sim.kexe` binary (`linkReleaseExecutableLinuxX64`, what
 * `docker compose run fast-sim` / the CLI actually runs). kDisco's
 * `Random.exp()`/`.normal()` historically diverged between JVM, native-debug, and
 * native-release builds because they routed the (bit-identical) raw uniform draws
 * through platform-delegating `kotlin.math.ln`/`exp`; that was fixed upstream by a
 * pure-Kotlin fdlibm `PortableMath` implementation:
 * https://github.com/bedaHovorka/kdisco/issues/69.
 */
class ShuntingLoopDeterminismTest {
	private companion object {
		const val END_TIME: Long = 1024L
		const val REPETITIONS: Int = 20
		const val EXPECTED_TRAINS_ENTERED: Int = 28
		const val EXPECTED_TRAINS_EXITED: Int = 14

		/**
		 * Per-iteration wall-clock budget. kotlin.test on linuxX64 has no `@Timeout`
		 * equivalent, so this is checked *after* each iteration returns: a
		 * slow-but-finishing run fails with a diagnosable message instead of an opaque CI
		 * step timeout. A hard-hung iteration is still only caught by the surrounding
		 * Gradle/CI step timeout.
		 *
		 * The budget is deliberately generous (an unloaded run takes ~5 s): it targets
		 * deadlock-like stalls, not performance regressions, and must tolerate heavy CPU
		 * contention when the whole multi-project build runs tests in parallel
		 * (observed >60 s per iteration under `./gradlew clean build integrationTest`).
		 */
		val PER_RUN_TIMEOUT = 120.seconds
	}

	private var previousLogLevel: Level = KotlinLoggingConfiguration.logLevel

	@BeforeTest
	fun setUp() {
		// Silence per-event INFO logging (kotlin-logging native defaults to INFO -> stdout).
		// 20 repetitions x 1024s of simulation otherwise flood Gradle's test-output reader
		// with enough stdout to exhaust its heap (observed as OutOfMemoryError in CI).
		previousLogLevel = KotlinLoggingConfiguration.logLevel
		KotlinLoggingConfiguration.logLevel = Level.WARN
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
		KotlinLoggingConfiguration.logLevel = previousLogLevel
	}

	@Test
	fun `NativeExampleRegistry shuntingLoop at 1024s is deterministic across repeated runs`() {
		repeat(REPETITIONS) { iteration ->
			val start = TimeSource.Monotonic.markNow()
			val factory = NativeContextFactory()
			NativeExampleRegistry.create("shuntingLoop", END_TIME, factory).use { ctx ->
				ctx.run()
				val elapsed = start.elapsedNow()
				if (elapsed > PER_RUN_TIMEOUT) {
					fail(
						"iteration $iteration exceeded $PER_RUN_TIMEOUT wall-clock " +
							"($elapsed) — possible performance regression or near-deadlock"
					)
				}
				val loop = ctx.getMainProcess() as ShuntingLoop

				assertEquals(
					EXPECTED_TRAINS_ENTERED,
					loop.getTrainsEntered(),
					"trains entered (iteration $iteration)"
				)
				assertEquals(
					EXPECTED_TRAINS_EXITED,
					loop.getTrainsExited(),
					"trains exited (iteration $iteration)"
				)
			}
		}
	}
}
