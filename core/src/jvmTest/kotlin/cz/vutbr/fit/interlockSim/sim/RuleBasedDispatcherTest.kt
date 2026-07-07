/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Direct unit tests for [RuleBasedDispatcher] — the branch-level coverage that the
 * `RuleBasedDispatcherDeterminismTest` integration gate cannot provide on its own
 * (determinism verifies cross-run consistency, not branch correctness).
 *
 * Uses a hand-written [FakeTickContext] that mirrors `ShuntingLoop`'s queue
 * semantics (admitting a train removes it from the unapproved queue and increments
 * the approved count) plus MockK stubs for the domain types
 * [DynamicTrackBlock], [DynamicRailSemaphore], and [TrackOccupant].
 *
 * @since Issue #540 (SP0.1 — Goal 10)
 */
@DisplayName("RuleBasedDispatcher — branch-level unit coverage")
class RuleBasedDispatcherTest {
	// ── Test doubles ────────────────────────────────────────────────────────

	private fun trainNamed(name: String): Train =
		mockk<Train>(relaxed = true).also {
			every { it.name } returns name
		}

	private fun semaphore(): DynamicRailSemaphore = mockk(relaxed = true)

	private fun otherSeparator(): OrientedPathSeparator = mockk()

	/** A mock [TrackOccupant]; [nextSemaphoreResult] is returned by `nextSemaphore()`. */
	private fun occupant(
		name: String,
		nextSemaphoreResult: OrientedPathSeparator?
	): TrackOccupant =
		mockk<TrackOccupant>(relaxed = true).also {
			every { it.name } returns name
			every { it.nextSemaphore() } returns nextSemaphoreResult
		}

	private fun block(
		state: TrackFacility.State,
		occupant: TrackOccupant? = null,
		trainName: String? = null,
		ends: Array<PathSeparator> = emptyArray()
	): DynamicTrackBlock =
		mockk<DynamicTrackBlock>(relaxed = true).also {
			every { it.getState() } returns state
			if (occupant != null) every { it.getTrackOccupant() } returns occupant
			every { it.trainName } returns trainName
			every { it.ends() } returns ends
		}

	/**
	 * Mirrors `ShuntingLoop.createTickContext`: admitting a train removes it from the
	 * unapproved queue and counts it as approved; [reservePath] records the call.
	 * `isPathSetUp` / `isPathExtendedBeyond` are parameter-sensitive so tests can
	 * distinguish the two ends of a block.
	 */
	private class FakeTickContext(
		initialUnapproved: List<Train> = emptyList(),
		override val innerBlocks: List<DynamicTrackBlock> = emptyList(),
		override val outerBlocks: Map<DynamicTrackBlock, DynamicRailSemaphore> = emptyMap(),
		private val pathSetUp: (DynamicTrackBlock, DynamicRailSemaphore) -> Boolean = { _, _ -> false },
		private val pathExtendedBeyond: (String, DynamicRailSemaphore) -> Boolean = { _, _ -> false },
		private val reservePathResult: Boolean = true
	) : DispatcherTickContext {
		private val queue: ArrayDeque<Train> = ArrayDeque(initialUnapproved)
		private val approved: MutableList<Train> = mutableListOf()
		override val approvedTrains: List<Train> get() = approved.toList()
		val reservePathCalls: MutableList<Pair<DynamicRailSemaphore, String>> = mutableListOf()

		override val approvedTrainCount: Int get() = approved.size
		override val simTime: Double get() = 0.0
		override val unapprovedTrains: List<Train> get() = queue.toList()
		override val perception: cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
			get() =
				object : cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort {
					override fun signalAspect(semaphoreName: String) = null

					override fun allSignalAspects() = emptyList<cz.vutbr.fit.interlockSim.ports.SemaphoreReading>()

					override fun blockOccupancy(blockId: String) = null

					override fun allBlockOccupancies() = emptyList<cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading>()

					override fun trainPosition(trainId: String) = null

					override fun allTrainPositions() = emptyList<cz.vutbr.fit.interlockSim.ports.TrainPositionReading>()

					override fun trainTimetable(trainId: String) = null

					override fun allTrainTimetables() = emptyList<cz.vutbr.fit.interlockSim.ports.TimetableReading>()

					override fun snapshot() =
						cz.vutbr.fit.interlockSim.ports.SimulationSnapshot(
							simTime = 0.0,
							semaphores = emptyList(),
							blocks = emptyList(),
							trainPositions = emptyList(),
							timetables = emptyList()
						)
				}

		override fun approveTrain(train: Train) {
			queue.remove(train)
			approved.add(train)
		}

		override fun reservePath(
			sem: DynamicRailSemaphore,
			trainName: String
		): Boolean {
			reservePathCalls += sem to trainName
			return reservePathResult
		}

		override fun isPathSetUp(
			block: DynamicTrackBlock,
			to: DynamicRailSemaphore
		): Boolean = pathSetUp(block, to)

		override fun isPathExtendedBeyond(
			trainName: String,
			sem: DynamicRailSemaphore
		): Boolean = pathExtendedBeyond(trainName, sem)
	}

	// ── approve() ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("approve() admits queued trains in FIFO order up to maxConcurrentTrains")
	fun approveAdmitsUpToCap() {
		val trains = (1..5).map { trainNamed("T$it") }
		val ctx = FakeTickContext(initialUnapproved = trains)
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 2)

		dispatcher.approve(ctx)

		assertThat(ctx.approvedTrains).containsExactly(trains[0], trains[1])
		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("approve() respects a higher maxConcurrentTrains")
	fun approveRespectsHigherCap() {
		val trains = (1..5).map { trainNamed("T$it") }
		val ctx = FakeTickContext(initialUnapproved = trains)
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 3)

		dispatcher.approve(ctx)

		assertThat(ctx.approvedTrains).containsExactly(trains[0], trains[1], trains[2])
	}

	@Test
	@DisplayName("approve() with empty queue approves nothing and does not throw")
	fun approveEmptyQueueIsNoOp() {
		val ctx = FakeTickContext(initialUnapproved = emptyList())
		val dispatcher = RuleBasedDispatcher()

		dispatcher.approve(ctx)

		assertThat(ctx.approvedTrains).isEmpty()
		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("approve() admits all trains when queue size is below the cap")
	fun approveAdmitsAllWhenBelowCap() {
		val trains = listOf(trainNamed("only"))
		val ctx = FakeTickContext(initialUnapproved = trains)
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 2)

		dispatcher.approve(ctx)

		assertThat(ctx.approvedTrains).containsExactly(trains[0])
	}

	// ── advancePaths() — checkOneEnd branches via outerBlocks ───────────────

	@Test
	@DisplayName("FREE block: no reservation attempted")
	fun freeBlockMakesNoReservation() {
		val to = semaphore()
		val block = block(TrackFacility.State.FREE)
		val ctx = FakeTickContext(outerBlocks = mapOf(block to to))

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("OCCUPIED block, train not approaching `to`: no reservation")
	fun occupiedNotApproachingMakesNoReservation() {
		val to = semaphore()
		val block =
			block(
				TrackFacility.State.OCCUPIED,
				occupant = occupant("T1", nextSemaphoreResult = otherSeparator())
			)
		val ctx = FakeTickContext(outerBlocks = mapOf(block to to))

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("OCCUPIED block, approaching `to`, path already extended: idempotent skip")
	fun occupiedApproachingAlreadyExtendedSkips() {
		val to = semaphore()
		val block =
			block(
				TrackFacility.State.OCCUPIED,
				occupant = occupant("T1", nextSemaphoreResult = to)
			)
		val ctx =
			FakeTickContext(
				outerBlocks = mapOf(block to to),
				pathExtendedBeyond = { _, _ -> true }
			)

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("OCCUPIED block, approaching `to`, not extended: reserves once with occupant name")
	fun occupiedApproachingReservesOnce() {
		val to = semaphore()
		val block =
			block(
				TrackFacility.State.OCCUPIED,
				occupant = occupant("T1", nextSemaphoreResult = to)
			)
		val ctx = FakeTickContext(outerBlocks = mapOf(block to to), reservePathResult = true)

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).hasSize(1)
		assertThat(ctx.reservePathCalls[0]).isEqualTo(to to "T1")
	}

	@Test
	@DisplayName("RESERVED block, path not set up toward `to`: no reservation")
	fun reservedNotSetUpMakesNoReservation() {
		val to = semaphore()
		val block = block(TrackFacility.State.RESERVED, trainName = "T2")
		val ctx = FakeTickContext(outerBlocks = mapOf(block to to), pathSetUp = { _, _ -> false })

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("RESERVED block, path set up, already extended: idempotent skip")
	fun reservedSetUpAlreadyExtendedSkips() {
		val to = semaphore()
		val block = block(TrackFacility.State.RESERVED, trainName = "T2")
		val ctx =
			FakeTickContext(
				outerBlocks = mapOf(block to to),
				pathSetUp = { _, _ -> true },
				pathExtendedBeyond = { _, _ -> true }
			)

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).isEmpty()
	}

	@Test
	@DisplayName("RESERVED block, path set up, not extended: reserves once with block.trainName")
	fun reservedSetUpReservesOnceWithBlockTrainName() {
		val to = semaphore()
		val block = block(TrackFacility.State.RESERVED, trainName = "T2")
		val ctx = FakeTickContext(outerBlocks = mapOf(block to to), pathSetUp = { _, _ -> true })

		RuleBasedDispatcher().advancePaths(ctx)

		assertThat(ctx.reservePathCalls).hasSize(1)
		assertThat(ctx.reservePathCalls[0]).isEqualTo(to to "T2")
	}

	// ── advancePaths() — checkBothEnds short-circuit via innerBlocks ────────

	@Test
	@DisplayName("checkBothEnds short-circuits after the first end reports a reservation/skip")
	fun checkBothEndsShortCircuitsOnFirstEnd() {
		val semA = semaphore()
		val semB = semaphore()
		// First end (semA): OCCUPIED + approaching + already extended -> returns true (skip).
		// Second end (semB): if reached, would reserve (approaching + not extended).
		// occupant.nextSemaphore() returns semA on first call, semB on the second, so the
		// second end WOULD reserve if it were ever evaluated.
		val occupant = mockk<TrackOccupant>(relaxed = true)
		every { occupant.name } returns "TA"
		every { occupant.nextSemaphore() } returns semA andThen semB
		val block =
			block(
				TrackFacility.State.OCCUPIED,
				occupant = occupant,
				ends = arrayOf<PathSeparator>(semA, semB)
			)
		val ctx =
			FakeTickContext(
				innerBlocks = listOf(block),
				pathExtendedBeyond = { _, sem -> sem == semA }, // semA already extended, semB not
				reservePathResult = true
			)

		RuleBasedDispatcher().advancePaths(ctx)

		// First end returned true -> second end never evaluated.
		assertThat(ctx.reservePathCalls).isEmpty()
		verify(exactly = 1) { occupant.nextSemaphore() }
	}

	// ── Constructor guard ───────────────────────────────────────────────────

	@Test
	@DisplayName("maxConcurrentTrains <= 0 is rejected")
	fun nonPositiveCapRejected() {
		assertFailure { RuleBasedDispatcher(maxConcurrentTrains = 0) }
			.isInstanceOf<IllegalArgumentException>()
		assertFailure { RuleBasedDispatcher(maxConcurrentTrains = -1) }
			.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	@DisplayName("default cap is the vyhybna physical capacity (2)")
	fun defaultCapIsTwo() {
		assertThat(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS).isEqualTo(2)
	}
}
