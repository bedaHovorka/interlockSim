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
 * @property name Semaphore name as configured in the railway XML (e.g. `"zA"`, `"doB1"`).
 * @property signal Current signal indication.
 *
 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
 */
data class SemaphoreReading(
	val name: String,
	val signal: Signal,
)
