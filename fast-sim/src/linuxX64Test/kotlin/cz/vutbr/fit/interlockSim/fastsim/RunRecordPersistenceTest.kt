/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.matches
import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.persistence.RecordedRun
import cz.vutbr.fit.interlockSim.persistence.RunRecordJsonWriter
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.deleteFile
import cz.vutbr.fit.interlockSim.util.directoryExists
import cz.vutbr.fit.interlockSim.util.readTextFile
import cz.vutbr.fit.interlockSim.util.tempDirectory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * `fast-sim example <name> <endTime> --runs-root=<dir>` persists the run JSON of `:core`'s
 * [RunRecordJsonWriter] (Issue #1327, Goal 6). That the JSON is the one the JVM writes for the same run
 * is pinned in `:desktop-ui` (`RunRecordJsonReadBackTest`); the binary's stdout and stderr in
 * `CrossPlatformParityTest`.
 *
 * @since Issue #1327 (Goal 6)
 */
class RunRecordPersistenceTest {
	private companion object {
		const val END_TIME: Long = 600L
		const val RUN_ID = "native-test-run"
		const val TIMESTAMP = "20261122-101500"
	}

	private var previousLogLevel: Level = KotlinLoggingConfiguration.logLevel
	private val createdPaths = mutableListOf<String>()

	@BeforeTest
	fun setUp() {
		previousLogLevel = KotlinLoggingConfiguration.logLevel
		KotlinLoggingConfiguration.logLevel = Level.WARN
		startKoin { modules(coreModule) }
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
		KotlinLoggingConfiguration.logLevel = previousLogLevel
		createdPaths.reversed().forEach { deleteFile(it) }
		createdPaths.clear()
	}

	// ---- the --runs-root flag ----

	@Test
	fun `the runs root flag is removed from the positional arguments wherever it stands`() {
		val args = arrayOf("--runs-root=/tmp/runs", CMD_EXAMPLE_FOR_TEST, ExampleNames.CERVENY_UJEZD, "600", "--quiet")

		assertThat(filterPositionalArgs(args).toList())
			.containsExactly(CMD_EXAMPLE_FOR_TEST, ExampleNames.CERVENY_UJEZD, "600")
	}

	@Test
	fun `the runs root is the value of the flag`() {
		val args = arrayOf(CMD_EXAMPLE_FOR_TEST, ExampleNames.CERVENY_UJEZD, "600", "--runs-root=/tmp/runs")

		assertThat(parseRunsRoot(args)).isEqualTo("/tmp/runs")
	}

	@Test
	fun `without the flag there is no runs root`() {
		assertThat(parseRunsRoot(arrayOf(CMD_EXAMPLE_FOR_TEST, ExampleNames.CERVENY_UJEZD, "600"))).isNull()
	}

	@Test
	fun `a runs root flag without a directory is an argument error`() {
		assertFailsWith<IllegalArgumentException> { parseRunsRoot(arrayOf("--runs-root=")) }
	}

	@Test
	fun `a bare runs root flag without the equals sign is an argument error`() {
		// "--runs-root <dir>" would leave <dir> among the positional arguments and move them.
		assertFailsWith<IllegalArgumentException> {
			parseRunsRoot(arrayOf(CMD_EXAMPLE_FOR_TEST, ExampleNames.CERVENY_UJEZD, "600", "--runs-root", "/tmp/runs"))
		}
	}

	@Test
	fun `a repeated runs root flag is an argument error`() {
		assertFailsWith<IllegalArgumentException> {
			parseRunsRoot(
				arrayOf(
					CMD_EXAMPLE_FOR_TEST,
					ExampleNames.CERVENY_UJEZD,
					"600",
					"--runs-root=/tmp/runs",
					"--runs-root=/tmp/other"
				)
			)
		}
	}

	// ---- the example name the JSON is filed under ----

	@Test
	fun `every native example is filed under the JVM example it equals`() {
		// The native examples are the synchronous builds; the JVM names without the suffix run the
		// dispatcher-agent stack, so their runs must not pool with native ones (#1327 review round).
		val twins = NativeExampleRegistry.AVAILABLE.associateWith { NativeExampleRegistry.jvmTwinOf(it) }

		assertThat(twins).isEqualTo(
			mapOf(
				ExampleNames.SHUNTING_LOOP to ExampleNames.SHUNTING_LOOP_SYNC,
				ExampleNames.CERVENY_UJEZD to ExampleNames.CERVENY_UJEZD_SYNC
			)
		)
	}

	@Test
	fun `the local timestamp has the file-name format of the JVM store`() {
		assertThat(localTimestamp()).matches(Regex("""\d{8}-\d{6}"""))
	}

	// ---- persisting a native run ----

	@Test
	fun `a native cervenyUjezd run is persisted under the rule-based arm with the writer's JSON`() {
		val root = "${tempDirectory().trimEnd('/')}/fast-sim-runs-${currentTimeMillisKMP()}"
		val armDirectory = "$root/rule_based"
		createdPaths += listOf(root, armDirectory)
		NativeExampleRegistry.create(ExampleNames.CERVENY_UJEZD, END_TIME, NativeContextFactory()).use { ctx ->
			ctx.run()

			val outcome =
				RunRecordPersistence(root, ExampleNames.CERVENY_UJEZD, END_TIME)
					.persist(ctx, mainProcessFailed = false, runId = RUN_ID, timestamp = TIMESTAMP)
			val path = assertIs<PersistOutcome.Written>(outcome, "outcome").path
			createdPaths += path

			assertThat(path).isEqualTo("$armDirectory/$TIMESTAMP-$RUN_ID.json")
			val record = assertNotNull(ctx.getMetricsServices().getRunRecordSource().runRecord(), "run record")
			val expected =
				RunRecordJsonWriter.snapshotJson(
					RecordedRun(record, RUN_ID, ExampleNames.CERVENY_UJEZD_SYNC, END_TIME.toDouble())
				)
			assertThat(readTextFile(path)).isEqualTo(expected)
		}
	}

	@Test
	fun `a run that has not ended has no record to persist`() {
		val root = "${tempDirectory().trimEnd('/')}/fast-sim-runs-unended-${currentTimeMillisKMP()}"
		NativeExampleRegistry.create(ExampleNames.SHUNTING_LOOP, END_TIME, NativeContextFactory()).use { ctx ->
			val outcome =
				RunRecordPersistence(root, ExampleNames.SHUNTING_LOOP, END_TIME)
					.persist(ctx, mainProcessFailed = false, runId = RUN_ID, timestamp = TIMESTAMP)

			assertThat(outcome).isEqualTo(PersistOutcome.NoRecord)
		}
	}

	@Test
	fun `an interrupted run is not persisted`() {
		val root = "${tempDirectory().trimEnd('/')}/fast-sim-runs-interrupted-${currentTimeMillisKMP()}"
		NativeExampleRegistry.create(ExampleNames.CERVENY_UJEZD, END_TIME, NativeContextFactory()).use { ctx ->
			ctx.run()

			val outcome =
				RunRecordPersistence(root, ExampleNames.CERVENY_UJEZD, END_TIME, interrupted = { true })
					.persist(ctx, mainProcessFailed = false, runId = RUN_ID, timestamp = TIMESTAMP)

			assertThat(outcome).isEqualTo(PersistOutcome.Interrupted)
			assertThat(directoryExists(root), name = "runs root created").isEqualTo(false)
		}
	}
}

/** The `example` mode token of the CLI, spelled out once for the tests above. */
private const val CMD_EXAMPLE_FOR_TEST = "example"
