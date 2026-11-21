/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Runs [body] with a Logback appender attached to the logger named [loggerName]; the appender is
 * detached in `finally`. `logback-test.xml` must keep that logger at the level the test reads.
 *
 * The one copy of this capture for `:core`'s JVM tests (Issue #1394): `PathReservationServiceTestBase`
 * and the fixture tests that extend it used to carry their own. It lives in `:core`'s `jvmTest`
 * `testutil` rather than in `:core-test`'s `jvmMain`, because only the former has Logback on its
 * compile classpath.
 */
fun withLogOf(
	loggerName: String,
	body: (ListAppender<ILoggingEvent>) -> Unit
) {
	val logger = LoggerFactory.getLogger(loggerName) as Logger
	val appender = ListAppender<ILoggingEvent>().also { it.start() }
	logger.addAppender(appender)
	try {
		body(appender)
	} finally {
		logger.detachAppender(appender)
	}
}

/** The formatted messages of the WARN events [this] captured, in capture order. */
fun ListAppender<ILoggingEvent>.warnMessages(): List<String> =
	list.filter { it.level == Level.WARN }.map { it.formattedMessage }
