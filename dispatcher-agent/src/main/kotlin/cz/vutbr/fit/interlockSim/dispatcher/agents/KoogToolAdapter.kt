/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Bridges one [DomainTool] to Koog's native tool-calling runtime (SP2b.9, Issue #566).
 *
 * Wraps [domainTool]'s Koog-agnostic [DomainToolParameter]/[ToolResult] contract as
 * `ai.koog.agents.core.tools.Tool<Map<String, Any?>, String>` so it can be registered in a Koog
 * `ToolRegistry` and invoked by `singleRunStrategy()`'s tool-calling loop.
 *
 * ## Why not [ai.koog.agents.core.tools.SimpleTool]
 *
 * [SimpleTool]'s only constructor derives its [ToolDescriptor] by reflecting over the `TArgs`
 * type — useful for a `@Serializable data class` per tool (Koog's own documented "getting
 * started" pattern), but useless for a raw `Map<String, Any?>`. This class instead extends
 * [Tool]'s primary (explicit-descriptor) constructor and builds the [ToolDescriptor] manually
 * from [DomainTool.parameters]. The project's 13 existing [DomainTool] implementations were
 * already built (SP1.6, Issue #551) against a shared `List<DomainToolParameter>` +
 * `Map<String, Any?>` contract; forking each into a bespoke typed argument class would be a much
 * larger, unrelated refactor.
 *
 * @since Issue #566 (SP2b.9 — Goal 10)
 */
class KoogToolAdapter(
	private val domainTool: DomainTool
) : Tool<Map<String, Any?>, String>(
		argsType = typeToken<Map<String, Any?>>(),
		resultType = typeToken<String>(),
		descriptor = domainTool.toKoogDescriptor()
	) {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override suspend fun execute(args: Map<String, Any?>): String {
		logger.debug { "KoogToolAdapter(${domainTool.name}).execute: args=$args" }
		return when (val result = domainTool.execute(args)) {
			is ToolResult.Success -> formatSuccessData(result.data)
			is ToolResult.Error -> throw ToolException.ValidationFailure(result.message)
		}
	}

	// Mirrors what SimpleTool does internally: the LLM must see the raw string, not a
	// JSON-quoted one (we can't extend SimpleTool itself — see class KDoc).
	override fun encodeResultToString(
		result: String,
		serializer: JSONSerializer
	): String = result

	override fun decodeArgs(
		rawArgs: JSONObject,
		serializer: JSONSerializer
	): Map<String, Any?> = rawArgs.entries.mapValues { (_, v) -> v.toKotlinValue() }

	// Symmetric with decodeArgs. Not exercised by the tool-calling loop itself, but cheap
	// insurance against a future tracing/opentelemetry feature that would otherwise hit a
	// KotlinxSerializer failure trying to reflect over a raw Map<String, Any?>.
	override fun encodeArgs(
		args: Map<String, Any?>,
		serializer: JSONSerializer
	): JSONObject = JSONObject(args.mapValues { (_, v) -> v.toJsonElement() })

	/**
	 * Renders a tool's success payload as LLM-legible text. Raw `Any?.toString()` is ambiguous
	 * for two shapes domain tools commonly return: `null` (a single-item lookup with no match)
	 * and an empty list (a perception/sensor query with nothing to report) — both would
	 * otherwise print as the bare token "null" or "[]", indistinguishable from an error. Every
	 * other shape (data-class readings, non-empty lists, plain strings) already has a legible
	 * default toString()/data-class rendering and is passed through unchanged.
	 */
	private fun formatSuccessData(data: Any?): String =
		when {
			data == null -> "(no matching data found)"
			data is List<*> && data.isEmpty() -> "(empty — nothing to report)"
			else -> data.toString()
		}
}

/**
 * Builds a Koog [ToolDescriptor] from [DomainTool.parameters], splitting required/optional by
 * [DomainToolParameter.required] — the mapping promised by [DomainTool.parameters]'s own KDoc.
 */
private fun DomainTool.toKoogDescriptor(): ToolDescriptor {
	val (required, optional) = parameters.partition { it.required }
	return ToolDescriptor(
		name = name,
		description = description,
		requiredParameters = required.map { it.toKoogParameterDescriptor() },
		optionalParameters = optional.map { it.toKoogParameterDescriptor() }
	)
}

private fun DomainToolParameter.toKoogParameterDescriptor(): ToolParameterDescriptor =
	ToolParameterDescriptor(name = name, description = description, type = type.toKoogType())

private fun DomainToolParameterType.toKoogType(): ToolParameterType =
	when (this) {
		DomainToolParameterType.String -> ToolParameterType.String
		DomainToolParameterType.Integer -> ToolParameterType.Integer
		DomainToolParameterType.Float -> ToolParameterType.Float
		DomainToolParameterType.Boolean -> ToolParameterType.Boolean
		is DomainToolParameterType.Enum -> ToolParameterType.Enum(entries.toTypedArray())
		is DomainToolParameterType.List -> ToolParameterType.List(itemsType.toKoogType())
	}

/** Converts one raw [JSONElement] (an LLM tool-call argument) to a plain Kotlin value. */
private fun JSONElement.toKotlinValue(): Any? =
	when (this) {
		is JSONObject -> entries.mapValues { (_, v) -> v.toKotlinValue() }
		is JSONArray -> elements.map { it.toKotlinValue() }
		JSONNull -> null
		is JSONPrimitive ->
			if (isString) {
				content
			} else {
				// Integer-shaped content becomes Int, decimal-shaped becomes Double, matching
				// DomainToolParameterType.Integer/Float's Kotlin-side expectations; falls back to
				// Boolean, then the raw content string, for anything else (e.g. "true").
				content.toIntOrNull() ?: content.toDoubleOrNull() ?: content.toBooleanStrictOrNull() ?: content
			}
	}

/** Inverse of [toKotlinValue], for [KoogToolAdapter.encodeArgs]. */
private fun Any?.toJsonElement(): JSONElement =
	when (this) {
		null -> JSONNull
		is Map<*, *> -> JSONObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
		is List<*> -> JSONArray(map { it.toJsonElement() })
		is Boolean -> JSONPrimitive(this)
		is Int -> JSONPrimitive(this)
		is Long -> JSONPrimitive(this)
		is Double -> JSONPrimitive(this)
		else -> JSONPrimitive(toString())
	}
