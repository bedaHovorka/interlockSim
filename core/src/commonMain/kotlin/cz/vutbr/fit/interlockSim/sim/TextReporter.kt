package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP

enum class Verbosity {
	QUIET,
	DEFAULT,
	VERBOSE
}

class TextReporter(
	private val verbosity: Verbosity = Verbosity.DEFAULT,
	private val output: (String) -> Unit = ::println
) : ContextPropertyChangeListener {
	private val allowedTypes: Set<ReportType> =
		when (verbosity) {
			Verbosity.QUIET -> emptySet()
			Verbosity.DEFAULT ->
				setOf(
					ReportType.TRAIN_EVENTS,
					ReportType.TRAIN_APPROVED,
					ReportType.NODE_EVENTS,
					ReportType.PATH_SETTING
				)
			Verbosity.VERBOSE ->
				setOf(
					ReportType.TRAIN_EVENTS,
					ReportType.TRAIN_APPROVED,
					ReportType.NODE_EVENTS,
					ReportType.PATH_SETTING,
					ReportType.TRAIN_CONTINUOUS
				)
		}

	private val trainNames = mutableSetOf<String>()
	private var lastSimTime = 0.0
	private val startWallTime = currentTimeMillisKMP()

	override fun propertyChange(event: ContextChangeEvent) {
		val simEvent = SimulationEvent.fromContextChangeEvent(event) ?: return
		lastSimTime = simEvent.simulationTime

		if (simEvent.eventType == ReportType.TRAIN_APPROVED) {
			val combined = "${simEvent.source} ${simEvent.message}"
			val match = APPROVED_TRAIN_NAME_REGEX.find(combined)
			if (match != null) {
				trainNames.add(match.groupValues[1])
			}
		}

		if (simEvent.eventType !in allowedTypes) return
		output(formatEvent(simEvent))
	}

	fun printSummary() {
		val wallMs = currentTimeMillisKMP() - startWallTime
		val wallFormatted = formatWallTime(wallMs)
		output(
			"--- Simulation complete: ${trainNames.size} trains, " +
				"${formatSimTime(lastSimTime)}s sim time, ${wallFormatted}s wall ---"
		)
	}

	internal companion object {
		/** Format wall-clock milliseconds as "N.D" (integer seconds with one decimal). */
		fun formatWallTime(wallMs: Long): String {
			val wallTenths = (wallMs / 100).coerceAtLeast(0) // integer tenths of seconds
			return "${wallTenths / 10}.${wallTenths % 10}"
		}

		/** Format simulation time as "N.D" (integer seconds with one decimal). */
		fun formatSimTime(simTime: Double): String {
			val tenths = (simTime * 10).toLong().coerceAtLeast(0)
			return "${tenths / 10}.${tenths % 10}"
		}

		private val APPROVED_TRAIN_NAME_REGEX = Regex("""train="([^"]+)"""")
	}

	private fun formatEvent(event: SimulationEvent): String {
		val prefix = "t=${event.simulationTime}  [${event.eventType.name}]"
		return if (event.source.isEmpty()) {
			"$prefix  ${event.message}"
		} else {
			"$prefix  ${event.source} ${event.message}"
		}
	}
}
