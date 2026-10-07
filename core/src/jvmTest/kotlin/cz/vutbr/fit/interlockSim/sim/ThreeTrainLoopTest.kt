/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1 SP4: ThreeTrainLoop prototype scenario tests (Issue #584).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Goal 1 SP4: deterministic three-train prototype on the built-in shunting loop.
 */
@Tag("integration-test")
@DisplayName("ThreeTrainLoop — three-train prototype on vyhybna.xml (Goal 1 SP4)")
class ThreeTrainLoopTest : KoinTestBase() {
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	fun `three trains on vyhybna all enter and exit`() {
		val ctx = loadVyhybnaContext(warmUpDynamicWrappers = true).tracked()
		val process = ThreeTrainLoop(ctx, endTime = 400L)
		ctx.setMainProcess(process)
		ctx.run()

		logger.info {
			"ThreeTrainLoop metrics: entered=${process.getTrainsEntered()} " +
				"exited=${process.getTrainsExited()} " +
				"maxConc=${process.getMaxConcurrentTrains()}"
		}

		assertThat(process.getTrainsEntered()).isEqualTo(3)
		assertThat(process.getTrainsExited()).isEqualTo(3)
		assertThat(process.getMaxConcurrentTrains()).isGreaterThanOrEqualTo(2)
		assertThat(process.getOccupiedResourceCount()).isZero()
	}
}
