package interlocksim.lang.vocab

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * A train route (vlaková cesta) from one signal to another through a station throat.
 *
 * Describes the operating intent: signal–signal bounds, the ordered set of switch
 * settings that form the running path, flank-protection settings, the target track,
 * and the ordered block sections the route traverses.
 *
 * This type carries **operating intent only** — it does not claim that the interlocking
 * has accepted, locked, or cleared the route.  The interlocking performs the four
 * mandatory route conditions (volnost, poloha výhybek, závěr, vyloučení confliktů)
 * independently when a [RouteRequest][interlocksim.lang.proto.Message.RouteRequest]
 * is processed.
 *
 * @property from Signal where the route begins (departure end).
 * @property to   Signal where the route ends (arrival end).
 * @property running Switch settings that form the running path (pojížděné výhybky).
 * @property flank  Flank-protection switch settings (odvratné výhybky). May be empty.
 * @property track  Station track this route uses, if known.
 * @property blocks Ordered block sections covered by the route.
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
@LLMDescription("A train route (vlaková cesta) from one signal to another through a station throat.")
data class TrainRoute(
	@LLMDescription("Signal where the route begins (from signal, departure end).")
	val from: SignalId,
	@LLMDescription("Signal where the route ends (to signal, arrival end).")
	val to: SignalId,
	@LLMDescription("Switch settings that form the running path (pojížděné výhybky).")
	val running: List<SwitchSetting>,
	@LLMDescription("Flank-protection switch settings required by the route (odvratné výhybky). May be empty.")
	val flank: List<SwitchSetting> = emptyList(),
	@LLMDescription("Station track or line section selected by this route, if known.")
	val track: TrackId? = null,
	@LLMDescription("Ordered block sections (prostorové oddíly) covered by the route.")
	val blocks: List<BlockId>
)
