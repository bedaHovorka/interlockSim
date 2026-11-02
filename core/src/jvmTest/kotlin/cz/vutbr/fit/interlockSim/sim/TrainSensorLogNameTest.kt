/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The SENSOR line of a train entering at an InOut names that InOut (Issue #1339).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.runReservedSingleTrainScenario
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
 * The SENSOR line of a train that enters at an InOut names that InOut (Issue #1339), driven end to
 * end on the shunting network.
 *
 * At its entry a train passes the InOut's embedded in-semaphore, which has no name of its own, so
 * the line used to read `detected at semaphore , signal=S80`. `TrainSemaphoreLogNameTest` pins
 * every branch of [Train.semaphoreLogName]; this class pins that `semaphoreAction` really prints it.
 *
 * `logback-test.xml` pins `sim.Train` to WARN and the SENSOR line is INFO, so the logger is raised
 * to INFO for the test and restored afterwards. Gradle forks run their tests one at a time, so
 * the change does not reach another test.
 */
@Tag("integration-test")
@DisplayName("The SENSOR line names the InOut a train enters at (Issue #1339)")
class TrainSensorLogNameTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private val trainLogger: Logger = LoggerFactory.getLogger(Train::class.java) as Logger
	private lateinit var appender: ListAppender<ILoggingEvent>
	private var savedLevel: Level? = null

	@BeforeEach
	fun setUp() {
		savedLevel = trainLogger.level
		trainLogger.level = Level.INFO
		appender = ListAppender<ILoggingEvent>().also { it.start() }
		trainLogger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		trainLogger.detachAppender(appender)
		trainLogger.level = savedLevel
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("an entering train's SENSOR line names its entry InOut, never a blank semaphore")
	fun theSensorLineOfAnEnteringTrainNamesItsInOut() {
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()

		runReservedSingleTrainScenario(
			context,
			inName = ENTRY,
			outName = EXIT,
			endTime = END_TIME,
			trainLength = TRAIN_LENGTH,
			findReservedPath = { realNav, trainId, separator -> realNav.findReservedPathForTrain(trainId, separator) }
		)

		val sensorLines = appender.list.map { it.formattedMessage }.filter { "SENSOR:" in it }
		assertThat(sensorLines, name = "SENSOR lines").isNotEmpty()
		assertThat(sensorLines.filter { "detected at semaphore ," in it }, name = "blank-name SENSOR lines").isEmpty()
		assertThat(sensorLines.joinToString("\n")).contains("detected at semaphore $ENTRY (entry), ")
	}

	private companion object {
		/** The InOut the train enters at, and the one it leaves by, on the shunting network. */
		const val ENTRY = "B"
		const val EXIT = "A"

		/** The entry SENSOR line is logged when the train starts; 30 s of simulated time is plenty. */
		const val END_TIME = 30L

		/** Short enough for the train to clear its entry InOut. */
		const val TRAIN_LENGTH = 20.0
	}
}
