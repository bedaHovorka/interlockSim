/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.runSampled
import cz.vutbr.fit.interlockSim.util.Array2DMap
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.component.get

/**
 * Issue #1473 (Goal 9B SP4.7, #1467 spec §7): each track-section name in the published
 * [SimulationSnapshot] equals the live element at the same tick.
 *
 * At every control step (the first code after publication, spec rule P3) the test reads the tick's
 * published snapshot from [ShuntingLoop.getLatestObservation] and compares each
 * [BlockOccupancyReading] with the live [DynamicTrackBlock] it names:
 * - [BlockOccupancyReading.sectionId] with the block's two end points in row order, read from
 *   the grid locations of `block.ends()` (an oracle independent of the port's graph scan);
 * - [BlockOccupancyReading.blockId] with `BlockIdentity.stableBlockId` of the same block;
 * - the state, so the join is the right block and not only the right name.
 *
 * Mismatches are collected and asserted after the run: a throw inside the listener would end the
 * run (`MainProcessFailureRunEndTest`).
 */
class SectionNameLiveEqualityTest : KoinTestBase() {
	private fun liveSectionId(
		ctx: DefaultSimulationContext,
		block: DynamicTrackBlock
	): String {
		val grid = ctx.getRailWayNetGrid()
		return block
			.ends()
			.map { end -> checkNotNull(grid.getLocation(end as Cell)) { "end $end of a graph block has no grid location" } }
			.sortedWith(Array2DMap.POINT_COMPARATOR)
			.joinToString("-") { "(${it.x},${it.y})" }
	}

	private fun runAndCompare(
		xml: String,
		trains: Int,
		maxConcurrent: Int?,
		switchLegalPairsOnly: Boolean
	) {
		val ctx: DefaultSimulationContext = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		val loop =
			ShuntingLoop(
				ctx,
				END_TIME,
				maxConcurrentTrains = maxConcurrent,
				seed = SEED,
				maxTrains = trains,
				switchLegalPairsOnly = switchLegalPairsOnly
			)
		wireSynchronousDispatcher(ctx, loop)
		val liveBlocks = ctx.getGraph().values().filterIsInstance<DynamicTrackBlock>()
		val dispatcherStep = checkNotNull(loop.controlStepListener)
		val mismatches = mutableListOf<String>()
		var comparedTicks = 0
		var occupiedReadings = 0
		loop.controlStepListener =
			ControlStepListener {
				val observation = loop.getLatestObservation()
				val published = observation.snapshot.blocks.associateBy { it.sectionId }
				if (published.size != liveBlocks.size) {
					mismatches += "tick ${observation.tick}: ${published.size} readings for ${liveBlocks.size} blocks"
				}
				for (block in liveBlocks) {
					val sectionId = liveSectionId(ctx, block)
					val reading = published[sectionId]
					when {
						reading == null -> mismatches += "tick ${observation.tick}: no reading for section $sectionId"
						reading.blockId != BlockIdentity.stableBlockId(block) ->
							mismatches += "tick ${observation.tick}: $sectionId blockId ${reading.blockId} " +
								"!= live ${BlockIdentity.stableBlockId(block)}"
						reading.state != block.getState() ->
							mismatches += "tick ${observation.tick}: $sectionId state ${reading.state} != live ${block.getState()}"
					}
					if (reading?.state == TrackFacility.State.OCCUPIED) occupiedReadings++
				}
				comparedTicks++
				dispatcherStep.onControlStep()
			}
		ctx.setMainProcess(loop)
		runSampled(ctx) { }

		assertThat(mismatches, name = "snapshot vs live mismatches").isEmpty()
		assertThat(comparedTicks, name = "compared ticks").isGreaterThan(0)
		assertThat(occupiedReadings, name = "occupied readings seen (trains ran)").isGreaterThan(0)
	}

	@Test
	fun cervenyUjezdSnapshotSectionNamesEqualTheLiveBlocksAtEveryTick() {
		runAndCompare(NetworkResources.CERVENY_UJEZD_XML, trains = 3, maxConcurrent = null, switchLegalPairsOnly = false)
	}

	@Test
	@Tag("integration-test")
	fun prahaSnapshotSectionNamesEqualTheLiveBlocksAtEveryTick() {
		runAndCompare(
			NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML,
			trains = 4,
			maxConcurrent = 4,
			switchLegalPairsOnly = true
		)
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val SEED: Long = 1473L
	}
}
