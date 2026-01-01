/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim;
/**
 *
 * Time for TimeTable
 */
public class Time implements Comparable<Time> {
	/**
	 * time value
	 */
	public final double value;

	/**
	 *
	 * @param value
	 */
	public Time(final double value) {
		super();
		this.value = value;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) return false;
		if (this == obj) return true;
		if (!(obj instanceof Time)) return false;
		return equals((Time)obj);
	}

	/**
	 * @param obj
	 * @return {@link Object#equals(Object)}
	 */
	public boolean equals(Time obj) {
		if (obj == null) return false;
		return this.value == obj.value;
	}

	@Override
	public int hashCode() {
		long l = Double.doubleToLongBits(value);
		return (int) (l ^ (l >>> 32));
	}

	public int compareTo(Time o) {
		return Double.compare(this.value, o.value);
	}
}
