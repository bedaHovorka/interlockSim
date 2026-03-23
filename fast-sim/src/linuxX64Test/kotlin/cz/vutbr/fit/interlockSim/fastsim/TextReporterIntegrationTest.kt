/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.sim.TextReporter
import cz.vutbr.fit.interlockSim.sim.Verbosity
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextReporterIntegrationTest {

	@BeforeTest
	fun setUp() {
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	@Test
	fun shuntingLoopProducesEventsViaTextReporter() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		val ctx = NativeExampleRegistry.create("shuntingLoop", 30, NativeContextFactory())
		ctx.addPropertyChangeListener(reporter)
		try {
			ctx.run()
			reporter.printSummary()
		} finally {
			ctx.close()
		}
		assertTrue(output.size > 1, "Expected events, got ${output.size} lines")
		assertTrue(output.last().startsWith("---"), "Last line should be summary")
	}

	@Test
	fun quietModeProducesOnlySummary() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.QUIET) { output.add(it) }
		val ctx = NativeExampleRegistry.create("shuntingLoop", 30, NativeContextFactory())
		ctx.addPropertyChangeListener(reporter)
		try {
			ctx.run()
			reporter.printSummary()
		} finally {
			ctx.close()
		}
		assertEquals(1, output.size, "QUIET should produce only summary")
		assertTrue(output[0].startsWith("---"))
	}

	@Test
	fun verboseModeIncludesTrainContinuous() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.VERBOSE) { output.add(it) }
		val ctx = NativeExampleRegistry.create("shuntingLoop", 30, NativeContextFactory())
		ctx.addPropertyChangeListener(reporter)
		try {
			ctx.run()
			reporter.printSummary()
		} finally {
			ctx.close()
		}
		assertTrue(output.any { "[TRAIN_CONTINUOUS]" in it }, "VERBOSE should include TRAIN_CONTINUOUS")
	}

	@Test
	fun outputMatchesParseableRegex() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		val ctx = NativeExampleRegistry.create("shuntingLoop", 30, NativeContextFactory())
		ctx.addPropertyChangeListener(reporter)
		try {
			ctx.run()
		} finally {
			ctx.close()
		}
		val regex = Regex("t=[\\d.]+\\s+\\[\\w+]\\s+.+")
		val eventLines = output.filter { !it.startsWith("---") }
		assertTrue(eventLines.isNotEmpty())
		eventLines.forEach { line ->
			assertTrue(regex.matches(line), "Line does not match format: $line")
		}
	}

	@Test
	fun summaryIncludesTrainCountGreaterThanZero() {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		val ctx = NativeExampleRegistry.create("shuntingLoop", 60, NativeContextFactory())
		ctx.addPropertyChangeListener(reporter)
		try {
			ctx.run()
			reporter.printSummary()
		} finally {
			ctx.close()
		}
		val summary = output.last()
		assertTrue(summary.startsWith("---"), "Summary should start with ---: $summary")
		assertTrue(summary.contains("trains"), "Summary should mention trains: $summary")
		assertTrue(!summary.contains("0 trains"), "Should have at least 1 train: $summary")
		assertTrue(summary.contains("sim time"), "Summary should mention sim time: $summary")
		assertTrue(summary.contains("wall"), "Summary should mention wall time: $summary")
	}
}
