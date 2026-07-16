package cz.vutbr.fit.interlockSim.lang.vocab

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

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
value class SignalId(
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
value class SwitchId(
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
value class BlockId(
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
value class TrackId(
	val name: String
)
