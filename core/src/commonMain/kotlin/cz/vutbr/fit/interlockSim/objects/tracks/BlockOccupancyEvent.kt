/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant

/**
 * Type of a block occupancy change event.
 *
 * These events are emitted by [DynamicTrackBlock] when its runtime state changes
 * in a way that is relevant to external observers (dispatcher/AI agents).
 */
enum class BlockOccupancyEventType {
	/** Block transitioned from FREE to RESERVED. */
	BLOCK_RESERVED,

	/** Block transitioned from RESERVED to OCCUPIED. */
	BLOCK_OCCUPIED,

	/** Block transitioned from RESERVED or OCCUPIED back to FREE. */
	BLOCK_RELEASED
}

/**
 * Event describing a change in a track block's occupancy or reservation state.
 *
 * @property block The track block whose state changed
 * @property type The kind of occupancy change
 * @property trainId The train identifier associated with the block, if any
 * @property occupant The physical occupant of the block at the time of the event, if any
 * @property previousState Block state before the transition
 * @property newState Block state after the transition
 * @property simulationTime Current simulation time when the event occurred
 *
 * @since Issue #569 (Goal 10 prerequisite)
 */
data class BlockOccupancyEvent(
	val block: DynamicTrackBlock,
	val type: BlockOccupancyEventType,
	val trainId: String?,
	val occupant: TrackOccupant?,
	val previousState: TrackFacility.State,
	val newState: TrackFacility.State,
	val simulationTime: Double
)

/**
 * Subscriber interface for external agents that need to react to block occupancy
 * or reservation changes.
 */
fun interface BlockOccupancyListener {
	/**
	 * Called whenever a block's occupancy or reservation state changes.
	 *
	 * Implementations must be non-blocking and must not throw, because they are
	 * invoked synchronously from the simulation event loop.
	 */
	fun onBlockOccupancyChanged(event: BlockOccupancyEvent)
}

/**
 * Low-level sink for emitting a [BlockOccupancyEvent].
 *
 * [DynamicTrackBlock] uses this interface to stay decoupled from the concrete
 * listener registry.
 */
fun interface BlockOccupancyEventSink {
	/**
	 * Emit a block occupancy event to the sink.
	 */
	fun emit(event: BlockOccupancyEvent)
}

/**
 * Notifier that maintains a set of [BlockOccupancyListener] subscribers and dispatches
 * events to them.
 */
interface BlockOccupancyNotifier : BlockOccupancyEventSink {
	/**
	 * Subscribe an external agent to block occupancy/release events.
	 */
	fun addBlockOccupancyListener(listener: BlockOccupancyListener)

	/**
	 * Unsubscribe a previously registered external agent.
	 */
	fun removeBlockOccupancyListener(listener: BlockOccupancyListener)
}
