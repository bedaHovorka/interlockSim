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
import assertk.assertions.endsWith
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.CommandSourceKind
import cz.vutbr.fit.interlockSim.dispatcher.planner.CountedAutomatic
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.SilentCycleCause
import cz.vutbr.fit.interlockSim.persistence.RecordedRun
import cz.vutbr.fit.interlockSim.persistence.RunRecordJsonWriter
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.test.get
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * The run JSON of `:core`'s [RunRecordJsonWriter] -- the one the native `fast-sim` writes -- is the run
 * JSON the JVM path writes for the same run (Issue #1327, Goal 6).
 *
 * This is the one module that sees both sides: each case runs one example through
 * `Main.runExampleContext` (the `example` mode, which persists through
 * `DispatcherRunSummaries.finishAndPersist` and `DefaultRunSnapshotStore`), then hands the same run's
 * [cz.vutbr.fit.interlockSim.sim.metrics.RunRecord] to the writer. Both files are read back with
 * [DefaultRunSnapshotStore.readAll] and compared whole, except the run id. The wall-clock figures need no
 * exception: both sides take them from the one record. The count maps of the dispatcher figures are
 * compared without their zero entries: the JVM recorder writes every vocabulary key with 0, the writer of
 * a run without a dispatcher agent writes no key, and every reader treats a missing key as 0.
 *
 * A new required snapshot field, or a required key the writer spells differently, makes `readAll` skip the
 * native file (it logs the parse error at WARN), so the case fails on the native file count.
 *
 * The JVM side uses [DispatcherRunConfig.fromProperties] with no system property, so its parameters are
 * the ones a plain JVM run records: the writer's parameters must equal them, or the report would put a
 * native run into another cell.
 *
 * @since Issue #1327 (Goal 6)
 */
@DisplayName("The :core run JSON writer writes the run JSON the JVM path writes for the same run")
class RunRecordJsonReadBackTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	/** A main process that ends on its first control step, long before the horizon, without a failure. */
	private class AtOnceEndingMainProcess : LoopProcess() {
		override suspend fun iteration() {
			terminate()
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	/** A main process that throws on its second control step (the #1361 (e) fixture). */
	private class FailingMainProcess : LoopProcess() {
		private var steps = 0

		override suspend fun iteration() {
			steps++
			if (steps == 2) error("main process failure injected by the test")
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	@Test
	@DisplayName("the writer's schema version is the store's current one")
	fun writerSchemaVersionIsCurrent() {
		assertThat(RunRecordJsonWriter.SCHEMA_VERSION, name = "writer schema version")
			.isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
	}

	@Test
	@DisplayName("the writer spells the clean-run keys as the JVM vocabularies do (Issue #1476)")
	fun writerSpellsTheCleanRunKeys() {
		assertThat(RunRecordJsonWriter.AUTHOR_KEYS, name = "authors").isEqualTo(ActionAuthor.entries.map { it.name })
		assertThat(RunRecordJsonWriter.COUNTED_AUTOMATIC_KEYS, name = "counted automatics")
			.isEqualTo(CountedAutomatic.entries.map { it.name })
		assertThat(RunRecordJsonWriter.SILENT_CYCLE_CAUSE_KEYS, name = "silent-cycle causes")
			.isEqualTo(SilentCycleCause.entries.map { it.name })
		assertThat(RunRecordJsonWriter.SOURCE_RULE_BASED).isEqualTo(CommandSourceKind.RULE_BASED.name)
		assertThat(RunRecordJsonWriter.SOURCE_NONE).isEqualTo(CommandSourceKind.NONE.name)
	}

	@ParameterizedTest(name = "{0} for {1} s with main process {2} ends {3} under {4}")
	@MethodSource("cz.vutbr.fit.interlockSim.RunRecordJsonReadBackTest#cases")
	@Tag("integration-test")
	@Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `the writer's JSON reads back equal to the JVM's`(
		example: String,
		endTime: String,
		mainProcess: MainProcess,
		expectedCause: RunEndCause,
		expectedArm: DispatcherArm,
		@TempDir dir: Path
	) {
		val jvmRoot = dir.resolve("jvm")
		val nativeRoot = dir.resolve("native")
		val factory = checkNotNull(get<ExampleRegistry>().examples[example])
		val context =
			factory(get<SimulationContextFactory>(), arrayOf("example", example, endTime)) as DefaultSimulationContext
		val config =
			DispatcherRunConfig.fromProperties(
				properties = mapOf(DispatcherRunConfig.PROP_RUN_ID to JVM_RUN_ID)::get
			)
		context.scope.declare(config.copy(runsRoot = jvmRoot.toString()))
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(jvmRoot))
		when (mainProcess) {
			MainProcess.EXAMPLE -> Unit
			MainProcess.ENDS_AT_ONCE -> context.setMainProcess(AtOnceEndingMainProcess())
			MainProcess.FAILS -> context.setMainProcess(FailingMainProcess())
		}
		val recordSource = context.getMetricsServices().getRunRecordSource()

		get<Main>().runExampleContext(context, endTime.toDouble(), example)

		val record = checkNotNull(recordSource.runRecord()) { "run record" }
		val nativePath =
			RunRecordJsonWriter.write(
				nativeRoot.toString(),
				RecordedRun(record, NATIVE_RUN_ID, example, endTime.toDouble(), mainProcess == MainProcess.FAILS),
				TIMESTAMP
			)
		// A temporary file next to it, as a writer interrupted before its rename leaves one: readAll skips it.
		Files.writeString(Path.of("$nativePath.tmp"), "{")

		val jvmFiles = DefaultRunSnapshotStore(jvmRoot).readAll(jvmRoot)
		val nativeFiles = DefaultRunSnapshotStore(nativeRoot).readAll(nativeRoot)
		assertThat(jvmFiles, name = "JVM run JSONs").hasSize(1)
		assertThat(nativeFiles, name = "native run JSONs read by readAll").hasSize(1)
		val jvm = jvmFiles.single()
		val native = nativeFiles.single()

		assertThat(jvm.endCause, name = "JVM end cause").isEqualTo(expectedCause)
		assertThat(jvm.arm, name = "JVM arm").isEqualTo(expectedArm)
		assertThat(native.runId, name = "native run id").isEqualTo(NATIVE_RUN_ID)
		assertThat(
			native.schemaVersion,
			name = "native schema version"
		).isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
		assertThat(comparable(native), name = "native snapshot").isEqualTo(comparable(jvm))
		val jvmFile = Files.walk(jvmRoot).use { files -> files.filter { it.toString().endsWith(".json") }.toList().single() }
		assertThat(
			Path
				.of(nativePath)
				.parent.fileName
				.toString(),
			name = "arm directory"
		).isEqualTo(jvmFile.parent.fileName.toString())
		assertThat(Path.of(nativePath).fileName.toString(), name = "file name").endsWith("-$NATIVE_RUN_ID.json")
	}

	/** [snapshot] without its run id and without the zero entries of its count maps. */
	private fun comparable(snapshot: DispatcherRunSnapshot): DispatcherRunSnapshot =
		snapshot.copy(
			runId = "",
			ticksByOutcome = snapshot.ticksByOutcome.withoutZeros(),
			timeoutNoOpByCause = snapshot.timeoutNoOpByCause.withoutZeros(),
			emittedByActionType = snapshot.emittedByActionType.withoutZeros(),
			rejectionsByCode = snapshot.rejectionsByCode.withoutZeros(),
			applyFailuresByCode = snapshot.applyFailuresByCode.withoutZeros(),
			actionsByAuthor = snapshot.actionsByAuthor.withoutZeros()
		)

	private fun Map<String, Long>.withoutZeros(): Map<String, Long> = filterValues { it != 0L }

	/** Which main process the case runs. */
	enum class MainProcess {
		/** The example's own main process. */
		EXAMPLE,

		/** [AtOnceEndingMainProcess]: the run ends short of its horizon without a failure. */
		ENDS_AT_ONCE,

		/** [FailingMainProcess]: the main process dies (#1361 (e)). */
		FAILS
	}

	companion object {
		private const val JVM_RUN_ID = "jvm-run"
		private const val NATIVE_RUN_ID = "native-run"
		private const val TIMESTAMP = "20261122-101500"

		/**
		 * `cervenyUjezdSync` is the JVM twin of the native `cervenyUjezd` (both built with
		 * `wireSynchronousDispatcher`), and the native binary files its runs under that name, so both sides
		 * write the same `example`. `multiTrainLoop` wires no dispatcher. A 20 s horizon lets trains in but
		 * none out (starved); a main process that ends at once stops the run short of its horizon; the
		 * failing main process dies (#1361 (e)).
		 */
		@JvmStatic
		fun cases(): Stream<Arguments> =
			Stream.of(
				Arguments.of(
					ExampleNames.CERVENY_UJEZD_SYNC,
					"600",
					MainProcess.EXAMPLE,
					RunEndCause.NATURAL_COMPLETION,
					DispatcherArm.RULE_BASED
				),
				Arguments.of(
					ExampleNames.CERVENY_UJEZD_SYNC,
					"20",
					MainProcess.EXAMPLE,
					RunEndCause.STARVED,
					DispatcherArm.RULE_BASED
				),
				Arguments.of(
					ExampleNames.MULTI_TRAIN_LOOP,
					"600",
					MainProcess.EXAMPLE,
					RunEndCause.NATURAL_COMPLETION,
					DispatcherArm.NO_DISPATCHER
				),
				Arguments.of(
					ExampleNames.MULTI_TRAIN_LOOP,
					"600",
					MainProcess.ENDS_AT_ONCE,
					RunEndCause.TERMINATED_EARLY,
					DispatcherArm.NO_DISPATCHER
				),
				Arguments.of(
					ExampleNames.MULTI_TRAIN_LOOP,
					"600",
					MainProcess.FAILS,
					RunEndCause.TERMINATED_EARLY,
					DispatcherArm.NO_DISPATCHER
				)
			)
	}
}
