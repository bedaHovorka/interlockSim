package interlocksim.lang.vocab

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Switch (výhybka) position used in route descriptions.
 *
 * Maps to the two stable positions of a railway point/switch. In Czech railway
 * terminology: "přímý směr" (straight/normal) and "odbočný směr" (diverging).
 *
 * Aligned with the SP3 operating vocabulary (Issue #570, §4.1).
 *
 * @see SwitchSetting
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@LLMDescription("Switch (výhybka) position: PLUS for normal/straight route, MINUS for diverging route.")
enum class SwitchPosition {
	@SerialName("plus")
	@LLMDescription("Normal or direct switch position (přímý směr).")
	PLUS,

	@SerialName("minus")
	@LLMDescription("Diverging switch position (odbočný směr).")
	MINUS
}
