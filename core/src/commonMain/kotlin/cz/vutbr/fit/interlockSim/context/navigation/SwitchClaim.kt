/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

/**
 * One train's claim on a switch in [PathReservationRegistry]: who owns it and why (Issue #1103).
 *
 * The owner and the purpose are kept in one record, so removing the claim removes the purpose with
 * it; a separate purpose set could outlive the ownership it described.
 *
 * @property trainId the owning train
 * @property isFlank `true` when the switch is held as FLANK protection (a facade `route.flank` lock,
 *   Issue #1076). A flank switch protects a route it is not adjacent to, so the "owner holds no block
 *   bounded by it" staleness rule of [PathReservationRegistry.isStaleSwitchOwnership] does not apply.
 */
internal data class SwitchClaim(
	val trainId: String,
	val isFlank: Boolean
)
