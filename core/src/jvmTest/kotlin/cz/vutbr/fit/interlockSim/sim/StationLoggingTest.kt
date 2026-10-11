/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The derived station is logged with block names and unique block ids, in English
 * (Goal 1B SP2.2, Issue #1162).
 */
class StationLoggingTest : KoinTestBase() {
	private val stationLogger = LoggerFactory.getLogger(Station::class.qualifiedName) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var previousLevel: Level? = null

	@BeforeEach
	fun attachAppender() {
		previousLevel = stationLogger.level
		stationLogger.level = Level.INFO
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		stationLogger.addAppender(appender)
	}

	@AfterEach
	fun detachAppender() {
		stationLogger.detachAppender(appender)
		stationLogger.level = previousLevel
	}

	@Test
	fun derivedVyhybnaStationIsLoggedWithBlockNamesAndIds() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()

		Station.from(context)

		val lines = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
		assertThat(lines).hasSize(1)
		val line = lines.single()
		assertThat(line).contains("capacity 2")
		assertThat(line).contains("k1 (16,8)-(25,8)")
		assertThat(line).contains("k2 (17,9)-(24,9)")
		assertThat(line).contains("kB (27,8)-(30,8)")
		assertThat(line).contains("kA (11,8)-(14,8)")
		assertThat(line).contains("home signal zA")
		assertThat(line).contains("signals doA1, doB1")
	}
}
