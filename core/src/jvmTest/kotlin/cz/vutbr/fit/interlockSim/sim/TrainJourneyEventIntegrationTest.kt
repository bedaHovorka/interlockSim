/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #673 (Goal 6 SP2): [TrainJourneyEvent]s emitted by a real `ShuntingLoop` run reach
 * [onTrainJourneyEvent] subscribers in journey order, and a throwing subscriber neither breaks the
 * run nor keeps the event from the subscribers after it.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
@DisplayName("Train journey events (Integration)")
@Tag("integration-test")
class TrainJourneyEventIntegrationTest : KoinTestBase() {
	private companion object {
		/** Long enough for two trains to leave `vyhybna.xml`. */
		const val END_TIME = 120L
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `every train reports queued, admitted and exited in that order despite a failing subscriber`() {
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				Util.assertInstanceOf<DefaultSimulationContext>(
					getKoin().get<SimulationContextFactory>().createContext(stream)
				)
			}
		val loop = prepareShuntingLoop(context, END_TIME)
		var failingCalls = 0
		context.onTrainJourneyEvent {
			failingCalls++
			error("subscriber failure injected by the test")
		}
		val events = mutableListOf<TrainJourneyEvent>()
		context.onTrainJourneyEvent { events += it }

		context.run()

		assertThat(events).isNotEmpty()
		assertThat(failingCalls, "the failing subscriber saw every event").isEqualTo(events.size)
		assertThat(loop.getTrainsExited(), "trains exited").isGreaterThan(0)
		val exited = events.filterIsInstance<TrainJourneyEvent.Exited>().map { it.trainId }
		assertThat(exited.size, "one exit per train").isEqualTo(exited.toSet().size)
		for (trainId in exited) {
			val ofTrain = events.filter { it.trainId == trainId }
			assertThat(ofTrain.map { it::class }, trainId).containsExactly(
				TrainJourneyEvent.Queued::class,
				TrainJourneyEvent.Admitted::class,
				TrainJourneyEvent.Exited::class
			)
			assertThat(ofTrain.map { it.time }.sorted(), "$trainId times in order").isEqualTo(ofTrain.map { it.time })
		}
	}
}
