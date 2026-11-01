/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1269): y-junction scenario where only a switch claim refuses a waiter.
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.extractUniqueBlocks
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.currentSimulationTime

/**
 * Runs two trains on the y-junction of [TestTopologies.yJunctionWithSwitchSimulation]: the main train
 * `Entry -> ExitMain` starts at once, the branch train `Entry -> ExitBranch` follows one time unit later
 * and waits. The wait-for edges and the main train's blocks are sampled every [SAMPLE_PERIOD].
 *
 * The scenario runs in its constructor; the caller owns [ctx] (tracks and closes it).
 *
 * @param ctx a fresh y-junction simulation context, not yet run
 */
internal class SwitchClaimWaitScenario(
	private val ctx: DefaultSimulationContext
) {
	/** One script sample: the clock, the wait-for edges, and the blocks the main train held then. */
	data class Sample(
		val time: Double,
		val edges: Map<String, Set<String>>,
		val mainTrainBlocks: List<DynamicTrackBlock>
	)

	private val service = AttemptRecordingService(ctx.getRoutingServices().getPathReservationService())
	private val samples = mutableListOf<Sample>()

	/** Every sample taken during the run. */
	val allSamples: List<Sample>
		get() = samples

	/** The blocks of every route from the entry to the branch exit. */
	private val branchBlocks: Set<DynamicTrackBlock>

	init {
		val inOuts = ctx.getInOuts()
		val entry = inOuts.first { it.name == TestTopologies.Y_JUNCTION_ENTRY }
		val exitBranch = inOuts.first { it.name == TestTopologies.Y_JUNCTION_EXIT_BRANCH }
		branchBlocks =
			ctx
				.getRouteFinder()
				.findRoutes(entry.staticRef, exitBranch.staticRef, ctx)
				.flatMap { extractUniqueBlocks(it.segments) }
				.toSet()
		runSimulation()
	}

	/** Id of the train attempted first: the main train. */
	val mainTrain: String by lazy { service.attempts.first().trainId }

	private val waiterAttempts by lazy { service.attempts.filter { it.trainId != mainTrain } }

	/** True when the branch train made at least one reservation attempt. */
	val waiterWasAttempted: Boolean
		get() = waiterAttempts.isNotEmpty()

	/** Id of the branch train; requires [waiterWasAttempted]. */
	val waiter: String
		get() = waiterAttempts.first().trainId

	/** Clock of the branch train's first attempt; requires [waiterWasAttempted]. */
	val firstAttemptTime: Double
		get() = waiterAttempts.first().time

	/** Clock of the branch train's first granted attempt, or null when it was never granted. */
	val grantedAt: Double? by lazy { waiterAttempts.firstOrNull { it.granted }?.time }

	/**
	 * The window: samples between the waiter's first attempt and its grant where the started main train
	 * holds blocks, none of them on the branch route. Empty when the waiter was never granted.
	 */
	val window: List<Sample> by lazy {
		val granted = grantedAt
		if (granted == null) {
			emptyList()
		} else {
			samples.filter { sample ->
				sample.time > firstAttemptTime &&
					sample.time < granted &&
					sample.mainTrainBlocks.isNotEmpty() &&
					sample.mainTrainBlocks.none { it in branchBlocks }
			}
		}
	}

	private fun runSimulation() {
		lateinit var loop: MultiTrainLoop
		val sampleTimes = generateSequence(SAMPLE_PERIOD) { it + SAMPLE_PERIOD }.takeWhile { it < END_TIME }
		val steps =
			sampleTimes
				.map { at ->
					at to {
						val first = service.attempts.firstOrNull()?.trainId
						samples +=
							Sample(
								currentSimulationTime(),
								loop.waitForEdges(),
								first?.let { service.getReservedBlocks(it) }.orEmpty()
							)
					}
				}.toList()
		loop =
			ScriptedMultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec(
							TestTopologies.Y_JUNCTION_ENTRY,
							TestTopologies.Y_JUNCTION_EXIT_MAIN,
							inTime = 0.0,
							length = TRAIN_LENGTH
						),
						MultiTrainLoop.TrainSpec(
							TestTopologies.Y_JUNCTION_ENTRY,
							TestTopologies.Y_JUNCTION_EXIT_BRANCH,
							inTime = 1.0,
							length = TRAIN_LENGTH
						)
					),
				pathReservationService = service,
				steps = steps
			)
		ctx.setMainProcess(loop)
		ctx.run()
	}

	private companion object {
		const val END_TIME: Long = 120L
		const val TRAIN_LENGTH: Double = 20.0
		const val SAMPLE_PERIOD: Double = 0.5
	}
}
