/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.withLogOf
import org.junit.jupiter.api.Test

/**
 * Issue #1330: the DEBUG "Metrics snapshot queried" line belongs to an explicit
 * [MetricsCollectionService.getSnapshot] call only. The snapshot published after every block event
 * and the one the run-end summary reads are built without it, so with DEBUG on the line no longer
 * appears once per block event.
 *
 * The logger runs at DEBUG for each test only (see [withLogOf]): its other DEBUG lines fire on every
 * block event, so the suite does not pin it at DEBUG in `logback-test.xml`.
 *
 * @since Issue #1330 (Goal 6)
 */
class MetricsSnapshotQueriedLogTest : KoinTestBase() {
	private companion object {
		val SERVICE_LOGGER: String = DefaultMetricsCollectionService::class.qualifiedName!!
		const val QUERIED = "Metrics snapshot queried"
	}

	private fun realBlock(): DynamicTrackBlock {
		val context =
			CommonTestFixtures
				.parseSimulationContext(
					NetworkResources.LINEAR_TRACK_XML,
					DefaultSimulationProcessFactory()
				).tracked()
		return context.getGraph().values().first()
	}

	private fun ListAppender<ILoggingEvent>.queriedLines(): List<String> =
		list.filter { it.level == Level.DEBUG && it.formattedMessage.contains(QUERIED) }.map { it.formattedMessage }

	@Test
	fun `block events publish their snapshots without a queried line`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		withLogOf(SERVICE_LOGGER, Level.DEBUG) { appender ->
			service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
			service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", time = 2.0))

			assertThat(appender.queriedLines(), "queried lines after two block events").isEmpty()
		}
	}

	@Test
	fun `the run-end summary reads its snapshot without a queried line`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))

		withLogOf(SERVICE_LOGGER, Level.DEBUG) { appender ->
			service.runEndSummary()

			assertThat(appender.queriedLines(), "queried lines after runEndSummary()").isEmpty()
		}
	}

	@Test
	fun `one explicit getSnapshot call logs exactly one queried line`() {
		val service = DefaultMetricsCollectionService()
		val block = realBlock()

		withLogOf(SERVICE_LOGGER, Level.DEBUG) { appender ->
			service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
			service.handleBlockEvent(BlockEvent.ReservationConflictDetected(block, "T2", "T1", time = 2.0))
			service.getSnapshot()

			assertThat(appender.queriedLines(), "queried lines after one getSnapshot()").hasSize(1)
		}
	}
}
