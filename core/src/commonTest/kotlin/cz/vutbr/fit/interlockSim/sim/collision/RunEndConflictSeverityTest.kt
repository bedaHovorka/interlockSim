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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
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
 * Severity of the [CollisionWarning.ReservationConflict] that
 * [DefaultCollisionDetectionService] makes from a [BlockEvent.ReservationConflictDetected]
 * (Issue #1331).
 *
 * A mid-run event (`atRunEnd = false`) is a double-booking attempt the registry refused: it stays
 * [CollisionWarning.Severity.CRITICAL] and pauses. An end-of-run flush event (`atRunEnd = true`) is a
 * train still queued behind another one when the run stopped. It is not a double-booking, so it is a
 * [CollisionWarning.Severity.WARNING] that does not pause, and the deduplication window does not
 * swallow it: the flush is one-shot, and one queued train can wait for two blocks of the same owner
 * (the issue's run: block `k4` and a switch block, both behind Train #18).
 *
 * Drives [DefaultCollisionDetectionService.handleBlockEvent] with synthetic events on real blocks of
 * a transformed test network. Runs in `commonTest` under both JVM and native targets.
 */
class RunEndConflictSeverityTest : KoinComponent {
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

	/** Two real [DynamicTrackBlock]s from a transformed test network. */
	private fun realBlocks(): List<DynamicTrackBlock> {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.VYHYBNA_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().take(2)
	}

	private fun conflictEvent(
		block: DynamicTrackBlock,
		time: Double,
		atRunEnd: Boolean
	) = BlockEvent.ReservationConflictDetected(
		block = block,
		trainId = "Train #19",
		conflictingTrainId = "Train #18",
		time = time,
		atRunEnd = atRunEnd
	)

	@Test
	fun `a mid-run conflict stays CRITICAL and pauses`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }

		service.handleBlockEvent(conflictEvent(realBlocks().first(), time = 100.0, atRunEnd = false))

		assertThat(received.map { it.severity }).containsExactly(CollisionWarning.Severity.CRITICAL)
		assertThat(pauseController.calls).isEqualTo(1)
	}

	@Test
	fun `a contention flushed at run end is a WARNING and does not pause`() {
		val pauseController = CountingPauseController()
		val service = DefaultCollisionDetectionService(pauseController)
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }

		service.handleBlockEvent(conflictEvent(realBlocks().first(), time = 601.0, atRunEnd = true))

		assertThat(received.map { it.severity }).containsExactly(CollisionWarning.Severity.WARNING)
		assertThat(pauseController.calls).isEqualTo(0)
	}

	@Test
	fun `two run-end contentions of one pair at one time give two warnings`() {
		val service = DefaultCollisionDetectionService(CountingPauseController())
		val received = mutableListOf<CollisionWarning.ReservationConflict>()
		service.onCollisionWarning { (it as? CollisionWarning.ReservationConflict)?.let(received::add) }
		val (k4, switchBlock) = realBlocks()

		service.handleBlockEvent(conflictEvent(k4, time = 601.0, atRunEnd = true))
		service.handleBlockEvent(conflictEvent(switchBlock, time = 601.0, atRunEnd = true))

		assertThat(received.map { it.conflictingBlock }).containsExactly(k4, switchBlock)
	}

	// The deduplication window is 1 s: the second event of each pair below lies inside it.

	@Test
	fun `a mid-run warning of the same pair does not swallow the run-end one`() {
		val service = DefaultCollisionDetectionService(CountingPauseController())
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }
		val block = realBlocks().first()

		service.handleBlockEvent(conflictEvent(block, time = 600.5, atRunEnd = false))
		service.handleBlockEvent(conflictEvent(block, time = 601.0, atRunEnd = true))

		assertThat(received.map { it.severity })
			.containsExactly(CollisionWarning.Severity.CRITICAL, CollisionWarning.Severity.WARNING)
	}

	@Test
	fun `mid-run duplicates of one pair are still suppressed`() {
		val service = DefaultCollisionDetectionService(CountingPauseController())
		val received = mutableListOf<CollisionWarning>()
		service.onCollisionWarning { received.add(it) }
		val block = realBlocks().first()

		service.handleBlockEvent(conflictEvent(block, time = 100.0, atRunEnd = false))
		service.handleBlockEvent(conflictEvent(block, time = 100.5, atRunEnd = false))

		assertThat(received.map { it.severity }).containsExactly(CollisionWarning.Severity.CRITICAL)
	}
}
