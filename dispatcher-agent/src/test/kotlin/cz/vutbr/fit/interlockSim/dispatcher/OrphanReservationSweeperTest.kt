/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OrphanReservationSweeper] (Issue #847 round 3, PR #891 defect B).
 *
 * ## The defect
 *
 * A reservation is released in exactly three ways (`PathReservationRegistry`): a train's `Tail`
 * physically leaving a block it entered, the train's journey completing, or an explicit
 * `releasePath`. All three require the train to *consume* the route. Nothing reclaims a route that
 * was granted and never travelled, and the registry keeps no timestamps, so there is no sweeper,
 * timeout or TTL anywhere.
 *
 * `MultiTrainLoop` calls `BlockResourceRegistry.releaseFreeResources()` once per iteration as its
 * safety net; `ShuntingLoop` — the loop `shuntingLoopAI` runs — has no equivalent. (It could not
 * use that one anyway: it holds no kDisco `Resource`s at all, and `BlockResourceRegistry` is
 * `internal` to `:core`. The gap is one layer up, at the reservation registry.)
 *
 * Round 2 left a live run with `Train #1` holding `k1` and `kA` RESERVED from `A` with no occupant
 * — a route it was granted and never consumed — after which every later train conflicted on those
 * blocks for the rest of the run. One 600 s run deadlocked outright at simTime 72.0 s.
 *
 * ## Why this can live outside `core/`
 *
 * Everything needed is already public: [NetworkPerceptionPort.snapshot] reports each block's state
 * and owning train id, [DispatchLoopSensorPort.getQueuedTrains] the admission queue, and
 * [NetworkActuatorPort.releaseRoute] performs the same cancel-and-unregister `cancel_route` does.
 * PR #891's zero-`core/`-changes property is preserved.
 *
 * @since Issue #847 (round 3)
 */
@DisplayName("OrphanReservationSweeper reclaims routes nothing else can release")
class OrphanReservationSweeperTest {
	private val train = "Train #1"

	/**
	 * `releaseRoute` returns `true` when it actually released something. The sweeper only calls it
	 * for a train the snapshot shows holding blocks, so the real port returns `true` there; a
	 * relaxed mock's default `false` would model a case that cannot arise and would silently
	 * suppress the release counters.
	 */
	private val actuatorPort =
		mockk<NetworkActuatorPort>(relaxed = true).apply {
			every { releaseRoute(any()) } returns true
		}

	private fun reading(
		blockId: String,
		owner: String?,
		state: TrackFacility.State
	) = BlockOccupancyReading(blockId = blockId, state = state, trainId = owner)

	private fun reserved(
		blockId: String,
		owner: String
	) = reading(blockId, owner, TrackFacility.State.RESERVED)

	private fun occupied(
		blockId: String,
		owner: String
	) = reading(blockId, owner, TrackFacility.State.OCCUPIED)

	/**
	 * Drives the sweeper over a scripted sequence of snapshots, one `sweep()` per element, and
	 * returns the sweeper so counters can be asserted. Ports are re-stubbed before each sweep so a
	 * test can vary sim time, block ownership and the queue between ticks.
	 */
	private fun sweepAll(
		staleAfterSimSeconds: Double = 60.0,
		ticks: List<Tick>,
		partialReleaser: PartialRouteReleaser? = null
	): OrphanReservationSweeper {
		val perceptionPort = mockk<NetworkPerceptionPort>()
		val sensorPort = mockk<DispatchLoopSensorPort>()
		val sweeper =
			OrphanReservationSweeper(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = sensorPort,
				actuatorPort = actuatorPort,
				staleAfterSimSeconds = staleAfterSimSeconds,
				partialReleaser = partialReleaser
			)
		for (tick in ticks) {
			every { perceptionPort.snapshot() } returns
				SimulationSnapshot.EMPTY.copy(
					simTime = tick.simTime,
					blocks = tick.blocks,
					trainPositions =
						tick.activeTrains.map {
							TrainPositionReading(
								trainId = it,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						}
				)
			every { sensorPort.getQueuedTrains() } returns
				tick.queuedTrains.map { QueuedTrainObservation(trainId = it, destinationInOutName = "B") }
			sweeper.sweep()
		}
		return sweeper
	}

	private data class Tick(
		val simTime: Double,
		val blocks: List<BlockOccupancyReading>,
		val activeTrains: List<String> = emptyList(),
		val queuedTrains: List<String> = emptyList()
	)

	@Test
	@DisplayName("a reservation owned by no known train is released on the first sweep")
	fun phantomOwnerIsReleasedImmediately() {
		// The exact round-1 failure: the system prompt's worked example made the model call
		// request_route(trainName="T1"), reserving real blocks for a train that never existed.
		val sweeper =
			sweepAll(
				ticks =
					listOf(
						Tick(
							simTime = 10.0,
							blocks = listOf(reserved("k1", "T1"), reserved("kA", "T1")),
							activeTrains = listOf("Train #1")
						)
					)
			)

		verify(exactly = 1) { actuatorPort.releaseRoute("T1") }
		assertThat(sweeper.phantomReleaseCount).isEqualTo(1)
	}

	@Test
	@DisplayName("a real train's untravelled route survives until the staleness threshold elapses")
	fun staleRouteIsNotReleasedBeforeTheThreshold() {
		val held = listOf(reserved("k1", "Train #1"), reserved("kA", "Train #1"))
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(10.0, held, activeTrains = listOf("Train #1")),
						Tick(69.0, held, activeTrains = listOf("Train #1"))
					)
			)

		verify(exactly = 0) { actuatorPort.releaseRoute(any()) }
		assertThat(sweeper.staleReleaseCount).isEqualTo(0)
	}

	@Test
	@DisplayName("a real train's untravelled route is released once the threshold elapses")
	fun staleRouteIsReleasedAfterTheThreshold() {
		val held = listOf(reserved("k1", "Train #1"), reserved("kA", "Train #1"))
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(10.0, held, activeTrains = listOf("Train #1")),
						Tick(70.0, held, activeTrains = listOf("Train #1"))
					)
			)

		verify(exactly = 1) { actuatorPort.releaseRoute("Train #1") }
		assertThat(sweeper.staleReleaseCount).isEqualTo(1)
	}

	/**
	 * Issue #893 task A7 review round 1, CRITICAL 2: every other test in this file stubs
	 * `releaseRoute` to always return `true`, so nothing previously exercised the sweeper's own
	 * call sites with a `false` result. Per [NetworkActuatorPort.releaseRoute]'s contract, `false`
	 * is never a failure indicator and must never itself trigger a retry -- this pins that at the
	 * sweeper boundary: a `false` result does not bump the reclaim counter, AND the owner is not
	 * revisited on the very next sweep (`holdings.remove(owner)` in `evaluateKnownTrain` runs
	 * unconditionally, outside the `if (releaseRoute(...))` counter check, so a `false` result
	 * still forgets the holding and restarts its staleness clock exactly like a `true` one would).
	 *
	 * TDD discriminator (recorded in the task A7 fix report): confirmed to FAIL when
	 * `holdings.remove(owner)` was temporarily moved *inside* the `if (actuatorPort.releaseRoute(owner))`
	 * block in `evaluateKnownTrain` -- with that change, a `false` result leaves the stale
	 * `Holding` in place, so the very next sweep (still past the now-doubly-stale threshold)
	 * immediately re-attempts the release, calling `releaseRoute` a second time. Reverted after
	 * capturing the failure; production code is unconditional `holdings.remove(owner)` as before.
	 */
	@Test
	@DisplayName("releaseRoute returning false is not a failure: no counter bump, and no retry next sweep")
	fun falseReleaseRouteResultDoesNotBumpCounterOrRetryNextSweep() {
		val neverSucceedsActuator = mockk<NetworkActuatorPort>()
		every { neverSucceedsActuator.releaseRoute(any()) } returns false
		val held = listOf(reserved("k1", "Train #1"))
		val perceptionPort = mockk<NetworkPerceptionPort>()
		val sensorPort = mockk<DispatchLoopSensorPort>()
		every { sensorPort.getQueuedTrains() } returns emptyList()
		val sweeper =
			OrphanReservationSweeper(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = sensorPort,
				actuatorPort = neverSucceedsActuator,
				staleAfterSimSeconds = 60.0
			)

		every { perceptionPort.snapshot() } returns
			SimulationSnapshot.EMPTY.copy(
				simTime = 10.0,
				blocks = held,
				trainPositions =
					listOf(
						TrainPositionReading(
							trainId = "Train #1",
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					)
			)
		sweeper.sweep() // First sight: the staleness clock starts.

		every { perceptionPort.snapshot() } returns
			SimulationSnapshot.EMPTY.copy(
				simTime = 70.0,
				blocks = held,
				trainPositions =
					listOf(
						TrainPositionReading(
							trainId = "Train #1",
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					)
			)
		sweeper.sweep() // Past the threshold: releaseRoute is called and returns false.

		assertThat(sweeper.staleReleaseCount, "staleReleaseCount must not bump on a false result").isEqualTo(0)
		verify(exactly = 1) { neverSucceedsActuator.releaseRoute("Train #1") }

		// Same block, unchanged -- "the very next sweep". holdings forgot the owner regardless of
		// the false result above, so this restarts a fresh staleness clock rather than an
		// immediate retry: releaseRoute must NOT be called again yet.
		every { perceptionPort.snapshot() } returns
			SimulationSnapshot.EMPTY.copy(
				simTime = 71.0,
				blocks = held,
				trainPositions =
					listOf(
						TrainPositionReading(
							trainId = "Train #1",
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					)
			)
		sweeper.sweep()

		assertThat(sweeper.staleReleaseCount, "staleReleaseCount must still be 0").isEqualTo(0)
		verify(exactly = 1) { neverSucceedsActuator.releaseRoute("Train #1") }
	}

	/**
	 * The safety constraint, not merely a conservative choice.
	 *
	 * `releaseRoute` delegates to `DefaultPathReservationService.releasePath`, which ends in a
	 * `finally` calling `registry.unregister(trainId)` — clearing **every** block registered to the
	 * train, including one it is physically standing on. Sweeping an occupying train would mark
	 * that block free with a train on it, and the next `request_route` could route another train
	 * into it. Relaxing this to reclaim more would trade a throughput problem for a collision.
	 *
	 * This is also why round 3 measured 301 sweeps reclaiming nothing: the live failure was a train
	 * stopped *on* a block while holding the next one reserved. See the class KDoc's known-gap note.
	 */
	@Test
	@DisplayName("a route the train is actually travelling is never released, however long it holds")
	fun occupiedRouteIsNeverReleased() {
		val held = listOf(occupied("k1", "Train #1"), reserved("kA", "Train #1"))
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(10.0, held, activeTrains = listOf("Train #1")),
						Tick(500.0, held, activeTrains = listOf("Train #1"))
					)
			)

		verify(exactly = 0) { actuatorPort.releaseRoute(any()) }
		assertThat(sweeper.staleReleaseCount).isEqualTo(0)
	}

	@Test
	@DisplayName("progress through the route restarts the staleness clock")
	fun changingTheHeldSetRestartsTheClock() {
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(
							10.0,
							listOf(reserved("k1", "Train #1"), reserved("kA", "Train #1")),
							activeTrains = listOf("Train #1")
						),
						// Tail cleared k1: the train is moving, so the clock restarts here.
						Tick(50.0, listOf(reserved("kA", "Train #1")), activeTrains = listOf("Train #1")),
						// 60 s after the first tick but only 30 s after the change — not yet stale.
						Tick(80.0, listOf(reserved("kA", "Train #1")), activeTrains = listOf("Train #1"))
					)
			)

		verify(exactly = 0) { actuatorPort.releaseRoute(any()) }
		assertThat(sweeper.staleReleaseCount).isEqualTo(0)
	}

	@Test
	@DisplayName("a queued train's untravelled route is swept on the same threshold")
	fun queuedTrainRouteIsAlsoSwept() {
		// The system prompt tells the model a queued train "stays parked, holding its reservation
		// indefinitely, until you separately call approve_train". That licence is what makes a
		// never-approved queued train a deadlock source: after the threshold the hold is dead
		// weight blocking every other train, so it is cancelled like any other stale route.
		val held = listOf(reserved("k1", "Train #2"))
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(10.0, held, queuedTrains = listOf("Train #2")),
						Tick(90.0, held, queuedTrains = listOf("Train #2"))
					)
			)

		verify(exactly = 1) { actuatorPort.releaseRoute("Train #2") }
		assertThat(sweeper.staleReleaseCount).isEqualTo(1)
	}

	@Test
	@DisplayName("a released route is not released a second time on the next sweep")
	fun releaseIsNotRepeated() {
		val held = listOf(reserved("k1", "Train #1"))
		sweepAll(
			staleAfterSimSeconds = 60.0,
			ticks =
				listOf(
					Tick(10.0, held, activeTrains = listOf("Train #1")),
					Tick(70.0, held, activeTrains = listOf("Train #1")),
					// releaseRoute has taken effect: the blocks are FREE and unowned again.
					Tick(71.0, listOf(reading("k1", null, TrackFacility.State.FREE)), activeTrains = listOf("Train #1"))
				)
		)

		verify(exactly = 1) { actuatorPort.releaseRoute("Train #1") }
	}

	/**
	 * Issue #893 task A7 (G7): before the fix, `DefaultNetworkActuatorPort.releaseRoute` reported
	 * `false` for a release that only reset a cleared signal (zero blocks), because
	 * `DefaultPathReservationService.releasePath` resets cleared signals unconditionally but
	 * `releaseRoute` only looked at the released-block list. The sweeper's counters never
	 * incremented for that case, and because nothing else in the sweeper distinguishes "released"
	 * from "genuine failure", a still-visible holding would have been retried on every subsequent
	 * sweep. This wires the REAL [DefaultNetworkActuatorPort] (not a hand-rolled fake) against a
	 * [PathReservationService] stub that models exactly that shape -- zero released blocks, one
	 * cleared signal -- so the test fails before the task A7 fix exactly as production would, and
	 * pins the fixed contract at the sweeper's own boundary: a `releaseRoute` that is `true` only
	 * because a signal was cleared is counted exactly like a block release, and once the release
	 * is reflected in a later snapshot the owner is not revisited.
	 */
	@Test
	@DisplayName("a signals-only reclaim (zero blocks released) is still counted and is not retried")
	fun signalsOnlyReclaimIsCountedAndNotRetried() {
		val reservationService = mockk<PathReservationService>(relaxed = true)
		every { reservationService.hasClearedSignals("Train #1") } returns true
		every { reservationService.releasePath("Train #1") } returns emptyList<DynamicTrackBlock>()
		val env = mockk<SimulationEnvironment>(relaxed = true)
		val actuator = DefaultNetworkActuatorPort(env = env, pathReservationService = reservationService)

		val held = listOf(reserved("k1", "Train #1"))
		val perceptionPort = mockk<NetworkPerceptionPort>()
		val sensorPort = mockk<DispatchLoopSensorPort>()
		every { sensorPort.getQueuedTrains() } returns emptyList()
		val sweeper =
			OrphanReservationSweeper(
				perceptionPort = perceptionPort,
				dispatchLoopSensorPort = sensorPort,
				actuatorPort = actuator,
				staleAfterSimSeconds = 60.0
			)

		every { perceptionPort.snapshot() } returns
			SimulationSnapshot.EMPTY.copy(
				simTime = 10.0,
				blocks = held,
				trainPositions =
					listOf(
						TrainPositionReading(
							trainId = "Train #1",
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					)
			)
		sweeper.sweep() // First sight: the staleness clock starts, nothing released yet.

		every { perceptionPort.snapshot() } returns
			SimulationSnapshot.EMPTY.copy(
				simTime = 70.0,
				blocks = held,
				trainPositions =
					listOf(
						TrainPositionReading(
							trainId = "Train #1",
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					)
			)
		sweeper.sweep() // Past the threshold: releaseRoute is called -- a signals-only reclaim.

		assertThat(sweeper.staleReleaseCount, "staleReleaseCount after the signals-only reclaim").isEqualTo(1)

		// The release has taken effect: the block is FREE and unowned again, and this train has
		// nothing left to release (both stubs now report empty/false, the state releaseRoute left
		// it in).
		every { reservationService.hasClearedSignals("Train #1") } returns false
		every { perceptionPort.snapshot() } returns
			SimulationSnapshot.EMPTY.copy(
				simTime = 71.0,
				blocks = listOf(reading("k1", null, TrackFacility.State.FREE))
			)
		sweeper.sweep()

		assertThat(sweeper.staleReleaseCount, "staleReleaseCount must not double count").isEqualTo(1)
		// The owner no longer appears in the snapshot at all (its block is FREE), so the sweeper
		// never revisits it -- releaseRoute is called exactly once across all three sweeps.
		verify(exactly = 1) { reservationService.releasePath("Train #1") }
	}

	@Test
	@DisplayName("a network with nothing reserved releases nothing")
	fun idleNetworkReleasesNothing() {
		val sweeper =
			sweepAll(
				ticks =
					listOf(
						Tick(10.0, listOf(reading("k1", null, TrackFacility.State.FREE))),
						Tick(200.0, listOf(reading("k1", null, TrackFacility.State.FREE)))
					)
			)

		verify(exactly = 0) { actuatorPort.releaseRoute(any()) }
		assertThat(sweeper.phantomReleaseCount).isEqualTo(0)
		assertThat(sweeper.staleReleaseCount).isEqualTo(0)
	}

	/**
	 * The counters are only worth keeping if a run reports them. Round 3 measures the sweeper's
	 * effect on throughput, and "reclaimed nothing" has to be distinguishable from "never ran" —
	 * the exact confusion that let round 1's unwired controller go unnoticed for a whole round of
	 * measurements.
	 */
	@Test
	@DisplayName("the summary line reports sweeps and both release causes")
	fun summaryLineReportsCountersAndCauses() {
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(10.0, listOf(reserved("k1", "ghost")), activeTrains = listOf("Train #1")),
						Tick(20.0, listOf(reserved("kA", "Train #1")), activeTrains = listOf("Train #1")),
						Tick(90.0, listOf(reserved("kA", "Train #1")), activeTrains = listOf("Train #1"))
					)
			)

		val summary = sweeper.summaryLine()

		assertThat(summary).contains("3 sweep")
		assertThat(summary).contains("1 phantom")
		assertThat(summary).contains("1 stale")
	}

	@Test
	@DisplayName("the summary line of an idle run says so rather than reading as if it never ran")
	fun summaryLineDistinguishesIdleFromUnwired() {
		val sweeper = sweepAll(ticks = listOf(Tick(10.0, emptyList()), Tick(20.0, emptyList())))

		val summary = sweeper.summaryLine()

		assertThat(summary).contains("2 sweep")
		assertThat(summary).contains("0 phantom")
		assertThat(summary).contains("0 stale")
	}

	@Test
	@DisplayName("one train's stale route does not drag another train's fresh route with it")
	fun onlyTheStaleOwnerIsReleased() {
		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(
							10.0,
							listOf(reserved("k1", "Train #1")),
							activeTrains = listOf("Train #1", "Train #2")
						),
						Tick(
							70.0,
							listOf(reserved("k1", "Train #1"), reserved("kA", "Train #2")),
							activeTrains = listOf("Train #1", "Train #2")
						)
					)
			)

		verify(exactly = 1) { actuatorPort.releaseRoute("Train #1") }
		verify(exactly = 0) { actuatorPort.releaseRoute("Train #2") }
		assertThat(sweeper.staleReleaseCount).isEqualTo(1)
	}

	// ── Partial release of an un-travelled tail (Issue #847 round 4, R4-3) ────────────────────

	/**
	 * Records what the sweeper asked to be partially released, without touching real track objects.
	 * The sweeper's job is to decide *which* blocks qualify and *when*; whether the release itself
	 * is interlocking-safe is [cz.vutbr.fit.interlockSim.dispatcher.RegistryPartialRouteReleaser]'s
	 * concern and is covered by its own test against a real reserved path.
	 */
	private class RecordingPartialReleaser(
		private val releaseResult: (List<String>) -> List<String> = { it }
	) : PartialRouteReleaser {
		val calls = mutableListOf<Pair<String, List<String>>>()

		override fun releaseUntravelledTail(
			trainId: String,
			blockIds: List<String>
		): List<String> {
			calls += trainId to blockIds
			return releaseResult(blockIds)
		}
	}

	/**
	 * The case round 3 measured most often and could not reclaim: a train stopped **on** a block it
	 * occupies while holding the next block RESERVED and empty ahead of it. Round 1 of the run ended
	 * with `Train #1` occupying `kB` and holding `kA`; 301 sweeps reclaimed nothing, because any
	 * occupied block aborts the whole-route path — and it must, since `releasePath` ends in
	 * `registry.unregister(trainId)`, which would drop the block the train is standing on.
	 *
	 * Only the un-travelled tail may go.
	 */
	@Test
	@DisplayName("a stopped train's un-travelled tail is released while the block it stands on is kept")
	fun untravelledTailIsReleasedButOccupiedBlockIsKept() {
		val releaser = RecordingPartialReleaser()
		val held = listOf(occupied("kB", train), reserved("kA", train))

		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(simTime = 0.0, blocks = held, activeTrains = listOf(train)),
						Tick(simTime = 70.0, blocks = held, activeTrains = listOf(train))
					),
				partialReleaser = releaser
			)

		assertThat(releaser.calls, "partial-release calls").hasSize(1)
		assertThat(releaser.calls.single().first, "train").isEqualTo(train)
		assertThat(releaser.calls.single().second, "blocks offered for release").containsExactly("kA")
		assertThat(sweeper.partialReleaseCount, "partialReleaseCount").isEqualTo(1)
		// The whole-route paths must not have fired: one would have dropped the occupied block.
		assertThat(sweeper.staleReleaseCount, "staleReleaseCount").isEqualTo(0)
		assertThat(sweeper.phantomReleaseCount, "phantomReleaseCount").isEqualTo(0)
	}

	@Test
	@DisplayName("a tail held for less than the staleness threshold is left alone")
	fun freshTailIsNotReleased() {
		val releaser = RecordingPartialReleaser()
		val held = listOf(occupied("kB", train), reserved("kA", train))

		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(simTime = 0.0, blocks = held, activeTrains = listOf(train)),
						Tick(simTime = 30.0, blocks = held, activeTrains = listOf(train))
					),
				partialReleaser = releaser
			)

		assertThat(releaser.calls, "partial-release calls").isEmpty()
		assertThat(sweeper.partialReleaseCount, "partialReleaseCount").isEqualTo(0)
	}

	/**
	 * A train that is moving through its route re-enters blocks and changes which ones it holds.
	 * Every change restarts the clock, so a merely slow train is never mistaken for a stalled one.
	 */
	@Test
	@DisplayName("a train that advances into its tail restarts the clock instead of being swept")
	fun advancingTrainRestartsTheClock() {
		val releaser = RecordingPartialReleaser()

		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(0.0, listOf(occupied("kB", train), reserved("kA", train)), activeTrains = listOf(train)),
						// Train moved up: kA is now the occupied one, k1 is the new tail.
						Tick(50.0, listOf(occupied("kA", train), reserved("k1", train)), activeTrains = listOf(train)),
						Tick(80.0, listOf(occupied("kA", train), reserved("k1", train)), activeTrains = listOf(train))
					),
				partialReleaser = releaser
			)

		assertThat(releaser.calls, "partial-release calls").isEmpty()
		assertThat(sweeper.partialReleaseCount, "partialReleaseCount").isEqualTo(0)
	}

	@Test
	@DisplayName("a train holding only the block it occupies has no tail to release")
	fun noTailMeansNoRelease() {
		val releaser = RecordingPartialReleaser()
		val held = listOf(occupied("kB", train))

		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(0.0, held, activeTrains = listOf(train)),
						Tick(90.0, held, activeTrains = listOf(train))
					),
				partialReleaser = releaser
			)

		assertThat(releaser.calls, "partial-release calls").isEmpty()
		assertThat(sweeper.partialReleaseCount, "partialReleaseCount").isEqualTo(0)
	}

	/**
	 * Round 3 shipped without a partial releaser and must keep working exactly as it did: with none
	 * wired, an occupying train is simply skipped, never whole-route released.
	 */
	@Test
	@DisplayName("with no partial releaser wired, an occupying train is skipped as before")
	fun withoutReleaserOccupyingTrainIsSkipped() {
		val held = listOf(occupied("kB", train), reserved("kA", train))

		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(0.0, held, activeTrains = listOf(train)),
						Tick(90.0, held, activeTrains = listOf(train))
					)
			)

		assertThat(sweeper.partialReleaseCount, "partialReleaseCount").isEqualTo(0)
		assertThat(sweeper.staleReleaseCount, "staleReleaseCount").isEqualTo(0)
		verify(exactly = 0) { actuatorPort.releaseRoute(any()) }
	}

	/**
	 * The releaser is the authority on what could actually be released — it refuses anything whose
	 * interlocking state cannot be made safe. The counter must reflect blocks actually reclaimed,
	 * not blocks offered, or the end-of-run summary would overstate what the sweeper achieved.
	 */
	@Test
	@DisplayName("a release the releaser refuses is not counted")
	fun refusedReleaseIsNotCounted() {
		val releaser = RecordingPartialReleaser(releaseResult = { emptyList() })
		val held = listOf(occupied("kB", train), reserved("kA", train))

		val sweeper =
			sweepAll(
				staleAfterSimSeconds = 60.0,
				ticks =
					listOf(
						Tick(0.0, held, activeTrains = listOf(train)),
						Tick(90.0, held, activeTrains = listOf(train))
					),
				partialReleaser = releaser
			)

		assertThat(releaser.calls, "partial-release calls").hasSize(1)
		assertThat(sweeper.partialReleaseCount, "partialReleaseCount").isEqualTo(0)
	}
}
