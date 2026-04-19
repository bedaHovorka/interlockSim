/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * JVM-side reference for native vs JVM semantic parity (Issue #417)
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Resources
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * JVM reference test verifying the same structural invariants as [NativeJvmParityTest]
 * (in `:fast-sim`). Together, they confirm that native and JVM simulations produce
 * semantically equivalent output.
 *
 * Exact event times are **not** compared because kDisco.Random may produce different
 * sequences on JVM vs native. Instead, only structural invariants are checked.
 *
 * Structural invariants (shuntingLoop, endTime=60):
 * 1. At least 1 train generated
 * 2. Simulation completes without exceptions
 * 3. Events are non-empty
 * 4. All events have non-negative timestamps
 * 5. Events are in chronological order (timestamps non-decreasing)
 * 6. Summary statistics are present and reasonable
 *
 * @since Issue #417 (native vs JVM semantic parity)
 * @see TextReporter
 */
@Tag("integration-test")
@DisplayName("JVM Parity Reference Tests (Issue #417)")
class JvmParityReferenceTest : KoinTestBase() {
	companion object {
		private const val END_TIME = 60L
		private const val VYHYBNA_RESOURCE = "/cz/vutbr/fit/interlockSim/resource/vyhybna.xml"
		private val timestampRegex = Regex("""t=([\d.]+)\s+""")
	}

	/**
	 * Creates a ShuntingLoop simulation context from vyhybna.xml, attaches a
	 * [TextReporter] with an output collector, runs the simulation, and returns
	 * the collected event lines and summary line.
	 */
	private fun runSimulationAndCollect(): Pair<List<String>, String> {
		val factory = getKoin().get<SimulationContextFactory>()
		val stream =
			Resources.read(VYHYBNA_RESOURCE.trimStart('/')).byteInputStream()
				?: throw IllegalStateException("Classpath resource not found: $VYHYBNA_RESOURCE")
		val output = mutableListOf<String>()
		Util.assertInstanceOf<DefaultSimulationContext>(
			stream.use { factory.createContext(it) }
		).use { context ->
			context.getInOuts()
			context.setMainProcess(ShuntingLoop(context, END_TIME))
			val reporter = TextReporter(Verbosity.DEFAULT) { output.add(it) }
			context.addPropertyChangeListener(reporter)
			context.run()
			reporter.printSummary()
		}
		val eventLines = output.filter { !it.startsWith("---") }
		val summary = output.last { it.startsWith("---") }
		return eventLines to summary
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `invariant 1 - at least 1 train generated`() {
		val (_, summary) = runSimulationAndCollect()
		val trainCountMatch = Regex("""(\d+) trains""").find(summary)
		assertThat(trainCountMatch).isNotNull()
		val trainCount = trainCountMatch!!.groupValues[1].toInt()
		assertThat(trainCount).isGreaterThanOrEqualTo(1)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `invariant 2 - simulation completes without exceptions`() {
		runSimulationAndCollect()
		// Implicit assertion: reaching this line means the simulation completed without throwing
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `invariant 3 - events are non-empty`() {
		val (events, _) = runSimulationAndCollect()
		assertThat(events).isNotEmpty()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `invariant 4 - all events have non-negative timestamps`() {
		val (events, _) = runSimulationAndCollect()
		events.forEach { line ->
			val match = timestampRegex.find(line)
			assertThat(match, name = "Timestamp in: $line").isNotNull()
			val time = match!!.groupValues[1].toDouble()
			assertThat(time).isGreaterThanOrEqualTo(0.0)
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
		assertThat(timestamps.size == events.size, name = "All lines should have timestamps").isTrue()
		for (i in 1 until timestamps.size) {
			assertThat(
				timestamps[i] >= timestamps[i - 1],
				name = "Chronological: t[${i - 1}]=${timestamps[i - 1]} <= t[$i]=${timestamps[i]}"
			).isTrue()
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `invariant 6 - summary statistics are present and reasonable`() {
		val (_, summary) = runSimulationAndCollect()
		assertThat(summary.startsWith("---"), name = "Summary starts with ---").isTrue()
		assertThat(summary.contains("trains"), name = "Summary mentions trains").isTrue()
		assertThat(summary.contains("sim time"), name = "Summary mentions sim time").isTrue()
		assertThat(summary.contains("wall"), name = "Summary mentions wall time").isTrue()
		assertThat(
			!Regex("""\b0\s+trains\b""").containsMatchIn(summary),
			name = "At least 1 train in: $summary"
		).isTrue()

		// Verify sim time is positive
		val simTimeMatch = Regex("""([\d.]+)s sim time""").find(summary)
		assertThat(simTimeMatch).isNotNull()
		val simTime = simTimeMatch!!.groupValues[1].toDouble()
		assertThat(simTime).isGreaterThanOrEqualTo(0.0)
	}
}
