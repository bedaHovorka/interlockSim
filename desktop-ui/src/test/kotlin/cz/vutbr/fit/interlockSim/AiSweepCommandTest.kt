/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

/**
 * Unit tests for [AiSweepCommand] flag parsing (SP2c.24, Issue #847).
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid)
 */
@DisplayName("SP2c.24 — aiSweep command line (#847)")
class AiSweepCommandTest {
	private fun parse(vararg args: String) = AiSweepCommand.parse(arrayOf("aiSweep", *args))

	@Test
	fun `grid alone is enough, everything else defaults`() {
		val command = parse("--grid", "grid.json")

		assertThat(command.gridFile).isEqualTo(Path.of("grid.json"))
		assertThat(command.outputRoot).isEqualTo(AiSweepCommand.DEFAULT_OUTPUT_ROOT)
		assertThat(command.repeatOverride).isNull()
		assertThat(command.timeoutOverride).isNull()
		assertThat(command.dryRun).isFalse()
	}

	@Test
	fun `every flag is parsed`() {
		val command =
			parse("--grid", "g.json", "--out", "out", "--repeat", "4", "--timeout", "120", "--dry-run")

		assertThat(command.gridFile).isEqualTo(Path.of("g.json"))
		assertThat(command.outputRoot).isEqualTo(Path.of("out"))
		assertThat(command.repeatOverride).isEqualTo(4)
		assertThat(command.timeoutOverride).isEqualTo(120L)
		assertThat(command.dryRun).isTrue()
	}

	@Test
	@DisplayName("the default output directory is not the shared dispatcher-runs directory")
	fun defaultOutputIsIsolated() {
		// dispatcher-runs accumulates every ad-hoc run ever made on the machine; a sweep's gate has
		// to be computed over that sweep's own runs.
		assertThat(AiSweepCommand.DEFAULT_OUTPUT_ROOT).isEqualTo(Path.of("build", "reports", "dispatcher-sweep"))
	}

	@Test
	fun `a missing grid is rejected`() {
		assertThrows<AiSweepUsageException> { parse("--out", "somewhere") }
	}

	@Test
	fun `an unknown option is rejected rather than ignored`() {
		assertThrows<AiSweepUsageException> { parse("--grid", "g.json", "--verbose") }
	}

	@Test
	fun `a flag without its value is rejected`() {
		assertThrows<AiSweepUsageException> { parse("--grid") }
	}

	@Test
	fun `a non-positive repeat or timeout is rejected`() {
		assertThrows<AiSweepUsageException> { parse("--grid", "g.json", "--repeat", "0") }
		assertThrows<AiSweepUsageException> { parse("--grid", "g.json", "--timeout", "-1") }
		assertThrows<AiSweepUsageException> { parse("--grid", "g.json", "--repeat", "many") }
	}
}
