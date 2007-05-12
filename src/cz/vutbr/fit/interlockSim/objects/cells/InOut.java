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

import java.util.EnumSet;
import java.util.Set;

import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore.Signal;
import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException;

/**
 * predstavuje spojeni s externi zeleznicni siti
 *
 */
public class InOut extends OrientedNodeCell {
	private String name;
	private final RailSemaphore inSemaphore;
	private final RailSemaphore outSemaphore;
	
	/**
	 * @param name
	 * @param orientation
	 * @param spatialType
	 */
	public InOut(String name, Boolean orientation, SpatialType spatialType) {
		super(orientation, spatialType);
		this.name = name;
		this.inSemaphore = new RailSemaphore(!orientation, spatialType); 
		assert inSemaphore.direction() == Segment.anti(direction()); 
		this.outSemaphore = RailSemaphore.getConstantInstance(orientation, spatialType, Signal.FREE);
		setName(name);
	}
	
	public Set<Segment> joins() {
		return EnumSet.of(direction());
	}

	@Override
	public Segment getFollowingSegment(Segment from) {
		if (from == null) return direction();
		assert from == direction();
		return null;
	}
	
	/**
	 * @return semaphore on input
	 */
	public RailSemaphore getInSemaphore() {
		return inSemaphore;
	}
	
	/**
	 * @return name of place
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return semaphore on output
	 */
	public RailSemaphore getOutSemaphore() {
		return outSemaphore;
	}
		
	private RailSemaphore getSemaphoreFor(Segment from, Segment to) {
		if (from == null && to == direction()) return inSemaphore;
		else if (to == null && from == direction()) return outSemaphore;
		return null;
	}
	
	private RailSemaphore getSemaphoreForWithExeption(Segment from, Segment to) throws PathSeparatorChangeException {
		final RailSemaphore sem = getSemaphoreFor(from, to);
		if (sem == null) throw new PathSeparatorChangeException(this);
		return sem;
	}
	
	public void setUpPath(Segment from, Segment to, double allowedSpeed) throws PathSeparatorChangeException {
		final RailSemaphore sem = getSemaphoreForWithExeption(from, to);
		sem.setSignal(Signal.forSpeed(allowedSpeed));
	}
	
	public void cancelPathSetup(Segment from, Segment to) throws PathSeparatorChangeException {
		final RailSemaphore sem = getSemaphoreForWithExeption(from, to);
		sem.setSignal(Signal.STOP);
	}
	
	public double allowedSpeed() {
		return PathElement.ABSOLUTE_MAX_SPEED;
	}
}
