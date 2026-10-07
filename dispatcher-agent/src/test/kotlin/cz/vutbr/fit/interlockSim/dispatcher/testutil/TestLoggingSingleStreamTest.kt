/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import assertk.assertThat
import assertk.assertions.isEmpty
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ConsoleAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Tripwire (Issue #1011): test logging of this module must stay on a single console stream.
 *
 * Gradle drains a test JVM's stdout and stderr with two separate forwarding threads, and
 * concurrent forwarding corrupts its binary test-result index
 * ([gradle/gradle#33990](https://github.com/gradle/gradle/issues/33990)). That surfaces as
 * "Could not write XML test results for ... to file ..." on a run where zero tests failed —
 * and the JUnit XML is the artifact the gate's pass/fail tally is read from. A console
 * appender on `System.err` re-introduces the two-stream pattern, so this test fails if one
 * reappears in `logback-test.xml`.
 */
class TestLoggingSingleStreamTest {
	@Test
	fun `no console appender writes to System err`() {
		val context = LoggerFactory.getILoggerFactory() as LoggerContext

		val stderrAppenders =
			context.loggerList
				.flatMap { logger -> logger.iteratorForAppenders().asSequence().toList() }
				.filterIsInstance<ConsoleAppender<*>>()
				.filter { appender -> appender.target == "System.err" }
				.map { appender -> appender.name }

		assertThat(stderrAppenders).isEmpty()
	}
}
