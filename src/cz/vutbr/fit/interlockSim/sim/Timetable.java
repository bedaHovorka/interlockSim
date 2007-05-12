/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim;

import cz.vutbr.fit.interlockSim.objects.cells.InOut;

/**
 * Timetable of Train
 *
 */
public class Timetable {
	private final InOut in;
	private final InOut out;
	private final Time inTime;
	private final Time outTime;
	
	//private TreeMap<Time, Station> stations; nekdy...
	private double length;
	
	/**
	 * Create time table for train
	 * @param in
	 * @param out
	 * @param inTime 
	 * @param outTime 
	 * @param length 
	 */
	public Timetable(final InOut in, final InOut out, final Time inTime, final Time outTime, double length) {
		super();
		this.in = in;
		this.out = out;
		this.inTime = inTime;
		this.outTime = outTime;
		this.length = length;
	}
	
	/**
	 * @return start point of train
	 */
	public InOut getIn() {
		return in;
	}

	/**
	 * @return end point of train
	 */
	public InOut getOut() {
		return out;
	}

//	/**
//	 * @return all station in net EXTENSION
//	 */
//	public TreeMap<Time, Station> getStations() {
//		return stations;
//	}

	/**
	 * @return time of departure from In 
	 */
	public Time getInTime() {
		return inTime;
	}

	/**
	 * @return time of arrival to Out
	 */
	public Time getOutTime() {
		return outTime;
	}

	/** 
	 * @return get length of train
	 */
	public double getLength() {
		// EXTENSION with parameter time
		return length ;
	}
}
