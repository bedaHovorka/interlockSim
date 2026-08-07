/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult

// Argument-extraction helpers for DomainTool implementations (SP1.6, Issue #551).
//
// Each tool extracts its string/enum arguments from the `Map<String, Any?>` passed to
// DomainTool.execute with the same shape: null out on missing / wrong-type / blank input so
// the tool can return a ToolResult.Error with a clear, parameter-specific message rather than
// forwarding a raw port exception. Centralising the pattern here keeps all tools consistent
// and guarantees blank-string rejection (#551 review: the per-tool `as? String` checks
// previously let `""` through to the port).

/**
 * Parameter name shared by every tool that targets a single train by id
 * (train_perception, approve_train, train_position, train_timetable).
 *
 * Centralising the literal keeps the [DomainToolParameter] declaration and the [stringParam]
 * lookup in lockstep, and removes the contiguous identical token run that Sonar CPD flagged
 * across the four train-id tools (Issue #552 review).
 */
internal const val TRAIN_ID_PARAM = "trainId"

/**
 * Error message returned by every train-id tool when [TRAIN_ID_PARAM] is missing, non-string,
 * or blank. Shared so the four tools do not repeat the literal (Sonar CPD de-duplication).
 */
internal const val TRAIN_ID_REQUIRED_MSG =
	"trainId parameter is required and must be a non-blank string"

/**
 * Builds the single `trainId` [DomainToolParameter] every train-targeting tool declares, with the
 * tool-specific [description] the LLM sees in the schema. Eliminates the duplicated
 * `DomainToolParameter(name = "trainId", …)` block across the four train-id tools.
 */
internal fun trainIdParameter(description: String): DomainToolParameter =
	DomainToolParameter(
		name = TRAIN_ID_PARAM,
		description = description,
		type = DomainToolParameterType.String,
		required = true
	)

/**
 * Returns the string value of [name] in this args map, or `null` if the entry is absent,
 * not a [String], or blank. Returning `null` for blank unifies the "missing" and "empty"
 * cases so a single `?: return ToolResult.Error(...)` handles both.
 */
internal fun Map<String, Any?>.stringParam(name: String): String? = (this[name] as? String)?.takeIf { it.isNotBlank() }

/**
 * Emits [action] through [sinkHolder], or returns the shared per-cycle-cap rejection.
 *
 * ## Why (Issue #847, SP2c.24)
 *
 * §5.5 caps a step at 0–3 actions, but the only implementation of that cap lived in
 * [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator], which production never constructs — so
 * on the live tool path the model could emit as many actions per cycle as it liked, and
 * `maxActionsPerTick` was recorded as the `-1` "not applicable" sentinel in every run JSON.
 * Routing all three mutating actuators through this one helper makes the cap real, keeps the
 * LLM-facing wording identical across them, and produces one
 * [RejectionCode.ACTION_LIMIT_EXCEEDED] the run recorder already knows how to count.
 *
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp] is deliberately not routed here:
 * `no_op` is a first-class decision (C6) and is exempt from the cap, exactly as in
 * [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator.validateBatch].
 *
 * @return `null` when the action was emitted; a [ToolResult.Error] to return verbatim otherwise.
 */
internal fun emitOrRejectForCap(
	sinkHolder: SinkHolder,
	action: DispatchAction
): ToolResult.Error? {
	if (sinkHolder.tryEmit(action)) return null
	return ToolResult.Error(
		"Per-tick action limit (${sinkHolder.maxActionsPerTick}) reached — this action was not " +
			"applied. Emit no more than ${sinkHolder.maxActionsPerTick} actions per dispatch cycle; " +
			"the rest of this situation can be handled on the next cycle.",
		rejection = RejectionCode.ACTION_LIMIT_EXCEEDED
	)
}

/**
 * Resolves a model-supplied train id against the [known] ids, tolerating the one abbreviation
 * the LLM demonstrably produces, and returns `null` when it cannot be resolved unambiguously.
 *
 * ## Why tolerate anything at all (Issue #847 round 2)
 *
 * Trains are named `"Train #1"`, `"Train #2"`, … (`Train.kt`), and the per-cycle message lists
 * those ids verbatim. The model nonetheless keeps sending the bare ordinal: in a 600 s
 * verification run **every one** of the 90 rejected train arguments was the literal `"1"` for
 * `"Train #1"` — 56 on `request_route`, 34 on `approve_train`. Zero trains completed as a result,
 * against 28 for the rule-based dispatcher on the identical scenario.
 *
 * Normally the answer would be to let the tool's error teach the model. That channel does exist —
 * an earlier version of this KDoc claimed it did not, citing Koog's Ollama converter as dropping
 * `MessagePart.Tool.Result` entirely at `OllamaConverters.kt:115`. That was a misreading, corrected
 * in round 3. `Prompt.toOllamaChatMessages` emits every tool-result part as its own
 * `OllamaChatMessageDTO(role = "tool", …)` (lines 37-44); line 115 is inside a helper that
 * assembles only the *text* half of a user turn and therefore skips tool parts by design, logging
 * a "Skipping unsupported message part" WARN that is noise rather than data loss.
 * `KoogRealOllamaToolCallingTest.a rejected tool argument is corrected on a later call` settles it
 * empirically: a token appearing nowhere but in a tool's error text comes back in the model's next
 * call.
 *
 * The channel is nonetheless thin, which is why resolution rather than strict rejection is still
 * the right call here. Ollama's message DTO carries no `tool_name` and no `is_error`, so with four
 * actuators on the surface a rejection arrives anonymous and unflagged.
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.ToolResultInliningPromptExecutor] now restores
 * both, but a 7B-class model correcting an id it has already re-sent 90 times in one run is a
 * prompt-compliance gamble, and every failed cycle is a dispatch opportunity lost. Resolving a
 * single unambiguous abbreviation costs nothing and cannot mis-target.
 *
 * So this resolves the abbreviation, conservatively:
 * - exact match always wins;
 * - otherwise, an input matches a known id only if that id ends with `"#<input>"`, and only when
 *   **exactly one** known id does — an ambiguous or unrecognized input still returns `null` and
 *   the caller still rejects it.
 *
 * This is deliberately narrow. It does not accept prefixes, fuzzy matches, or anything that could
 * silently retarget an action onto the wrong train — the failure this whole round exists to
 * prevent. Callers must emit the returned canonical id, never the raw input, so nothing
 * downstream ever sees the abbreviated form.
 */
internal fun resolveTrainId(
	raw: String,
	known: Set<String>
): String? {
	if (raw in known) return raw
	val suffix = "#$raw"
	return known.filter { it.endsWith(suffix) }.singleOrNull()
}

/**
 * Returns the enum value of [name] in this args map parsed case-insensitively against
 * [E]'s entries, or `null` if the entry is absent, not a [String], blank, or not a valid
 * [E] name. Using `enumValues<E>()` (reified) keeps parsing in lockstep with the domain
 * enum, so the [DomainToolParameterType.Enum] descriptor entries and the runtime parse
 * cannot drift apart.
 */
internal inline fun <reified E : Enum<E>> Map<String, Any?>.enumParam(name: String): E? {
	val raw = (this[name] as? String)?.takeIf { it.isNotBlank() } ?: return null
	return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
