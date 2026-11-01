/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1269): exact wait-for edges from the blocked-by result.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import kotlin.test.Test

/**
 * Issue #1269: the watchdog's wait-for edges come from what actually refused a waiting train, so a
 * STARTED train that keeps a waiter out appears as an edge even when it holds no block of the
 * waiter's candidate routes.
 *
 * On the y-junction `Entry -[trackEntry]- Junction -[trackMain]- ExitMain` (branch
 * `Junction -[trackBranch]- ExitBranch`) the first train runs `Entry -> ExitMain` and the second
 * waits for `Entry -> ExitBranch`. Once the first train's tail has cleared trackEntry it holds only
 * trackMain -- no block of the branch route -- but its claim on Junction (main position) still
 * refuses the second train. The candidate-route builder before Issue #1269 saw no edge in that window.
 */
class WaitForEdgesFromOwnersTest : CommonKoinTestBase() {
	private fun runScenario() = SwitchClaimWaitScenario(TestTopologies.yJunctionWithSwitchSimulation().tracked())

	@Test
	fun branchTrainIsAttempted() {
		assertThat(runScenario().waiterWasAttempted, name = "the branch train was attempted").isTrue()
	}

	@Test
	fun branchTrainIsGrantedInTheEnd() {
		assertThat(runScenario().grantedAt, name = "the branch train was granted in the end").isNotNull()
	}

	@Test
	fun onlyTheSwitchClaimKeepsTheWaiterOutInTheWindow() {
		assertThat(runScenario().window, name = "samples where only the switch claim keeps the waiter out")
			.isNotEmpty()
	}

	@Test
	fun everyWindowSampleHasTheEdgeFromWaiterToMainTrain() {
		val scenario = runScenario()
		val withoutEdge =
			scenario.window
				.filter { scenario.mainTrain !in it.edges[scenario.waiter].orEmpty() }
				.map { it.time }
		assertThat(withoutEdge, name = "window samples without the edge ${scenario.waiter} -> ${scenario.mainTrain}")
			.isEmpty()
	}
}
