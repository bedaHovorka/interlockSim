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
 * Immutable snapshot of a semaphore's signal aspect at a moment in simulation time.
 *
 * Produced by [NetworkPerceptionPort.signalAspect] and [NetworkPerceptionPort.allSignalAspects].
 * The snapshot captures the signal state at the instant it is read; it is not updated
 * as the simulation advances.
 *
 * ## Signal Mapping
 *
 * - [Signal.STOP] (Hp0) — train must stop; path not set or interlocking clearing
 * - [Signal.S30]/[Signal.S40]/[Signal.S60]/[Signal.S80]/[Signal.S100] — proceed at stated max km/h
 * - [Signal.FREE] — proceed at track-section maximum speed; path fully clear
 *
 * ## Direction fields
 *
 * When [signal] is an allowing aspect (not [Signal.STOP]), [authorizedFrom] and [authorizedTo]
 * identify the segment names (e.g. `"F"`, `"A"`) the current proceed aspect is **actually
 * authorized for** — the direction the reservation flow recorded when clearing the signal, not
 * the semaphore's static forward. A train approaching this semaphore from [authorizedFrom]
 * toward [authorizedTo] may proceed; any other approach direction should be treated as
 * [Signal.STOP]. This matters when a route is reserved for the reverse direction (the
 * vyhybna.xml shunting loop runs both A→B and B→A): a lit forward-facing semaphore cleared for
 * the reverse direction reports the reverse segment pair here, and a forward-facing observer
 * must treat it as STOP.
 *
 * Both fields are `null` when [signal] is [Signal.STOP] (nothing is authorized), and also
 * `null` in tests that construct this class with two-argument syntax and do not supply direction
 * information.
 *
 * @property name Semaphore name as configured in the railway XML (e.g. `"zA"`, `"doB1"`).
 * @property signal Current signal indication.
 * @property authorizedFrom Segment name from which travel is authorized (`Cell.Segment.name`),
 *   or `null` when the signal is not allowing.
 * @property authorizedTo Segment name to which travel is authorized (`Cell.Segment.name`),
 *   or `null` when the signal is not allowing.
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 * @since Issue #812 (direction-aware signal display — [authorizedFrom]/[authorizedTo] added)
 */
data class SemaphoreReading(
	val name: String,
	val signal: Signal,
	val authorizedFrom: String? = null,
	val authorizedTo: String? = null
)
