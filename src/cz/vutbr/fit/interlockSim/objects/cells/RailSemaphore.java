/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells;

import java.util.Set;

import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException;

/**
 * "Navestidlo"
 */
public class RailSemaphore extends OrientedNodeCell {	
	/**
	 * Represents ligths (command) on semaphore
	 */
	public enum Signal {
		/**
		 * red signal, stop
		 */
		STOP(0),
		/**
		 * allow speed max 30 km/h
		 */
		S30(30),
		/**
		 * allow speed max 40 km/h
		 */
		S40(40),
		/**
		 * allow speed max 60 km/h
		 */
		S60(60),
		/**
		 * allow speed max 80 km/h
		 */
		S80(80),
		/**
		 * allow speed max 100 km/h
		 */
		S100(100),
		/**
		 * allow speed maximal in track section
		 */
		FREE(),
		
//		MOVE(20), //? posun
//		CALL(20) //? privolavacka
		;
		private final double allowedSpeed;
		private static final Signal[] values = values();
		
		/**
		 * in km/h !!!!
		 * @param speed
		 */
		Signal(int speed) {
			this.allowedSpeed = speed  / 3.6;
		}
		
		Signal() {
			this.allowedSpeed = PathElement.ABSOLUTE_MAX_SPEED;
		}
		
		/**
		 * @return if the signal allow next move
		 */
		public boolean isAllowing() {
			return allowedSpeed > 0;
		}
		
		/**
		 * @return speed in in m/s !!!
		 */
		public double allowedSpeed() {
			return allowedSpeed;
		}
		
		/**
		 * @param speed
		 * @return signal with allowedSpeed less then speed
		 */
		public static Signal forSpeed(double speed) {
			assert speed >= S30.allowedSpeed() || speed == 0;
			for (Signal s : values) {
				if (s.allowedSpeed() > speed) return s.ordinal()==0 ? STOP : values[s.ordinal()-1];
			}
			return FREE;
		}
	}

	private static final class ConstantSemaphore extends RailSemaphore {
		ConstantSemaphore(boolean orientation, SpatialType spatialType, Signal signal) {
			super(orientation, spatialType);
			super.setSignal(signal);
		}

		@Override
		public void setSignal(Signal signal) {
			//EMPTY
		}
	}
	
	private Signal signal = Signal.STOP;
	
	/**
	 * @param orientation
	 * @param spatialType
	 */
	public RailSemaphore(Boolean orientation, SpatialType spatialType) {
		super(orientation, spatialType);
	}
	
	/**
	 * create semaphore, which don't change signal - like: "predzvest", impasse end "naraznik", "rychlostnik"
	 * @param orientation
	 * @param spatialType
	 * @param signal
	 * @return constant orientented aPath separator
	 */
	public static RailSemaphore getConstantInstance(boolean orientation, SpatialType spatialType, Signal signal) {
		return new ConstantSemaphore(orientation, spatialType, signal);
	}

	@Override
	public RailSemaphore clone() throws CloneNotSupportedException {
	    return (RailSemaphore) super.clone();//CAUTION
	}
	
	public Set<Segment> joins() {
		return joinsOnLine();
	}
	
	@Override
	public Segment getFollowingSegment(Segment from) {
		return secondOnLine(from);
	}
	
	/**
	 * @return atribute getter {@link Signal}
	 */
	public Signal getSignal() {
		return signal;
	}

	/**
	 * atribute setter {@link Signal}
	 * @param signal
	 */
	public void setSignal(Signal signal) {
		this.signal = signal;
	}

	public void cancelPathSetup(Segment from, Segment to) throws PathSeparatorChangeException {
		checkPathSegments(from, to);
		setSignal(Signal.STOP);
	}

	public void setUpPath(Segment from, Segment to, double allowedSpeed) throws PathSeparatorChangeException {
		if (checkPathSegments(from, to)) {
			setSignal(Signal.forSpeed(allowedSpeed));
		}
	}

	public double allowedSpeed() {
		return getSignal().allowedSpeed();
	}
	
	private boolean checkPathSegments(Segment from, Segment to) throws PathSeparatorChangeException {
		final Segment d = direction();
		if (to == d && from == Segment.anti(d)) return true; 
		else if (from == d && to == Segment.anti(d)) return false;
		throw new PathSeparatorChangeException("wrong aPath segments", this);
	}
}
