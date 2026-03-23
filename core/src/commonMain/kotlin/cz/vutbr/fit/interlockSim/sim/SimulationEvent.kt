package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import kotlin.math.roundToInt

data class SimulationEvent(
	val simulationTime: Double,
	val eventType: ReportType,
	val source: String,
	val message: String
) {
	fun formatTime(): String {
		val totalSeconds = simulationTime.toInt()
		val hours = totalSeconds / 3600
		val minutes = (totalSeconds % 3600) / 60
		val seconds = totalSeconds % 60
		val millis = ((simulationTime - totalSeconds) * 1000).roundToInt().coerceIn(0, 999)
		return "${hours.pad(2)}:${minutes.pad(2)}:${seconds.pad(2)}.${millis.pad(3)}"
	}

	fun formatForDisplay(): String {
		val sourcePrefix = if (source.isNotEmpty()) "$source " else ""
		return "[${formatTime()}] [${eventType.name}] $sourcePrefix$message"
	}

	companion object {
		fun fromContextChangeEvent(event: ContextChangeEvent): SimulationEvent? {
			val reportType = try {
				ReportType.valueOf(event.propertyName)
			} catch (_: IllegalArgumentException) {
				return null
			}
			val fullMessage = event.newValue?.toString() ?: return null
			val parts = fullMessage.trim().split(Regex("\\s+"), limit = 3)
			if (parts.isEmpty()) return null
			val simulationTime = parts[0].toDoubleOrNull() ?: 0.0
			val source = if (parts.size > 1) parts[1] else ""
			// When only one non-time token exists (degenerate input), source and message are the same token.
			val message = if (parts.size > 2) parts[2] else if (parts.size > 1) parts[1] else fullMessage
			return SimulationEvent(simulationTime, reportType, source, message)
		}

		private fun Int.pad(width: Int): String = toString().padStart(width, '0')
	}
}
