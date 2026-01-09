/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
import java.beans.PropertyChangeListener

/**
 * Represents the program Context - editing or simulation ...
 * Interface to shared functions of inner data model, which is allowed allways
 *
 * Point is used as Pair of integers
 */
interface Context {
	/**
	 * get grid, which is graphic representation of model
	 * @return grid
	 */
	fun getRailWayNetGrid(): RailwayNetGrid

	/**
	 * ...
	 * @return graph
	 */
	fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment>

	/**
	 * Add a listener for context changes.
	 * Replaces deprecated addObserver(Observer) method.
	 *
	 * @param listener the listener to add
	 */
	fun addPropertyChangeListener(listener: PropertyChangeListener)

	/**
	 * Remove a listener for context changes.
	 * Replaces deprecated deleteObserver(Observer) method.
	 *
	 * @param listener the listener to remove
	 */
	fun removePropertyChangeListener(listener: PropertyChangeListener)
}
