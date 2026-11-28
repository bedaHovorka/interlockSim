/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * The clean-run rule of Goal 9B (Issue #1476, rulings Q1 and Q2 of #1475): a run is clean when its end
 * cause is natural completion, its run JSON has the #1476 schema or later, every author other than `LLM`
 * and `TIMEOUT_NOOP` has zero actions, and the unattributed-action count is zero.
 *
 * Every case drives a real [DefaultDispatcherRunRecorder] and judges its frozen snapshot, so the test pins
 * the counters the recorder writes together with the rule that reads them. One case per counted row of
 * #1475 (C1-C6 and C13-C14 through [CountedAutomatic], C8-C12 and C15 through the action author).
 */
@DisplayName("The clean-run rule judges a run by its run JSON (Issue #1476)")
class CleanRunRuleTest {
	private val params =
		RunParameters(
			tickPeriodMs = 0L,
			historyN = 0,
			temperature = 0.28,
			maxActionsPerTick = 3,
			model = MODEL,
			seed = null
		)

	private fun recorder(arm: DispatcherArm = DispatcherArm.LLM_TOOL_CALLING) =
		DefaultDispatcherRunRecorder(runId = "clean-run", arm = arm, params = params)

	private fun outcome(
		author: ActionAuthor,
		tickIndex: Long = 0L
	): ActionOutcome =
		ActionOutcome(
			phase = ActionPhase.APPLIED,
			rejection = null,
			applyFailure = null,
			authored =
				AuthoredAction(author = author, reason = "test", decisionKind = "RequestRoute", tickIndex = tickIndex)
		)

	/** A recorder that saw [llmActions] applied LLM actions in as many acting cycles. */
	private fun llmRun(llmActions: Int = 3): DefaultDispatcherRunRecorder =
		recorder().apply {
			repeat(llmActions) { index ->
				onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = index.toDouble()))
				onActionOutcome(outcome(ActionAuthor.LLM, tickIndex = index.toLong()))
			}
		}

	@Test
	fun `a run with only LLM actions that completed naturally is clean`() {
		val snapshot = llmRun().finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(CleanRunRule.violations(snapshot)).isEmpty()
		assertThat(CleanRunRule.isClean(snapshot)).isTrue()
		assertThat(snapshot.schemaVersion).isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
		assertThat(snapshot.commandSource).isEqualTo(CommandSource(CommandSourceKind.LLM, MODEL))
	}

	@ParameterizedTest(name = "{0} counts under its author and breaks a clean run")
	@EnumSource(CountedAutomatic::class)
	fun `each counted automatic without an action path raises its author's counter and the run is not clean`(
		automatic: CountedAutomatic
	) {
		val recorder = llmRun()
		recorder.onCountedAutomatics(mapOf(automatic to 1L))
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(snapshot.countedAutomatics?.get(automatic.name), name = "row counter").isEqualTo(1L)
		assertThat(snapshot.actionsByAuthor[automatic.author.name], name = "author counter").isEqualTo(1L)
		assertThat(snapshot.actionsByAuthor[ActionAuthor.LLM.name], name = "LLM counter").isEqualTo(3L)
		assertThat(CleanRunRule.isClean(snapshot)).isFalse()
		assertThat(CleanRunRule.violations(snapshot)).contains(CleanRunRule.Violation.COUNTED_AUTHOR_ACTED)
	}

	@Test
	fun `the rows map to the authors the 1475 table names`() {
		assertThat(CountedAutomatic.EXPIRY_WHOLE_HOLDING.author).isEqualTo(ActionAuthor.EXPIRY)
		assertThat(CountedAutomatic.EXPIRY_OCCUPYING_TAIL.author).isEqualTo(ActionAuthor.EXPIRY)
		assertThat(CountedAutomatic.EXPIRY_ON_REQUEST.author).isEqualTo(ActionAuthor.EXPIRY)
		assertThat(CountedAutomatic.SWEEP_STALE.author).isEqualTo(ActionAuthor.ORPHAN_SWEEP)
		assertThat(CountedAutomatic.SWEEP_PARTIAL.author).isEqualTo(ActionAuthor.ORPHAN_SWEEP)
		assertThat(CountedAutomatic.SWEEP_PHANTOM.author).isEqualTo(ActionAuthor.ORPHAN_SWEEP)
		assertThat(CountedAutomatic.EMERGENCY_RESTRICTION.author).isEqualTo(ActionAuthor.EMERGENCY_SYSTEM)
		assertThat(CountedAutomatic.WATCHDOG_STOP.author).isEqualTo(ActionAuthor.WATCHDOG)
	}

	@ParameterizedTest(name = "{0}: an action by {1} counts under it and breaks a clean run")
	@CsvSource(
		"C8 rule fallback, RULE_FALLBACK",
		"C9 admission safety net, SAFETY_NET",
		"C10 interlocking target choice, SAFETY_NET",
		"C11 entry target choice, SAFETY_NET",
		"C12 SEMI_AUTO approval, OPERATOR",
		"C15 rule-based control arm, RULE_BASED"
	)
	fun `each counted row with an action path raises its author's counter and the run is not clean`(
		row: String,
		author: ActionAuthor
	) {
		val recorder = llmRun()
		recorder.onActionOutcome(outcome(author))
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(snapshot.actionsByAuthor[author.name], name = "$row author counter").isEqualTo(1L)
		// Counted once, under its own author, and not also under LLM (C10/C11 rule of #1475).
		assertThat(snapshot.actionsByAuthor[ActionAuthor.LLM.name], name = "$row LLM counter").isEqualTo(3L)
		assertThat(CleanRunRule.isClean(snapshot), name = row).isFalse()
	}

	@Test
	fun `a run whose silent cycles are timeouts, no_op cycles and empty plans and otherwise only LLM actions is clean`() {
		val recorder = llmRun()
		recorder.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, 10.0, timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS))
		recorder.onTick(TickRecord(TickOutcome.TIMEOUT_NOOP, 11.0, timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS))
		recorder.onTick(
			TickRecord(TickOutcome.TIMEOUT_NOOP, 12.0, timeoutNoOpCause = TimeoutNoOpCause.EMPTY_UNPARSEABLE)
		)
		recorder.onTick(TickRecord(TickOutcome.LLM_SILENT_ALL_MOVING, 13.0))
		repeat(3) { recorder.onTick(TickRecord(TickOutcome.LLM_NO_OP, 14.0 + it)) }
		// The deadline substitute itself is a TIMEOUT_NOOP-authored no-op: allowed by the rule.
		recorder.onActionOutcome(outcome(ActionAuthor.TIMEOUT_NOOP))
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(CleanRunRule.violations(snapshot)).isEmpty()
		assertThat(snapshot.silentCyclesByCause).isEqualTo(
			mapOf(
				SilentCycleCause.TIMEOUT.name to 2L,
				SilentCycleCause.THINKING_TRUNCATED.name to 0L,
				SilentCycleCause.EMPTY_PLAN.name to 2L,
				SilentCycleCause.NO_OP.name to 3L
			)
		)
	}

	@Test
	fun `a cycle cut by the num_predict cap counts under THINKING_TRUNCATED only, never also as a timeout`() {
		// THINKING_TRUNCATED is the #1342 sub-cause of TIMEOUT_NOOP; the map is read by name, so it is
		// counted as soon as #1342 adds the cause.
		val counts =
			SilentCycleCause.countsFrom(
				ticksByOutcome = mapOf(TickOutcome.TIMEOUT_NOOP.name to 3L),
				timeoutNoOpByCause =
					mapOf(
						TimeoutNoOpCause.DEADLINE_MISS.name to 1L,
						SilentCycleCause.THINKING_TRUNCATED.name to 2L
					)
			)

		assertThat(counts[SilentCycleCause.THINKING_TRUNCATED.name]).isEqualTo(2L)
		assertThat(counts[SilentCycleCause.TIMEOUT.name]).isEqualTo(1L)
		assertThat(counts.values.sum()).isEqualTo(3L)
	}

	@Test
	fun `a cut cycle that already emitted counts its actions under LLM`() {
		// A cycle the num_predict cap cut after it emitted commands is an acting cycle, not a silent one
		// (#1475 §1b.1): its tick is LLM_ACTIONS and its commands are LLM actions. The MockWebServer
		// done_reason=length variant comes with the chain rebase onto #1342.
		val recorder = llmRun()
		recorder.onTick(TickRecord(TickOutcome.LLM_ACTIONS, simTime = 20.0))
		recorder.onActionOutcome(outcome(ActionAuthor.LLM, tickIndex = 20L))
		recorder.onActionOutcome(outcome(ActionAuthor.LLM, tickIndex = 20L))
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(snapshot.actionsByAuthor[ActionAuthor.LLM.name]).isEqualTo(5L)
		assertThat(snapshot.silentCyclesByCause?.values?.sum()).isEqualTo(0L)
		assertThat(CleanRunRule.isClean(snapshot)).isTrue()
	}

	@Test
	fun `a snapshot of the clean-run schema without a command source is not clean`() {
		val snapshot = llmRun().finish(RunEndCause.NATURAL_COMPLETION).copy(commandSource = null)

		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.NO_COMMAND_SOURCE)
	}

	@Test
	fun `a non-zero unattributed-action count makes the run not clean`() {
		val recorder = llmRun()
		recorder.onActionOutcome(outcome(ActionAuthor.LLM, tickIndex = -1L))
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(snapshot.unattributedApplies).isEqualTo(1L)
		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.UNATTRIBUTED_ACTIONS)
	}

	@Test
	fun `a clean-run snapshot with an empty countedAutomatics map is not clean`() {
		val snapshot = llmRun().finish(RunEndCause.NATURAL_COMPLETION).copy(countedAutomatics = emptyMap())

		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.INCOMPLETE_COUNTERS)
	}

	@ParameterizedTest(name = "a snapshot without the {0} row is not clean")
	@EnumSource(CountedAutomatic::class)
	fun `a clean-run snapshot missing one counted-automatic key is not clean`(automatic: CountedAutomatic) {
		val complete = llmRun().finish(RunEndCause.NATURAL_COMPLETION)
		val snapshot = complete.copy(countedAutomatics = complete.countedAutomatics?.minus(automatic.name))

		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.INCOMPLETE_COUNTERS)
	}

	@ParameterizedTest(name = "a snapshot without the {0} author is not clean")
	@EnumSource(ActionAuthor::class)
	fun `a clean-run snapshot missing one author key is not clean`(author: ActionAuthor) {
		val complete = llmRun().finish(RunEndCause.NATURAL_COMPLETION)
		val snapshot = complete.copy(actionsByAuthor = complete.actionsByAuthor - author.name)

		assertThat(CleanRunRule.violations(snapshot)).contains(CleanRunRule.Violation.INCOMPLETE_COUNTERS)
	}

	@Test
	fun `a clean-run snapshot without silentCyclesByCause is not clean`() {
		val complete = llmRun().finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(CleanRunRule.violations(complete.copy(silentCyclesByCause = null)))
			.containsExactly(CleanRunRule.Violation.INCOMPLETE_COUNTERS)
		assertThat(
			CleanRunRule.violations(
				complete.copy(silentCyclesByCause = complete.silentCyclesByCause?.minus(SilentCycleCause.NO_OP.name))
			)
		).containsExactly(CleanRunRule.Violation.INCOMPLETE_COUNTERS)
	}

	@Test
	fun `an author total below the sum of its counted rows is not clean`() {
		val complete = llmRun().finish(RunEndCause.NATURAL_COMPLETION)
		// The row says the expiry acted twice, the one map the rule reads says it never did.
		val snapshot =
			complete.copy(
				countedAutomatics = complete.countedAutomatics?.plus(CountedAutomatic.EXPIRY_WHOLE_HOLDING.name to 2L)
			)

		assertThat(snapshot.actionsByAuthor[ActionAuthor.EXPIRY.name]).isEqualTo(0L)
		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.INCOMPLETE_COUNTERS)
	}

	@Test
	fun `the keys a schema-17 file must carry stay pinned to schema 17`() {
		assertThat(CleanRunRule.requiredCountedAutomaticKeys(SCHEMA_OF_1476))
			.isEqualTo(CountedAutomatic.entries.map { it.name }.toSet())
		assertThat(CleanRunRule.requiredAuthorKeys(SCHEMA_OF_1476)).isEqualTo(ActionAuthor.entries.map { it.name }.toSet())
		assertThat(CleanRunRule.requiredCountedAutomaticKeys(SCHEMA_BEFORE_1476)).isEmpty()
		assertThat(CleanRunRule.requiredAuthorKeys(SCHEMA_BEFORE_1476)).isEmpty()
	}

	@ParameterizedTest(name = "{0} has the schema it was introduced in")
	@EnumSource(CountedAutomatic::class)
	fun `every counted automatic names the schema that introduced it`(automatic: CountedAutomatic) {
		assertThat(automatic.sinceSchema).isEqualTo(SCHEMA_OF_1476)
	}

	@ParameterizedTest(name = "{0} is required from a pinned schema")
	@EnumSource(ActionAuthor::class)
	fun `every ActionAuthor has a since-schema`(author: ActionAuthor) {
		assertThat(CleanRunRule.requiredAuthorKeys(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)).contains(author.name)
	}

	@Test
	fun `a clean run read back from the store stays clean`(
		@TempDir dir: Path
	) {
		val written = llmRun().finish(RunEndCause.NATURAL_COMPLETION)
		DefaultRunSnapshotStore(dir).write(written)

		val read = DefaultRunSnapshotStore(dir).readAll(dir).single()
		assertThat(CleanRunRule.violations(read)).isEmpty()
	}

	@ParameterizedTest(name = "a run that ended {0} is not clean")
	@EnumSource(RunEndCause::class, names = ["NATURAL_COMPLETION"], mode = EnumSource.Mode.EXCLUDE)
	fun `only a natural completion can be clean`(cause: RunEndCause) {
		val snapshot = llmRun().finish(cause)

		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.NOT_NATURAL_COMPLETION)
	}

	@Test
	fun `an in-progress snapshot is not clean`() {
		assertThat(CleanRunRule.isClean(llmRun().snapshot())).isFalse()
	}

	@Test
	fun `a run of the rule-based arm or without a dispatcher is not judged clean`() {
		val ruleBased = recorder(DispatcherArm.RULE_BASED).finish(RunEndCause.NATURAL_COMPLETION)
		val none = recorder(DispatcherArm.NO_DISPATCHER).finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(ruleBased.commandSource).isEqualTo(CommandSource(CommandSourceKind.RULE_BASED, ""))
		assertThat(none.commandSource).isEqualTo(CommandSource(CommandSourceKind.NONE, ""))
		assertThat(CleanRunRule.violations(ruleBased)).containsExactly(CleanRunRule.Violation.NO_COMMAND_SOURCE)
		assertThat(CleanRunRule.violations(none)).containsExactly(CleanRunRule.Violation.NO_COMMAND_SOURCE)
	}

	@Test
	fun `a scripted run carries author LLM and is told apart only by its command source`() {
		val recorder = llmRun()
		recorder.recordCommandSource(CommandSource(CommandSourceKind.SCRIPTED, SCRIPT))
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(snapshot.commandSource).isEqualTo(CommandSource(CommandSourceKind.SCRIPTED, SCRIPT))
		assertThat(CleanRunRule.isClean(snapshot)).isTrue()
	}

	@Test
	fun `the command source and the new counters are written and read back`(
		@TempDir dir: Path
	) {
		val recorder = llmRun()
		recorder.recordCommandSource(CommandSource(CommandSourceKind.SCRIPTED, SCRIPT))
		recorder.onCountedAutomatics(mapOf(CountedAutomatic.SWEEP_PHANTOM to 2L))
		val written = recorder.finish(RunEndCause.NATURAL_COMPLETION)
		val path = DefaultRunSnapshotStore(dir).write(written)

		val keys = Json.parseToJsonElement(Files.readString(path)).jsonObject
		assertThat(keys.getValue("commandSource").jsonObject.keys).isEqualTo(setOf("kind", "name"))
		assertThat(keys.getValue("commandSource").jsonObject["kind"]).isEqualTo(JsonPrimitive("SCRIPTED"))
		val read = DefaultRunSnapshotStore(dir).readAll(dir).single()
		assertThat(read).isEqualTo(written)
		assertThat(read.countedAutomatics?.get(CountedAutomatic.SWEEP_PHANTOM.name)).isEqualTo(2L)
		assertThat(read.actionsByAuthor[ActionAuthor.ORPHAN_SWEEP.name]).isEqualTo(2L)
	}

	@Test
	fun `a file of the schema before 1476 reads, aggregates and is never clean`(
		@TempDir dir: Path
	) {
		val current =
			llmRun()
				.finish(RunEndCause.NATURAL_COMPLETION)
				.copy(example = ExampleNames.SHUNTING_LOOP_AI)
		val file = dir.resolve("llm_tool_calling").resolve("20261120-101500-old.json")
		Files.createDirectories(file.parent)
		Files.writeString(file, schemaBefore1476(current))

		val read = DefaultRunSnapshotStore(dir).readAll(dir)
		assertThat(read).hasSize(1)
		val old = read.single()
		assertThat(old.schemaVersion).isEqualTo(SCHEMA_BEFORE_1476)
		assertThat(old.commandSource).isNull()
		assertThat(old.countedAutomatics).isNull()
		assertThat(old.silentCyclesByCause).isNull()
		// The old keys map: the run aggregates with the LLM actions it recorded, and the command source
		// comes from its arm and model.
		val aggregator = RunReportAggregator(DefaultRunSnapshotStore(dir))
		val report = aggregator.aggregate(read)
		assertThat(report.runCount).isEqualTo(1)
		// The run is judged by its own exit bar as before; only the clean-run rule refuses it.
		aggregator.runPassed(old)
		assertThat(old.actionsByAuthorTyped()[ActionAuthor.LLM]).isEqualTo(3L)
		assertThat(ExitPassBars.barFor(old.example)).isNotNull()
		assertThat(old.commandSourceOrDerived()).isEqualTo(CommandSource(CommandSourceKind.LLM, MODEL))
		assertThat(CleanRunRule.isClean(old)).isFalse()
		assertThat(CleanRunRule.violations(old)).containsExactly(CleanRunRule.Violation.SCHEMA_BEFORE_CLEAN_RUN_RULE)
	}

	/**
	 * [current] as a schema-16 writer wrote it: schema 16, none of the #1476 keys, and the six authors that
	 * vocabulary had.
	 */
	private fun schemaBefore1476(current: DispatcherRunSnapshot): String {
		val json = Json { encodeDefaults = true }
		val element = json.encodeToJsonElement(DispatcherRunSnapshot.serializer(), current).jsonObject
		val oldAuthors = setOf("LLM", "TIMEOUT_NOOP", "RULE_BASED", "RULE_FALLBACK", "SAFETY_NET", "OPERATOR")
		val byAuthor = element.getValue("actionsByAuthor").jsonObject.filterKeys { it in oldAuthors }
		val old =
			element
				.filterKeys { it !in setOf("commandSource", "countedAutomatics", "silentCyclesByCause") }
				.plus("schemaVersion" to JsonPrimitive(SCHEMA_BEFORE_1476))
				.plus("actionsByAuthor" to JsonObject(byAuthor))
		return JsonObject(old).toString()
	}

	private companion object {
		const val MODEL = "qwen2.5:7b-instruct"
		const val SCRIPT = "perfect-dispatcher"
		const val SCHEMA_BEFORE_1476 = 16
		const val SCHEMA_OF_1476 = 17
	}
}
