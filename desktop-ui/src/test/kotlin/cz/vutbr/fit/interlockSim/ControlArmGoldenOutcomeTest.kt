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

import assertk.all
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.test.get
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The railway outcome of the rule-based control arms, pinned per run (Issue #1458, Goal 9B SP3.3):
 * the CI half of the #895 bracket. Goal 9B changes the routing path, the command path and the
 * dispatcher lifecycle several times; each of those changes must leave every figure below unchanged,
 * or say in its PR why it moves.
 *
 * The control arm is the rule-based dispatcher on the dispatcher-agent stack, and it is
 * byte-identical run to run (`GOAL_10_SP2C14_RELIABILITY_REPORT.md` section 0), so one run per
 * example and seed is enough: no repeat, no wall-clock assertion. Each run is built from the CLI map
 * [ExampleRegistry.examples] and run through [Main.runExampleContext] on a fresh context, exactly as
 * `java -jar interlockSim.jar example <name> 600 [seed]` runs it, and the run JSON it persists (the
 * file `aiSweep` reads) is read back from a temporary runs root. The figures:
 *
 * - trains entered, trains exited, block transitions, failed reservations: the persisted
 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome];
 * - exits by the own (timetable) InOut: trains exited minus wrong exits, as `ExitPassBars` counts them;
 * - class A blind, class A fallback, class B: the wrong-out classes of `SP10-praha-llm-dispatcher.md`,
 *   from the loop's [ShuntingLoop.getWrongOutClassA], [ShuntingLoop.getWrongOutClassAFallback] and
 *   [ShuntingLoop.getWrongOutClassB] (blind = class A minus fallback);
 * - end cause: the persisted [RunEndCause].
 *
 * Train names are not pinned: they come from a process-wide counter, so they depend on what ran
 * before in the same JVM. `shuntingLoop` and `cervenyUjezd` read no seed argument; they run on the
 * generator's historical seed 0.
 *
 * **Rule: the golden values in [GOLDEN] are never re-recorded without a traffic-simulation-expert
 * ruling in the PR that changes them.** A failing row is a finding about the change under test, not
 * a stale table: explain the moved figure, get the ruling, then edit the row.
 *
 * Recorded on `develop` 9b63f780 (the base of the Goal 9B stack). Cross-checked against the demand
 * files: `shuntingLoop` and `cervenyUjezd` against the #895 bracket in `SP10-praha-llm-dispatcher.md`
 * (15 / 15 and 30 transitions; 19 / 19, 38 transitions and 1 failed reservation per run), the Praha
 * seeds against its per-seed table (8 exited on every seed, the own-InOut exits and fallback A per seed).
 *
 * @since Issue #1458 (Goal 9B SP3.3)
 */
@Tag("integration-test")
@DisplayName("Control-arm golden outcome: the rule-based arms move the same trains on every run")
class ControlArmGoldenOutcomeTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	/** The pinned railway outcome of one control-arm run. */
	data class ControlArmOutcome(
		val trainsEntered: Long,
		val trainsExited: Long,
		val ownInOutExits: Long,
		val classABlind: Int,
		val classAFallback: Int,
		val classB: Int,
		val blockTransitions: Long,
		val failedReservations: Long,
		val endCause: RunEndCause
	)

	/** One golden row: the example, the seed argument (`null`: the example takes none) and its outcome. */
	data class GoldenRun(
		val example: String,
		val seed: Long?,
		val expected: ControlArmOutcome
	) {
		val label: String get() = if (seed == null) "$example (no seed argument)" else "$example seed $seed"

		override fun toString(): String = label
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("goldenRuns")
	@Timeout(value = 300, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `the control arm run reproduces its golden outcome`(
		golden: GoldenRun,
		@TempDir runsRoot: Path
	) {
		val (outcome, snapshot) = runControlArm(golden, runsRoot)
		logger.info { "Control-arm golden run ${golden.label}: $outcome" }

		assertThat(snapshot.example, name = "${golden.label}: example in the run JSON").isEqualTo(golden.example)
		assertThat(outcome, name = golden.label).all {
			prop(ControlArmOutcome::trainsEntered).isEqualTo(golden.expected.trainsEntered)
			prop(ControlArmOutcome::trainsExited).isEqualTo(golden.expected.trainsExited)
			prop(ControlArmOutcome::ownInOutExits).isEqualTo(golden.expected.ownInOutExits)
			prop(ControlArmOutcome::classABlind).isEqualTo(golden.expected.classABlind)
			prop(ControlArmOutcome::classAFallback).isEqualTo(golden.expected.classAFallback)
			prop(ControlArmOutcome::classB).isEqualTo(golden.expected.classB)
			prop(ControlArmOutcome::blockTransitions).isEqualTo(golden.expected.blockTransitions)
			prop(ControlArmOutcome::failedReservations).isEqualTo(golden.expected.failedReservations)
			prop(ControlArmOutcome::endCause).isEqualTo(golden.expected.endCause)
		}
	}

	/**
	 * Runs [golden]'s example once on a fresh context with [runsRoot] as the runs root, and returns its
	 * outcome and the run JSON read back. The runs root reaches the run through the
	 * [DispatcherRunConfig] singleton, which reads it when the example is built; Koin restarts per test.
	 */
	private fun runControlArm(
		golden: GoldenRun,
		runsRoot: Path
	): Pair<ControlArmOutcome, DispatcherRunSnapshot> {
		val args = listOfNotNull("example", golden.example, END_TIME, golden.seed?.toString()).toTypedArray()
		val previous = System.getProperty(DispatcherRunConfig.PROP_RUNS_ROOT)
		System.setProperty(DispatcherRunConfig.PROP_RUNS_ROOT, runsRoot.toString())
		val loop =
			try {
				val factory = checkNotNull(get<ExampleRegistry>().examples[golden.example]) { "${golden.label}: not registered" }
				val context = factory(get(), args) as DefaultSimulationContext
				val loop = context.mainProcess as ShuntingLoop
				get<Main>().runExampleContext(context, END_TIME.toDouble(), golden.example)
				loop
			} finally {
				if (previous == null) {
					System.clearProperty(DispatcherRunConfig.PROP_RUNS_ROOT)
				} else {
					System.setProperty(DispatcherRunConfig.PROP_RUNS_ROOT, previous)
				}
			}
		val snapshot = DefaultRunSnapshotStore(runsRoot).readAll(runsRoot).single()
		val railway = snapshot.railwayOutcome

		fun figure(
			value: Long?,
			field: String
		): Long = checkNotNull(value) { "${golden.label}: $field missing from the run JSON" }
		val exited = figure(railway.trainsExited, "trainsExited")
		val outcome =
			ControlArmOutcome(
				trainsEntered = figure(railway.trainsEntered, "trainsEntered"),
				trainsExited = exited,
				ownInOutExits = exited - figure(railway.wrongExits, "wrongExits"),
				classABlind = loop.getWrongOutClassA() - loop.getWrongOutClassAFallback(),
				classAFallback = loop.getWrongOutClassAFallback(),
				classB = loop.getWrongOutClassB(),
				blockTransitions = figure(railway.blockTransitions, "blockTransitions"),
				failedReservations = figure(railway.failedReservations, "failedReservations"),
				endCause = checkNotNull(snapshot.endCause) { "${golden.label}: endCause missing from the run JSON" }
			)
		return outcome to snapshot
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** The simulated horizon of the #895 bracket and of the 1B-SP10 per-seed table. */
		private const val END_TIME = "600"

		private fun praha(
			seed: Long,
			ownInOutExits: Long,
			classAFallback: Int,
			blockTransitions: Long
		) = GoldenRun(
			ExampleNames.PRAHA_HLAVNI_NADRAZI,
			seed,
			ControlArmOutcome(
				trainsEntered = 8,
				trainsExited = 8,
				ownInOutExits = ownInOutExits,
				classABlind = 0,
				classAFallback = classAFallback,
				classB = 0,
				blockTransitions = blockTransitions,
				failedReservations = 0,
				endCause = RunEndCause.NATURAL_COMPLETION
			)
		)

		/**
		 * The golden values, one row per run. Never re-recorded without a traffic-simulation-expert
		 * ruling in the PR that changes them (see the class KDoc).
		 */
		val GOLDEN: List<GoldenRun> =
			listOf(
				GoldenRun(
					ExampleNames.SHUNTING_LOOP,
					null,
					ControlArmOutcome(
						trainsEntered = 15,
						trainsExited = 15,
						ownInOutExits = 15,
						classABlind = 0,
						classAFallback = 0,
						classB = 0,
						blockTransitions = 30,
						failedReservations = 0,
						endCause = RunEndCause.NATURAL_COMPLETION
					)
				),
				GoldenRun(
					ExampleNames.CERVENY_UJEZD,
					null,
					ControlArmOutcome(
						trainsEntered = 19,
						trainsExited = 19,
						ownInOutExits = 19,
						classABlind = 0,
						classAFallback = 0,
						classB = 0,
						blockTransitions = 38,
						failedReservations = 1,
						endCause = RunEndCause.NATURAL_COMPLETION
					)
				),
				praha(seed = 1366, ownInOutExits = 7, classAFallback = 1, blockTransitions = 16),
				praha(seed = 0, ownInOutExits = 8, classAFallback = 0, blockTransitions = 16),
				praha(seed = 1, ownInOutExits = 8, classAFallback = 0, blockTransitions = 16),
				praha(seed = 2, ownInOutExits = 8, classAFallback = 0, blockTransitions = 16),
				praha(seed = 3, ownInOutExits = 8, classAFallback = 0, blockTransitions = 16),
				praha(seed = 4, ownInOutExits = 8, classAFallback = 0, blockTransitions = 16),
				praha(seed = 5, ownInOutExits = 5, classAFallback = 3, blockTransitions = 16),
				praha(seed = 6, ownInOutExits = 7, classAFallback = 1, blockTransitions = 16),
				praha(seed = 7, ownInOutExits = 6, classAFallback = 2, blockTransitions = 16),
				praha(seed = 8, ownInOutExits = 7, classAFallback = 1, blockTransitions = 16)
			)

		@JvmStatic
		fun goldenRuns(): List<GoldenRun> = GOLDEN
	}
}
