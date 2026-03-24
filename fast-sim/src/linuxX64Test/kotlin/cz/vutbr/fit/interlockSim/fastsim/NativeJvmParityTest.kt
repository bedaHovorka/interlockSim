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
import kotlin.test.assertTrue

/**
 * Structural invariant tests for native simulation output.
 *
 * Verifies that the native (linuxX64) simulation produces structurally valid results
 * by checking platform-independent invariants. Exact event times are **not** compared
 * because kDisco.Random determinism across JVM and native platforms is unknown.
 *
 * A matching JVM reference test ([JvmParityReferenceTest]) verifies the same invariants,
 * confirming semantic parity between platforms.
 *
 * Structural invariants checked (shuntingLoop, endTime=60):
 * 1. At least 1 train generated
 * 2. Simulation completes without exceptions
 * 3. Events are non-empty
 * 4. All events have non-negative timestamps
 * 5. Events are in chronological order (timestamps non-decreasing)
 * 6. Summary statistics are present and reasonable
 *
 * @since Issue #417 (native vs JVM semantic parity)
 * @see TextReporter
 * @see NativeExampleRegistry
 */
class NativeJvmParityTest {

	companion object {
		private const val END_TIME = 60L
		private val timestampRegex = Regex("""t=([\d.]+)\s+""")
	}

	@BeforeTest
	fun setUp() {
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	/**
	 * Runs the shuntingLoop simulation and collects output lines via [TextReporter].
	 *
	 * @return Pair of (event lines excluding summary, summary line)
	 */
	private fun runSimulationAndCollect(): Pair<List<String>, String> {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		val ctx = NativeExampleRegistry.create("shuntingLoop", END_TIME, NativeContextFactory())
		ctx.addPropertyChangeListener(reporter)
		try {
			ctx.run()
			reporter.printSummary()
		} finally {
			ctx.close()
		}
		val eventLines = output.filter { !it.startsWith("---") }
		val summary = output.last { it.startsWith("---") }
		return eventLines to summary
	}

	@Test
	fun `invariant 1 - at least 1 train generated`() {
		val (_, summary) = runSimulationAndCollect()
		// Summary format: "--- Simulation complete: N trains, ..."
		val trainCountMatch = Regex("""(\d+) trains""").find(summary)
		assertTrue(trainCountMatch != null, "Summary should mention train count: $summary")
		val trainCount = trainCountMatch.groupValues[1].toInt()
		assertTrue(trainCount >= 1, "Expected at least 1 train, got $trainCount")
	}

	@Test
	fun `invariant 2 - simulation completes without exceptions`() {
		// If this test body completes, the simulation ran without exception.
		val (events, _) = runSimulationAndCollect()
		assertTrue(events.isNotEmpty(), "Simulation should produce output")
	}

	@Test
	fun `invariant 3 - events are non-empty`() {
		val (events, _) = runSimulationAndCollect()
		assertTrue(events.isNotEmpty(), "Expected at least one event line")
	}

	@Test
	fun `invariant 4 - all events have non-negative timestamps`() {
		val (events, _) = runSimulationAndCollect()
		events.forEach { line ->
			val match = timestampRegex.find(line)
			assertTrue(match != null, "Event line should contain timestamp: $line")
			val time = match.groupValues[1].toDouble()
			assertTrue(time >= 0.0, "Timestamp should be non-negative: $time in line: $line")
		}
	}

	@Test
	fun `invariant 5 - events are in chronological order`() {
		val (events, _) = runSimulationAndCollect()
		val timestamps = events.mapNotNull { line ->
			timestampRegex.find(line)?.groupValues?.get(1)?.toDouble()
		}
		assertTrue(timestamps.size == events.size, "All event lines should have parseable timestamps")
		for (i in 1 until timestamps.size) {
			assertTrue(
				timestamps[i] >= timestamps[i - 1],
				"Events should be chronologically ordered: t[${i - 1}]=${timestamps[i - 1]} > t[$i]=${timestamps[i]}"
			)
		}
	}

	@Test
	fun `invariant 6 - summary statistics are present and reasonable`() {
		val (_, summary) = runSimulationAndCollect()
		assertTrue(summary.startsWith("---"), "Summary should start with ---")
		assertTrue(summary.contains("trains"), "Summary should mention trains")
		assertTrue(summary.contains("sim time"), "Summary should mention sim time")
		assertTrue(summary.contains("wall"), "Summary should mention wall time")
		assertTrue(
			!Regex("""\b0\s+trains\b""").containsMatchIn(summary),
			"Should have at least 1 train: $summary"
		)

		// Verify sim time is positive
		val simTimeMatch = Regex("""([\d.]+)s sim time""").find(summary)
		assertTrue(simTimeMatch != null, "Summary should contain sim time value")
		val simTime = simTimeMatch.groupValues[1].toDouble()
		assertTrue(simTime > 0.0, "Sim time should be positive: $simTime")
	}
}
