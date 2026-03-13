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

import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.PointF

/**
 * Immutable snapshot of simulation state for animation rendering.
 *
 * This data class captures the complete visual state of the railway simulation
 * at a specific moment in time. It is designed to be thread-safe and immutable,
 * allowing safe transfer of simulation state from the jDisco simulation thread
 * to the Swing Event Dispatch Thread (EDT) for rendering.
 *
 * ## Threading Model
 *
 * - **Captured on:** jDisco simulation thread (via PropertyChangeListener or Reporter)
 * - **Used on:** Swing EDT (by AnimatedSimulationCellRenderer)
 * - **Immutability:** All fields are `val` with immutable collections
 *
 * ## Usage
 *
 * ```kotlin
 * // On simulation thread:
 * val state = captureSimulationState(context)
 *
 * // Marshal to EDT:
 * SwingUtilities.invokeLater {
 *     animationController.updateState(state)
 * }
 *
 * // On EDT during rendering:
 * val trackState = animationState.trackStates[trackBlock]
 * val color = when (trackState?.state) {
 *     State.FREE -> Color.GRAY
 *     State.RESERVED -> Color.YELLOW
 *     State.OCCUPIED -> Color.RED
 *     null -> Color.LIGHT_GRAY // Not tracked
 * }
 * ```
 *
 * @property simulationTime Current simulation time in seconds
 * @property trainStates Map of train number to [TrainState] (immutable)
 * @property trackStates Map of [TrackBlock] to [TrackState] (immutable)
 * @property signalStates Map of [RailSemaphore] to [SignalState] (immutable)
 * @property switchStates Map of [RailSwitch] to [SwitchState] (immutable)
 *
 * @see TrainState
 * @see TrackState
 * @see SignalState
 * @see SwitchState
 * @see AnimationController
 */
data class AnimationState(
	val simulationTime: Double,
	val trainStates: Map<Int, TrainState>,
	val trackStates: Map<TrackBlock, TrackState>,
	val signalStates: Map<RailSemaphore, SignalState>,
	val switchStates: Map<RailSwitch, SwitchState>
) {
	companion object {
		/**
		 * Empty animation state representing the beginning of simulation.
		 *
		 * All collections are empty, simulation time is 0.0.
		 * Used as initial state before first update.
		 */
		val EMPTY =
			AnimationState(
				simulationTime = 0.0,
				trainStates = emptyMap(),
				trackStates = emptyMap(),
				signalStates = emptyMap(),
				switchStates = emptyMap()
			)
	}
}

/**
 * Immutable snapshot of a single train's state at a moment in time.
 *
 * Captures position, velocity, acceleration, and occupancy information
 * needed for visual train rendering and animation.
 *
 * ## Position Representation
 *
 * - **position:** Total distance traveled along the path (meters)
 * - **frontGridLocation:** Grid coordinates for rendering train front (nullable if not calculable)
 *
 * ## Color Coding by Origin InOut
 *
 * - **travelingRight:** Color selector based on train's origin InOut
 *   - true = Blue (trains from InOut "B")
 *   - false = Orange (trains from InOut "A" or other names)
 *   - Trains maintain their origin color throughout their entire journey
 *
 * **Implementation:**
 * Train origin is determined via `Train.originInOut` property which accesses
 * the train's Timetable entry InOut. This provides accurate color coding
 * regardless of train creation order or Generator shuffling.
 *
 * @see AnimationStateCapture.determineOriginColorVariant
 *
 * @property trainNumber Unique train identifier
 * @property position Total distance traveled in meters
 * @property velocity Current velocity in m/s
 * @property acceleration Current acceleration in m/s²
 * @property frontGridLocation Grid coordinates for train front rendering (nullable)
 * @property length Train length in meters
 * @property travelingRight Color selector: true for blue (InOut B), false for orange (InOut A)
 */
data class TrainState(
	val trainNumber: Int,
	val position: Double,
	val velocity: Double,
	val acceleration: Double,
	val frontGridLocation: PointF?,
	val length: Double,
	val travelingRight: Boolean
)

/**
 * Immutable snapshot of a track block's state at a moment in time.
 *
 * Captures occupancy and reservation status needed for track color rendering.
 *
 * ## State Mapping to Colors
 *
 * - [TrackFacility.State.FREE] → Gray (available for path setup)
 * - [TrackFacility.State.RESERVED] → Yellow (path set up, train approaching)
 * - [TrackFacility.State.OCCUPIED] → Red (train currently on block)
 *
 * @property trackBlock Reference to the static track block configuration
 * @property state Current occupancy state from [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrack]
 */
data class TrackState(
	val trackBlock: TrackBlock,
	val state: TrackFacility.State
)

/**
 * Immutable snapshot of a signal's state at a moment in time.
 *
 * Captures semaphore signal indication needed for signal color rendering.
 *
 * ## Signal Mapping to Colors
 *
 * - [Signal.STOP] (Hp0) → Red (train must stop)
 * - [Signal.ALLOW] (Hp1) → Green (train may proceed)
 * - [Signal.ALLOW_40] (Hp2) → Yellow (proceed at 40 km/h)
 * - [Signal.ANNOUNCE] (Vr0/Vr1/Vr2) → Orange/Yellow (advance warning)
 *
 * @property semaphore Reference to the static semaphore configuration
 * @property signal Current signal indication from [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore]
 */
data class SignalState(
	val semaphore: RailSemaphore,
	val signal: Signal
)

/**
 * Immutable snapshot of a railway switch's state at a moment in time.
 *
 * Captures switch configuration (MAIN vs BRANCH) needed for rendering the
 * active segments during animation playback.
 *
 * ## Configuration Mapping
 *
 * - [RailSwitch.Conf.MAIN] → Straight/normal path through switch
 * - [RailSwitch.Conf.BRANCH] → Diverging/turnout path through switch
 *
 * This state is essential for animated playback to show which route was
 * historically set through each switch at any point in time.
 *
 * @property railSwitch Reference to the static switch configuration
 * @property conf Current configuration from [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch]
 */
data class SwitchState(
	val railSwitch: RailSwitch,
	val conf: RailSwitch.Conf
)
