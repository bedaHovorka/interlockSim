/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1062: the start signal of a route that begins at a signal must be configured.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * A route that STARTS at a signal must turn that signal to proceed and must not log a
 * `SEMAPHORE_CONFIG_WARNING` (Issue #1062). Before the fix `configureSemaphoreSignal` asked the
 * route's first block for the speed limit as seen from the start signal, which the block rejected
 * ("Path separator must be an end of this track") whenever the signal is not an end of that block,
 * so the signal kept STOP and the train waited at a red signal with a valid route.
 */
@Tag("integration-test")
class StartSignalOfRouteFromSignalTest : PathReservationServiceTestBase() {
	private lateinit var appender: ListAppender<ILoggingEvent>

	@BeforeEach
	fun attachAppender() {
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(appender)
	}

	@AfterEach
	fun detachAppender() {
		(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(appender)
	}

	private fun configWarnings() = appender.list.map { it.formattedMessage }.filter { "SEMAPHORE_CONFIG_WARNING" in it }

	@Test
	fun `a red start signal turns proceed when its route is granted from it`() {
		val zB = findSemaphoreByName("zB")
		val doA1 = findSemaphoreByName("doA1")
		assertThat(zB.signal).isEqualTo(Signal.STOP)

		val result = service.reservePath("startTrain", zB, doA1)

		assertThat(result)
			.withMessage("the route zB -> doA1 must be granted")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(zB.signal.isAllowing()).withMessage("the start signal zB must turn proceed").isTrue()
		assertThat(configWarnings()).isEmpty()
	}

	@Test
	fun `a red start signal turns proceed when the route is extended from it`() {
		val zA = findSemaphoreByName("zA")
		val first = service.reservePath("extTrain", zA, findSemaphoreByName("doB1"))
		assertThat(first).isInstanceOf<PathReservationService.ReservationResult.Success>()
		zA.signal = Signal.STOP
		appender.list.clear()

		val extension = service.reservePath("extTrain", zA, inOutNamed("B"))

		assertThat(extension)
			.withMessage("the extension zA -> B must be granted")
			.isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(zA.signal.isAllowing()).withMessage("the start signal zA must turn proceed").isTrue()
		assertThat(configWarnings()).isEmpty()
	}
}
