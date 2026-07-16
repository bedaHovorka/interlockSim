package cz.vutbr.fit.interlockSim.lang.vocab

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
 * mandatory route conditions (volnost, poloha výhybek, závěr, vyloučení konfliktů)
 * independently when a [RouteRequest][cz.vutbr.fit.interlockSim.lang.proto.Message.RouteRequest]
 * is processed.
 *
 * @property from Signal where the route begins (departure end).
 * @property to   Signal where the route ends (arrival end).
 * @property running Switch settings that form the running path (pojížděné výhybky). May be empty
 *                   for a straight route with no turnouts.
 * @property flank  Flank-protection switch settings (odvratné výhybky). May be empty.
 * @property track  Station track this route uses, if known.
 * @property blocks Ordered block sections covered by the route.
 *
 * @since Issue #570 (SP3.2 — Goal 10)
 */
@Serializable
data class TrainRoute(
	val from: SignalId,
	val to: SignalId,
	val running: List<SwitchSetting>,
	val flank: List<SwitchSetting> = emptyList(),
	val track: TrackId? = null,
	val blocks: List<BlockId>
)
