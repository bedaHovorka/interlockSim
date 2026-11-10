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
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.xml.XmlContextReader
import org.junit.jupiter.api.Test
import org.koin.core.component.get
import org.slf4j.LoggerFactory

/**
 * Issue #1273 (Goal 1B SP3 demand D6): a network whose XML leaves a semaphore unnamed can be
 * dispatched through that semaphore by its generated name. Before #1273 every `ReservePath`
 * through such a signal was denied with "Unknown route endpoint".
 */
class UnnamedSemaphoreRoutingTest : KoinTestBase() {
	@Test
	fun routeToAnAutoNamedSemaphoreIsGrantedByItsGeneratedName() {
		// SEMAPHORE_BASIC_XML: InOut IN (10,10), unnamed semaphore (15,10), InOut OUT (20,10); the
		// semaphore faces trains running from OUT towards IN.
		val context =
			CommonTestFixtures.parseSimulationContext(NetworkResources.SEMAPHORE_BASIC_XML, get()).tracked()
		context.getInOuts()
		val facade = DefaultInterlockingFacade(context, context.scope.get<PathReservationRegistry>())

		val response = facade.requestRouteByEndpoints("T1", "OUT", "S_15_10")

		assertThat((response as? InterlockingFacade.RouteResponse.Denied)?.reason, name = "denial reason").isNull()
		assertThat(response).isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
	}

	@Test
	fun loadingANetworkWithUnnamedSemaphoresWarnsOnceWithTheCount() {
		withReaderLog { events ->
			XmlContextReader().parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).close()

			val warns = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
			assertThat(warns).hasSize(1)
			assertThat(warns.single()).contains("18 unnamed RailSemaphore")
			assertThat(warns.single()).contains("S_<X>_<Y>")
		}
	}

	@Test
	fun loadingAFullyNamedNetworkDoesNotWarn() {
		withReaderLog { events ->
			XmlContextReader().parse(NetworkResources.VYHYBNA_XML).close()

			assertThat(events.filter { it.level == Level.WARN }).isEmpty()
		}
	}

	private fun withReaderLog(block: (List<ILoggingEvent>) -> Unit) {
		val logger = LoggerFactory.getLogger(XmlContextReader::class.qualifiedName) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		val previousLevel = logger.level
		logger.level = Level.INFO
		logger.addAppender(appender)
		try {
			block(appender.list)
		} finally {
			logger.detachAppender(appender)
			logger.level = previousLevel
		}
	}
}
