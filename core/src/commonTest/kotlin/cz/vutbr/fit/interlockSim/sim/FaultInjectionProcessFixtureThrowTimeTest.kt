/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Simulation
import cz.vutbr.fit.interlockSim.testutil.FaultInjectionProcessFixture
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test

/**
 * The fault-injection fixture throws at the configured simulated time on every target (Issue #1451).
 * Runs in `:core`'s commonTest, so `linuxX64Test` runs it natively; `:core-test` has no commonTest.
 * The JVM-only `FaultInjectionProcessFixtureTest` in `:core-test` pins the escape and the other
 * processes. The run outcome of the failure is not pinned here (Issue #1112).
 *
 * The fixture throws a [CancellationException] here: on Kotlin/Native an uncaught process exception
 * ends the whole test process (there is no thread `UncaughtExceptionHandler` to record it), whereas
 * a cancellation is process control that kDisco absorbs. The throw time is what is pinned.
 *
 * The delayed-activation case pins the absolute semantics: kDisco's `hold` takes a duration, so the
 * fixture holds only the remaining stretch from its activation to `failAt`. An activation after the
 * target cannot be pinned here (an uncaught process exception would end the whole Native test
 * process); the JVM suite records that rejection instead.
 */
class FaultInjectionProcessFixtureThrowTimeTest {
	@Test
	fun `the fixture throws at the configured time exactly`() {
		val fixture =
			FaultInjectionProcessFixture(
				failAt = 4.5,
				throwable = CancellationException("fault injected by the test")
			)
		val bystander =
			object : Process() {
				override suspend fun actions() {
					hold(10.0)
				}
			}

		val sim =
			Simulation.create {
				Process.activate(fixture)
				Process.activate(bystander)
			}
		assertThat(fixture.thrownAtTime).isNull()
		runBlocking { sim.run(20.0) }

		assertThat(fixture.thrownAtTime).isEqualTo(4.5)
	}

	@Test
	fun `the fixture throws at the configured time when its activation was delayed`() {
		val fixture =
			FaultInjectionProcessFixture(
				failAt = 4.0,
				throwable = CancellationException("fault injected by the test")
			)
		val bystander =
			object : Process() {
				override suspend fun actions() {
					hold(10.0)
				}
			}

		val sim =
			Simulation.create {
				Process.activate(fixture, delay = 2.0)
				Process.activate(bystander)
			}
		runBlocking { sim.run(20.0) }

		assertThat(fixture.thrownAtTime).isEqualTo(4.0)
	}
}
