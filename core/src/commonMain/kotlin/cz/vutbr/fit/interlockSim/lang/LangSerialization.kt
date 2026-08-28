package cz.vutbr.fit.interlockSim.lang

import kotlinx.serialization.json.Json

/**
 * Shared `Json` configuration for the SP3 operating language (vocab + proto).
 *
 * All production (de)serialization of [cz.vutbr.fit.interlockSim.lang.vocab] and
 * [cz.vutbr.fit.interlockSim.lang.proto] types MUST go through this instance, so the wire
 * contract is stable and evolves forward-compatibly:
 *
 * - [classDiscriminator] = `"type"` matches the stable `@SerialName` discriminators on every
 *   sealed subtype (`Aspect`, `Message`, `AgentRole`, …).
 * - [ignoreUnknownKeys] lets an older consumer decode a message produced by a newer producer
 *   that has added a field — forward compatibility across protocol versions.
 * - [encodeDefaults] ensures fields with default values are written explicitly, so the wire
 *   form is stable regardless of later default changes.
 *
 * Tests in `:core` (jvmTest) and `:dispatcher-agent` reuse this instance instead of each
 * inventing their own `Json {}`, which previously caused inconsistent decode behaviour.
 *
 * @since Issue #571 (SP3.3 — Goal 10)
 */
object LangSerialization {
	val json: Json =
		Json {
			classDiscriminator = "type"
			ignoreUnknownKeys = true
			encodeDefaults = true
		}
}
