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
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.metrics.TrainJourneyRecord
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

	/** What one run left behind, compared between a plain run and one with a throwing raw subscriber. */
	private data class RunOutcome(
		val trainsExited: Int,
		val completedTrains: Int,
		val journeys: List<TrainJourneyRecord>
	)

	private fun newContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { stream ->
			Util.assertInstanceOf<DefaultSimulationContext>(
				getKoin().get<SimulationContextFactory>().createContext(stream)
			)
		}

	/** One `ShuntingLoop` run; [beforeRun] may subscribe after the metrics service has subscribed. */
	private fun runOnce(beforeRun: (DefaultSimulationContext) -> Unit): RunOutcome {
		val context = newContext()
		val loop = prepareShuntingLoop(context, END_TIME)
		val metrics = context.getMetricsServices().getMetricsCollectionService()
		beforeRun(context)
		context.run()
		// Train numbers come from a JVM-wide counter, so the second run's trains have other names;
		// everything else about the journeys must match.
		val journeys = metrics.journeys().map { it.copy(trainId = "") }
		return RunOutcome(loop.getTrainsExited(), metrics.getSnapshot().completedTrains, journeys)
	}

	/**
	 * A raw [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onSimulationEvent] subscriber is
	 * not isolated by kDisco's bus, so without the guard in [emitTrainJourneyEvent] its failure would
	 * end the train or generator process that emitted the step. With the guard the run is the same as
	 * a run without that subscriber: same exits, same completed trains, same journey records. The
	 * subscriber registers after the metrics service, so the service still sees every step.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a raw subscriber that throws on a journey step does not change the run`() {
		val plain = runOnce {}
		var thrown = 0
		val withThrower =
			runOnce { context ->
				context.onSimulationEvent { event ->
					if ((event as? SimulationEvent.Custom)?.payload is TrainJourneyEvent) {
						thrown++
						error("raw subscriber failure injected by the test")
					}
				}
			}

		assertThat(plain.trainsExited, "trains exited in the plain run").isGreaterThan(0)
		val steps = withThrower.journeys.sumOf { listOfNotNull(it.entryTime, it.admissionTime, it.exitTime).size }
		assertThat(thrown, "the raw subscriber threw on every step").isEqualTo(steps)
		assertThat(withThrower).isEqualTo(plain)
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
