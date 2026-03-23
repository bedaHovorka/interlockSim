package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextReporterTest {

	private fun fireEvent(
		reporter: TextReporter,
		type: ReportType,
		message: String = "10.0 vlak1 test message"
	) {
		reporter.propertyChange(ContextChangeEvent(type.name, null, message))
	}

	@Test
	fun defaultVerbosityOutputsTrainEvents() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS)
		assertEquals(1, output.size)
	}

	@Test
	fun defaultVerbosityOutputsNodeEvents() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.NODE_EVENTS)
		assertEquals(1, output.size)
	}

	@Test
	fun defaultVerbosityOutputsPathSetting() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.PATH_SETTING)
		assertEquals(1, output.size)
	}

	@Test
	fun defaultVerbosityFiltersTrainContinuous() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_CONTINUOUS)
		assertTrue(output.isEmpty())
	}

	@Test
	fun verboseIncludesTrainContinuous() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.VERBOSE) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_CONTINUOUS)
		assertEquals(1, output.size)
	}

	@Test
	fun quietProducesNoEventOutput() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.QUIET) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS)
		fireEvent(reporter, ReportType.NODE_EVENTS)
		fireEvent(reporter, ReportType.TRAIN_CONTINUOUS)
		assertTrue(output.isEmpty())
	}

	@Test
	fun outputFormatMatchesRegex() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS)
		assertTrue(output[0].matches(Regex("t=[\\d.]+\\s+\\[\\w+]\\s+.+")))
	}

	@Test
	fun summaryIncludesTrainCount() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "1.0 vlak1 approved IO1->IO2")
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "2.0 vlak2 approved IO2->IO1")
		reporter.printSummary()
		val summary = output.last()
		assertTrue(summary.contains("2 trains"), "Summary should say 2 trains: $summary")
	}

	@Test
	fun summaryIncludesSimTime() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "60.0 vlak1 ends")
		reporter.printSummary()
		assertTrue(output.last().contains("60.0"))
	}

	@Test
	fun nonReportTypePropertyIsIgnored() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		reporter.propertyChange(ContextChangeEvent("cellAdded", null, "some value"))
		assertTrue(output.isEmpty())
	}

	@Test
	fun outputIncludesSourceAndMessage() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "5.0 vlak1 stopped at signal")
		assertTrue(output[0].contains("vlak1"))
		assertTrue(output[0].contains("stopped at signal"))
	}
}
