/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import java.awt.Color

/**
 * Centralized color scheme for railway animation rendering.
 *
 * Provides standard railway signaling colors for track block states, semaphore signals, and trains,
 * following conventional railway color coding:
 * - Track states: Gray (free), Yellow (reserved), Red (occupied)
 * - Signals: Red (stop), Yellow (limited speed), Green (full speed)
 * - Trains: Blue (body), White (ID text)
 *
 * All colors are defined as sRGB values following standard railway conventions.
 *
 * **Thread Safety:** This object is immutable and thread-safe.
 *
 * **Usage:**
 * ```kotlin
 * val trackColor = AnimationColors.forTrackState(TrackFacility.State.OCCUPIED)  // Red
 * val signalColor = AnimationColors.forSignal(Signal.STOP)                     // Red
 * val trainBodyColor = AnimationColors.TRAIN_BODY                              // Blue
 * ```
 *
 * @since 2026-01-22 (Issue #202, #203)
 */
object AnimationColors {
	// ========== Track Block State Colors ==========

	/** Track block is free (available for reservation) - Standard gray */
	val TRACK_FREE: Color = Color(0x80, 0x80, 0x80)

	/** Track block is reserved (path set up, waiting for train) - Standard yellow */
	val TRACK_RESERVED: Color = Color(0xFF, 0xFF, 0x00)

	/** Track block is occupied (train present) - Standard red */
	val TRACK_OCCUPIED: Color = Color(0xFF, 0x00, 0x00)

	// ========== Semaphore Signal Colors ==========

	/** Signal showing STOP (Hp0) - Standard red */
	val SIGNAL_STOP: Color = Color(0xFF, 0x00, 0x00)

	/** Signal showing limited speed 40 km/h (Hp2) - Standard yellow */
	val SIGNAL_ALLOW_40: Color = Color(0xFF, 0xFF, 0x00)

	/** Signal showing full/higher speed (Hp1) - Standard green */
	val SIGNAL_ALLOW: Color = Color(0x00, 0xFF, 0x00)

	// ========== Default/Fallback Colors ==========

	/** Default track color when state is unknown - Light gray */
	val DEFAULT_TRACK: Color = Color(0xC0, 0xC0, 0xC0)

	/** Default signal color when state is unknown - Light gray */
	val DEFAULT_SIGNAL: Color = Color(0xC0, 0xC0, 0xC0)

	// ========== Train Colors ==========

	/** Train body color for trains from InOut B - Blue */
	val TRAIN_FROM_B: Color = Color(0x00, 0x00, 0xFF)

	/** Train body color for trains from InOut A - Orange */
	val TRAIN_FROM_A: Color = Color(0xFF, 0x8C, 0x00)

	/** Train border color - Black */
	val TRAIN_BORDER: Color = Color(0x00, 0x00, 0x00)

	/** Train ID text color - White */
	val TRAIN_ID: Color = Color(0xFF, 0xFF, 0xFF)

	/**
	 * Returns the rendering color for a track facility state.
	 *
	 * Maps track states to standard railway colors:
	 * - [TrackFacility.State.FREE] → Gray (0x808080)
	 * - [TrackFacility.State.RESERVED] → Yellow (0xFFFF00)
	 * - [TrackFacility.State.OCCUPIED] → Red (0xFF0000)
	 *
	 * @param state The track facility state to map
	 * @return The corresponding color for rendering
	 */
	fun forTrackState(state: TrackFacility.State): Color =
		when (state) {
			TrackFacility.State.FREE -> TRACK_FREE
			TrackFacility.State.RESERVED -> TRACK_RESERVED
			TrackFacility.State.OCCUPIED -> TRACK_OCCUPIED
		}

	/**
	 * Returns the rendering color for a semaphore signal.
	 *
	 * Maps signal aspects to standard railway colors:
	 * - [Signal.STOP] → Red (0xFF0000) - Hp0: Stop signal
	 * - [Signal.S40] → Yellow (0xFFFF00) - Hp2: Proceed at 40 km/h
	 * - All other allowing signals (S30, S60, S80, S100, FREE) → Green (0x00FF00) - Hp1: Proceed
	 *
	 * Note: S40 is rendered in yellow to distinguish speed restriction,
	 * all other allowing signals use green for "proceed" indication.
	 *
	 * @param signal The signal aspect to map
	 * @return The corresponding color for rendering
	 */
	fun forSignal(signal: Signal): Color =
		when (signal) {
			Signal.STOP -> SIGNAL_STOP
			Signal.S40 -> SIGNAL_ALLOW_40
			Signal.S30, Signal.S60, Signal.S80, Signal.S100, Signal.FREE -> SIGNAL_ALLOW
		}
}
