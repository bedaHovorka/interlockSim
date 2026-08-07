/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.sweep

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [SweepGrid], [SweepAxes] and [SweepCell] (SP2c.24, Issue #847).
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid)
 */
@DisplayName("SP2c.24 — sweep grid parsing and expansion (#847)")
class SweepGridTest {
	@TempDir
	lateinit var tempDir: Path

	private fun gridFile(json: String): Path = tempDir.resolve("grid.json").also { Files.writeString(it, json) }

	@Test
	fun `a minimal grid parses and takes the production defaults`() {
		val grid = SweepGrid.load(gridFile("""{ "repeat": 3 }"""))

		assertThat(grid.repeat).isEqualTo(3)
		assertThat(grid.endTimeSeconds).isEqualTo(SweepGrid.DEFAULT_END_TIME_SECONDS)
		assertThat(grid.perRunTimeoutSeconds).isEqualTo(SweepGrid.DEFAULT_PER_RUN_TIMEOUT_SECONDS)
		assertThat(grid.axes.cells()).hasSize(1)
		assertThat(
			grid.axes
				.cells()
				.single()
				.example
		).isEqualTo(SweepAxes.DEFAULT_EXAMPLE)
	}

	@Test
	fun `the grid expands to the cartesian product times repeat`() {
		val grid =
			SweepGrid.load(
				gridFile(
					"""
					{
					  "repeat": 2,
					  "axes": {
					    "example": ["shuntingLoopAI", "shuntingLoop"],
					    "temperature": [0.28, 0.5]
					  }
					}
					""".trimIndent()
				)
			)

		// 2 examples x 2 temperatures x 2 repeats
		assertThat(grid.expand()).hasSize(8)
		assertThat(grid.expand().map { it.runId }.toSet()).hasSize(8)
	}

	@Test
	@DisplayName("expansion order is stable, because run ids are the resumption key")
	fun expansionIsStable() {
		val json = """{ "repeat": 2, "axes": { "temperature": [0.28, 0.5] } }"""
		val first = SweepGrid.load(gridFile(json)).expand().map { it.runId }
		val second = SweepGrid.load(gridFile(json)).expand().map { it.runId }

		assertThat(first).isEqualTo(second)
	}

	@Test
	@DisplayName("an unknown axis name is rejected rather than silently sweeping the default")
	fun unknownKeyRejected() {
		// A typo would otherwise produce a report of the wrong measurement, with run JSONs that
		// look entirely correct.
		assertThrows<SweepGridException> {
			SweepGrid.load(gridFile("""{ "repeat": 2, "axes": { "temperture": [0.5] } }"""))
		}
	}

	@Test
	fun `a missing file is reported as a grid error, not an IO stack trace`() {
		assertThrows<SweepGridException> { SweepGrid.load(tempDir.resolve("absent.json")) }
	}

	@Test
	fun `malformed JSON is reported as a grid error`() {
		assertThrows<SweepGridException> { SweepGrid.load(gridFile("{ not json")) }
	}

	@Test
	fun `an empty axis list is rejected`() {
		assertThrows<SweepGridException> {
			SweepGrid.load(gridFile("""{ "repeat": 1, "axes": { "temperature": [] } }"""))
		}
	}

	@Test
	fun `a non-positive repeat is rejected`() {
		assertThrows<SweepGridException> { SweepGrid.load(gridFile("""{ "repeat": 0 }""")) }
	}

	@Test
	@DisplayName("an omitted model or temperature means 'leave the executor default', not a value")
	fun omittedAxisMeansUnchanged() {
		val cell =
			SweepGrid
				.load(gridFile("""{ "repeat": 1 }"""))
				.axes
				.cells()
				.single()

		assertThat(cell.model).isNull()
		assertThat(cell.temperature).isNull()
		assertThat(cell.systemProperties("run-1", "out").keys)
			.isEqualTo(
				setOf(
					"interlocksim.dispatcher.tickPeriodMs",
					"interlocksim.dispatcher.historyN",
					"interlocksim.dispatcher.maxActionsPerTick",
					"interlocksim.dispatcher.runId",
					"interlocksim.dispatcher.runsRoot"
				)
			)
	}

	@Test
	@DisplayName("run ids are file-name safe, so a substring scan of the output directory is exact")
	fun runIdsAreFileNameSafe() {
		val cell =
			SweepGrid
				.load(gridFile("""{ "repeat": 1, "axes": { "model": ["qwen2.5:7b-instruct"] } }"""))
				.axes
				.cells()
				.single()

		val runId = cell.runId(1)
		assertThat(runId).contains("qwen2.5-7b-instruct")
		assertThat(runId).isEqualTo(runId.replace(Regex("[^A-Za-z0-9._-]"), "_"))
	}

	@Test
	@DisplayName("a run id survives the store's own sanitiser and the driver's file-name scan intact")
	fun runIdSurvivesStoreRoundTrip() {
		// [runIdsAreFileNameSafe] checks SweepCell's alphabet against the store's regex in isolation.
		// This drives the actual round trip the resumption logic depends on: the id goes into a real
		// DefaultRunSnapshotStore write, comes back out of the written file name, and must be
		// byte-identical. The two sanitisers use different replacement characters ("-" here, "_" in
		// the store), so a widened alphabet on either side would silently break resumption -- the
		// driver would stop recognising its own completed runs and re-execute the entire grid.
		val cell =
			SweepGrid
				.load(
					gridFile(
						"""{ "repeat": 1, "axes": { "model": ["qwen2.5:7b-instruct"], "temperature": [0.2] } }"""
					)
				).axes
				.cells()
				.single()
		val runId = cell.runId(1)

		val written =
			DefaultRunSnapshotStore(tempDir.resolve("runs")).write(
				abortedSnapshot(runId, cell.arm, cell.runParameters(), RunEndCause.NATURAL_COMPLETION)
			)

		// Exactly how AiSweepDriver.existingRunIds recovers an id: strip the timestamp prefix.
		val recovered = Regex("""\d{8}-\d{6}-(.+)\.json""").matchEntire(written.fileName.toString())
		assertThat(recovered?.groupValues?.get(1)).isEqualTo(runId)
	}

	@Test
	@DisplayName("the arm is derivable before the run, so a killed run lands in the right directory")
	fun armDerivedFromExampleName() {
		val axes = SweepAxes(example = listOf("shuntingLoopAI", "shuntingLoop"))
		val cells = axes.cells()

		assertThat(cells.first { it.example == "shuntingLoopAI" }.arm)
			.isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(cells.first { it.example == "shuntingLoop" }.arm)
			.isEqualTo(DispatcherArm.RULE_BASED)
	}
}
