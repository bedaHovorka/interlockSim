/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 (Issue #1154): the deadlock watchdog of MultiTrainLoop.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlin.test.Test

/**
 * Pins the watchdog of Issue #1154: a wait-for cycle that persists across dispatcher ticks while
 * the simulation clock advances raises [cz.vutbr.fit.interlockSim.exceptions.SimulationStalled]
 * and aborts the run; a long wait that is not a cycle does not.
 *
 * The cycle is constructed with a reservation service that refuses every `reservePath` and
 * reports a staged holding per train: the first train seen holds the first block of the `A -> B`
 * route, the second train the second block, and every candidate route of both runs over both
 * blocks, so each waits for the other. Since Issue #1269 the edges come from each train's refusal:
 * the refusal names the route blocks it found held, and the watchdog asks
 * [PathReservationService.getBlockOwner] for their current owners. The staged holdings are
 * therefore published through `getBlockOwner` only, not through `getReservedBlocks`: the loop does
 * not attempt a train that already holds a path, and a train that never attempts has no refusal.
 */
class WatchdogDetectsCycleTest : CommonKoinTestBase() {
	private companion object {
		const val END_TIME: Long = 300L
		const val TRAIN_LENGTH: Double = 20.0

		/** Dispatcher ticks the cycle must persist; small to keep the run short. */
		const val PERSIST_TICKS: Int = 4

		/** Generous upper bound of the simulation time at which the persisted cycle is reported. */
		const val STALL_DEADLINE: Double = 40.0

		/** Generous upper bound of the simulation time at which the churn bound reports. */
		const val CHURN_DEADLINE: Double = 100.0
	}

	/**
	 * Refuses every request, and reports staged holdings (indexed by the order in which trains first
	 * attempt) as the owners of the route blocks. [holdingsAt] is evaluated per query: a static
	 * stage passes a lambda returning a fixed list, an alternating test rotates its stages by the
	 * simulation tick. [lastQueryTime] is the last simulation time the loop asked it anything: it
	 * shows when the loop stopped. A refusal names every block of [routeBlocks] as blocked; the
	 * owner of a block is the train whose staged holding contains it at the time of the query.
	 */
	private class StagedHoldingService(
		real: PathReservationService,
		private val routeBlocks: List<DynamicTrackBlock>,
		private val holdingsAt: () -> List<List<DynamicTrackBlock>>
	) : PathReservationService by real {
		private val seen = mutableListOf<String>()
		var lastQueryTime: Double = 0.0

		private fun holdingOf(trainId: String): List<DynamicTrackBlock> {
			if (trainId !in seen) seen.add(trainId)
			return holdingsAt().getOrElse(seen.indexOf(trainId)) { emptyList() }
		}

		override fun getBlockOwner(block: DynamicTrackBlock): String? {
			lastQueryTime = currentSimulationTime()
			return seen.firstOrNull { block in holdingOf(it) }
		}

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			lastQueryTime = currentSimulationTime()
			holdingOf(trainId)
			// Every route block, also the train's own: the stages rotate, and the watchdog drops self-edges.
			val blocked = routeBlocks.associateWith { getBlockOwner(it) }
			return PathReservationService.ReservationResult.AllPathsBlocked(
				attemptedPaths = 1,
				blockedBy = listOf(PathReservationService.BlockedCandidate(0, blocked, emptyMap()))
			)
		}
	}

	private fun runWith(
		lateTrains: Int = 0,
		stagedHoldings: (List<DynamicTrackBlock>) -> List<List<DynamicTrackBlock>>
	): Triple<MultiTrainLoop, StagedHoldingService, RunEndSummary> {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		val blocks = lineRouteBlocks(ctx)
		assertThat(blocks.size, name = "route blocks of A -> B").isGreaterThanOrEqualTo(2)
		val holdings = stagedHoldings(blocks)
		val service =
			StagedHoldingService(ctx.getRoutingServices().getPathReservationService(), blocks) { holdings }
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec("A", "B", inTime = 1.0, length = TRAIN_LENGTH)
					) +
						// Generated long after the report below; they prove the run stopped when absent.
						(0 until lateTrains).map {
							MultiTrainLoop.TrainSpec("A", "B", inTime = 100.0, length = TRAIN_LENGTH)
						},
				pathReservationService = service,
				stallPersistTicks = PERSIST_TICKS
			)
		ctx.setMainProcess(loop)
		ctx.run()
		// Issue #1304: the stall as the metrics service counted it from events.
		return Triple(loop, service, ctx.getMetricsServices().getMetricsCollectionService().runEndSummary())
	}

	@Test
	fun constructedWaitForCycleRaisesSimulationStalledAndAborts() {
		// A third train is generated at t=100, long after the report: if the run kept going, it
		// would be approved, so staying absent proves the run actually stopped.
		val (loop, service, summary) =
			runWith(
				lateTrains = 1
			) { blocks -> listOf(listOf(blocks[0]), listOf(blocks[1])) }

		val stall = loop.stall
		assertThat(stall, name = "stall report").isNotNull()
		stall!!
		assertThat(stall.cycle.size, name = "trains in the cycle").isEqualTo(2)
		assertThat(stall.persistedTicks, name = "ticks the cycle persisted").isEqualTo(PERSIST_TICKS)
		assertThat(stall.simTime, name = "simulation time of the report").isLessThan(STALL_DEADLINE)
		assertThat(service.lastQueryTime, name = "last time the loop ran (aborted)")
			.isLessThanOrEqualTo(stall.simTime + 1.0)
		assertThat(loop.getApprovedTrains().size, name = "the late train was never approved").isEqualTo(2)
		assertThat(loop.getTrainsExited(), name = "trains exited").isZero()
		// Issue #1304: the same stall reaches the metrics as data, and the run is classified by it.
		val counted = summary.stall
		assertThat(counted, name = "stall in the run-end summary").isNotNull()
		assertThat(counted!!.cycle, name = "counted cycle").isEqualTo(stall.cycle)
		assertThat(counted.knot, name = "counted knot").isEqualTo(stall.knot)
		assertThat(counted.persistedTicks, name = "counted ticks").isEqualTo(stall.persistedTicks)
		assertThat(counted.simTime, name = "counted time").isEqualTo(stall.simTime)
		assertThat(summary.classification, name = "classification").isEqualTo(RunEndClassification.STALLED)
	}

	@Test
	fun alternatingCyclesAreStillReportedAtTheChurnBound() {
		// The member set alternates per tick, so the same-member rule restarts; the churn bound
		// (PERSIST_TICKS * 10 ticks of any cycle) must still report (Copilot round on Issue #1154).
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		val blocks = lineRouteBlocks(ctx)
		assertThat(blocks.size, name = "route blocks of A -> B").isGreaterThanOrEqualTo(2)
		val stages =
			listOf(
				listOf(listOf(blocks[0]), listOf(blocks[1]), emptyList(), emptyList()),
				listOf(emptyList(), emptyList(), listOf(blocks[0]), listOf(blocks[1]))
			)
		val service =
			StagedHoldingService(
				ctx.getRoutingServices().getPathReservationService(),
				blocks
			) { stages[((currentSimulationTime() / 2.0).toInt()) % stages.size] }
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					(0 until 4).map {
						MultiTrainLoop.TrainSpec("A", "B", inTime = it.toDouble(), length = TRAIN_LENGTH)
					},
				pathReservationService = service,
				stallPersistTicks = PERSIST_TICKS
			)
		ctx.setMainProcess(loop)

		ctx.run()

		val stall = loop.stall
		assertThat(stall, name = "stall report at the churn bound").isNotNull()
		stall!!
		assertThat(stall.simTime, name = "reported within the churn bound").isLessThan(CHURN_DEADLINE)
		assertThat(loop.getTrainsExited(), name = "trains exited").isZero()
	}

	@Test
	fun longWaitWithoutACycleDoesNotRaise() {
		// The first train holds nothing and waits on the second, which holds one route block but
		// needs nothing held by the first: a chain, not a cycle.
		val (loop, service, summary) = runWith { blocks -> listOf(emptyList(), listOf(blocks[1])) }

		assertThat(loop.stall, name = "stall report").isNull()
		// Issue #1304: a stall-free run shows no stall in the metrics and is not classified STALLED.
		assertThat(summary.stall, name = "stall in the run-end summary").isNull()
		assertThat(summary.endTimeReached, name = "end time reached").isTrue()
		assertThat(service.lastQueryTime, name = "the run reached its end").isGreaterThanOrEqualTo(END_TIME - 5.0)
		assertThat(loop.getEntryFailures(), name = "no entry failure").isEmpty()
	}
}
