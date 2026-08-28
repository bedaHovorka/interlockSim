package cz.vutbr.fit.interlockSim.lang.vocab

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
enum class SwitchPosition {
	@SerialName("plus")
	PLUS,

	@SerialName("minus")
	MINUS
}
