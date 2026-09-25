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
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import java.util.stream.Stream

/**
 * Issue #1066 review round (PR #1082): every [RouteRequestResult] branch of the tool-driven
 * [DispatchDecision.RequestRoute] arm must produce its own log line. The sealed hierarchy
 * gained [RouteRequestResult.DivergesFromHeldRoute] in this PR, and Sonar's new-code report
 * showed none of the `when` branches in `requestRouteAndLog` were exercised by tests at all —
 * only indirectly through integration runs that do not assert on log output.
 *
 * Each case feeds one result subtype through a mocked [NetworkActuatorPort] and asserts the
 * distinctive fragment of that branch's message, so a branch that silently stops logging (or
 * logs a fragment that a caller greps for) fails here. The sim test config keeps the root
 * logger at WARN, so this test attaches a [ListAppender] to the root logger and raises its
 * level itself — the same pattern as [ToolDrivenDecisionsReleaseRouteTest].
 */
@DisplayName("applyToolDrivenToActuator logs every RouteRequestResult branch (Issue #1066 review round)")
class ToolDrivenDecisionsRequestRouteTest {
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

	/** Applies a RequestRoute whose actuator answers with [result]; returns the actuator. */
	private fun applyWith(result: RouteRequestResult): NetworkActuatorPort {
		val actuator =
			mockk<NetworkActuatorPort>().also {
				every { it.requestRoute("T-1082", "zA", "doB1") } returns result
			}
		DispatchDecision
			.RequestRoute("T-1082", "zA", "doB1")
			.applyToolDrivenToActuator(actuator, "unit-test")
		return actuator
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("resultsAndExpectedFragments")
	fun `every result branch produces its own log line`(
		result: RouteRequestResult,
		expectedFragment: String
	) {
		val actuator = applyWith(result)

		val messages = appender.list.map { it.formattedMessage }
		assertThat(
			messages.any { expectedFragment in it },
			"expected a log line containing \"$expectedFragment\""
		).isTrue()
		verify(exactly = 1) { actuator.requestRoute("T-1082", "zA", "doB1") }
	}

	companion object {
		/**
		 * One row per [RouteRequestResult] subtype: the result the actuator returns, and a
		 * fragment only that branch's log line contains. Kept in the same order as the
		 * `when` in `requestRouteAndLog` so a missing row is easy to spot in review.
		 */
		@JvmStatic
		fun resultsAndExpectedFragments(): Stream<Arguments> =
			Stream.of(
				Arguments.of(RouteRequestResult.Reserved("T-1082", 3), "reserved 3 block(s) for T-1082"),
				Arguments.of(
					RouteRequestResult.AllPathsBlocked(2),
					"all paths blocked for T-1082 (zA → doB1); attempted: 2"
				),
				Arguments.of(
					RouteRequestResult.Conflict("k1", "other-train"),
					"conflict for T-1082 — block 'k1' owned by 'other-train'"
				),
				Arguments.of(RouteRequestResult.NoRouteExists("zA", "doB1"), "no route exists zA → doB1 for T-1082"),
				Arguments.of(
					RouteRequestResult.OriginNotContiguous("zA", "zA bounds no block the train holds"),
					"origin not contiguous for T-1082 — zA bounds no block the train holds"
				),
				Arguments.of(
					RouteRequestResult.ConditionFailed("semaphore zA faces away", retryable = false),
					"four-condition refusal for T-1082 (permanent): semaphore zA faces away"
				),
				Arguments.of(
					RouteRequestResult.GeometricallyImpossible("start signal zA faces away"),
					"geometrically impossible for T-1082 — start signal zA faces away"
				),
				Arguments.of(
					RouteRequestResult.DivergesFromHeldRoute("doB2", "new path starts at zA but the stored path ends at doB2"),
					"diverges from the held route for T-1082 — new path starts at zA but the stored path ends at doB2"
				)
			)
	}
}
