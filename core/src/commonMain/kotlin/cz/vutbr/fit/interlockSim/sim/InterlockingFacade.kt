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

import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute

/**
 * Deterministic safety kernel for ESA-11 interlocking route conditions.
 *
 * The InterlockingFacade owns the four mandatory route conditions atomically:
 *
 * 1. **Volnost jízdní cesty** (Route freedom) —
 *    All blocks in the route are FREE (not reserved for or occupied by other trains).
 *
 * 2. **Správná poloha pojížděných i odvratných výhybek/výkolejek** (Correct switch positions) —
 *    All running switches (pojížděné výhybky) are in the required position.
 *    All flank-protection switches (odvratné výhybky) are locked in safe positions.
 *
 * 3. **Závěr** (Locking / route lock) —
 *    All blocks and switches in the route are locked (reserved) for the requesting train.
 *    The route lock is atomic — either all elements lock or none do.
 *
 * 4. **Vyloučení současně zakázaných cest** (Exclusion of conflicting routes) —
 *    No conflicting route is active (locked for another train).
 *    A conflicting route is one that shares any block or switch with this route.
 *
 * ## Request Contract
 *
 * When a train (or dispatcher LLM agent) requests a route via [requestRoute]:
 * - The kernel **atomically** checks all four conditions
 * - If **all conditions pass**, the route is locked and [RouteResponse.Granted] is returned with:
 *   - The cleared signal aspect (showing the entry signal's new state)
 *   - This is the dispatcher's "postaveno a volno" response (route is set and signal is clear)
 * - If **any condition fails**, [RouteResponse.Denied] is returned with:
 *   - A Czech human-readable reason (block occupied, switch locked by other train, etc.)
 *   - No locks are acquired; the network state is unchanged
 *
 * ## Release Contract
 *
 * When a train vacates its path via [releaseRoute]:
 * - The kernel **progressively** releases the rušení závěru (lock release)
 * - Locks are released incrementally as the train physically clears blocks
 * - (Progressive release is deferred to SP3.5; initial MVP releases the entire route atomically)
 *
 * ## Safety Guarantees (§1, §7, §8 of Issue #533)
 *
 * **No tool or agent may force a proceed aspect.** The kernel never clears a signal
 * unless the four conditions are provably satisfied. The agent layer only produces
 * *intent* via [RouteRequest]; the kernel independently re-validates and enforces safety.
 *
 * @since Issue #572 (SP3.4 — Goal 10)
 */
interface InterlockingFacade {
	/**
	 * Route request response — either GRANTED with cleared aspect, or DENIED with reason.
	 */
	sealed interface RouteResponse {
		/**
		 * Route request was **granted**: all four conditions passed, locks acquired, signal cleared.
		 *
		 * @property clearedAspect The aspect now shown at the entry signal (e.g., `Volno`, `Rychlost(40)`).
		 *                         This is the cleared signal state that authorizes train movement.
		 * @property lockedRoute   The full route that is now locked for this train (for confirmation/logging).
		 */
		data class Granted(
			val clearedAspect: Aspect,
			val lockedRoute: TrainRoute
		) : RouteResponse

		/**
		 * Route request was **denied**: one or more conditions failed, no locks acquired.
		 *
		 * @property reason Czech human-readable reason (e.g., "úsek U7 obsazen vlaku 42",
		 *                 "výhybka V3 zafixovaná vlaku 99").
		 *                 Suitable for dispatcher operator display and agent LLM context.
		 */
		data class Denied(
			val reason: String
		) : RouteResponse
	}

	/**
	 * Request a train route — atomically check all four ESA-11 conditions.
	 *
	 * **Conditions checked (in order):**
	 *
	 * 1. **Route freedom (Volnost jízdní cesty)** —
	 *    All blocks in [route.blocks] are FREE (unoccupied and unreserved).
	 *    Returns [RouteResponse.Denied] if any block is occupied/reserved by another train.
	 *
	 * 2. **Switch positions (Správná poloha výhybek)** —
	 *    All running switches in [route.running] and flank switches in [route.flank] are not
	 *    locked by another train. Returns [RouteResponse.Denied] if any switch is locked.
	 *    (Verifying the actual switch position against [SwitchSetting.position] is deferred
	 *    to SP3.5 — the dispatcher is responsible for setting switches before requesting a route.)
	 *
	 * 3. **Route lock (Závěr)** —
	 *    All blocks and switches are atomically locked (reserved) for [trainId].
	 *    If any lock acquisition fails (already locked by another train), all locks are rolled back
	 *    and [RouteResponse.Denied] is returned. Atomic all-or-nothing semantics.
	 *
	 * 4. **Conflict exclusion (Vyloučení konfliktů)** —
	 *    No other train has a conflicting route (one that shares blocks or switches with this route).
	 *    Returns [RouteResponse.Denied] if a conflicting active route exists.
	 *
	 * **On success:** The entry signal ([entrySignal]) is cleared to [clearedAspect], and
	 * [RouteResponse.Granted] is returned. This is the Czech interlocking's "postaveno a volno"
	 * (route is set and signal is clear) response to the dispatcher.
	 *
	 * **On failure:** No locks are acquired, signal remains STOP, and [RouteResponse.Denied]
	 * is returned with a Czech reason.
	 *
	 * @param trainId The train requesting the route (for ownership tracking and conflict detection).
	 * @param entrySignal The signal at which the train is stopped/slowing (will be cleared if request is granted).
	 * @param route The requested route, including switch settings and block sections.
	 * @param clearedAspect The signal aspect to display if the route is granted
	 *                      (e.g., `Volno`, `Rychlost(40)`, determined by dispatcher intent and network state).
	 *
	 * @return [RouteResponse.Granted] if all conditions passed (locks acquired, signal cleared).
	 *         [RouteResponse.Denied] if any condition failed (no changes to network).
	 *
	 * @since Issue #572
	 */
	fun requestRoute(
		trainId: String,
		entrySignal: SignalId,
		route: TrainRoute,
		clearedAspect: Aspect
	): RouteResponse

	/**
	 * Release a route — progressively clear locks as the train vacates blocks.
	 *
	 * **Implementation note (SP3.4 initial MVP):**
	 * This implementation performs **atomic release** of the entire route.
	 * Progressive release (blocking section-by-section as the train physically clears blocks)
	 * is deferred to SP3.5 (#nnnn).
	 *
	 * **On invocation:**
	 * - All blocks reserved for [trainId] are released (state transitions: RESERVED/OCCUPIED → FREE).
	 * - All switches locked for [trainId] are unlocked.
	 * - The entry signal is reset to STOP (safe aspect).
	 * - If [trainId] has no active route, the call succeeds silently (idempotent).
	 *
	 * **Conflict resolution:**
	 * When a train's route is released, waiting trains (with [RouteResponse.Denied] responses)
	 * may become eligible for their requested routes. The dispatcher logic is responsible for
	 * re-querying [requestRoute] for those trains; the kernel does not emit notifications.
	 *
	 * @param trainId The train releasing its route (must match the train that requested the route).
	 * @param exitSignal The signal marking the end of the route (will be reset to STOP).
	 *                   This is provided for logging/auditing; it must match the [entrySignal]
	 *                   from the corresponding [requestRoute] call.
	 *
	 * @since Issue #572
	 */
	fun releaseRoute(
		trainId: String,
		exitSignal: SignalId
	)
}
