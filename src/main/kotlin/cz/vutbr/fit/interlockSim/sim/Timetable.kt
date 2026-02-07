/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut

/**
 * Timetable of Train
 *
 */
class Timetable(
	private var `in`: DynamicInOut,
	private var out: DynamicInOut,
	private val inTime: Time,
	private val outTime: Time,
	private var length: Double
) {
	// private TreeMap<Time, Station> stations; nekdy...

	/**
	 * @return start point of train
	 */
	fun getIn(): DynamicInOut = `in`

	/**
	 * @return end point of train
	 */
	fun getOut(): DynamicInOut = out

	// 	/**
	// 	 * @return all station in net EXTENSION
	// 	 */
	// 	public TreeMap<Time, Station> getStations() {
	// 		return stations;
	// 	}

	/**
	 * @return time of departure from In
	 */
	fun getInTime(): Time = inTime

	/**
	 * @return time of arrival to Out
	 */
	fun getOutTime(): Time = outTime

	/**
	 * @return get length of train
	 */
	fun getLength(): Double {
		// EXTENSION with parameter time
		return length
	}

	/**
	 * Reverse the direction of travel by swapping In and Out points.
	 * This simulates the train engineer moving to the opposite end of the train.
	 *
	 * GitHub #62: Bidirectional train operation support
	 */
	fun reverseDirection() {
		val temp = `in`
		`in` = out
		out = temp
	}
}
