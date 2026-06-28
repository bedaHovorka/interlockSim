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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.io.InputStream
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Goal 1 SP4: deterministic three-train prototype on the built-in shunting loop.
 */
@Tag("integration-test")
@DisplayName("ThreeTrainLoop — three-train prototype on vyhybna.xml (Goal 1 SP4)")
class ThreeTrainLoopTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	private fun loadVyhybnaContext(): DefaultSimulationContext {
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")

		val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
		val simCtx = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		simCtx.getInOuts()
		context = simCtx
		return simCtx
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `three trains on vyhybna all enter and exit`() {
		val ctx = loadVyhybnaContext()
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
