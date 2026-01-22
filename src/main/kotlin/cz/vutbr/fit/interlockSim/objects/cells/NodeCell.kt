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
	private val spatialType: Cell.SpatialType
) : AbstractCell(),
	PathSeparator {
	private var toStringValue: String = ""
	private var name: String = ""

	override fun getSpatialType(): Cell.SpatialType = spatialType

	/**
	 * setter
	 * @param name
	 */
	open fun setName(name: String) {
		this.name = name
		toStringValue = this::class.java.simpleName + ':' + name
	}

	/**
	 *
	 * @return
	 */
	open fun getName(): String = name

	override fun toString(): String = toStringValue
}
