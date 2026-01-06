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

/**
 * Time for TimeTable
 */
class Time(
	val value: Double
) : Comparable<Time> {
	override fun equals(other: Any?): Boolean {
		if (other == null) return false
		if (this === other) return true
		if (other !is Time) return false
		return this.value == other.value
	}

	override fun hashCode(): Int {
		val l = value.toBits()
		return (l xor (l ushr 32)).toInt()
	}

	override fun compareTo(other: Time): Int = value.compareTo(other.value)
}
