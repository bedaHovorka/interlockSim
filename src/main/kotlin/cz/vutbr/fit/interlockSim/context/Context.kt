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

import cz.vutbr.fit.interlockSim.objects.cells.Cell
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
 *
 * ## Type Parameter
 *
 * @param C The type of cells stored in the railway network grid. Must extend [Cell].
 *          - Both [EditingContext] and [SimulationContext] use [Cell] as the grid type
 *          - The grid stores mixed cell types: [cz.vutbr.fit.interlockSim.objects.cells.NodeCell]
 *            subclasses (RailSwitch, RailSemaphore, InOut) and
 *            [cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart] (intermediate track segments)
 *          - During simulation, [cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator]
 *            wrappers are maintained separately via [SimulationContext.toDynamic] methods,
 *            not stored in the grid
 *
 * The type parameter provides compile-time type safety for grid access operations.
 *
 * ## Thread Safety
 *
 * **This interface and its implementations are NOT thread-safe.**
 *
 * Context instances must not be accessed from multiple threads concurrently.
 * All operations on the railway network grid, graph structure, and property
 * change notifications assume single-threaded access.
 *
 * If concurrent access is required, external synchronization must be used to
 * ensure thread safety.
 *
 * ### Rationale
 *
 * Railway interlocking simulations are inherently sequential by design:
 * - The jDisco discrete event simulation framework operates in a single thread
 * - Physical railway operations follow sequential causality (trains cannot
 *   simultaneously occupy the same track)
 * - The simulation model enforces discrete event ordering
 *
 * Thread-safety mechanisms would introduce unnecessary complexity and performance
 * overhead without providing practical benefits for the intended use cases.
 *
 * ### Usage Guidelines
 *
 * - **DO NOT** access Context from multiple threads
 * - **DO NOT** share Context instances across thread boundaries
 * - **DO** use external synchronization if multi-threaded access is unavoidable
 * - **DO** keep all Context operations within the simulation thread
 *
 * @see javax.annotation.concurrent.NotThreadSafe
 */
interface Context<out C : Cell> {
	/**
	 * get grid, which is graphic representation of model
	 * @return grid with cells of type C
	 */
	fun getRailWayNetGrid(): RailwayNetGrid<C>

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
