/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

/**
 * "jakykoliv (z ruznych pohledu) usek koleji mezi pathseparatory (cast cesty)"
 * "muze se tvorit rekurzivne"
 *
 * Combines both static (immutable) and dynamic (mutable) track aspects.
 * For interface segregation, see StaticTrack and DynamicTrackBehavior.
 *
 * Note: Track length constants are defined in [StaticTrack.Companion].
 */
interface Track : StaticTrack, DynamicTrackBehavior
