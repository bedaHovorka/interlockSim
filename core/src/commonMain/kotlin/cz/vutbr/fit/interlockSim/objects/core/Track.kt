/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

/**
 * "jakykoliv (z ruznych pohledu) usek koleji mezi pathseparatory (cast cesty)"
 * "muze se tvorit rekurzivne"
 *
 * Combines both static (immutable) and dynamic (mutable) track aspects.
 * For interface segregation, see StaticTrack and DynamicTrackBehavior.
 *
 * Note: Track length constants live in cz.vutbr.fit.interlockSim.domain.PhysicsConstants.
 */
interface Track :
	StaticTrack,
	DynamicTrackBehavior
