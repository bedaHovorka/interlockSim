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

	// Not thread-safe: assumes single-threaded kDisco event delivery.
	private val trainNames = mutableSetOf<String>()
	private var lastSimTime = 0.0
	private val startWallTime = currentTimeMillisKMP()

	override fun propertyChange(event: ContextChangeEvent) {
		val simEvent = SimulationEvent.fromContextChangeEvent(event) ?: return
		lastSimTime = simEvent.simulationTime

		// Track unique trains from "approved" events. The source may be truncated
		// (e.g. "Train" instead of "Train #1") because obj.toString() can contain
		// spaces that get split by fromContextChangeEvent(). Use combined
		// source+message to detect and extract the full train identifier.
		// See #425: fragile — couples train counting to the English word "approved" in
		//  the message. A proper fix would emit a dedicated TRAIN_APPROVED ReportType from
		//  the simulation layer instead of parsing free-form text.
		if (simEvent.eventType == ReportType.TRAIN_EVENTS) {
			val combined = "${simEvent.source} ${simEvent.message}"
			val approvedIndex = combined.indexOf("approved")
			if (approvedIndex > 0) {
				trainNames.add(combined.substring(0, approvedIndex).trim())
			}
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

	private fun formatEvent(event: SimulationEvent): String {
		val prefix = "t=${event.simulationTime}  [${event.eventType.name}]"
		return if (event.source.isEmpty()) "$prefix  ${event.message}"
		else "$prefix  ${event.source} ${event.message}"
	}
}
