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

import cz.vutbr.fit.interlockSim.domain.MINIMAL_MAX_SPEED
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.StaticTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.IdentityHashMap

/**
 * Base implementation of {@link StaticTrack} - immutable track configuration
 *
 * Represents common track unit with ONLY static (editing-time) properties:
 * - Physical geometry (length, endpoints)
 * - Speed limits (different for each direction)
 * - Topology connections
 *
 * **DOES NOT** include dynamic state (occupancy, reservation, state transitions).
 * For dynamic behavior during simulation, see {@link DynamicTrack}.
 *
 * Part of Phase 1: Track Wrapper Infrastructure (bedaHovorka/interlockSim#100.2)
 *
 * @see DynamicTrack for mutable simulation-time wrapper
 * @see StaticTrack for the interface this class implements
 */
abstract class SimpleTrack(
	end1: PathSeparator,
	end2: PathSeparator,
	private val length: Double,
	maxSpeed1: Double,
	maxSpeed2: Double
) : AbstractTrack(),
	StaticTrack {
	companion object {
		private val logger = KotlinLogging.logger {}

		// Track minimum length constant
		private const val MIN_LENGTH = StaticTrack.MIN_LENGTH
	}

	private val speeds: IdentityHashMap<PathSeparator, Double> = IdentityHashMap()
	private val ends: Array<PathSeparator>

	init {
		if (length < MIN_LENGTH || maxSpeed1 < MINIMAL_MAX_SPEED || maxSpeed2 < MINIMAL_MAX_SPEED) {
			throw IllegalArgumentException("length or maxspeed is very small")
		}
		ends = arrayOf(end1, end2)
		speeds[end2] = maxSpeed2
		speeds[end1] = maxSpeed1
	}

	override fun length(): Double = length

	override fun ends(): Array<PathSeparator> = ends

	override fun maxSpeed(from: PathSeparator?): Double {
		requireSimulation(isEnd(from!!)) { "Path separator must be an end of this track" }
		// Extract static separator for map lookup (speeds map uses static keys)
		val staticFrom =
			if (from is cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator) {
				cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
					.assertNodeCell(from)
			} else {
				from
			}
		return speeds[staticFrom]!!
	}
}
