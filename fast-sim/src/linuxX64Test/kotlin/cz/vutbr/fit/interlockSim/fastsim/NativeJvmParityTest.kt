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
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.TextReporter
import cz.vutbr.fit.interlockSim.sim.Verbosity
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural invariant tests for native simulation output.
 *
 * Verifies that the native (linuxX64) simulation produces structurally valid results
 * by checking platform-independent invariants.
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
 * 7. Exact entered/exited train counts (cross-platform determinism)
 *
 * Invariant 7 asserts **exact** counts, which must equal the JVM test's constants:
 * kDisco's `Random.exp()`/`.normal()` are bit-identical across JVM and native since
 * bedaHovorka/kdisco#69 was fixed (pure-Kotlin fdlibm `PortableMath` replacing
 * platform-delegating `kotlin.math.ln`/`exp`).
 *
 * @since Issue #417 (native vs JVM semantic parity)
 * @see TextReporter
 * @see NativeExampleRegistry
 */
class NativeJvmParityTest {
	companion object {
		private const val END_TIME = 60L
		private val timestampRegex = Regex("""t=([\d.]+)\s+""")

		/**
		 * Exact expected counts for `ShuntingLoop(endTime=60)` with the fixed-seed
		 * generator. Cross-platform identical (bedaHovorka/kdisco#69 fixed); must match
		 * [cz.vutbr.fit.interlockSim.sim.JvmParityReferenceTest] in `:core`.
		 */
		private const val EXPECTED_TRAINS_ENTERED = 2
		private const val EXPECTED_TRAINS_EXITED = 1
	}

	@BeforeTest
	fun setUp() {
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	/** Collected output plus final train counters of one simulation run. */
	private data class SimRun(
		val events: List<String>,
		val summary: String,
		val trainsEntered: Int,
		val trainsExited: Int
	)

	/**
	 * Runs the shuntingLoop simulation and collects output lines via [TextReporter]
	 * plus the final train counters.
	 */
	private fun runSimulation(): SimRun {
		val output = mutableListOf<String>()
		val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
		var trainsEntered = -1
		var trainsExited = -1
		NativeExampleRegistry.create("shuntingLoop", END_TIME, NativeContextFactory()).use { ctx ->
			ctx.addPropertyChangeListener(reporter)
			ctx.run()
			reporter.printSummary()
			val loop = ctx.getMainProcess() as ShuntingLoop
			trainsEntered = loop.getTrainsEntered()
			trainsExited = loop.getTrainsExited()
		}
		val eventLines = output.filter { !it.startsWith("---") }
		val summary = output.last { it.startsWith("---") }
		return SimRun(eventLines, summary, trainsEntered, trainsExited)
	}

	private fun runSimulationAndCollect(): Pair<List<String>, String> {
		val run = runSimulation()
		return run.events to run.summary
	}

	@Test
	fun `invariant 7 - exact train counts match cross-platform constants`() {
		val run = runSimulation()
		assertEquals(EXPECTED_TRAINS_ENTERED, run.trainsEntered, "trains entered")
		assertEquals(EXPECTED_TRAINS_EXITED, run.trainsExited, "trains exited")
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
		runSimulationAndCollect()
		// Implicit assertion: reaching this line means the simulation completed without throwing
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
		val timestamps =
			events.mapNotNull { line ->
				timestampRegex
					.find(line)
					?.groupValues
					?.get(1)
					?.toDouble()
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
