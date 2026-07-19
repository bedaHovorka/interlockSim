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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
 * **Expected counts (28 entered / 14 exited) intentionally match the JVM heavy test's
 * numbers exactly** — verified empirically, not a coincidence expected to hold in
 * general. The *release* `fast-sim.kexe` binary (`linkReleaseExecutableLinuxX64`, what
 * `docker compose run fast-sim` / the CLI actually runs) deterministically produces a
 * **different** outcome instead (16 entered / 14 exited) for the identical seed and
 * construction path. Root cause: kDisco's `Random.exp()`/`.normal()` route the
 * (bit-identical) raw uniform draws through `kotlin.math.ln`/`exp`, which are not
 * required to be bit-identical by IEEE-754/the Java Math spec across JVM vs
 * debug-vs-release Kotlin/Native codegen — amplified by kDisco's discrete-event
 * scheduling into a different train count over a long run. This test intentionally
 * covers only the debug-binary path (the only one `linuxX64Test` can exercise); the
 * release-binary divergence is a further, separately-observed instance of the same
 * class of bug. Tracked upstream: https://github.com/bedaHovorka/kdisco/issues/69.
 */
class ShuntingLoopDeterminismTest {
	private companion object {
		const val END_TIME: Long = 1024L
		const val REPETITIONS: Int = 20
		const val EXPECTED_TRAINS_ENTERED: Int = 28
		const val EXPECTED_TRAINS_EXITED: Int = 14
	}

	@BeforeTest
	fun setUp() {
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	@Test
	fun `NativeExampleRegistry shuntingLoop at 1024s is deterministic across repeated runs`() {
		repeat(REPETITIONS) { iteration ->
			val factory = NativeContextFactory()
			NativeExampleRegistry.create("shuntingLoop", END_TIME, factory).use { ctx ->
				ctx.run()
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
