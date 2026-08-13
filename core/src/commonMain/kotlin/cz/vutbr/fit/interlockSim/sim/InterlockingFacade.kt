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
		 * Machine-readable cause of a [Denied] response — the discriminant a caller branches on,
		 * as opposed to [Denied.reason], which is prose for a human operator or an LLM.
		 *
		 * ## Why this exists (Issue #834, task alpha-7a)
		 *
		 * [requestRouteByEndpoints] already distinguishes all four
		 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult]
		 * failures, but before this type existed it threw every one of them into the free-text
		 * [Denied.reason]. [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort] had
		 * nothing to branch on, so on its facade branch — the one production always takes —
		 * every denial except the contiguity rejection collapsed to
		 * `RouteRequestResult.AllPathsBlocked(0)`. That mislabelled permanent impossibility as
		 * retryable contention, reported a path count that contradicted its own contract, and
		 * left the facade and legacy branches classifying the same kernel outcome differently.
		 *
		 * Each subtype carries **only payloads that already existed** in the kernel result it is
		 * built from; nothing here is newly computed, and populating it changes no decision.
		 *
		 * @since Issue #834 (SP2c.11 — Goal 10, task alpha-7a)
		 */
		sealed interface DenialCause {
			/**
			 * No topological path connects the requested endpoints. Maps from
			 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NoPathExists].
			 *
			 * Permanent for the requested endpoint pair: retrying is pointless.
			 */
			data object NoPath : DenialCause

			/**
			 * A topological path exists but every candidate was blocked (OCCUPIED or RESERVED by
			 * other trains). Maps from
			 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.AllPathsBlocked].
			 *
			 * The only genuinely retryable denial cause: contention clears on its own.
			 *
			 * @property attemptedPaths Number of topological candidate paths that were checked —
			 *   the kernel's own `candidatePaths.size`, forwarded unchanged. A denial with no
			 *   candidate-path count behind it is [Other], never this.
			 */
			data class AllPathsBlocked(
				val attemptedPaths: Int
			) : DenialCause

			/**
			 * A path exists but a block along it is already owned by another train. Maps from
			 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.Conflict].
			 *
			 * Only string identifiers are carried, so no live domain object escapes the kernel.
			 *
			 * @property blockName     Name of the conflicting block, or `null` if it is unnamed.
			 * @property existingOwner Name of the train that already owns that block.
			 */
			data class Conflict(
				val blockName: String?,
				val existingOwner: String
			) : DenialCause

			/**
			 * The requested origin bounds none of the blocks the train holds or occupies, so it
			 * could never reach the route no matter how long it waits. Maps from
			 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NonContiguousStart].
			 *
			 * Must never be re-sorted into [Conflict] or [AllPathsBlocked]: it is a dispatcher
			 * **output** defect, and folding it into a contention counter is exactly what Issue
			 * #893 stopped. The offending origin name is not repeated here — the port already
			 * knows it (it is the caller's own `fromEndpointName`) and [Denied.reason] names it
			 * and every legal alternative.
			 *
			 * @since Issue #893 (phase alpha, task A-R1b), promoted from the
			 *   `originNotContiguous` boolean by Issue #834 task alpha-7a.
			 */
			data object NonContiguousStart : DenialCause

			/**
			 * One of the four ESA-11 route conditions ([requestRoute]) failed. Distinct from
			 * [Other] (the endpoint-resolution residual): this cause has a four-condition denial
			 * behind it, not an unresolvable endpoint, and it carries a [retryable] flag so a
			 * caller routing it through
			 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort.requestRoute]'s
			 * `classifyDenial` does not collapse transient contention onto the permanent
			 * [NoPath]/[NonContiguousStart] side the way [Other] →
			 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.RouteRequestResult.NoRouteExists]
			 * would.
			 *
			 * `retryable` is decided per underlying reason at the denial site (a
			 * traffic-simulation-expert ruling, sanity-checked with gemma4):
			 * - **Permanent** (`retryable = false`) — a dispatcher *output defect*: an identical
			 *   retry fails identically. Empty route, entry-signal mismatch, and any *unknown
			 *   name* (unknown block/switch/signal the route references) — these are defects in
			 *   the requested route or the network map, not track contention.
			 * - **Transient** (`retryable = true`) — track *contention* that clears on its own:
			 *   a block OCCUPIED/RESERVED by another train (C1), a switch held in the wrong
			 *   position or locked by another train (C2/C3), an atomic-lock conflict where another
			 *   train grabbed a resource first (C3/C4). Retrying the same request later can
			 *   succeed once the other train releases the resource.
			 *
			 * The signal-un-clearable denial (the entry signal is unknown or the requested aspect
			 * has no Signal equivalent) is permanent (`retryable = false`): it is an output/map
			 * defect, not "ahead occupied".
			 *
			 * Only [requestRoute] (the four-condition kernel) produces this cause;
			 * [requestRouteByEndpoints] never does — it goes through the reservation service and
			 * sets [NoPath]/[AllPathsBlocked]/[Conflict]/[NonContiguousStart] instead.
			 *
			 * @property retryable `true` when the underlying reason is transient contention
			 *   (another train holds a resource); `false` when it is a permanent dispatcher
			 *   output defect (unknown name, empty route, mismatched signal, un-clearable signal).
			 *   See [cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade.requestRoute] for the
			 *   per-reason assignment.
			 * @since Issue #834 (SP2c.11 — Goal 10, review finding #2)
			 */
			data class ConditionFailed(
				val retryable: Boolean
			) : DenialCause

			/**
			 * Residual cause: a denial with no reservation outcome behind it and no four-condition
			 * failure behind it either, so no candidate-path count, conflicting owner, or
			 * retryability flag exists to report.
			 *
			 * Covers the endpoint-resolution failures of [requestRouteByEndpoints] (an unknown
			 * endpoint name). Four-condition [requestRoute] denials use [ConditionFailed], not
			 * this cause.
			 *
			 * Callers must **not** classify this as contention — there is no count to report and
			 * a retry is not indicated. See
			 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort.requestRoute].
			 */
			data object Other : DenialCause
		}

		/**
		 * Route request was **denied**: one or more conditions failed, no locks acquired.
		 *
		 * @property reason English human-readable reason (e.g., "Block U7 occupied by train 42",
		 *                 "Switch V3 locked by train 99"). Czech is permitted only as inline,
		 *                 genuinely untranslatable railway technical terms — see the project
		 *                 CLAUDE.md "Language: English Only" rule.
		 *                 Suitable for dispatcher operator display and agent LLM context.
		 *                 **Prose only** — never parse it; branch on [cause] instead.
		 * @property cause Machine-readable discriminant for this denial (Issue #834, task
		 *   alpha-7a). Defaults to [DenialCause.Other], the residual cause, so a denial raised
		 *   without a reservation outcome behind it can never be mistaken for contention.
		 *   [requestRouteByEndpoints] populates it from the kernel result it already holds.
		 *
		 * **Data-class surface change (Issue #834).** [cause] replaced the former
		 * `originNotContiguous: Boolean` as the second *component*, so `component2()` now returns
		 * [DenialCause] and `copy(originNotContiguous = …)` no longer compiles — use
		 * `copy(cause = …)`. No in-repo call site used either form, but an external consumer of
		 * this type would.
		 */
		data class Denied(
			val reason: String,
			val cause: DenialCause = DenialCause.Other
		) : RouteResponse {
			/**
			 * Source-compatibility constructor for Issue #893's `originNotContiguous` call sites.
			 *
			 * @param originNotContiguous `true` selects [DenialCause.NonContiguousStart],
			 *   `false` selects [DenialCause.Other] — the same two-way split this boolean
			 *   expressed before [DenialCause] existed.
			 */
			@Deprecated(
				"Pass a DenialCause instead: it distinguishes all six denial causes, not two.",
				ReplaceWith(
					"Denied(reason, if (originNotContiguous) DenialCause.NonContiguousStart else DenialCause.Other)"
				)
			)
			constructor(
				reason: String,
				originNotContiguous: Boolean
			) : this(
				reason,
				if (originNotContiguous) DenialCause.NonContiguousStart else DenialCause.Other
			)

			/**
			 * `true` when this denial is specifically
			 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult.NonContiguousStart]
			 * surfaced through [requestRouteByEndpoints] — the requested origin bounds none of the
			 * blocks the train holds or occupies (Issue #893, task A-R1b).
			 *
			 * Derived from [cause] since Issue #834 task alpha-7a, which replaced this boolean
			 * with the full [DenialCause] hierarchy. It is retained so #893's semantics and tests
			 * read unchanged, but it answers only one of five questions — branch on [cause].
			 */
			@Deprecated(
				"Branch on `cause` instead; this flag collapses four distinct causes into 'not it'.",
				ReplaceWith("cause is InterlockingFacade.RouteResponse.DenialCause.NonContiguousStart")
			)
			val originNotContiguous: Boolean
				get() = cause is DenialCause.NonContiguousStart
		}
	}

	/**
	 * Request a train route — atomically check all four ESA-11 conditions.
	 *
	 * **Preconditions (validated before the four conditions):**
	 *
	 * - [route.blocks] must be non-empty — a route with no track sections is denied
	 *   (`"Empty route — no track sections"`). A real route always protects at least one block.
	 * - [entrySignal] must identify the same signal as [route.from] — the signal the kernel
	 *   clears must be the route's entry separator. A mismatch is denied
	 *   (`"Signal X does not match route origin Y"`).
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
	 *         the path was reserved; otherwise [RouteResponse.Denied] with an English reason **and**
	 *         a [RouteResponse.DenialCause] identifying which reservation outcome produced it
	 *         (Issue #834, task alpha-7a). Every implementation must set a cause other than
	 *         [RouteResponse.DenialCause.Other] whenever the corresponding
	 *         [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.ReservationResult]
	 *         is available, so callers never have to parse the reason text.
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
