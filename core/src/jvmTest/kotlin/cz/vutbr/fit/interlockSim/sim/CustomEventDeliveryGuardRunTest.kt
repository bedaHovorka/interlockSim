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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.metrics.TrainJourneyRecord
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.prepareShuntingLoop
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The delivery guard of `DefaultSimulationContext` (Issue #1361): a raw
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.onSimulationEvent] subscriber that throws
 * on a block or reservation payload does not change a `ShuntingLoop` run, and every subscriber after
 * it still gets the event.
 *
 * The throwing subscriber registers first, before every service of the context, so without the guard
 * kDisco's delivery ends at it: the typed block listeners (the metrics, the collision detection) and
 * the raw listeners after it miss the event, and the emitting process may die with it. The evidence is
 * the run's outcome -- exits, completed trains, the journey records with their per-train expiries --
 * and the payload stream a raw subscriber registered after the throwing one saw, compared with the
 * same run without the throwing subscriber.
 *
 * @since Issue #1361
 */
@DisplayName("Custom event delivery guard: full ShuntingLoop runs (Issue #1361)")
class CustomEventDeliveryGuardRunTest : KoinTestBase() {
	private companion object {
		/** Long enough for trains to leave `vyhybna.xml`. */
		const val END_TIME = 120L

		/** Long enough for three trains queued at one InOut to leave `vyhybna.xml`. */
		const val MULTI_TRAIN_END_TIME = 400L
		const val TRAIN_LENGTH = 20.0

		/** The block payloads every train's journey emits. */
		private val blockPayloads: List<KClass<*>> =
			listOf(
				BlockEvent.BlockReserved::class,
				BlockEvent.BlockReleased::class,
				BlockEvent.OccupancySet::class,
				BlockEvent.OccupancyCleared::class
			)

		@JvmStatic
		fun shuntingLoopPayloads(): List<Arguments> = blockPayloads.map { Arguments.of(it) }

		/** The block payloads `MultiTrainLoop`'s typed release wake listens to. */
		@JvmStatic
		fun releaseWakePayloads(): List<Arguments> =
			listOf(BlockEvent.BlockReleased::class, BlockEvent.OccupancyCleared::class).map { Arguments.of(it) }

		/** Trains queued behind each other also emit the mid-run contention event. */
		@JvmStatic
		fun multiTrainLoopPayloads(): List<Arguments> =
			(blockPayloads + ConflictDetectedEvent::class).map { Arguments.of(it) }
	}

	/** What one run left behind; train numbers differ between runs, so they are left out. */
	private data class RunOutcome(
		val trainsExited: Int,
		val completedTrains: Int,
		val journeys: List<TrainJourneyRecord>,
		val laterSubscriberSaw: List<String>,
		val laterTypedListenerSaw: List<String>
	)

	private fun newContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(getKoin().get<SimulationContextFactory>())

	/** The loop under test and how it reports its exits. */
	private class LoopUnderTest(
		val trainsExited: () -> Int
	)

	private fun shuntingLoop(context: DefaultSimulationContext): LoopUnderTest {
		val loop = prepareShuntingLoop(context, END_TIME)
		return LoopUnderTest { loop.getTrainsExited() }
	}

	/**
	 * Three trains entering at InOut `A` two seconds apart: the second and the third wait for the
	 * first, so the run depends on the loop's release wake (typed `BlockReleased`/`OccupancyCleared`
	 * listeners) and emits the contention event.
	 */
	private fun multiTrainLoop(context: DefaultSimulationContext): LoopUnderTest {
		context.getInOuts()
		val loop =
			MultiTrainLoop(
				context,
				endTime = MULTI_TRAIN_END_TIME,
				trainSpecs =
					listOf(0.0, 2.0, 4.0).map { inTime ->
						MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = inTime, length = TRAIN_LENGTH)
					}
			)
		context.setMainProcess(loop)
		return LoopUnderTest { loop.getTrainsExited() }
	}

	/**
	 * One run of [loopOf], with a first raw subscriber throwing on [throwOn] when it is given, or a first
	 * typed block listener throwing on [typedThrowOn]. A raw and a typed listener registered after them
	 * record every payload they get.
	 */
	private fun runOnce(
		throwOn: KClass<*>?,
		loopOf: (DefaultSimulationContext) -> LoopUnderTest,
		typedThrowOn: KClass<out BlockEvent>? = null
	): Pair<RunOutcome, Int> {
		val context = newContext()
		var thrown = 0
		if (typedThrowOn != null) {
			context.onBlockEvent { event ->
				if (typedThrowOn.isInstance(event)) {
					thrown++
					error("typed block listener failure injected by the test")
				}
			}
		}
		if (throwOn != null) {
			context.onSimulationEvent { event ->
				if (throwOn.isInstance((event as? SimulationEvent.Custom)?.payload)) {
					thrown++
					error("raw subscriber failure injected by the test")
				}
			}
		}
		val seen = mutableListOf<String>()
		context.onSimulationEvent { event ->
			if (event is SimulationEvent.Custom) seen += "${event.payload?.let { it::class.simpleName }}@${event.time}"
		}
		val typedSeen = mutableListOf<String>()
		context.onBlockEvent { event -> typedSeen += "${event::class.simpleName}@${event.time}" }
		val loop = loopOf(context)
		val metrics = context.getMetricsServices().getMetricsCollectionService()
		context.run()
		val outcome =
			RunOutcome(
				trainsExited = loop.trainsExited(),
				completedTrains = metrics.getSnapshot().completedTrains,
				journeys = metrics.journeys().map { it.copy(trainId = "") },
				laterSubscriberSaw = seen,
				laterTypedListenerSaw = typedSeen
			)
		return outcome to thrown
	}

	private fun assertRunUnchanged(
		payload: KClass<*>,
		loopOf: (DefaultSimulationContext) -> LoopUnderTest
	) {
		val (plain, _) = runOnce(throwOn = null, loopOf = loopOf)
		val (withThrower, thrown) = runOnce(throwOn = payload, loopOf = loopOf)

		assertThat(plain.trainsExited, "trains exited in the plain run").isGreaterThan(0)
		assertThat(thrown, "the raw subscriber threw on ${payload.simpleName}").isGreaterThan(0)
		assertThat(withThrower, "run outcome with the throwing subscriber").isEqualTo(plain)
	}

	@ParameterizedTest(name = "ShuntingLoop, a first raw subscriber throwing on {0}")
	@MethodSource("shuntingLoopPayloads")
	// A separate thread, so a regression that keeps the run spinning fails instead of hanging.
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `a raw subscriber throwing on a payload does not change a ShuntingLoop run`(payload: KClass<*>) =
		assertRunUnchanged(payload, ::shuntingLoop)

	@ParameterizedTest(name = "MultiTrainLoop, a first raw subscriber throwing on {0}")
	@MethodSource("multiTrainLoopPayloads")
	// A separate thread: a run whose main process died does not end (Issue #1361 step (e)), and only a
	// timeout on another thread can fail it.
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `a raw subscriber throwing on a payload does not change a MultiTrainLoop run`(payload: KClass<*>) =
		assertRunUnchanged(payload, ::multiTrainLoop)

	/**
	 * A typed `onBlockEvent` listener registered before every service throws on a release payload. All
	 * typed block listeners share one listener on the kDisco bus, so without the guard around each of them
	 * the ones after the throwing listener -- the metrics and `MultiTrainLoop`'s release wake -- miss the
	 * event. With it the run equals the plain run, and a later typed listener sees the full stream.
	 */
	@ParameterizedTest(name = "MultiTrainLoop, a first typed block listener throwing on {0}")
	@MethodSource("releaseWakePayloads")
	@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `a typed block listener throwing on a release payload does not change a MultiTrainLoop run`(
		payload: KClass<out BlockEvent>
	) {
		val (plain, _) = runOnce(throwOn = null, loopOf = ::multiTrainLoop)
		val (withThrower, thrown) = runOnce(throwOn = null, loopOf = ::multiTrainLoop, typedThrowOn = payload)

		assertThat(plain.trainsExited, "trains exited in the plain run").isGreaterThan(0)
		assertThat(thrown, "the typed listener threw on ${payload.simpleName}").isGreaterThan(0)
		assertThat(withThrower, "run outcome with the throwing typed listener").isEqualTo(plain)
	}
}
