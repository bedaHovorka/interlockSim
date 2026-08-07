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
 *    All flank-protection switches (odvratné výhybky) are in their required (safe) position.
 *    The switch's physical position ([cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch.conf])
 *    is compared against the route's [cz.vutbr.fit.interlockSim.lang.vocab.SwitchSetting.position]
 *    using the canonical `PLUS ↔ MAIN` / `MINUS ↔ BRANCH` mapping; a mismatch denies the route
 *    before any lock is acquired.
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
		 * @property reason English human-readable reason (e.g., "Block U7 occupied by train 42",
		 *                 "Switch V3 locked by train 99"). Czech is permitted only as inline,
		 *                 genuinely untranslatable railway technical terms — see the project
		 *                 CLAUDE.md "Language: English Only" rule.
		 *                 Suitable for dispatcher operator display and agent LLM context.
		 * @property originNotContiguous `true` when this denial is specifically
		 *   [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NonContiguousStart]
		 *   surfaced through [requestRouteByEndpoints] — the requested origin bounds none of the
		 *   blocks the train holds or occupies, so it could never reach the route no matter how
		 *   long it waits. [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort] consults
		 *   this flag on its facade branch to map the denial to
		 *   [cz.vutbr.fit.interlockSim.ports.RouteRequestResult.OriginNotContiguous] instead of the
		 *   default `AllPathsBlocked(0)` collapse. `false` (the default) for every other denial
		 *   reason, which still collapses on the facade branch — widening that is a spin-off
		 *   candidate, not part of this fix (Issue #893, task A-R1b).
		 */
		data class Denied(
			val reason: String,
			val originNotContiguous: Boolean = false
		) : RouteResponse
	}

	/**
	 * Request a train route — atomically check all four ESA-11 conditions.
	 *
	 * **Preconditions (validated before the four conditions):**
	 *
	 * - [route.blocks] must be non-empty — a route with no track sections is denied
	 *   (`"Prázdná jízdní cesta"`). A real route always protects at least one block.
	 * - [entrySignal] must identify the same signal as [route.from] — the signal the kernel
	 *   clears must be the route's entry separator. A mismatch is denied
	 *   (`"Návěstidlo … neodpovídá počátku cesty"`).
	 *
	 * **Conditions checked (in order):**
	 *
	 * 1. **Route freedom (Volnost jízdní cesty)** —
	 *    All blocks in [route.blocks] are FREE (unoccupied and unreserved).
	 *    Returns [RouteResponse.Denied] if any block is occupied/reserved by another train.
	 *
	 * 2. **Switch positions (Správná poloha výhybek)** —
	 *    All running switches in [route.running] and flank switches in [route.flank] must be in
	 *    the position required by their [SwitchSetting.position]. The physical position
	 *    ([cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch.conf]) is compared against
	 *    the required position using the canonical `PLUS ↔ MAIN` / `MINUS ↔ BRANCH` mapping.
	 *    Returns [RouteResponse.Denied] if any switch is not in the required position.
	 *    (The dispatcher is responsible for setting switches to the required position before
	 *    requesting a route; the kernel refuses to clear the signal until it observes them there.)
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
	 * **On success:** The entry signal ([entrySignal]) is cleared to [clearedAspect] (and the
	 * kernel remembers it cleared that signal for the matching [releaseRoute] call), and
	 * [RouteResponse.Granted] is returned. This is the Czech interlocking's "postaveno a volno"
	 * (route is set and signal is clear) response to the dispatcher.
	 *
	 * **On failure:** No locks are acquired, signal remains STOP, and [RouteResponse.Denied]
	 * is returned with a Czech reason. If the locks were acquired but the signal could not be
	 * cleared (unknown signal or unmappable aspect), the locks are rolled back and the request
	 * is denied — [RouteResponse.Granted] is never returned unless the signal actually shows
	 * [clearedAspect].
	 *
	 * @param trainId The train requesting the route (for ownership tracking and conflict detection).
	 * @param entrySignal The signal at which the train is stopped/slowing (will be cleared if request
	 *                    is granted). Must identify the same signal as [route.from].
	 * @param route The requested route, including switch settings and block sections. Must have ≥1 block.
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
	 * SP3.5: String-based thin RPC for agent tool calls.
	 *
	 * Atomically checks C1 (route freedom), C3 (locking), and C4 (conflict exclusion) by
	 * delegating to [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePath].
	 * Condition C2 (switch positions) is **skipped** — the dispatcher agent is responsible for
	 * positioning switches via [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.setSwitchPosition]
	 * before requesting a route. No entry signal is cleared here; signal aspect control is a
	 * separate agent action via tool primitives.
	 *
	 * This method is the implementation point in [DefaultInterlockingFacade];
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort] delegates
	 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.requestRoute] here when an
	 * [InterlockingFacade] is wired in (SP3.5 production path).
	 *
	 * @param trainId            The train requesting the route (for ownership tracking).
	 * @param fromEndpointName   Name of the entry InOut or Semaphore endpoint.
	 * @param toEndpointName     Name of the exit InOut or Semaphore endpoint.
	 * @return [RouteResponse.Granted] with a minimal [TrainRoute] (block list from reservation) if
	 *         the path was reserved; [RouteResponse.Denied] with an English reason otherwise.
	 * @since Issue #573 (SP3.5 — Goal 10)
	 */
	fun requestRouteByEndpoints(
		trainId: String,
		fromEndpointName: String,
		toEndpointName: String
	): RouteResponse

	/**
	 * Release a route — progressively clear locks as the train vacates blocks.
	 *
	 * **Implementation note (SP3.4 initial MVP):**
	 * This implementation performs **atomic release of the entire route** — every block and
	 * switch reserved for [trainId] is released in one call, and the signal the kernel cleared in
	 * the matching [requestRoute] call is reset to STOP. It does **not** release locks
	 * section-by-section as the train physically clears blocks. **Do not call this until the train
	 * has fully vacated the route** — calling it mid-traverse releases blocks the train still
	 * occupies, which is unsafe. Progressive (section-by-section) release is deferred to SP3.5.
	 *
	 * **Signal reset (C4/I4):** The kernel tracks which entry signal it cleared for [trainId]
	 * during [requestRoute]. [exitSignal] is **for logging/audit only** and is NOT used to select
	 * a signal to reset — the kernel resets the signal it actually cleared. If [trainId] has no
	 * active route (no tracked cleared signal), no signal is reset, so a caller error cannot
	 * disrupt another train's cleared entry signal.
	 *
	 * **On invocation:**
	 * - All blocks reserved for [trainId] are released (state transitions: RESERVED/OCCUPIED → FREE).
	 * - All switches locked for [trainId] are unlocked.
	 * - The entry signal the kernel cleared for [trainId] is reset to STOP (safe aspect).
	 * - If [trainId] has no active route, the call succeeds silently (idempotent).
	 *
	 * **Conflict resolution:**
	 * When a train's route is released, waiting trains (with [RouteResponse.Denied] responses)
	 * may become eligible for their requested routes. The dispatcher logic is responsible for
	 * re-querying [requestRoute] for those trains; the kernel does not emit notifications.
	 *
	 * @param trainId The train releasing its route (must match the train that requested the route).
	 * @param exitSignal The signal marking the end of the route. Provided for logging/auditing
	 *                   only; the kernel resets the signal it cleared in [requestRoute], which
	 *                   equals [cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute.from] of that request.
	 *
	 * @since Issue #572
	 */
	fun releaseRoute(
		trainId: String,
		exitSignal: SignalId
	)
}
