/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * Node managed by dispatcher (if is in main graph)
 *
 */
abstract class NodeCell(
	private val spatialType: Cell.SpatialType,
	name: String = ""
) : AbstractCell(),
	PathSeparator {
	/** Immutable name of this node. */
	private val nodeName: String = name

	override fun getSpatialType(): Cell.SpatialType = spatialType

	/**
	 * @return name of this node
	 */
	open fun getName(): String = nodeName

	/**
	 * Create a copy of this node cell with the given name.
	 * Implements the wither pattern for immutable name updates.
	 *
	 * @param newName The new name for the copy
	 * @return A new instance with the updated name
	 */
	abstract fun withName(newName: String): NodeCell

	override fun toString(): String = this::class.simpleName + ':' + nodeName
}
