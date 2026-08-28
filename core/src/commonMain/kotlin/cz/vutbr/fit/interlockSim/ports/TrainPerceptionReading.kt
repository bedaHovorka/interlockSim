/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import cz.vutbr.fit.interlockSim.objects.cells.Signal

/**
 * Immutable first-person perception snapshot of a single train at one simulation instant.
 *
 * Produced by [NetworkPerceptionPort.trainPerception] and
 * [NetworkPerceptionPort.allTrainPerceptions] on the kDisco simulation thread, and
 * by [SnapshotProjectionNetworkPerceptionPort] off-thread from the most recent
 * [SimulationSnapshot.trainPerceptions].
 *
 * This reading bundles all information a reactive train agent (SP2a, Issue #537) needs
 * for its sense → decide → act loop:
 *
 * | Perception facet | Fields |
 * |---|---|
 * | Signal ahead (immediate) | [signalAheadName], [signalAheadAspect], [distanceToSignalAheadMetres] |
 * | Second signal ahead (next along reserved route) | [nextSignalAheadName], [nextSignalAheadAspect] |
 * | Speed limit   | [currentSpeedLimitMps] |
 * | Own kinematics | [velocity], [acceleration], [totalDistance], [frontSectionName] |
 * | Next timetable event | [destinationInOutName], [scheduledArrivalTime] |
 * | Dwell state   | [isDwelling] |
 *
 * ## Signal-ahead semantics
 *
 * [signalAheadAspect] reflects the signal at the **next semaphore** along the reserved
 * path, as seen by the train's [cz.vutbr.fit.interlockSim.sim.Train.nextSemaphore]
 * method. Both fields are `null` when no path is currently reserved for the train
 * (e.g. the train is still queued at an InOut waiting for the interlocking to set a route).
 *
 * [nextSignalAheadAspect] reflects the signal at the **second** semaphore along the reserved
 * route — the one after [signalAheadName]. It is `null` when there is no second semaphore on the
 * reserved route (no route reserved, or the train is within one semaphore of its destination InOut).
 * Only the **aspect/name** of the second signal is exposed (not its distance) — the SP2a.2
 * decision-maker derives the second signal's distance from the immediate
 * [distanceToSignalAheadMetres] and the known track-segment lengths along the reserved route.
 *
 * ## Předvěst / Výstraha are ENCODED by the 2-aspects pair (SŽ D1)
 *
 * Per Czech railway signalling rules (SŽ D1), a *předvěst* (distant signal) does not introduce a
 * new aspect — it **encodes the next main signal's aspect**, because "brzdná dráha je delší než
 * viditelnost návěstidla" (the braking distance may exceed the sight distance to the main signal).
 * Likewise an *oddílové návěstidlo* (block signal) "zároveň informuje o návěsti následujícího
 * návěstidla", and the *LS* liniový vlakový zabezpečovač (cab signalling) conveys the next aspect
 * in-cab for the same reason. The `Signal` enum deliberately has **no** `Výstraha` (Caution) value
 * (see [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore.Signal]: `STOP, S30…S100,
 * FREE`, where `isAllowing() = allowedSpeed > 0`); Výstraha is *derived*, not stored.
 *
 * The `(signalAheadAspect, nextSignalAheadAspect)` pair this reading exposes **is** that
 * distant-signal model — a reactive train agent (SP2a.2) decodes it as driver intent:
 *
 * - `signalAheadAspect == STOP` → stop at the immediate signal.
 * - `signalAheadAspect.isAllowing() && nextSignalAheadAspect == STOP` → **Výstraha**: may proceed
 *   past the immediate signal but must brake to stop at the second signal — begin braking now
 *   (braking distance may exceed the sight to the second signal).
 * - `signalAheadAspect.isAllowing() && nextSignalAheadAspect.isAllowing()` → **Volno ahead**: no
 *   advance braking needed.
 * - `signalAheadAspect == S<X> && nextSignalAheadAspect == STOP` → speed-restricted caution
 *   ("očekávej rychlost X" combined with Výstraha): proceed at ≤ X km/h, then brake to stop.
 * - `signalAheadAspect == S<X> && nextSignalAheadAspect.isAllowing()` → proceed at ≤ X km/h, next
 *   signal clear.
 * - `nextSignalAheadAspect == null` → no second signal on the reserved route (within one semaphore
 *   of the destination InOut); only the immediate aspect governs.
 *
 * This is why exposing up to 2 aspects ahead replaces the need for a dedicated `Výstraha` enum
 * value: the pair carries exactly what a předvěst / oddílové návěstidlo / LS conveys to a driver.
 *
 * ### SP2b-scope gaps (not this PR)
 *
 * - Signal **type** (hlavní / cestové / oddílové / předvěst) is not surfaced in the perception —
 *   `DynamicRailSemaphore.signal` is a single aspect with no type distinction. The 2-aspects pair
 *   carries braking intent, but type still matters for movement authority; deferred to SP2b.
 * - Sight / braking-distance context ("rozhledové poměry", `zábrzdná vzdálenost`) is not exposed —
 *   only the immediate [distanceToSignalAheadMetres] is; the decision-maker derives the rest.
 * - LS-style continuous supervision is out of scope (the train decision-maker is an algorithmic
 *   `:core` policy, not a cab-signalling system).
 *
 * ## Speed-limit semantics
 *
 * [currentSpeedLimitMps] is the physical track-section speed limit of the reserved path
 * from the train's current position onwards, computed via [cz.vutbr.fit.interlockSim.objects.paths.Path.maxSpeed].
 * It is independent of the signal aspect: even if the signal is FREE, the track limit may
 * be lower. When no path is reserved, it falls back to
 * [cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED] (no known constraint).
 *
 * ## Dwell semantics
 *
 * [isDwelling] is `true` when the train is not moving (velocity = 0). This covers both
 * *waiting at a STOP signal* and *holding at a station between direction reversals*.
 * The reactive agent's decision step (SP2a.2) can distinguish these cases by inspecting
 * [signalAheadAspect]: if aspect is [Signal.STOP] the train is blocked by a signal; if
 * aspect is allowing (or null) the train may be at a station dwell.
 *
 * [isStationDwelling] is narrower: it is `true` only while a commanded station dwell
 * (SP2a.3 [cz.vutbr.fit.interlockSim.ports.TrainActuatorPort.holdAtStation]) is actively
 * running. Unlike [isDwelling] it flips back to `false` the moment the dwell expires, even
 * though the train is still at rest — this is the signal the act step's agent uses to know
 * the minimum dwell has elapsed and it may accelerate away.
 *
 * @property trainId Train identifier (e.g. `"Train #1"`).
 * @property signalAheadName Name of the next semaphore ahead, or `null` if no path set.
 * @property signalAheadAspect [Signal] aspect of the next semaphore, or `null` if no path.
 * @property nextSignalAheadName Name of the **second** semaphore ahead (the one after the
 *   immediate next semaphore along the reserved route), or `null` if there is no second
 *   semaphore on the reserved route. Defaults to `null` for call sites that predate this
 *   field (snapshot EMPTY, projection, tool).
 * @property nextSignalAheadAspect [Signal] aspect of the **second** semaphore ahead, or
 *   `null` if there is none. Together with [signalAheadAspect] this pair encodes předvěst /
 *   Výstraha semantics (see "Předvěst / Výstraha are ENCODED by the 2-aspects pair" above).
 *   Defaults to `null` for legacy call sites.
 * @property distanceToSignalAheadMetres Distance from the train's front to the next
 *   semaphore in metres (≥ 0). Zero when no path is reserved.
 * @property currentSpeedLimitMps Maximum allowed speed from the reserved path in **m/s**.
 *   Reflects physical track constraints, independent of signal aspect. Falls back to
 *   [cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED] when no path is set.
 * @property velocity Current speed of the train's front in **m/s** (≥ 0).
 * @property acceleration Current acceleration in **m/s²** (positive = accelerating,
 *   negative = braking).
 * @property totalDistance Total distance traveled by the train's front since departure
 *   from the origin InOut, in **metres** (≥ 0).
 * @property frontSectionName Name of the track block currently containing the train's
 *   front, or `null` if not yet entered.
 * @property destinationInOutName Name of the InOut at which this train aims to arrive on
 *   its current leg of the journey (e.g. `"A"`, `"B"`).
 * @property scheduledArrivalTime Simulation time (in seconds) at which the train is
 *   scheduled to arrive at [destinationInOutName]. Zero if unscheduled.
 * @property isDwelling `true` when the train is stopped (velocity = 0).
 * @property isStationDwelling `true` while a commanded station dwell is actively in
 *   progress (SP2a.3). Defaults to `false` for call sites that predate this field
 *   (snapshot EMPTY, projection, tool).
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 */
data class TrainPerceptionReading(
	val trainId: String,
	val signalAheadName: String?,
	val signalAheadAspect: Signal?,
	val distanceToSignalAheadMetres: Double,
	val currentSpeedLimitMps: Double,
	val velocity: Double,
	val acceleration: Double,
	val totalDistance: Double,
	val frontSectionName: String?,
	val destinationInOutName: String,
	val scheduledArrivalTime: Double,
	val isDwelling: Boolean,
	val nextSignalAheadName: String? = null,
	val nextSignalAheadAspect: Signal? = null,
	val isStationDwelling: Boolean = false
)
