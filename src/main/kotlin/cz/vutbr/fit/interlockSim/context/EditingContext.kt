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
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.Point

/**
 * Interface to shared functions of inner data model, which is allowed by editing
 *
 * ## Type Parameter
 *
 * EditingContext specializes Context to use [Cell] as the cell type.
 * While editing operations via [putCell] only accept [NodeCell] and its
 * subtypes (InOut, RailSwitch, RailSemaphore), the grid also contains
 * [cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart] cells that are
 * automatically generated when joining nodes with track blocks.
 *
 * The grid returns [RailwayNetGrid]<[Cell]>, containing both NodeCell subclasses
 * and TrackBlockPart instances.
 *
 * ## Thread Safety
 *
 * **This interface is NOT thread-safe.** See [Context] for detailed thread safety
 * documentation and usage guidelines.
 *
 * All editing operations (putCell, removeCell, moveCell, joinCells, removeLine)
 * must be performed from a single thread.
 *
 * @see Context
 * @see javax.annotation.concurrent.NotThreadSafe
 */
interface EditingContext : Context<Cell> {
	/**
	 * put the cell into context, the cell must be {@link NodeCell}
	 * @param key
	 * @param cell
	 */
	fun putCell(
		key: Point,
		cell: NodeCell
	)

	/**
	 * remove cell from context
	 * @param key
	 */
	fun removeCell(key: Point)

	/**
	 * @param from
	 * @param to
	 */
	fun moveCell(
		from: Point,
		to: Point
	)

	/**
	 * Remove {@link TrackBlock} from context
	 * @param block
	 */
	fun removeLine(block: TrackBlock)

	/**
	 * Current maximal speed, which is setting in elements
	 */
	var currentMaxSpeed: Double

	/**
	 * Current track length, which is setting in elements
	 */
	var currentTrackLength: Double

	/**
	 * Create relation between nodes. In specified places, must be {@link NodeCell}
	 * @param key1 location of first node
	 * @param key2 location of second node
	 * @param trackBlock block between nodes
	 */
	fun joinCells(
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	)

	/**
	 * Name which is setting in elements
	 */
	var currentNameString: String
}
