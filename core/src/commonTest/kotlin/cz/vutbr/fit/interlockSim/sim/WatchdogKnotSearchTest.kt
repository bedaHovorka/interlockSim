/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1270): the watchdog is exact for OR-waits.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.extractUniqueBlocks
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlin.test.Test

/**
 * Pins the knot search of the deadlock watchdog in `MultiTrainLoop` (Issue #1270). A waiting train
 * needs only one of its candidate routes (an OR wait); the watchdog reports a stall only for a
 * knot: a set of waiters where every route of every member is blocked by a member. Before #1270 the
 * watchdog unioned the blockers of all routes into one graph (an AND model) and reported any
 * persisting cycle of it.
 *
 * A fake reservation service refuses every request. Its refusals list staged candidate routes
 * (block indices of the `A -> B` route of a three-signal line) per train, indexed by the order in
 * which trains first attempt; [PathReservationService.getBlockOwner] answers from staged holdings,
 * or names a train outside the loop that is not waiting (a running train). The free or
 * running-train alternative stands for a route whose blocker has gone or will go, while the
 * waiter's retry has not been granted yet.
 */
class WatchdogKnotSearchTest : CommonKoinTestBase() {
	private companion object {
		const val END_TIME: Long = 300L
		const val TRAIN_LENGTH: Double = 20.0

		/** Dispatcher ticks a stall must persist; small to keep the run short. */
		const val PERSIST_TICKS: Int = 4

		/** The reviewed default of `stallPersistTicks` (Issue #1270): five ticks before the knot search. */
		const val REVIEWED_DEFAULT_PERSIST_TICKS: Int = 2

		/** Generous upper bound of the simulation time at which a persisted knot is reported. */
		const val STALL_DEADLINE: Double = 40.0

		/** A train of no loop, never waiting: it releases what it holds one day. */
		const val RUNNING_TRAIN: String = "Running train"
	}

	/**
	 * Refuses every request with the staged [candidates] of the attempting train. [holdings] gives
	 * the block indices each train holds, [outsideHolders] the blocks the running train holds.
	 * [seen] lists the trains in the order of their first attempt; [lastQueryTime] is the last
	 * simulation time the loop asked anything.
	 */
	private class StagedRefusalService(
		real: PathReservationService,
		private val blocks: List<DynamicTrackBlock>,
		private val holdings: List<Set<Int>>,
		private val outsideHolders: Map<Int, String>,
		private val candidates: List<List<Set<Int>>>
	) : PathReservationService by real {
		val seen = mutableListOf<String>()
		var lastQueryTime: Double = 0.0

		override fun getBlockOwner(block: DynamicTrackBlock): String? {
			lastQueryTime = currentSimulationTime()
			val index = blocks.indexOf(block)
			return seen.firstOrNull { index in holdings.getOrElse(seen.indexOf(it)) { emptySet() } }
				?: outsideHolders[index]
		}

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			lastQueryTime = currentSimulationTime()
			if (trainId !in seen) seen.add(trainId)
			val routes = candidates.getOrElse(seen.indexOf(trainId)) { emptyList() }
			return PathReservationService.ReservationResult.AllPathsBlocked(
				attemptedPaths = routes.size,
				blockedBy =
					routes.mapIndexed { index, route ->
						PathReservationService.BlockedCandidate(
							index,
							route.associate { blocks[it] to getBlockOwner(blocks[it]) },
							emptyMap()
						)
					}
			)
		}
	}

	private fun routeBlocks(ctx: SimulationContext): List<DynamicTrackBlock> {
		val inOuts = ctx.getInOuts()
		val inIo = inOuts.first { it.name == "A" }
		val outIo = inOuts.first { it.name == "B" }
		return ctx
			.getRouteFinder()
			.findRoutes(inIo.staticRef, outIo.staticRef, ctx)
			.flatMap { extractUniqueBlocks(it.segments) }
			.distinct()
	}

	private fun runWith(
		holdings: List<Set<Int>>,
		candidates: List<List<Set<Int>>>,
		outsideHolders: Map<Int, String> = emptyMap(),
		persistTicks: Int? = PERSIST_TICKS
	): Pair<MultiTrainLoop, StagedRefusalService> {
		val ctx =
			TestTopologies
				.linearPathWithSemaphoreSequenceSimulation(semaphoreCount = 3, semaphoresAllowing = true)
				.tracked()
		val blocks = routeBlocks(ctx)
		assertThat(blocks.size, name = "route blocks of A -> B").isGreaterThanOrEqualTo(4)
		val service =
			StagedRefusalService(
				ctx.getRoutingServices().getPathReservationService(),
				blocks,
				holdings,
				outsideHolders,
				candidates
			)
		val specs =
			candidates.indices.map {
				MultiTrainLoop.TrainSpec("A", "B", inTime = it.toDouble(), length = TRAIN_LENGTH)
			}
		// null: the loop's own default, so the reviewed default is what runs.
		val loop =
			if (persistTicks == null) {
				MultiTrainLoop(ctx, endTime = END_TIME, trainSpecs = specs, pathReservationService = service)
			} else {
				MultiTrainLoop(
					ctx,
					endTime = END_TIME,
					trainSpecs = specs,
					pathReservationService = service,
					stallPersistTicks = persistTicks
				)
			}
		ctx.setMainProcess(loop)
		ctx.run()
		return loop to service
	}

	private fun assertRanToTheEndWithoutAStall(
		loop: MultiTrainLoop,
		service: StagedRefusalService
	) {
		assertThat(loop.stall, name = "stall report").isNull()
		assertThat(service.lastQueryTime, name = "the run reached its end").isGreaterThanOrEqualTo(END_TIME - 5.0)
		assertThat(loop.getEntryFailures(), name = "no entry failure").isEmpty()
	}

	@Test
	fun waiterWithAFreeAlternativeRouteIsNotReportedAsAStall() {
		// The two trains wait for each other on their first routes (an AND cycle), but the first
		// train's second route (block 2) is held by nobody.
		val (loop, service) =
			runWith(
				holdings = listOf(setOf(0), setOf(1)),
				candidates = listOf(listOf(setOf(1), setOf(2)), listOf(setOf(0)))
			)

		assertRanToTheEndWithoutAStall(loop, service)
	}

	@Test
	fun waiterWhoseAlternativeOnlyARunningTrainHoldsIsNotReportedAsAStall() {
		val (loop, service) =
			runWith(
				holdings = listOf(setOf(0), setOf(1)),
				candidates = listOf(listOf(setOf(1), setOf(2)), listOf(setOf(0))),
				outsideHolders = mapOf(2 to RUNNING_TRAIN)
			)

		assertRanToTheEndWithoutAStall(loop, service)
	}

	@Test
	fun knotOfTwoWaitersOverTwoRoutesEachRaisesSimulationStalled() {
		// Each train's second route is also held by the running train, but its first block is held
		// by the other waiter, which never moves: no route of either train can ever free.
		val (loop, service) =
			runWith(
				holdings = listOf(setOf(0), setOf(1)),
				candidates = listOf(listOf(setOf(1), setOf(1, 2)), listOf(setOf(0), setOf(0, 3))),
				outsideHolders = mapOf(2 to RUNNING_TRAIN, 3 to RUNNING_TRAIN)
			)

		val stall = loop.stall
		assertThat(stall, name = "stall report").isNotNull()
		stall!!
		assertThat(stall.knot, name = "knot members").containsOnly(*service.seen.toTypedArray())
		assertThat(stall.cycle.toSet(), name = "cycle members").containsOnly(*service.seen.toTypedArray())
		assertThat(stall.persistedTicks, name = "ticks the knot persisted").isEqualTo(PERSIST_TICKS)
		assertThat(stall.simTime, name = "simulation time of the report").isLessThan(STALL_DEADLINE)
		assertThat(loop.getTrainsExited(), name = "trains exited").isZero()
	}

	@Test
	fun waiterBlockedOnlyByAKnotIsReportedInTheKnotButNotInTheCycle() {
		val (loop, service) =
			runWith(
				holdings = listOf(setOf(0), setOf(1), emptySet()),
				candidates = listOf(listOf(setOf(1)), listOf(setOf(0)), listOf(setOf(0)))
			)

		val stall = loop.stall
		assertThat(stall, name = "stall report").isNotNull()
		stall!!
		assertThat(stall.knot, name = "knot members").containsOnly(*service.seen.toTypedArray())
		assertThat(stall.cycle.toSet(), name = "cycle members").containsOnly(service.seen[0], service.seen[1])
	}

	@Test
	fun knotIsReportedAfterTheReviewedDefaultOfTwoTicks() {
		val (loop, _) =
			runWith(
				holdings = listOf(setOf(0), setOf(1)),
				candidates = listOf(listOf(setOf(1)), listOf(setOf(0))),
				persistTicks = null
			)

		val stall = loop.stall
		assertThat(stall, name = "stall report").isNotNull()
		assertThat(stall!!.persistedTicks, name = "ticks behind the report (the default guard)")
			.isEqualTo(REVIEWED_DEFAULT_PERSIST_TICKS)
	}
}
