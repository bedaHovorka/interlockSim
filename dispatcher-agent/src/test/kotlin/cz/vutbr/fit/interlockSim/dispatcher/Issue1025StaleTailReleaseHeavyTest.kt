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

import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.StaleTailReclaimHarness
import cz.vutbr.fit.interlockSim.dispatcher.testutil.assertHealthyReclaim
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Manual soak of [Issue1025StaleTailReleaseTest]: the same 300 s stale-tail reclaim run, repeated.
 *
 * A single run is a weak witness for a race, so this repeats it under the `heavy-test` tag,
 * which `test`, `integrationTest` and `build` all exclude. Run it by hand with
 * `./gradlew :dispatcher-agent:heavyTest` after a change to the sweeper, the partial releaser, or
 * the rollback paths of `DefaultPathReservationService`.
 */
@DisplayName("Issue #1025 — stale-tail reclaim soak (manual)")
@Tag("heavy-test")
@Timeout(value = 60, unit = TimeUnit.MINUTES)
class Issue1025StaleTailReleaseHeavyTest : DispatcherKoinTestBase() {
	@RepeatedTest(REPETITIONS)
	fun `a reclaimed stale tail leaves the simulation thread alive, repeatedly`() {
		val outcome =
			StaleTailReclaimHarness.run(
				context = TestFixtures.newShuntingSimulationContext().tracked(),
				simEndTime = Issue1025StaleTailReleaseTest.SIM_END_TIME,
				staleAfterSimSeconds = Issue1025StaleTailReleaseTest.AGGRESSIVE_STALE_SECONDS
			)

		outcome.assertHealthyReclaim()
	}

	private companion object {
		const val REPETITIONS = 10
	}
}
