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

/**
 * "Navestidlo"
 */
open class RailSemaphore : OrientedNodeCell {
	/**
	 * Primary constructor accepting orientation and spatialType (for backward compatibility).
	 */
	constructor(
		orientation: Boolean,
		spatialType: Cell.SpatialType
	) : super(orientation, spatialType)

	/**
	 * Constructor accepting optional name, orientation, and spatialType.
	 * Used by XML parser when name attribute is present.
	 *
	 * Example XML:
	 * ```xml
	 * <RailSemaphore X="16" Y="8" SpatialType="HORIZONTAL"
	 *                 orientation="true" name="signal_north_entry"/>
	 * ```
	 *
	 * @param name Optional name for the semaphore (alphanumeric, -, _, max 50 chars).
	 *             Null or empty = auto-generated name.
	 * @param orientation Semaphore orientation (true/false)
	 * @param spatialType Spatial type (HORIZONTAL/VERTICAL)
	 * @since 2026-01 (Issue #296 Phase 4, Issue #306)
	 */
	constructor(
		name: String?,
		orientation: Boolean,
		spatialType: Cell.SpatialType
	) : super(orientation, spatialType, name ?: "")

	override fun joins(): Set<Cell.Segment> = joinsOnLine()

	override fun getFollowingSegment(from: Cell.Segment?): Cell.Segment? = secondOnLine(from)

	/**
	 * Create a copy of this semaphore with the given name.
	 *
	 * @param newName The new name for the copy
	 * @return A new RailSemaphore with the updated name
	 */
	override fun withName(newName: String): RailSemaphore = RailSemaphore(newName, getOrientation(), getSpatialType())

	/**
	 * Implementation of asRailSemaphore() for RailSemaphore.
	 * Returns self since this is already a RailSemaphore.
	 */
	override fun asRailSemaphore(): RailSemaphore = this
}
