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
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Manual soak of [Issue1025CommittedTrainReleaseTest]: the same committed-train window scenario,
 * repeated. A single run is a weak witness for a race, so this repeats it under the `heavy-test`
 * tag, which `test`, `integrationTest` and `build` all exclude. Run it by hand with
 * `./gradlew :dispatcher-agent:heavyTest` after a change to the sweeper, the partial releaser, or
 * the rollback paths of `DefaultPathReservationService`.
 *
 * The scenario and its assertions live once in [Issue1025CommittedTrainReleaseScenario], so this
 * soak cannot drift from the light test.
 */
@DisplayName("Issue #1025 — committed-train release soak (manual)")
@Tag("heavy-test")
@Timeout(value = 60, unit = TimeUnit.MINUTES)
class Issue1025CommittedTrainReleaseHeavyTest : DispatcherKoinTestBase() {
	@RepeatedTest(REPETITIONS)
	fun `a release inside the hold window defers, frees nothing, and the train books the block, repeatedly`() =
		Issue1025CommittedTrainReleaseScenario.runOnceAndAssert(
			TestFixtures.newShuntingSimulationContext().tracked()
		)

	private companion object {
		const val REPETITIONS = 10
	}
}
