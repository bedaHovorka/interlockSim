/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Simulation Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Issue #1066 review round (PR #1082), reviewer recommendation 4: pins the premise behind the
 * `InOutWorker` `DivergesFromHeldRoute` branch.
 *
 * The branch's comment says it is "unreachable by construction": a train at the head of an InOut
 * admission queue has no stored PathInfo, so nothing can diverge. If that reasoning is ever wrong,
 * the branch must fail loudly (an `APPROVAL_ERROR` log plus `env.errorStop`), because `continue`
 * would re-attempt the same refused request without advancing simulation time.
 *
 * This test breaks the premise on purpose: it seeds a stored PathInfo (ending at `zB`) for each
 * queued train before the dispatcher approves it. The worker's `reservePathToAnyNextSemaphore`
 * from the entry InOut then diverges (`start != old.target`), and the run must stop with the
 * worker's error line rather than spin.
 */
@Tag("integration-test")
@DisplayName("InOutWorker fails loudly when a queued train already holds a route (Issue #1066)")
class InOutWorkerDivergesFromHeldRouteTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private val rootLogger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var originalLevel: Level? = null
	private lateinit var context: DefaultSimulationContext

	@BeforeEach
	fun setUp() {
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		originalLevel = rootLogger.level
		rootLogger.level = Level.DEBUG
		rootLogger.addAppender(appender)
		context = TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
	}

	@AfterEach
	fun tearDown() {
		rootLogger.detachAppender(appender)
		rootLogger.level = originalLevel
		context.close()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a queued train holding a stored route stops the run with the worker's APPROVAL_ERROR")
	fun queuedTrainWithStoredRouteStopsTheRun() {
		val registry = context.scope.get<PathReservationRegistry>()
		val zB = context.separatorAt(27, 8)
		val seeded = mutableSetOf<String>()

		val loop = prepareShuntingLoop(context, endTime = 120L)
		val wired = loop.controlStepListener ?: error("prepareShuntingLoop did not install a listener")
		loop.controlStepListener =
			ControlStepListener {
				// Break the "no stored PathInfo at the head of the queue" premise BEFORE the
				// dispatcher approves the train. The first registration for a trainId stores
				// directly (no merge), so nothing is reserved -- it only makes every candidate
				// start elsewhere than the stored target.
				loop.getQueuedTrains().filter { seeded.add(it.trainId) }.forEach {
					registry.registerPathInfo(
						it.trainId,
						PathInfo(
							start = zB,
							target = zB,
							reservedPath = ArrayPath(context).apply { add(zB) },
							entryDirections = emptyMap()
						)
					)
				}
				wired.onControlStep()
			}

		context.run()

		val errors =
			appender.list
				.filter { it.level == Level.ERROR }
				.map { it.formattedMessage }
		assertThat(seeded.isNotEmpty(), "a train must have been queued and seeded").isTrue()
		assertThat(
			errors.any { "APPROVAL_ERROR" in it && "diverges from the route already held" in it },
			"the worker must fail loudly, but ERROR lines were: $errors"
		).isTrue()
		// errorStop ended the run early: no train got through the network.
		assertThat(loop.getTrainsExited() == 0, "no train may complete after the stop").isTrue()
	}
}
