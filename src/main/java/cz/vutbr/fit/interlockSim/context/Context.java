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
import java.beans.PropertyChangeListener;

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
	 * Add a listener for context changes.
	 * Replaces deprecated addObserver(Observer) method.
	 *
	 * @param listener the listener to add
	 */
	public void addPropertyChangeListener(PropertyChangeListener listener);

	/**
	 * Remove a listener for context changes.
	 * Replaces deprecated deleteObserver(Observer) method.
	 *
	 * @param listener the listener to remove
	 */
	public void removePropertyChangeListener(PropertyChangeListener listener);
}
