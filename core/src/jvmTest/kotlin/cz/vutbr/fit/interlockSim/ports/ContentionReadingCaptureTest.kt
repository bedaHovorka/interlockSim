/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.util.Array2DMap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #1470 (Goal 9B SP4.4): [DefaultNetworkPerceptionPort.captureSnapshot] carries every open
 * contention of `RoutingServices.getContentionQuery()` as a [ContentionReading], on a scripted
 * two-train state inside a running kernel: `train1` reserves A to B at 0 s, `train2` asks for the
 * same route at [REFUSED_AT] and is refused, so it waits for the section `train1` holds.
 *
 * At [OBSERVED_AT] the script captures a snapshot and, on the same simulation instant, reads the live
 * [OpenContention]s; the readings must equal them field by field, the section named by the
 * [BlockOccupancyReading.sectionId] of the live block (the join key to [SimulationSnapshot.blocks]).
 * After `train1` releases and `train2` gets its route, the next capture carries no contention.
 */
class ContentionReadingCaptureTest : KoinTestBase() {
	private lateinit var context: DefaultSimulationContext

	private val service: PathReservationService
		get() = context.getRoutingServices().getPathReservationService()

	private fun inOut(name: String): DynamicPathSeparator =
		context.toDynamic(
			context.getInOuts().single {
				it.name == name
			}
		)

	private var waiting: SimulationSnapshot = SimulationSnapshot.EMPTY
	private var published: SimulationSnapshot = SimulationSnapshot.EMPTY
	private var liveWhileWaiting: List<OpenContention> = emptyList()
	private var afterRelease: SimulationSnapshot = SimulationSnapshot.EMPTY

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a waiting train and the train that holds its section are captured as values`() {
		context = loadVyhybnaContext(warmUpDynamicWrappers = true).tracked()
		val port = DefaultNetworkPerceptionPort(env = context, activeTrains = { emptyList() })
		context.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					assertReservationSuccess(service.reservePath("train1", inOut("A"), inOut("B")))
					hold(REFUSED_AT)
					assertThat(service.reservePath("train2", inOut("A"), inOut("B")))
						.transform { it::class }
						.isEqualTo(PathReservationService.ReservationResult.AllPathsBlocked::class)
					hold(OBSERVED_AT - REFUSED_AT)
					waiting = port.captureSnapshot()
					published = port.snapshot()
					liveWhileWaiting = context.getRoutingServices().getContentionQuery().openContentions()
					service.releasePath("train1")
					assertReservationSuccess(service.reservePath("train2", inOut("A"), inOut("B")))
					afterRelease = port.captureSnapshot()
					terminate()
				}
			}
		)
		context.run()

		val live = liveWhileWaiting.single()
		assertThat(live.owner, name = "live owner").isEqualTo("train1")
		assertThat(waiting.contentions, name = "captured contentions").containsExactly(
			ContentionReading(
				waitingTrainId = live.trainId,
				sectionId = liveSectionId(live.block),
				ownerTrainId = live.owner,
				sinceSimTime = live.since
			)
		)
		assertThat(live.trainId to live.since, name = "the scripted wait").isEqualTo("train2" to REFUSED_AT)
		// The section id is the join key to the blocks facet of the same snapshot.
		assertThat(waiting.blocks.filter { it.sectionId == waiting.contentions.single().sectionId }.map { it.trainId })
			.containsExactly("train1")
		assertThat(published, name = "the published snapshot").isSameInstanceAs(waiting)
		assertThat(afterRelease.contentions, name = "contentions after the grant").isEmpty()
	}

	/** The grid id of [block] from its end points' grid locations, independent of the port's graph scan. */
	private fun liveSectionId(block: DynamicTrackBlock): String {
		val grid = context.getRailWayNetGrid()
		return block
			.ends()
			.map { end -> checkNotNull(grid.getLocation(end as Cell)) { "end $end of a graph block has no grid location" } }
			.sortedWith(Array2DMap.POINT_COMPARATOR)
			.joinToString("-") { "(${it.x},${it.y})" }
	}

	private companion object {
		const val REFUSED_AT = 10.0
		const val OBSERVED_AT = 12.0
	}
}
