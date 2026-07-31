/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import java.util.concurrent.ConcurrentHashMap

/**
 * Latches [ConflictDetectedEvent] hints per blocked train (SP2c.4, Issue #827 — Goal 9
 * C7 ruling, option (a)).
 *
 * ## Motivation
 *
 * [ConflictDetectedEvent] is deduplicated to **once per `(trainId, block)` contention**:
 * a train stuck behind a busy block emits exactly one event and then nothing. A one-shot
 * signal would disappear from the affordance annotation after exactly one tick. This latch
 * captures the hint at event time and clears it lazily when the observation shows the blocked
 * train is no longer in [TrainPhase.HELD] — i.e., when the contention resolves.
 *
 * ## Thread safety
 *
 * - [onConflict] is called on the kDisco simulation thread (inside a registered listener).
 * - [updateFromObservation] and [getHint] are called on the agent-driver thread.
 *
 * All mutation uses [ConcurrentHashMap] for safe cross-thread access. [updateFromObservation]
 * calls [retainAll] on the key set of a [ConcurrentHashMap], which is a weakly-consistent
 * operation: it is possible for a concurrent [onConflict] to insert a key that is immediately
 * pruned by an in-flight [updateFromObservation] if the inserted train is not HELD in the
 * current observation. This is safe: the next observation will include the train again if it
 * is genuinely HELD, and the conflict will re-emit only if the block is still contested.
 *
 * ## Deduplication
 *
 * [putIfAbsent] is used so that the first hint for a given blocked train wins. Because
 * [ConflictDetectedEvent] is already deduplicated upstream, re-insertion is unlikely but
 * harmless: the latch stores exactly one hint per `trainId`.
 *
 * @since Issue #827 (SP2c.4 — Goal 10, Goal 9 C7 ruling option (a))
 */
class ConflictHintLatch {
	private val hints: ConcurrentHashMap<String, String> = ConcurrentHashMap()

	/**
	 * Records a conflict hint for the blocked train identified by [event].
	 *
	 * Called on the sim thread; safe to call from any thread.
	 * Uses [putIfAbsent] — the first hint for a given [ConflictDetectedEvent.trainId] wins.
	 */
	fun onConflict(event: ConflictDetectedEvent) {
		val blockId = BlockIdentity.stableBlockId(event.block)
		hints.putIfAbsent(event.trainId, "blocked at $blockId by ${event.conflictingTrainId}")
	}

	/**
	 * Retains hints only for trains that are currently in [TrainPhase.HELD] phase.
	 *
	 * Call this at the start of each [AffordanceAnnotator.annotate] cycle (before reading hints)
	 * so that resolved contentions are cleared before the affordances are computed.
	 *
	 * A train is considered "no longer held" when [observation] does not include it in
	 * [DispatcherObservation.trains] with [TrainPhase.HELD]. Trains that exited or are now
	 * RUNNING/DWELLING no longer need their conflict hints.
	 */
	fun updateFromObservation(observation: DispatcherObservation) {
		val heldTrainIds =
			observation.trains
				.filter { it.phase == TrainPhase.HELD }
				.mapTo(HashSet()) { it.trainId }
		hints.keys.retainAll(heldTrainIds)
	}

	/**
	 * Returns the latched conflict hint for [trainId], or `null` if no hint is stored.
	 *
	 * Called on the agent-driver thread; safe to call from any thread.
	 */
	fun getHint(trainId: String): String? = hints[trainId]

	/**
	 * Returns an immutable snapshot of all currently latched hints (for testing).
	 */
	fun snapshot(): Map<String, String> = HashMap(hints)
}
