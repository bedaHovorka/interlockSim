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

import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.agents.PromptVariant
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters

/**
 * One point of a [SweepGrid] — a complete parameter assignment, before the repeat index
 * (SP2c.24, Issue #847).
 *
 * @property example Registered console example name to run (`shuntingLoopAI`, `shuntingLoop`, …).
 * @property model Ollama model tag, or `null` to leave the executor default in place.
 * @property temperature Sampling temperature, or `null` to leave the executor default in place.
 * @property tickPeriodMs Minimum wall-clock spacing between driver cycles.
 * @property historyN Number of previous cycles rendered into each prompt; `0` disables the block.
 * @property maxActionsPerTick Per-cycle cap on non-NoOp actuator emissions.
 * @property inferenceTimeoutSeconds Per-cycle LLM inference deadline in seconds, or `null` to leave
 *   [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]'s own default (30s) in
 *   place — the grid says "unchanged", not "some particular value that happens to match today's
 *   default", exactly like [model]/[temperature] (Issue #893 iteration 2).
 * @property promptVariant Which DISPATCHER system-prompt revision this cell runs, or `null` to
 *   leave [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig.promptVariant]'s own resolution
 *   (system property > committed file > [PromptVariant.DEFAULT]) in place — the same "unchanged,
 *   not coincidentally-equal" reasoning as [model]/[temperature]/[inferenceTimeoutSeconds]. This
 *   is the axis Task 11 (#834) exists to create: a prompt revision can only be judged by running
 *   both arms of the same grid, never by assertion.
 */
data class SweepCell(
	val example: String,
	val model: String?,
	val temperature: Double?,
	val tickPeriodMs: Long,
	val historyN: Int,
	val maxActionsPerTick: Int,
	val inferenceTimeoutSeconds: Long? = null,
	val promptVariant: PromptVariant? = null
) {
	/**
	 * Filename- and run-id-safe identifier for this cell.
	 *
	 * Deterministic by construction, because it *is* the resumption key: the driver decides
	 * whether a run already happened by looking for its id among the JSONs already written, and
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore] sanitises anything
	 * outside `[A-Za-z0-9._-]` when it builds the file name. Producing the sanitised form here
	 * means the id written into the JSON and the id embedded in the file name are the same string,
	 * so the scan cannot miss a match.
	 */
	val slug: String
		get() =
			listOf(
				"ex-${sanitise(example)}",
				"m-${sanitise(model ?: "default")}",
				"t-${sanitise(temperature?.toString() ?: "default")}",
				"p-$tickPeriodMs",
				"h-$historyN",
				"a-$maxActionsPerTick",
				"it-${inferenceTimeoutSeconds ?: "default"}",
				"pv-${promptVariant?.name ?: "default"}"
			).joinToString("_")

	/** Deterministic run id for the [repeatIndex]-th repetition of this cell (1-based). */
	fun runId(repeatIndex: Int): String = "sweep-$slug-r%02d".format(repeatIndex)

	/**
	 * The `-D` properties a forked run needs to realise this cell.
	 *
	 * `model` and `temperature` are omitted when `null` so the child keeps whatever
	 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig] defaults to — the grid
	 * says "unchanged", not "some particular value that happens to match today's default".
	 */
	fun systemProperties(
		runId: String,
		runsRoot: String
	): Map<String, String> =
		buildMap {
			model?.let { put(DispatcherRunConfig.PROP_MODEL, it) }
			temperature?.let { put(DispatcherRunConfig.PROP_TEMPERATURE, it.toString()) }
			put(DispatcherRunConfig.PROP_TICK_PERIOD_MS, tickPeriodMs.toString())
			put(DispatcherRunConfig.PROP_HISTORY_N, historyN.toString())
			put(DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK, maxActionsPerTick.toString())
			put(DispatcherRunConfig.PROP_RUN_ID, runId)
			put(DispatcherRunConfig.PROP_RUNS_ROOT, runsRoot)
			inferenceTimeoutSeconds?.let { put(DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS, it.toString()) }
			promptVariant?.let { put(DispatcherRunConfig.PROP_PROMPT_VARIANT, it.name) }
		}

	/**
	 * The arm a run of this cell will record itself under.
	 *
	 * Mirrors what the example factories declare, and is needed before the run exists so that a
	 * timed-out or crashed run can still be written into the right arm directory — a failed run
	 * that vanished from the arm it belongs to would quietly improve that arm's measured rate.
	 */
	val arm: DispatcherArm
		get() = if (example.endsWith("AI")) DispatcherArm.LLM_TOOL_CALLING else DispatcherArm.RULE_BASED

	/**
	 * [RunParameters] as they will be recorded, used for the abort snapshot of a killed run.
	 *
	 * Must agree field-for-field with whatever the live recording path (the DI-built rule-based
	 * `RunParameters`, or the LLM arm's `llmRunParameters`) produces for an equivalent
	 * [DispatcherRunConfig] — otherwise an aborted run of this cell groups into a different
	 * report cell than its completed siblings, corrupting [RunReportAggregator]'s per-cell stats.
	 *
	 * ## How each arm resolves an omitted axis
	 *
	 * The rule-based arm records the empty-string sentinel for [model] and [promptVariant] and
	 * `0.0` for [temperature] — it never assembles a prompt or contacts Ollama, so those axes are
	 * not part of its identity. [inferenceTimeoutSeconds] still mirrors [systemProperties]'s `null`
	 * handling (`null` means "leave [KoogAgentPlanAdapter]'s own default in place"), so the recorded
	 * value is that default, not `null` (the field is non-nullable on [RunParameters]).
	 *
	 * The LLM arm resolves an **omitted** [model]/[temperature]/[promptVariant] through the same
	 * file-tier resolution the live path uses — [DispatcherRunConfig.fromProperties] for
	 * [promptVariant] and [inferenceTimeoutSeconds], [OllamaExecutorConfig.default] for [model] and
	 * [temperature] — so this abort snapshot records the value the forked child actually ran with
	 * (e.g. the committed `REVISED` prompt and `qwen2.5:7b-instruct` model), not a hardcoded
	 * `""`/`0.0`/[PromptVariant.DEFAULT] that the file has since diverged from.
	 *
	 * The `fromProperties` call is made with its system-property tier disabled
	 * (`properties = { null }`). This snapshot is recorded by the **parent** sweep driver, but a
	 * completed run of the same cell is recorded by the **forked child**, which inherits no parent
	 * `-D` flags — so for an omitted axis the child's own `fromProperties` resolves file > code.
	 * Matching the child requires this snapshot to do the same; reading `System.getProperty`
	 * here would let a parent launched with `-Dinterlocksim.dispatcher.*` diverge from its own
	 * completed children — the exact mis-grouping finding #7 exists to prevent. Review finding #7
	 * (Issue #834): the previous `model ?: ""` / `temperature ?: 0.0` / `promptVariant ?:
	 * PromptVariant.DEFAULT` only agreed with the live path on the one configuration where the
	 * file's committed values happened to equal the code constants — an omitted-axis LLM run that
	 * aborted would have grouped into a spurious Parameter Sweep cell, splitting its failure out
	 * of the real cell and inflating that cell's apparent passing rate.
	 *
	 * ## Which cells have no prompt at all
	 *
	 * The empty-string sentinel belongs to the rule-based arm, and [arm] is what decides that — not
	 * `model == null`, which Task 4 (#834) used as a stand-in before this axis existed. The two
	 * disagree in both directions and each disagreement misfiles a run: a rule-based cell that
	 * *does* pin a model (harmless — the rule-based arm never contacts Ollama, but a grid may still
	 * hold the axis fixed) would be recorded as having used a prompt it never assembled, and an LLM
	 * cell that leaves `model` at the executor default would be recorded as having used no prompt
	 * at all and grouped with the rule-based rows. [arm] is derived from the same `example` name the
	 * run's recorder is armed from, so this abort snapshot and the live recording can never
	 * disagree about which arm a run belongs to.
	 */
	fun runParameters(): RunParameters {
		// The rule-based arm never assembles a prompt or contacts Ollama, so its omitted axes are
		// the empty/zero sentinel, never the file-tier LLM values.
		if (arm == DispatcherArm.RULE_BASED) {
			return RunParameters(
				tickPeriodMs = tickPeriodMs,
				historyN = historyN,
				temperature = temperature ?: 0.0,
				maxActionsPerTick = maxActionsPerTick,
				model = model ?: "",
				seed = null,
				inferenceTimeoutSeconds = inferenceTimeoutSeconds ?: KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS,
				promptVariant = ""
			)
		}
		// LLM arm: resolve omitted model/temperature/promptVariant/inferenceTimeoutSeconds through
		// the same file-tier resolution the live path (DispatcherAgentModule's
		// runConfig ?: OllamaExecutorConfig.default()) uses, then override with this cell's pinned
		// values.
		//
		// The system-property tier is deliberately skipped here: this abort snapshot is recorded by
		// the *parent* sweep driver, but a completed run of the same cell is recorded by the *forked
		// child*, which receives only this cell's -D flags (ProcessBuilder passes no parent -D
		// inheritance — AiSweepDriver builds the java command from exactly systemProperties()). For
		// an omitted axis the child has no -D flag, so its own fromProperties() resolves file > code.
		// Reading System.getProperty here would let a parent launched with
		// -Dinterlocksim.dispatcher.* diverge from its own completed children — the exact
		// mis-grouping finding #7 exists to prevent. fromProperties(properties = { null }) resolves
		// file > code, matching the child for an omitted axis regardless of parent -D.
		val executorDefault = OllamaExecutorConfig.default()
		val runConfigDefault = DispatcherRunConfig.fromProperties(properties = { null })
		return RunParameters(
			tickPeriodMs = tickPeriodMs,
			historyN = historyN,
			temperature = (temperature ?: executorDefault.temperature).toDouble(),
			maxActionsPerTick = maxActionsPerTick,
			model = model ?: executorDefault.modelName,
			seed = null,
			inferenceTimeoutSeconds =
				inferenceTimeoutSeconds ?: runConfigDefault.inferenceTimeoutSeconds,
			promptVariant = (promptVariant ?: runConfigDefault.promptVariant).name
		)
	}

	private fun sanitise(raw: String): String = raw.replace(Regex("[^A-Za-z0-9.-]"), "-")
}

/**
 * One scheduled run: a [SweepCell] plus which repetition of it this is.
 *
 * @property cell The parameter assignment.
 * @property repeatIndex 1-based repetition number within [cell].
 * @property endTimeSeconds Simulated end time passed to the example.
 */
data class SweepRun(
	val cell: SweepCell,
	val repeatIndex: Int,
	val endTimeSeconds: Long
) {
	/** Deterministic, resumption-safe identifier for this run. */
	val runId: String get() = cell.runId(repeatIndex)
}
