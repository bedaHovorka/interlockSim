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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import java.util.IdentityHashMap

/**
 * Utility for transforming static railway network grids to dynamic grids.
 *
 * Transforms `RailwayNetGrid<NodeCell>` to `RailwayNetGrid<DynamicPathSeparator>`
 * by wrapping static cells in dynamic wrappers while preserving object identity.
 *
 * Part of Phase 5: Grid Transformation (bedaHovorka/interlockSim#131.5)
 *
 * ## Transformation Algorithm
 *
 * 1. Create new grid with same dimensions as source grid
 * 2. Iterate through all cells in source grid
 * 3. Wrap NodeCell instances in corresponding Dynamic* wrappers
 * 4. Skip TrackBlockPart cells (not path separators)
 * 5. Maintain bidirectional mapping for reverse lookup
 * 6. Preserve identity via `staticRef` property
 *
 * ## Usage Example
 *
 * ```kotlin
 * val staticGrid: RailwayNetGrid<Cell> = context.getRailWayNetGrid()
 * val result = GridTransformer.transformGrid(staticGrid)
 * val dynamicGrid = result.dynamicGrid
 * val mapping = result.staticToDynamicMap
 * ```
 *
 * @see DynamicPathSeparator
 * @see RailwayNetGrid
 */
object GridTransformer {
	/**
	 * Result of grid transformation containing the new dynamic grid and mapping.
	 *
	 * @property dynamicGrid The new grid containing dynamic wrappers (stored as Cell type)
	 * @property staticToDynamicMap Bidirectional mapping for identity preservation
	 */
	data class TransformationResult(
		val dynamicGrid: DefaultRailWayNetGrid,
		val staticToDynamicMap: Map<NodeCell, DynamicPathSeparator>
	)

	/**
	 * Transform a static grid to a dynamic grid.
	 *
	 * Creates a new grid instance with same dimensions, replacing static NodeCell
	 * instances with Dynamic* wrappers. TrackBlockPart cells are skipped as they
	 * are not path separators.
	 *
	 * @param staticGrid The source grid containing static cells
	 * @return TransformationResult containing the new dynamic grid and mapping
	 * @throws IllegalStateException if unknown cell type is encountered
	 */
	fun transformGrid(staticGrid: RailwayNetGrid<Cell>): TransformationResult {
		val cols = staticGrid.getCols()
		val rows = staticGrid.getRows()
		
		// Create new grid for dynamic cells
		val dynamicGrid = DefaultRailWayNetGrid(cols, rows)
		
		// Mapping for identity preservation and reverse lookup
		val staticToDynamicMap = IdentityHashMap<NodeCell, DynamicPathSeparator>()
		
		// Iterate through all cells in source grid
		for ((point, cell) in staticGrid) {
			// Skip cells that are not NodeCells (e.g., TrackBlockPart)
			if (cell !is NodeCell) {
				continue
			}
			
			// Create dynamic wrapper based on cell type
			val dynamicCell = when (cell) {
				is RailSwitch -> {
					DynamicRailSwitch(cell)
				}
				is RailSemaphore -> {
					createDynamicInstance(cell)
				}
				is InOut -> {
					createDynamic(cell)
				}
				else -> {
					error("Unknown NodeCell type: ${cell::class.simpleName} at $point")
				}
			}
			
			// Add to dynamic grid (DynamicPathSeparator extends Cell, so this is safe)
			dynamicGrid.put(point, dynamicCell)
			
			// Add to mapping for identity preservation
			staticToDynamicMap[cell] = dynamicCell
			
			// Special handling for InOut: map embedded semaphores
			if (cell is InOut && dynamicCell is DynamicInOut) {
				// Map InOut's semaphores to their Dynamic wrappers
				// These semaphores might be used in paths before they're encountered as separate cells
				staticToDynamicMap.putIfAbsent(cell.getInSemaphore(), dynamicCell.inSemaphore)
				staticToDynamicMap.putIfAbsent(cell.getOutSemaphore(), dynamicCell.outSemaphore)
			}
		}
		
		return TransformationResult(dynamicGrid, staticToDynamicMap)
	}
	
	/**
	 * Create DynamicInOut wrapper with properly initialized semaphores.
	 *
	 * @param inOut The static InOut to wrap
	 * @return DynamicInOut with initialized in/out semaphores
	 */
	private fun createDynamic(inOut: InOut): DynamicInOut {
		val inSemaphore = createDynamicInstance(inOut.getInSemaphore())
		val outSemaphore = createConstantInstance(inOut.getOutSemaphore(), Signal.FREE)
		return DynamicInOut(inOut, inSemaphore, outSemaphore)
	}
}
