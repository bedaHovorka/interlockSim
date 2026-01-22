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

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType

/**
 * Represents a single event in the railway simulation.
 *
 * This immutable data class captures discrete events and continuous updates
 * from the simulation engine for display in the event timeline.
 *
 * ## Event Types
 *
 * - **PATH_SETTING** - Path reservation/release commands
 * - **NODE_EVENTS** - Switch/signal state changes
 * - **TRAIN_EVENTS** - Train discrete events (enter signal, stop, exit)
 * - **TRAIN_CONTINUOUS** - Train position/velocity updates (1Hz)
 *
 * ## Usage
 *
 * ```kotlin
 * val event = SimulationEvent(
 *     simulationTime = 123.456,
 *     eventType = ReportType.TRAIN_EVENTS,
 *     message = "Train 1 stopped at signal S1"
 * )
 * ```
 *
 * @property simulationTime Simulation time in seconds (e.g., 123.456)
 * @property eventType Type of event from [ReportType] enum
 * @property message Human-readable event description
 *
 * @see ReportType
 * @see EventTimelinePanel
 */
data class SimulationEvent(
	val simulationTime: Double,
	val eventType: ReportType,
	val message: String
) {
	/**
	 * Format simulation time as HH:MM:SS.mmm.
	 *
	 * Examples:
	 * - 0.0 → "00:00:00.000"
	 * - 61.234 → "00:01:01.234"
	 * - 3661.5 → "01:01:01.500"
	 *
	 * @return Formatted time string
	 */
	fun formatTime(): String {
		val totalSeconds = simulationTime.toInt()
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		val millis = ((simulationTime - totalSeconds) * 1000).toInt()

		return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
	}

	/**
	 * Format event as a single line for display in event timeline.
	 *
	 * Format: `[HH:MM:SS.mmm] [TYPE] message`
	 *
	 * Example: `[00:01:23.456] [TRAIN_EVENTS] Train 1 stopped at signal S1`
	 *
	 * @return Formatted event string
	 */
	fun formatForDisplay(): String {
		return "[${formatTime()}] [${eventType.name}] $message"
	}

	companion object {
		/**
		 * Create a train continuous update event.
		 *
		 * @param simulationTime Simulation time in seconds
		 * @param trainId Train identifier
		 * @param position Position in meters
		 * @param velocity Velocity in m/s
		 * @return New SimulationEvent instance
		 */
		fun trainContinuous(
			simulationTime: Double,
			trainId: String,
			position: Double,
			velocity: Double
		): SimulationEvent {
			return SimulationEvent(
				simulationTime = simulationTime,
				eventType = ReportType.TRAIN_CONTINUOUS,
				message = "Train $trainId: pos=%.2fm, vel=%.2fm/s".format(position, velocity)
			)
		}

		/**
		 * Create a train discrete event.
		 *
		 * @param simulationTime Simulation time in seconds
		 * @param trainId Train identifier
		 * @param event Event description (e.g., "stopped at signal S1")
		 * @return New SimulationEvent instance
		 */
		fun trainEvent(
			simulationTime: Double,
			trainId: String,
			event: String
		): SimulationEvent {
			return SimulationEvent(
				simulationTime = simulationTime,
				eventType = ReportType.TRAIN_EVENTS,
				message = "Train $trainId: $event"
			)
		}

		/**
		 * Create a node state change event.
		 *
		 * @param simulationTime Simulation time in seconds
		 * @param nodeId Node identifier (e.g., switch or signal)
		 * @param event Event description (e.g., "switched to position A")
		 * @return New SimulationEvent instance
		 */
		fun nodeEvent(
			simulationTime: Double,
			nodeId: String,
			event: String
		): SimulationEvent {
			return SimulationEvent(
				simulationTime = simulationTime,
				eventType = ReportType.NODE_EVENTS,
				message = "Node $nodeId: $event"
			)
		}

		/**
		 * Create a path setting event.
		 *
		 * @param simulationTime Simulation time in seconds
		 * @param pathId Path identifier
		 * @param action Action performed (e.g., "reserved", "released")
		 * @return New SimulationEvent instance
		 */
		fun pathSetting(
			simulationTime: Double,
			pathId: String,
			action: String
		): SimulationEvent {
			return SimulationEvent(
				simulationTime = simulationTime,
				eventType = ReportType.PATH_SETTING,
				message = "Path $pathId: $action"
			)
		}
	}
}
