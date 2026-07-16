package cz.vutbr.fit.interlockSim.lang.vocab

import kotlinx.serialization.Serializable

/**
 * Required position of one switch within a train or shunting route.
 *
 * A route consists of a set of running switch settings and, optionally, flank-protection
 * switch settings.  This type carries the operating intent only — it does not imply that
 * the interlocking has accepted or locked the route.
 *
 * @property switch The switch to be set.
 * @property position The required position of that switch.
 *
 * @see TrainRoute
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
data class SwitchSetting(
	val switch: SwitchId,
	val position: SwitchPosition
)
