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

	private val allowedTypes: Set<ReportType> = when (verbosity) {
		Verbosity.QUIET -> emptySet()
		Verbosity.DEFAULT -> setOf(ReportType.TRAIN_EVENTS, ReportType.NODE_EVENTS, ReportType.PATH_SETTING)
		Verbosity.VERBOSE -> setOf(
			ReportType.TRAIN_EVENTS, ReportType.NODE_EVENTS,
			ReportType.PATH_SETTING, ReportType.TRAIN_CONTINUOUS
		)
	}

	private val trainNames = mutableSetOf<String>()
	private var lastSimTime = 0.0
	private val startWallTime = currentTimeMillisKMP()

	override fun propertyChange(event: ContextChangeEvent) {
		val simEvent = SimulationEvent.fromContextChangeEvent(event) ?: return
		lastSimTime = simEvent.simulationTime

		if (simEvent.eventType == ReportType.TRAIN_EVENTS &&
			simEvent.message.startsWith("approved")
		) {
			trainNames.add(simEvent.source)
		}

		if (simEvent.eventType !in allowedTypes) return
		output(formatEvent(simEvent))
	}

	fun printSummary() {
		val wallSeconds = (currentTimeMillisKMP() - startWallTime) / 1000.0
		val wallFormatted = ((wallSeconds * 10).toLong() / 10.0).toString()
		output(
			"--- Simulation complete: ${trainNames.size} trains, " +
				"${lastSimTime}s sim time, ${wallFormatted}s wall ---"
		)
	}

	private fun formatEvent(event: SimulationEvent): String =
		"t=${event.simulationTime}  [${event.eventType.name}]  ${event.source} ${event.message}"
}
