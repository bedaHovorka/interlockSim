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
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Issue #1066 review round (PR #1082): the `MultiTrainLoop` branch for
 * [PathReservationService.ReservationResult.DivergesFromHeldRoute].
 *
 * The branch is not expected in production (the train has no stored route when it first reserves
 * its entry path), so it is driven with a reservation service that always answers with the
 * divergent verdict. The loop must log a WARN naming the train and the reason, and never complete the
 * refused train's journey.
 */
@Tag("integration-test")
@DisplayName("MultiTrainLoop logs a divergent entry reservation and moves on (Issue #1066)")
class MultiTrainLoopDivergesFromHeldRouteTest : KoinTestBase() {
	private val rootLogger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var originalLevel: Level? = null
	private var context: DefaultSimulationContext? = null

	@BeforeEach
	fun setUp() {
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		originalLevel = rootLogger.level
		rootLogger.level = Level.DEBUG
		rootLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		rootLogger.detachAppender(appender)
		rootLogger.level = originalLevel
		context?.close()
		context = null
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a DivergesFromHeldRoute entry reservation is logged as WARN and the train never exits")
	fun divergentEntryReservationIsLoggedAndSkipped() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true)
		ctx.getInOuts()
		context = ctx
		val real = ctx.getRoutingServices().getPathReservationService()
		val reason = "non-contiguous merge for train under test"
		val divergent =
			object : PathReservationService by real {
				override fun reservePath(
					trainId: String,
					start: DynamicPathSeparator,
					target: DynamicPathSeparator,
					maxDepth: Int
				): PathReservationService.ReservationResult =
					PathReservationService.ReservationResult.DivergesFromHeldRoute("held", reason)
			}

		val loop =
			MultiTrainLoop(
				ctx,
				endTime = 40L,
				trainSpecs = listOf(MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = 0.0, length = 20.0)),
				pathReservationService = divergent
			)
		ctx.setMainProcess(loop)
		ctx.run()

		val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
		assertThat(
			warnings.any { "route diverges from the held route" in it && reason in it },
			"expected the divergent WARN, but WARN lines were: $warnings"
		).isTrue()
		assertThat(loop.getTrainsExited()).isEqualTo(0)
	}
}
