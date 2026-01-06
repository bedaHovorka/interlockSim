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

import cz.vutbr.fit.interlockSim.objects.cells.InOut

/**
 * Timetable of Train
 *
 */
class Timetable(
	private val `in`: InOut,
	private val out: InOut,
	private val inTime: Time,
	private val outTime: Time,
	private var length: Double
) {
	// private TreeMap<Time, Station> stations; nekdy...

	/**
	 * @return start point of train
	 */
	fun getIn(): InOut = `in`

	/**
	 * @return end point of train
	 */
	fun getOut(): InOut = out

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
}
