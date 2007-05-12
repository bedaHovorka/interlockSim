/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths;

import java.util.Deque;

import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.tracks.Track;

/**
 * Represents aPath
 * is a sequence of {@link PathElement} - {@link PathSeparator} and {@link Track}
 *
 */
public interface Path extends Deque<PathElement>, Track {
	/**
	 * @return last element must be semaphore
	 */
	public RailSemaphore getLastPathSemaphore();
	
	/**
	 * @return minimal value of {@link Track#maxSpeed(PathSeparator)} in sequence
	 */
	public double maxSpeed(PathSeparator sep);
	
	/**
	 * @return copy of this aPath in reverse
	 */
	public Path reversePath();
	
	public PathSeparator getFirst();
	
	public OrientedPathSeparator getLast();
	
	/**
	 * Same as list equals, because {@link Deque#equals(Object)} should test identity
	 * @param path 
	 * @return if path have same elements in same order
	 */
	public boolean equalsWithElements(Path path);
//	 z konvence pro deque neprekryvam equals a hashcode, cesta je "mutable" a to by delalo problemy v kolekcich
}
