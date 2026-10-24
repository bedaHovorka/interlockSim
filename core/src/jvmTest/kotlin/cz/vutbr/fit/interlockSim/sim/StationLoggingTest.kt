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
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.component.get
import org.slf4j.LoggerFactory

/**
 * The derived station is logged with block names and unique block ids, in English
 * (Goal 1B SP2.2, Issue #1162). The [ShuntingLoop] names its resolved station capacity
 * and warns when a scenario override exceeds the topology value.
 *
 * [LogTap] restores each logger's level on close even when a test fails, so a
 * leaked level or appender cannot poison the rest of the suite.
 */
class StationLoggingTest : KoinTestBase() {
	private val stationLogger = LoggerFactory.getLogger(Station::class.qualifiedName) as Logger
	private val loopLogger = LoggerFactory.getLogger(ShuntingLoop::class.qualifiedName) as Logger
	private lateinit var stationTap: LogTap
	private lateinit var loopTap: LogTap

	@BeforeEach
	fun attachAppenders() {
		stationTap = LogTap(stationLogger)
		loopTap = LogTap(loopLogger)
	}

	@AfterEach
	fun detachAppenders() {
		stationTap.close()
		loopTap.close()
	}

	@Test
	fun derivedVyhybnaStationIsLoggedWithBlockNamesAndIds() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()

		Station.from(context)

		val lines =
			stationTap.events.list
				.filter { it.level == Level.INFO }
				.map { it.formattedMessage }
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

	@Test
	fun prahaLogNamesTheEntryExitBlocksWithoutHomeSignal() {
		val context =
			CommonTestFixtures
				.parseSimulationContext(
					NetworkResources.PRAHA_HLAVNI_NADRAZI_XML,
					get()
				).tracked()
		context.getInOuts()

		Station.from(context)

		val lines =
			stationTap.events.list
				.filter { it.level == Level.INFO }
				.map { it.formattedMessage }
		assertThat(lines).hasSize(1)
		assertThat(lines.single()).contains("no home signal")
	}

	@Test
	fun shuntingLoopCapacityLogNamesTheTopologyValueByDefault() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()

		ShuntingLoop(context, endTime = 10L)

		val lines =
			loopTap.events.list
				.filter { it.level == Level.INFO }
				.map { it.formattedMessage }
		assertThat(lines.single { it.contains("station capacity") })
			.contains("ShuntingLoop: station capacity 2 (topology 2, scenario override none)")
	}

	@Test
	fun scenarioOverrideAboveTheTopologyCapacityIsWarnedAbout() {
		val context = TestFixtures.newShuntingSimulationContext().tracked()

		ShuntingLoop(context, endTime = 10L, maxConcurrentTrains = 3)

		val warns =
			loopTap.events.list
				.filter { it.level == Level.WARN }
				.map { it.formattedMessage }
		assertThat(warns).hasSize(1)
		assertThat(warns.single())
			.contains("ShuntingLoop: scenario override 3 exceeds the topology capacity 2; the override wins")
	}
}

/** Captures one logger's events for the class lifetime and restores its level on [close]. */
private class LogTap(
	logger: Logger
) : AutoCloseable {
	val events = ListAppender<ILoggingEvent>().also { it.start() }
	private val logger: Logger = logger
	private val previousLevel = logger.level

	init {
		logger.level = Level.INFO
		logger.addAppender(events)
	}

	override fun close() {
		logger.detachAppender(events)
		logger.level = previousLevel
	}
}
