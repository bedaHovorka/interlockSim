/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The reservation kernel withdrew a holding its train did not use (Issue #1243; the Goal 9B demand
 * recorded in SP12.8). Emitted by [DefaultReservationExpiry] once per completed expiry -- on the call
 * that frees the last un-entered block -- never on a deferral, never on a release that threw, and
 * never by `cancel_route` or the orphan sweeper, whose releases are the dispatcher's own acts.
 *
 * Delivered as a kDisco `SimulationEvent.Custom` payload on the simulation thread, synchronously
 * inside the expiry call (the loop tick, or the top of another train's `reservePath`); subscribe
 * through [onReservationExpired] before `run()`. A listener must only record: it runs inside the
 * reservation service and must not call back into a mutating seam. The expiry is counted
 * (`expiredHoldingCount` / `expiredTailCount`) before it is delivered, so a listener reading the
 * counters sees it included.
 *
 * All fields are read at emission time on the simulation thread.
 */
data class ReservationExpired(
	/** The train whose holding was withdrawn. */
	val trainId: String,
	/** Which rule condemned the holding: phase 1 or phase 2. */
	val kind: Kind,
	/**
	 * The blocks the completing release call freed, in release order. After an approach-lock
	 * deferral this is only the remaining head: the earlier call's blocks went out as
	 * [BlockEvent.BlockReleased] already and are not repeated here.
	 */
	val releasedBlocks: List<DynamicTrackBlock>,
	/**
	 * The blocks the train stands on, which the release kept. Empty for [Kind.WHOLE_HOLDING]. A
	 * consumer that sees two [Kind.OCCUPYING_TRAIN_TAIL] expiries of one train with the same
	 * [keptBlocks] knows the train was granted a route in between and still did not reach a new block.
	 * Two empty lists compare equal too, so a consumer keying on this footprint reads two
	 * [Kind.WHOLE_HOLDING] expiries as no movement: the dispatcher's test-source quarantine keeps a
	 * present train standing on nothing quarantined until it leaves the snapshot, on purpose.
	 */
	val keptBlocks: List<DynamicTrackBlock>,
	/**
	 * Phase 1: the age of the train's newest block registration; phase 2: the length of its
	 * standstill (ruling D2 on #1220). Simulated seconds, unrounded; always >= [ttlSimSeconds].
	 */
	val stalledForSimSeconds: Double,
	/** The TTL that condemned the holding (`DefaultPathReservationService.reservationTtlSimSeconds`). */
	val ttlSimSeconds: Double,
	/** Simulation time of the completing release. */
	val time: Double
) {
	/** Which expiry rule condemned the holding (Issue #1243). */
	enum class Kind {
		/** Phase 1 (Issue #1174): the train stood on nothing and its whole holding went. */
		WHOLE_HOLDING,

		/** Phase 2 (Issue #1220): the train stands on [keptBlocks]; only its un-entered RESERVED tail went. */
		OCCUPYING_TRAIN_TAIL
	}
}

private val logger = KotlinLogging.logger {}

/**
 * Subscribe to [ReservationExpired] (Issue #1243). Sugar over [SimulationEnvironment.onSimulationEvent],
 * with its contract: register before `run()`, delivered synchronously on the simulation thread.
 *
 * Each subscriber registered here is isolated: a [listener] that throws is logged at WARN and the
 * event still reaches the subscribers after it. kDisco's bus walks its listeners with no guard of its
 * own, so only subscribers registered through this function are isolated; a raw
 * [SimulationEnvironment.onSimulationEvent] subscriber that throws ends delivery to the ones after
 * it, and [DefaultReservationExpiry] then only contains the failure so the completed expiry stands.
 */
fun SimulationEnvironment.onReservationExpired(listener: (ReservationExpired) -> Unit) =
	onSimulationEvent { event ->
		val payload = (event as? SimulationEvent.Custom)?.payload
		if (payload is ReservationExpired) {
			try {
				listener(payload)
			} catch (e: Exception) {
				logger.warn(e) { "A ReservationExpired listener failed for '${payload.trainId}' (Issue #1243)" }
			}
		}
	}
