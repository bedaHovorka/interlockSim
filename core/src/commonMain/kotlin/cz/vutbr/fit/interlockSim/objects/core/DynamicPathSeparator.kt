package cz.vutbr.fit.interlockSim.objects.core

import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException

/**
 * Path separator in simulation, which can change its configuration during simulation
 */
interface DynamicPathSeparator : PathSeparator {
	/**
	 * @param from
	 * @param to
	 * @throws PathSeparatorChangeException
	 */
	fun cancelPathSetup(
		from: Cell.Segment?,
		to: Cell.Segment?
	)

	/**
	 * @param from
	 * @param to
	 * @param allowedSpeed
	 * @throws PathSeparatorChangeException
	 */
	fun setUpPath(
		from: Cell.Segment?,
		to: Cell.Segment?,
		allowedSpeed: Double,
		trackOccupant: TrackOccupant
	)

	/**
	 * @return allowed speed through separator in m/s
	 */
	fun allowedSpeed(): Double

	/**
	 * @param from
	 * @return segment reprezents element configuration (dynamic)
	 */
	fun getFollowingSegment(from: Cell.Segment?): Cell.Segment?
}
