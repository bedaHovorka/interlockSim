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

// Argument-extraction helpers for DomainTool implementations (SP1.6, Issue #551).
//
// Each tool extracts its string/enum arguments from the `Map<String, Any?>` passed to
// DomainTool.execute with the same shape: null out on missing / wrong-type / blank input so
// the tool can return a ToolResult.Error with a clear, parameter-specific message rather than
// forwarding a raw port exception. Centralising the pattern here keeps the 13 tools consistent
// and guarantees blank-string rejection (#551 review: the per-tool `as? String` checks
// previously let `""` through to the port).

/**
 * Returns the string value of [name] in this args map, or `null` if the entry is absent,
 * not a [String], or blank. Returning `null` for blank unifies the "missing" and "empty"
 * cases so a single `?: return ToolResult.Error(...)` handles both.
 */
internal fun Map<String, Any?>.stringParam(name: String): String? = (this[name] as? String)?.takeIf { it.isNotBlank() }

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
