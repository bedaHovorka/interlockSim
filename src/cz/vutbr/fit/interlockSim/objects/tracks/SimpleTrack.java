/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks;

import java.util.IdentityHashMap;

import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator;
import cz.vutbr.fit.interlockSim.sim.TrackOperationException;

/**
 * Base implementation of {@link Track} and {@link TrackSection} and {@link TrackFacility}
 *
 * Represents common track unit
 */
public abstract class SimpleTrack extends AbstractTrack implements TrackSection, TrackFacility {
	private final IdentityHashMap<PathSeparator, Double> speeds = new IdentityHashMap<PathSeparator, Double>();
	private final double length;
	private final PathSeparator[] ends;
	private TrackOccupant in;
	private PathSeparator from;
	private State state = State.FREE;

	/**
	 * @param end1
	 * @param end2
	 * @param length
	 * @param maxSpeed1 speed from end1
	 * @param maxSpeed2 speed from end2
	 */
	public SimpleTrack(PathSeparator end1, PathSeparator end2, double length, double maxSpeed1, double maxSpeed2) {
		super();
		if (length < MIN_LENGTH || maxSpeed1 < PathElement.MINIMAL_MAX_SPEED || maxSpeed2 < PathElement.MINIMAL_MAX_SPEED)
			throw new IllegalArgumentException("length or maxspeed is very small");
		this.length = length;
		ends = new PathSeparator[]{end1, end2};
		speeds.put(end2, maxSpeed2);
		speeds.put(end1, maxSpeed1);
	}

	public void enter(TrackOccupant occupant) {
		assert in == null; //tak to zavani srazkou (posuny neimplementovany)
		assertGoodStateChange(State.RESERVED, State.OCCUPIED);
		in = occupant;
		from = null;
	}

	public void leave(TrackOccupant occupant) {
		assert in == occupant;
		assertGoodStateChange(State.OCCUPIED, State.FREE);
		in = null;
	}

	public boolean isFreeFrom(PathSeparator seg) {
		return state == State.FREE;
	}

	public void setUpPath(PathSeparator sep) throws TrackOperationException {
		exeptionStateChange(State.FREE, State.RESERVED);
		from = sep;
	}

	public boolean isSetUpPath(PathSeparator sep) {
		assert sep != null;
		if (state == State.RESERVED) {
			return sep == from;
		}
		assert from == null;
		return false;
	}

	public void cancelPathSetup(PathSeparator sep) throws TrackOperationException {
		exeptionStateChange(State.RESERVED, State.FREE);
		if (sep != from) throw new TrackOperationException("wrong end on cancel", this);
		from = null;
	}

	private boolean stateChange(State from, State to) {
		final boolean ok = state == from;
		if (ok) state = to;
		return ok;
	}

	private void exeptionStateChange(State from, State to) throws TrackOperationException {
		if (!stateChange(from, to))
			throw new TrackOperationException(errorStateMessage(from), this);
	}

	private String errorStateMessage(State from) {
		return "Wrong state: " + state + " , expected : " + from;
	}

	private void assertGoodStateChange(State from, State to) {
		//mozna nekdy jina vyjimka...
		final boolean stateChange = stateChange(from, to);
		assert stateChange : errorStateMessage(from);
	}

	@Override
	public double length() {
		return length;
	}

	public State getState() {
		return state;
	}

	public PathSeparator[] ends() {
		return ends;
	}

	public TrackOccupant getTrackOccupant() {
		return in;
	}

	public double maxSpeed(PathSeparator from) {
		assert isEnd(from);
		return speeds.get(from);
	}
}
