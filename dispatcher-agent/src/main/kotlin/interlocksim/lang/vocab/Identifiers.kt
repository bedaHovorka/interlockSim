package interlocksim.lang.vocab

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Typed identifier for a signal or semaphore (návěstidlo) in the operating vocabulary.
 *
 * Value class — serialises as its wrapped string scalar.
 * Prevents accidental interchange with other identifier types.
 *
 * Example: `SignalId("L1")`, `SignalId("S2")`
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@JvmInline
@LLMDescription("Identifier of a signal or semaphore in the operating vocabulary.")
value class SignalId(
	@LLMDescription("Human-readable signal name, for example L1 or S2.")
	val name: String
)

/**
 * Typed identifier for a railway switch or point (výhybka).
 *
 * Value class — serialises as its wrapped string scalar.
 *
 * Example: `SwitchId("V7")`, `SwitchId("V9")`
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@JvmInline
@LLMDescription("Identifier of a railway switch or point (výhybka).")
value class SwitchId(
	@LLMDescription("Human-readable switch name or number, for example V7.")
	val name: String
)

/**
 * Typed identifier for a block section (prostorový oddíl).
 *
 * Only one train may occupy a block section at a time (the fundamental interlocking
 * constraint). Value class — serialises as its wrapped string scalar.
 *
 * Example: `BlockId("U3")`, `BlockId("U4")`
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@JvmInline
@LLMDescription("Identifier of a block section (prostorový oddíl); at most one train may occupy it.")
value class BlockId(
	@LLMDescription("Human-readable block identifier, for example U3 or U4.")
	val name: String
)

/**
 * Typed identifier for a station or line track (kolej).
 *
 * Value class — serialises as its wrapped string scalar.
 *
 * Example: `TrackId("3")`, `TrackId("4a")`
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@JvmInline
@LLMDescription("Identifier of a station track or line section.")
value class TrackId(
	@LLMDescription("Track number or label, for example 3 or 4a.")
	val name: String
)
