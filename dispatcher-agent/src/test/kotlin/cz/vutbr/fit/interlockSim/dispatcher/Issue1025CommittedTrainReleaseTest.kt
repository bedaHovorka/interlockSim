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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Runs [Issue1025CommittedTrainReleaseScenario] once, as the CI-facing integration test. The
 * mechanism, the arrangement and the assertions are documented on the scenario; the manual soak
 * that repeats the same scenario is [Issue1025CommittedTrainReleaseHeavyTest].
 */
@DisplayName("Issue #1025 — a release inside the hold(1.0) window must not free a committed train's block")
@Tag("integration-test")
class Issue1025CommittedTrainReleaseTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 3, unit = TimeUnit.MINUTES)
	fun `releasing the next block of a train committed at a proceed aspect frees nothing and the train books it`() =
		Issue1025CommittedTrainReleaseScenario.runOnceAndAssert(
			TestFixtures.newShuntingSimulationContext().tracked()
		)
}
