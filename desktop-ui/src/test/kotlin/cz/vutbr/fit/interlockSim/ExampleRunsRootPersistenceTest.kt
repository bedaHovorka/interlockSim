/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.test.get
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A plain `example cervenyUjezd` run with `-Dinterlocksim.dispatcher.runsRoot=<dir>` writes its run JSON
 * under that directory (Issue #1327, Goal 6: "every harness prints and persists the same run record").
 *
 * Goes through `Main.runExample`, as `java -jar interlockSim.jar example cervenyUjezd 600` does, with the
 * runs root set as the system property the usage text documents: the property reaches the run through the
 * `DispatcherRunConfig` singleton, which reads it on first use (`DispatcherRunConfig.fromProperties`).
 *
 * @since Issue #1327 (Goal 6)
 */
@DisplayName("A plain example run with a runs root writes its run JSON there")
class ExampleRunsRootPersistenceTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	@Test
	@Tag("integration-test")
	@Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `example cervenyUjezd with a runs root writes one run JSON under the rule-based arm`(
		@TempDir root: Path
	) {
		val previous = System.getProperty(DispatcherRunConfig.PROP_RUNS_ROOT)
		System.setProperty(DispatcherRunConfig.PROP_RUNS_ROOT, root.toString())
		val outcome =
			try {
				get<Main>().runExample(arrayOf("example", ExampleNames.CERVENY_UJEZD, END_TIME))
			} finally {
				if (previous == null) {
					System.clearProperty(DispatcherRunConfig.PROP_RUNS_ROOT)
				} else {
					System.setProperty(DispatcherRunConfig.PROP_RUNS_ROOT, previous)
				}
			}

		assertThat(outcome, name = "outcome").isEqualTo(RunOutcome.COMPLETED)
		val files = Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".json") }.toList() }
		assertThat(files.size, name = "run JSON files under the runs root").isEqualTo(1)
		assertThat(
			files
				.single()
				.parent.fileName
				.toString(),
			name = "arm directory"
		).isEqualTo(DispatcherArm.RULE_BASED.name.lowercase())
		val snapshot = DefaultRunSnapshotStore(root).readAll(root).single()
		assertThat(snapshot.schemaVersion, name = "schema version").isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
		assertThat(snapshot.example, name = "example").isEqualTo(ExampleNames.CERVENY_UJEZD)
		assertThat(snapshot.arm, name = "arm").isEqualTo(DispatcherArm.RULE_BASED)
		assertThat(snapshot.endCause, name = "end cause").isNotNull()
		assertThat(snapshot.railwayOutcome.trainsEntered, name = "trains entered").isNotNull()
	}

	private companion object {
		const val END_TIME = "600"
	}
}
