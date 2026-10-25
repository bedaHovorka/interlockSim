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

import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Whether a train stands on [block]: it has an occupant or is already booked as OCCUPIED. The one
 * "occupied" notion of the route release ([RouteReleaser]'s kept blocks and approach locking) and of
 * the kernel reservation expiry ([PathReservationRegistry.expiredHoldings], Issue #1174), so the two
 * can never disagree about which train is on the track.
 */
internal fun isTrainOnBlock(block: DynamicTrackBlock): Boolean =
	block.occupant != null || block.getState() == TrackFacility.State.OCCUPIED
