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
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRelease
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Issue #1050 review round: the tool-driven [DispatchDecision.ReleaseRoute] arm must go through
 * [NetworkActuatorPort.releaseRouteDetailed] and surface a deferred (partial) release in its log,
 * so a partly-kept route is visible where the tool-driven path has no rendered outcome to read.
 *
 * The sim test config keeps the root logger at WARN, so this test attaches a [ListAppender]
 * to the root logger and raises its level itself — the same pattern as
 * `StartSignalOfRouteFromSignalTest`.
 */
@DisplayName("applyToolDrivenToActuator surfaces a deferred ReleaseRoute (Issue #1050 review round)")
class ToolDrivenDecisionsReleaseRouteTest {
	private val logger: Logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>

	// Restored in tearDown: a fixed level would leak into later test classes in the same JVM.
	private var originalLevel: Level? = null

	@BeforeEach
	fun setUp() {
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		originalLevel = logger.level
		logger.level = Level.DEBUG
		logger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		logger.detachAppender(appender)
		logger.level = originalLevel
	}

	private fun actuatorReturning(release: RouteRelease): NetworkActuatorPort =
		mockk<NetworkActuatorPort>().also { every { it.releaseRouteDetailed("T-087") } returns release }

	private fun applyDecision(actuator: NetworkActuatorPort) =
		DispatchDecision.ReleaseRoute("T-087").applyToolDrivenToActuator(actuator, "unit-test")

	@Test
	fun `a deferred release is logged as partly released with its kept blocks (Issue 1050)`() {
		val actuator = actuatorReturning(RouteRelease(anyReleased = true, deferredBlockIds = listOf("k1")))

		applyDecision(actuator)

		val messages = appender.list.map { it.formattedMessage }
		assertThat(
			messages.any { "only partly released" in it && "k1" in it },
			"the deferred release must be logged with its kept block"
		).isTrue()
		verify(exactly = 1) { actuator.releaseRouteDetailed("T-087") }
		verify(exactly = 0) { actuator.releaseRoute(any()) }
	}

	@Test
	fun `a release with nothing kept back logs no partial-release line (Issue 1050)`() {
		val actuator = actuatorReturning(RouteRelease(anyReleased = true, deferredBlockIds = emptyList()))

		applyDecision(actuator)

		val messages = appender.list.map { it.formattedMessage }
		assertThat(messages.none { "only partly released" in it }, "no partial-release line expected").isTrue()
	}

	@Test
	fun `a release of a train without a reservation is a logged no-op (Issue 1050)`() {
		val actuator = actuatorReturning(RouteRelease(anyReleased = false, deferredBlockIds = emptyList()))

		applyDecision(actuator)

		val messages = appender.list.map { it.formattedMessage }
		assertThat(
			messages.any { "held no reservation" in it },
			"the no-op must be logged for the tool-driven caller"
		).isTrue()
		assertThat(
			messages.none { "only partly released" in it },
			"an empty deferral must not be logged as partial"
		).isTrue()
	}
}
