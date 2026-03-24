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
	fun summaryCountsTrainsWithSpacesInName() {
		// Real Train objects have name "Train #N" which contains a space.
		// Report format: "<time> Train #1 approved IO1->IO2" — split limit=3 gives
		// source="Train", message="#1 approved IO1->IO2". Train counting must handle this.
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "1.0 Train #1 approved IO1->IO2")
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "2.0 Train #2 approved IO2->IO1")
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
	fun summaryWallTimeHasCleanDecimalFormat() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		fireEvent(reporter, ReportType.TRAIN_EVENTS, "1.0 vlak1 approved IO1->IO2")
		reporter.printSummary()
		val summary = output.last()
		// Wall time must be a clean "N.D" format (integer dot single digit), no IEEE 754 artifacts
		val wallTimeMatch = Regex("""(\d+\.\d)s wall""").find(summary)
		assertTrue(wallTimeMatch != null, "Summary wall time should match N.Ds format: $summary")
		// Ensure no extra decimal digits (e.g. "3.1000000000000001")
		val wallValue = wallTimeMatch!!.groupValues[1]
		assertEquals(wallValue, wallValue.trimEnd('0').let { if (it.endsWith('.')) it + "0" else it },
			"Wall time should have exactly one decimal digit: $wallValue")
	}

	// --- formatWallTime edge-case tests (covers coerceAtLeast and integer formatting) ---

	@Test
	fun formatWallTimeZeroMs() {
		assertEquals("0.0", TextReporter.formatWallTime(0L))
	}

	@Test
	fun formatWallTimeSubSecond() {
		// 350 ms = 3 tenths → "0.3"
		assertEquals("0.3", TextReporter.formatWallTime(350L))
	}

	@Test
	fun formatWallTimeExactSecond() {
		assertEquals("1.0", TextReporter.formatWallTime(1000L))
	}

	@Test
	fun formatWallTimeOneAndAHalfSeconds() {
		// 1500 ms = 15 tenths → "1.5"
		assertEquals("1.5", TextReporter.formatWallTime(1500L))
	}

	@Test
	fun formatWallTimeLargeValue() {
		// 12345 ms = 123 tenths → "12.3"
		assertEquals("12.3", TextReporter.formatWallTime(12345L))
	}

	@Test
	fun formatWallTimeNegativeClampsToZero() {
		// Negative wallMs (clock went backwards) should produce "0.0" via coerceAtLeast(0)
		assertEquals("0.0", TextReporter.formatWallTime(-500L))
	}

	@Test
	fun formatWallTimeSlightlyNegativeClampsToZero() {
		// -1 ms edge case
		assertEquals("0.0", TextReporter.formatWallTime(-1L))
	}

	@Test
	fun formatWallTimeLargeNegativeClampsToZero() {
		assertEquals("0.0", TextReporter.formatWallTime(-999_999L))
	}

	@Test
	fun formatWallTimeTruncatesNotRounds() {
		// 990 ms = 9 tenths (not 10) — truncation, not rounding
		assertEquals("0.9", TextReporter.formatWallTime(990L))
		// 999 ms still 9 tenths
		assertEquals("0.9", TextReporter.formatWallTime(999L))
	}

	@Test
	fun formatWallTimeUnder100MsIsZeroTenths() {
		// 99 ms → 0 tenths → "0.0"
		assertEquals("0.0", TextReporter.formatWallTime(99L))
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
