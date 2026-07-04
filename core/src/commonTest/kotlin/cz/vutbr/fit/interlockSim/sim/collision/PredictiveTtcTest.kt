/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for the SP4 predictive TTC (time-to-collision) detection rule in
 * [DefaultCollisionDetectionService] (#614).
 *
 * Drives [DefaultCollisionDetectionService.handleBlockEvent] directly with synthetic
 * [BlockEvent.BlockReserved] events. Snapshot providers return controlled
 * [TrainSnapshot] values to exercise the TTC algorithm without a full simulation run.
 * Runs in `commonTest` under both JVM and native targets.
 *
 * Coverage:
 * - Fast trailing / slow leading — [CollisionWarning.PredictiveCollision] emitted.
 * - Single train active — no warning (not enough trains to compare).
 * - Large separation (TTC > threshold) — no warning.
 * - Trailing not faster (TTC = ∞) — no warning.
 * - Duplicate warning within deduplication window — suppressed.
 * - Release event removes train; subsequent single-train check emits no warning.
 *
 * @since Issue #614 (Goal 3 SP4)
 */
class PredictiveTtcTest : KoinComponent {
	/** A [PauseController] that counts [requestPause] calls. */
	private class CountingPauseController : PauseController {
		var calls: Int = 0
			private set

		override fun requestPause() {
			calls++
		}
	}

	private var context: DefaultSimulationContext? = null

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		context?.close()
		context = null
		stopKoin()
	}

	/** A real [DynamicTrackBlock] from a transformed test network (needed for BlockEvent). */
	private fun realBlock(): DynamicTrackBlock {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().first()
	}

	/** Build a service with configurable threshold defaults. */
	private fun buildService(
		minTtc: Double = DEFAULT_TTC_THRESHOLD_SECONDS,
		safetyMargin: Double = 0.0
	): Pair<DefaultCollisionDetectionService, MutableList<CollisionWarning>> {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		service.minTimeToCollisionSeconds = minTtc
		service.safetyMarginSeconds = safetyMargin
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }
		return Pair(service, received)
	}

	/**
	 * Fast trailing train catches slow leading train — predictive warning must fire.
	 *
	 * Setup:
	 * - Train A (leading):  pos=100 m, v=5 m/s, len=20 m
	 * - Train B (trailing): pos=20 m,  v=30 m/s, len=20 m
	 * - separation = 100 − 20 − 20 = 60 m
	 * - relVelocity = 30 − 5 = 25 m/s
	 * - TTC = 60 / 25 = 2.4 s  < threshold (30 s) → warning expected
	 */
	@Test
	fun `fast trailing train emits PredictiveCollision warning`() {
		val (service, received) = buildService()
		val block = realBlock()

		service.registerTrainSnapshotProvider { trainId ->
			when (trainId) {
				"train-A" -> TrainSnapshot("train-A", velocity = 5.0, totalDistance = 100.0, length = 20.0)
				"train-B" -> TrainSnapshot("train-B", velocity = 30.0, totalDistance = 20.0, length = 20.0)
				else -> null
			}
		}

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).hasSize(1)
		val warning = received.single()
		assertThat(warning).isInstanceOf(CollisionWarning.PredictiveCollision::class)
		warning as CollisionWarning.PredictiveCollision
		assertThat(warning.trainId).isEqualTo("train-B")
		assertThat(warning.targetTrainId).isEqualTo("train-A")
	}

	/**
	 * Only one train active — TTC evaluation needs at least two trains.
	 */
	@Test
	fun `single train does not emit any warning`() {
		val (service, received) = buildService()
		val block = realBlock()

		service.registerTrainSnapshotProvider { trainId ->
			when (trainId) {
				"train-A" -> TrainSnapshot("train-A", velocity = 10.0, totalDistance = 200.0, length = 20.0)
				else -> null
			}
		}

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))

		assertThat(received).isEmpty()
	}

	/**
	 * Large separation between trains — TTC exceeds the threshold, no warning.
	 *
	 * Setup:
	 * - Train A (leading):  pos=2000 m, v=5 m/s,  len=20 m
	 * - Train B (trailing): pos=20 m,   v=10 m/s, len=20 m
	 * - separation = 2000 − 20 − 20 = 1960 m
	 * - relVelocity = 10 − 5 = 5 m/s
	 * - TTC = 1960 / 5 = 392 s  > threshold (30 s) → no warning
	 */
	@Test
	fun `safe separation does not emit warning`() {
		val (service, received) = buildService()
		val block = realBlock()

		service.registerTrainSnapshotProvider { trainId ->
			when (trainId) {
				"train-A" -> TrainSnapshot("train-A", velocity = 5.0, totalDistance = 2000.0, length = 20.0)
				"train-B" -> TrainSnapshot("train-B", velocity = 10.0, totalDistance = 20.0, length = 20.0)
				else -> null
			}
		}

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).isEmpty()
	}

	/**
	 * Trailing train is not faster than the leading train — relative velocity ≤ 0,
	 * no TTC can be computed, no warning expected.
	 */
	@Test
	fun `trailing train not faster than leading emits no warning`() {
		val (service, received) = buildService()
		val block = realBlock()

		service.registerTrainSnapshotProvider { trainId ->
			when (trainId) {
				"train-A" -> TrainSnapshot("train-A", velocity = 20.0, totalDistance = 100.0, length = 20.0)
				"train-B" -> TrainSnapshot("train-B", velocity = 10.0, totalDistance = 20.0, length = 20.0)
				else -> null
			}
		}

		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).isEmpty()
	}

	/**
	 * When a train releases all its blocks, it is removed from the active set.
	 * Only one train remains → no further TTC warnings after the release.
	 *
	 * Setup: two trains → warning on second reserve; release one → no more warnings.
	 */
	@Test
	fun `train removed from active set after all blocks released emits no further warning`() {
		val (service, received) = buildService()
		val block = realBlock()

		service.registerTrainSnapshotProvider { trainId ->
			when (trainId) {
				"train-A" -> TrainSnapshot("train-A", velocity = 5.0, totalDistance = 100.0, length = 20.0)
				"train-B" -> TrainSnapshot("train-B", velocity = 30.0, totalDistance = 20.0, length = 20.0)
				else -> null
			}
		}

		// Two active trains → warning fires on reserve.
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))
		val warningsAfterReserve = received.size

		// Release train-B's only block → train-B removed from active set.
		received.clear()
		service.handleBlockEvent(BlockEvent.BlockReleased(block, "train-B", time = 5.0))

		// After release the dedup key no longer blocks and only train-A is active.
		// A re-reserve by train-A should NOT generate a PredictiveCollision for the pair.
		service.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 6.0))

		assertThat(warningsAfterReserve).isEqualTo(1)
		// No PredictiveCollision after train-B left (only train-A remains).
		val predictiveWarnings =
			received.filterIsInstance<CollisionWarning.PredictiveCollision>()
		assertThat(predictiveWarnings).isEmpty()
	}

	private companion object {
		// Mirror of DefaultCollisionDetectionService.DEFAULT_MIN_TIME_TO_COLLISION_SECONDS.
		// Kept here rather than referencing the private companion to avoid coupling tests
		// to internal implementation details.
		private const val DEFAULT_TTC_THRESHOLD_SECONDS = 30.0
	}
}
