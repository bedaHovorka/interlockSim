/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.awt.Point;
import java.util.Observable;
import java.util.Observer;

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph;

/**
 * Represents the program Context - editing or simulation ...
 * Interface to shared functions of inner data model, which is allowed allways
 *
 * Point is used as Pair of integers
 */
public interface Context {
		/**
		 * get grid, which is graphic representation of model
		 * @return grid
		 */
	public RailwayNetGrid getRailWayNetGrid();

	/**
	 * ...
	 * @return graph
	 */
	public ExtendedUnorientedGraph<Point, TrackBlock, Segment> getGraph();

	/**
	 * @see Observable#addObserver(Observer)
	 * @param observer
	 */
	public void addObserver(Observer observer);

	/**
	 * @see Observable#deleteObserver(Observer)
	 * @param observer
	 */
	public void deleteObserver(Observer observer);
}
