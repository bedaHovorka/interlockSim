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

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Reclaims track reservations that nothing else in the system can release (Issue #847 round 3,
 * PR #891 defect B).
 *
 * ## The gap this fills
 *
 * `PathReservationRegistry` releases a reservation in exactly three ways: a train's `Tail`
 * physically leaving a block it entered (`unregisterBlock`), the train's journey completing
 * (`releaseTrainReservations`), or an explicit `releasePath`. Every one of them requires the train
 * to *consume* the route. A route that is granted and never travelled is therefore permanent — the
 * registry keeps no timestamps, and there is no sweeper, timeout or TTL anywhere in it.
 *
 * `MultiTrainLoop` runs `BlockResourceRegistry.releaseFreeResources()` once per iteration as its
 * safety net. `ShuntingLoop` — the loop `shuntingLoopAI` runs — has none, and could not use that
 * one regardless: it holds no kDisco `Resource`s at all, and `BlockResourceRegistry` is `internal`
 * to `:core`. The reclaimable state lives one layer up.
 *
 * Two ways an unconsumable reservation arises, both observed in round-2 verification runs:
 *
 * - **Phantom owner.** `DefaultNetworkActuatorPort.requestRoute` validates endpoint names but
 *   checks the train name only for non-blankness, so an id the model invented reserves real blocks.
 *   No `Train` bears that name, so no tail ever clears it and `cancel_route` rejects the id
 *   precisely because no such train is active. Round 1's `"T1"` cascade was exactly this.
 * - **Stale route.** A route granted to a real train that it never travels, held un-travelled
 *   indefinitely while other trains conflict on those blocks.
 *
 * ## Policy
 *
 * A phantom owner is released on sight. A route held by a known train is released only when it is
 * **entirely un-travelled** — every block RESERVED, none OCCUPIED — and the set of blocks held has
 * not changed for [staleAfterSimSeconds] of simulated time. Any OCCUPIED block, or any change to
 * the held set, means the train is making progress and restarts the clock.
 *
 * ## The occupied-block rule is a safety constraint, not conservatism
 *
 * [NetworkActuatorPort.releaseRoute] delegates to `DefaultPathReservationService.releasePath`,
 * which ends in a `finally` calling `registry.unregister(trainId)` — that clears **every** block
 * registered to the train, including one it is physically standing on. Releasing a train that
 * occupies a block would therefore tell the registry that block is free while a train sits on it,
 * and the next `request_route` could route another train straight into it. The occupied-block check
 * is what prevents that, and it must not be relaxed to catch more cases.
 *
 * ## Known gap: a stopped train holding the next block reserved
 *
 * The case this sweeper does **not** reclaim, measured live in round 3: a train stopped on a block
 * it occupies while holding the next block RESERVED and unoccupied ahead of it — run 1 ended with
 * `Train #1` occupying `kB` and holding `kA` reserved, and 301 sweeps reclaimed nothing. That
 * holding is un-travelled and is blocking other trains, but the train is also legitimately on the
 * network, so the all-or-nothing `releaseRoute` above cannot be used.
 *
 * Reclaiming it needs a **partial** release: dropping only the un-travelled blocks while leaving
 * the occupied one registered. `PathReservationService.unregisterBlock` is public and refuses
 * occupied blocks by its own precondition, so the block-level half is safe — but it skips
 * `cancelPathSetup` and switch unlocking, so the signals and switches of the cancelled portion
 * would stay set. Whether that is an acceptable interlocking state is a railway-domain question,
 * not a coding one, and per TEAM.md it belongs to the traffic-simulation-expert. Deliberately left
 * open rather than guessed at.
 *
 * Queued trains are swept on the same threshold as active ones. The system prompt tells the model a
 * queued train "stays parked, holding its reservation indefinitely, until you separately call
 * approve_train" — and that licence is exactly what makes a never-approved queued train a deadlock
 * source. Past the threshold the hold is dead weight blocking every other train, so it is cancelled
 * like any other stale route. This is the route-cancellation a real dispatcher performs.
 *
 * ## Why this lives outside `core/`
 *
 * Everything it needs is already public: [NetworkPerceptionPort.snapshot] reports each block's
 * state and owning train id, [DispatchLoopSensorPort.getQueuedTrains] the admission queue, and
 * [NetworkActuatorPort.releaseRoute] performs the same cancel-and-unregister that `cancel_route`
 * does. PR #891's zero-`core/`-changes property is preserved.
 *
 * ## Threading
 *
 * [sweep] must run on the kDisco simulation thread — it reads live simulation state and mutates
 * reservations. It is called from the `ControlStepListener` that `ShuntingLoop.iteration()` invokes
 * once per tick, alongside [DispatchDecisionApplier.onControlStep]. Not thread-safe, and does not
 * need to be.
 *
 * **Coverage limitation:** this is wired only into the dispatcher-agent examples. Runs driven by
 * `SynchronousDispatcherWiring` (`:core`, used by `:fast-sim` and the non-AI `shuntingLoop`
 * example) are unaffected — closing that would require a `core/` change.
 *
 * @property perceptionPort Live network state: block ownership, block state, and active trains.
 * @property dispatchLoopSensorPort Source of the admission queue.
 * @property actuatorPort Used to cancel a reclaimed route.
 * @property staleAfterSimSeconds Simulated seconds an entirely un-travelled route may be held
 *   unchanged before it is cancelled.
 *
 * @since Issue #847 (round 3)
 */
class OrphanReservationSweeper(
	private val perceptionPort: NetworkPerceptionPort,
	private val dispatchLoopSensorPort: DispatchLoopSensorPort,
	private val actuatorPort: NetworkActuatorPort,
	private val staleAfterSimSeconds: Double = DEFAULT_STALE_AFTER_SIM_SECONDS
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Default staleness threshold, in simulated seconds.
		 *
		 * Comfortably longer than a train takes to enter a route it actually intends to travel on
		 * `vyhybna.xml` (`ShuntingLoop` ticks once per simulated second and a train clears a block
		 * in well under a minute), so a train that is merely slow is never mistaken for one that
		 * has abandoned its route.
		 */
		const val DEFAULT_STALE_AFTER_SIM_SECONDS: Double = 60.0
	}

	/**
	 * One-line account of what this sweeper did, for the end-of-run summary.
	 *
	 * Reports the sweep count alongside the release counts on purpose: without it, "reclaimed
	 * nothing" and "never ran at all" read identically — and the second is exactly the failure that
	 * let round 1's unwired `ThrottlingSimulationController` survive a whole round of measurements
	 * before anyone noticed the runs were empty.
	 */
	fun summaryLine(): String =
		"OrphanReservationSweeper: $sweepCount sweeps, " +
			"${phantomReleaseCount + staleReleaseCount} route(s) reclaimed " +
			"($phantomReleaseCount phantom-owner, $staleReleaseCount stale)"

	/** Emits [summaryLine] at INFO. Call once, after the run has ended. */
	fun logSummary() {
		logger.info { summaryLine() }
	}

	/** What a train held, and since when, the last time its holding changed. */
	private data class Holding(
		val blockIds: List<String>,
		val sinceSimTime: Double
	)

	private val holdings = mutableMapOf<String, Holding>()

	/** Routes cancelled because their owner matched no known train. */
	var phantomReleaseCount: Int = 0
		private set

	/** Routes cancelled because they were held un-travelled past [staleAfterSimSeconds]. */
	var staleReleaseCount: Int = 0
		private set

	/**
	 * Times [sweep] has run. Gives the release counts their denominator in the end-of-run summary,
	 * and distinguishes "swept every tick and found nothing" from "never ran at all" — the latter
	 * being the failure mode that made round 1's controller fix necessary in the first place.
	 */
	var sweepCount: Long = 0
		private set

	init {
		require(staleAfterSimSeconds > 0.0) {
			"staleAfterSimSeconds must be positive, was $staleAfterSimSeconds"
		}
	}

	/**
	 * Examines every held reservation once and cancels those that qualify. Call once per
	 * simulation tick, on the simulation thread.
	 */
	fun sweep() {
		sweepCount++
		val snapshot = perceptionPort.snapshot()
		val knownTrainIds =
			snapshot.trainPositions.map { it.trainId }.toSet() +
				dispatchLoopSensorPort.getQueuedTrains().map { it.trainId }.toSet()

		val heldByTrain: Map<String, List<BlockOccupancyReading>> =
			snapshot.blocks
				.mapNotNull { block -> block.trainId?.let { it to block } }
				.groupBy({ it.first }, { it.second })

		for ((owner, blocks) in heldByTrain) {
			if (owner !in knownTrainIds) {
				releasePhantom(owner, blocks)
			} else {
				evaluateKnownTrain(owner, blocks, snapshot.simTime)
			}
		}

		// Forget trains that no longer hold anything, so a later route of theirs starts a fresh
		// clock rather than inheriting a stale one.
		holdings.keys.retainAll(heldByTrain.keys)
	}

	private fun releasePhantom(
		owner: String,
		blocks: List<BlockOccupancyReading>
	) {
		logger.warn {
			"OrphanReservationSweeper: releasing route of unknown train '$owner' — no such train is " +
				"active or queued, so nothing can ever consume or cancel it. " +
				"Blocks: ${blocks.joinToString(", ") { it.blockId }}"
		}
		if (actuatorPort.releaseRoute(owner)) {
			phantomReleaseCount++
		}
		holdings.remove(owner)
	}

	private fun evaluateKnownTrain(
		owner: String,
		blocks: List<BlockOccupancyReading>,
		simTime: Double
	) {
		// Any occupied block means the train is on its route and consuming it.
		if (blocks.any { it.state == TrackFacility.State.OCCUPIED }) {
			holdings.remove(owner)
			return
		}

		val blockIds = blocks.map { it.blockId }.sorted()
		val previous = holdings[owner]
		if (previous == null || previous.blockIds != blockIds) {
			holdings[owner] = Holding(blockIds, simTime)
			return
		}

		if (simTime - previous.sinceSimTime < staleAfterSimSeconds) return

		logger.warn {
			"OrphanReservationSweeper: cancelling stale route of '$owner' — held un-travelled for " +
				"${simTime - previous.sinceSimTime}s of simulated time (threshold " +
				"${staleAfterSimSeconds}s). Blocks: ${blockIds.joinToString(", ")}"
		}
		if (actuatorPort.releaseRoute(owner)) {
			staleReleaseCount++
		}
		holdings.remove(owner)
	}
}
