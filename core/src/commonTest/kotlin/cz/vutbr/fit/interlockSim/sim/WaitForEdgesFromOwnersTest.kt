/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1269): exact wait-for edges from the blocked-by result.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.navigation.extractUniqueBlocks
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlin.test.Test

/**
 * Issue #1269: the watchdog's wait-for edges come from what actually refused a waiting train, so a
 * STARTED train that keeps a waiter out appears as an edge even when it holds no block of the
 * waiter's candidate routes.
 *
 * On the y-junction `Entry -[trackEntry]- Junction -[trackMain]- ExitMain` (branch
 * `Junction -[trackBranch]- ExitBranch`) the first train runs `Entry -> ExitMain` and the second
 * waits for `Entry -> ExitBranch`. Once the first train's tail has cleared trackEntry it holds only
 * trackMain -- no block of the branch route -- but its claim on Junction (main position) still
 * refuses the second train. The candidate-route builder before Issue #1269 saw no edge in that window.
 */
class WaitForEdgesFromOwnersTest : CommonKoinTestBase() {
	private companion object {
		const val END_TIME: Long = 120L
		const val TRAIN_LENGTH: Double = 20.0
		const val SAMPLE_PERIOD: Double = 0.5
	}

	/** One script sample: the clock, the wait-for edges, and the blocks the main train held then. */
	private data class Sample(
		val time: Double,
		val edges: Map<String, Set<String>>,
		val mainTrainBlocks: List<DynamicTrackBlock>
	)

	@Test
	fun startedTrainHoldingOnlyTheSwitchClaimIsAnEdgeOfTheWaiter() {
		val ctx = TestTopologies.yJunctionWithSwitchSimulation().tracked()
		val service = AttemptRecordingService(ctx.getRoutingServices().getPathReservationService())
		val inOuts = ctx.getInOuts()
		val entry = inOuts.first { it.name == "Entry" }
		val exitBranch = inOuts.first { it.name == "ExitBranch" }
		val branchBlocks =
			ctx
				.getRouteFinder()
				.findRoutes(entry.staticRef, exitBranch.staticRef, ctx)
				.flatMap { extractUniqueBlocks(it.segments) }
				.toSet()

		lateinit var loop: MultiTrainLoop
		val samples = mutableListOf<Sample>()
		val sampleTimes = generateSequence(SAMPLE_PERIOD) { it + SAMPLE_PERIOD }.takeWhile { it < END_TIME }
		val steps =
			sampleTimes
				.map { at ->
					at to {
						val mainTrain = service.attempts.firstOrNull()?.trainId
						samples +=
							Sample(
								currentSimulationTime(),
								loop.waitForEdges(),
								mainTrain?.let { service.getReservedBlocks(it) }.orEmpty()
							)
					}
				}.toList()
		loop =
			ScriptedMultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("Entry", "ExitMain", inTime = 0.0, length = TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec("Entry", "ExitBranch", inTime = 1.0, length = TRAIN_LENGTH)
					),
				pathReservationService = service,
				steps = steps
			)
		ctx.setMainProcess(loop)

		ctx.run()

		val mainTrain = service.attempts.first().trainId
		val waiterAttempts = service.attempts.filter { it.trainId != mainTrain }
		assertThat(waiterAttempts, name = "attempts of the branch train").isNotEmpty()
		val waiter = waiterAttempts.first().trainId
		val firstAttempt = waiterAttempts.first().time
		val grantedAt = waiterAttempts.firstOrNull { it.granted }?.time
		assertThat(grantedAt, name = "the branch train was granted in the end").isNotNull()
		// The window: the waiter is refused and still out, and the started main train holds blocks,
		// none of them on the branch route.
		val window =
			samples.filter { sample ->
				sample.time > firstAttempt &&
					sample.time < grantedAt!! &&
					sample.mainTrainBlocks.isNotEmpty() &&
					sample.mainTrainBlocks.none { it in branchBlocks }
			}
		assertThat(window, name = "samples where only the switch claim keeps the waiter out").isNotEmpty()
		val withoutEdge = window.filter { mainTrain !in it.edges[waiter].orEmpty() }.map { it.time }
		assertThat(withoutEdge, name = "window samples without the edge $waiter -> $mainTrain").isEmpty()
	}
}
