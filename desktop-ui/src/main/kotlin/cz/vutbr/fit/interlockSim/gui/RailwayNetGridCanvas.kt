/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.context.AutoNameGenerator
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.exceptions.requireEditor
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.gui.gridcanvas.AnimatedSimulationCellRenderer
import cz.vutbr.fit.interlockSim.gui.gridcanvas.CellRenderer
import cz.vutbr.fit.interlockSim.gui.gridcanvas.EditorCellRenderer
import cz.vutbr.fit.interlockSim.gui.gridcanvas.GridCanvasEditingPopupMenu
import cz.vutbr.fit.interlockSim.gui.gridcanvas.GridCanvasPopupMenu
import cz.vutbr.fit.interlockSim.gui.gridcanvas.SimulationCellRenderer
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.mp.KoinPlatform.getKoin
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants
import cz.vutbr.fit.interlockSim.util.Point as GridPoint

/**
 * Main GUI component for rendering and editing railway elements in a grid.
 * Implements both visual rendering and interaction handling (mouse events, scrolling).
 *
 * ## Context Type Handling
 *
 * This component supports both [EditingContext] and [SimulationContext] without assuming
 * inheritance relationship between them (preparation for Issue #153.5).
 *
 * - Use [setContext] to switch between editing and simulation modes
 * - Use [getEditingContext] to access EditingContext (only when in EDITING state)
 * - Use [getSimulationContext] to access SimulationContext (only when in SIMULATION state)
 *
 * The state machine ensures type-safe access to the appropriate context type.
 *
 * ## Animation and Event Logging (Issue #205)
 *
 * When in simulation mode, this component integrates with:
 * - [cz.vutbr.fit.interlockSim.gui.animation.AnimationController] - 30 FPS rendering and state management
 * - [cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel] - Event logging display (optional)
 *
 * Use [setEventTimelinePanel] before calling [setContext] with a [SimulationContext] to enable
 * event logging. Events are forwarded from AnimationController to the panel via PropertyChangeListener.
 *
 * Use [getAnimationController] to access the current animation state (e.g., for time display updates).
 *
 * @see setEventTimelinePanel
 * @see getAnimationController
 */
class RailwayNetGridCanvas :
	JComponent(),
	Scrollable,
	MouseMotionListener,
	StatusProducer,
	ContextPropertyChangeListener {
	// Rendering modes for editing and simulation
	private enum class State(
		val cellRenderer: CellRenderer?,
		val popupMenu: GridCanvasPopupMenu?
	) {
		EDITING(EditorCellRenderer(CELL_WIDTH, CELL_HEIGHT), GridCanvasEditingPopupMenu()),
		SIMULATION(SimulationCellRenderer(CELL_WIDTH, CELL_HEIGHT), null)

		// EXTENSION - Consider moving to separate enum
	}

	// Abstract base class for mouse event handling with different behaviors per mode
	private abstract inner class GridMouseAdapter :
		MouseMotionListener,
		MouseListener {
		override fun mouseDragged(e: MouseEvent) {
			// Override in subclasses if needed
		}

		override fun mouseMoved(e: MouseEvent) {
			// Override in subclasses if needed
		}

		final override fun mouseClicked(e: MouseEvent) {
			when (e.button) {
				MouseEvent.BUTTON1 -> leftMouseClicked(e)
				MouseEvent.BUTTON2 -> middleMouseClicked(e)
				MouseEvent.BUTTON3 -> rightMouseClicked(e)
				else -> error("Unknown mouse button: ${e.button}")
			}
		}

		private fun rightMouseClicked(e: MouseEvent) {
			state.popupMenu?.show(this@RailwayNetGridCanvas, e, currentKey(e), cellOn(e))
		}

		@Suppress("EmptyFunctionBlock")
		protected open fun middleMouseClicked(e: MouseEvent) {
			// Optional hook: editing mode overrides this to remove a cell; simulation mode ignores it.
		}

		@Suppress("EmptyFunctionBlock")
		protected open fun leftMouseClicked(e: MouseEvent) {
			// Optional hook: overridden by GridMouseEditListener; simulation mode ignores clicks.
		}

		@Suppress("EmptyFunctionBlock")
		override fun mouseEntered(e: MouseEvent) {
			// MouseListener member the canvas does not use: entering the canvas changes no state.
		}

		@Suppress("EmptyFunctionBlock")
		override fun mouseExited(e: MouseEvent) {
			// MouseListener member the canvas does not use: leaving the canvas changes no state.
		}

		@Suppress("EmptyFunctionBlock")
		override fun mousePressed(e: MouseEvent) {
			// MouseListener member the canvas does not use: it acts on the completed click only.
		}

		@Suppress("EmptyFunctionBlock")
		override fun mouseReleased(e: MouseEvent) {
			// MouseListener member the canvas does not use: mouseClicked() carries the button.
		}
	}

	// Mouse event handler for editing mode - allows creation and connection of elements
	private inner class GridMouseEditListener : GridMouseAdapter() {
		override fun leftMouseClicked(e: MouseEvent) {
			val cellAtClick = cellOn(e)
			val editingContext = getEditingContext()
			val clickKey = currentKey(e)

			when {
				cellAtClick == null -> {
					// Empty cell - create new element if one is selected in toolbar
					if (toolbarCellClass == null) {
						selectedKey = null
						repaint()
						return
					}
					try {
						@Suppress("UNCHECKED_CAST")
						val newCell =
							getEditingContextFactory().createNew(
								editingContext,
								toolbarCellClass!!,
								*(toolbarArgs!! as Array<Any>)
							) as NodeCell

						// Auto-name newly created elements
						logger.debug {
							"Creating cell: ${newCell.javaClass.simpleName}, " +
								"currentNameString: '${editingContext.currentNameString}'"
						}
						val namedCell: NodeCell =
							when (newCell) {
								is InOut -> {
									// Auto-generate sequential names for InOuts (IO1, IO2, ...)
									// Users can customize via toolbar nameString or rename dialog afterward
									val name =
										if (editingContext.currentNameString.isNotEmpty()) {
											logger.debug { "InOut: Using toolbar name: '${editingContext.currentNameString}'" }
											editingContext.currentNameString
										} else {
											val autoName = AutoNameGenerator.generateName(newCell::class, editingContext)
											logger.debug { "InOut: Auto-generated name: '$autoName'" }
											autoName
										}
									val result = newCell.withName(name)
									logger.debug { "InOut: After withName(), getName() returns: '${result.getName()}'" }
									result
								}
								is RailSemaphore, is RailSwitch -> {
									// Auto-generate sequential names for semaphores and switches
									val autoName =
										AutoNameGenerator.generateName(
											newCell::class,
											editingContext
										)
									val result = newCell.withName(autoName)
									logger.debug {
										"${newCell::class.simpleName}: Auto-named as '$autoName', " +
											"getName() returns: '${result.getName()}'"
									}
									result
								}
								// Other NodeCell types could be added here in the future
								else -> newCell
							}

						editingContext.putCell(clickKey, namedCell)
						// Clear selection after creating a cell to prevent auto-joining
						selectedKey = null
					} catch (e1: Exception) {
						error("Failed to create cell: $e1")
					}
				}
				selectedKey != null -> {
					// Cell is selected - connect if different cell clicked
					if (selectedKey == clickKey) return
					val selectedPoint = selectedKey!!
					selectedKey = null

					if (cellAtClick is NodeCell) {
						val selectedCell = context?.getRailWayNetGrid()?.get(selectedPoint) as? NodeCell
						if (selectedCell != null) {
							val trackBlock =
								SimpleTrackBlock(
									selectedCell,
									cellAtClick,
									editingContext.currentTrackLength,
									editingContext.currentMaxSpeed
								)
							editingContext.joinCells(selectedPoint, clickKey, trackBlock)
							repaint()
						}
					}
				}
				cellAtClick is NodeCell -> {
					// Select cell for future connection
					selectedKey = clickKey
					repaint()
				}
			}
		}

		override fun middleMouseClicked(e: MouseEvent) {
			val editingContext = getEditingContext()
			editingContext.removeCell(currentKey(e))
		}
	}

	// Mouse event handler for simulation mode - currently no interaction
	private inner class GridMouseSimulationControlListener : GridMouseAdapter() {
		override fun leftMouseClicked(e: MouseEvent) {
			// EXTENSION - Add simulation control features
		}

		override fun middleMouseClicked(e: MouseEvent) {
			// EXTENSION - Add simulation control features
		}
	}

	// Instance variables

	/** Whether the helper grid is painted over the canvas. */
	var showGrid: Boolean = false
	private var context: Context<*, *>? = null
	private val editListener = GridMouseEditListener()
	private val simulationControlListener = GridMouseSimulationControlListener()
	private var state = State.EDITING

	/**
	 * Internal test accessor for toolbar cell class.
	 *
	 * This accessor is intended for testing purposes only. It allows tests to verify
	 * that [setNodeOnToolbar] correctly stores the toolbar state.
	 *
	 * The currently selected cell class for toolbar operations, or null if none selected.
	 *
	 * @see setNodeOnToolbar
	 */
	internal var toolbarCellClass: Class<out NodeCell>? = null
		private set

	/**
	 * Internal test accessor for toolbar constructor arguments.
	 *
	 * This accessor is intended for testing purposes only. It allows tests to verify
	 * that [setNodeOnToolbar] correctly stores the toolbar state.
	 *
	 * The constructor arguments for the selected cell class, or null if none selected.
	 *
	 * @see setNodeOnToolbar
	 */
	internal var toolbarArgs: Array<Any?>? = null
		private set

	private var selectedKey: GridPoint? = null

	// Animation support (Issue #202)

	/**
	 * The current animation controller if running (Issue #205), or null if not in simulation mode.
	 *
	 * Used by [cz.vutbr.fit.interlockSim.gui.Frame] to access animation state
	 * for time display updates in [cz.vutbr.fit.interlockSim.gui.animation.ControlPanel].
	 *
	 * **Must be read from EDT.**
	 */
	var animationController: AnimationController? = null
		private set
	private var animatedRenderer: CellRenderer? = null

	// Event timeline integration (Issue #205)
	private var eventTimelinePanel: cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel? = null

	// Path preview highlight state (Issue #596)
	// Maps route index → set of grid points to highlight for that route.
	private var previewHighlights: Map<Int, Set<GridPoint>> = emptyMap()
	private var selectedPreviewIndex: Int = -1

	// Warning block highlight state (Issue #616, Goal 3 SP6)
	// Grid points for the block involved in the currently-selected collision warning.
	private var warningHighlights: Set<GridPoint> = emptySet()

	init {
		background = Color.BLACK
		autoscrolls = true
		addMouseMotionListener(this)
		addMouseListener(editListener)
	}

	/**
	 * Switch context and update mouse listeners for the appropriate mode.
	 *
	 * Explicitly handles both EditingContext and SimulationContext without assuming
	 * inheritance relationship between them (preparation for Issue #153.5).
	 *
	 * ## Animation Support (Issue #202)
	 *
	 * When switching to [SimulationContext], this method creates and starts an
	 * [AnimationController] for 30 FPS animated rendering with state-based colors.
	 * The controller is automatically stopped when switching away from simulation mode.
	 */
	fun setContext(newContext: Context<*, *>) {
		// Stop any existing animation controller
		stopAnimation()
		// Clear path preview when switching context
		clearPathPreview()

		when (newContext) {
			is SimulationContext -> {
				// Handle SimulationContext first (more specific type)
				state = State.SIMULATION
				changeListeners(editListener, simulationControlListener)

				// Create and start animation infrastructure (Issue #202)
				startAnimation(newContext)
			}
			is EditingContext -> {
				// Handle EditingContext (base editing functionality)
				state = State.EDITING
				changeListeners(simulationControlListener, editListener)
			}
			else -> {
				// Future context types default to simulation mode (read-only)
				// This provides safe fallback behavior for unknown context types
				state = State.SIMULATION
				changeListeners(editListener, simulationControlListener)
			}
		}
		changeContext(newContext)
	}

	/**
	 * Start animation controller for simulation mode (Issue #202).
	 *
	 * Creates [AnimationController] and [AnimatedSimulationCellRenderer] for
	 * state-based animated rendering at 30 FPS.
	 *
	 * **Must be called from EDT.**
	 */
	private fun startAnimation(simulationContext: SimulationContext) {
		// Create animation controller with event timeline integration (Issue #205)
		animationController = AnimationController(simulationContext, this, eventTimelinePanel)

		// Create animated renderer with state-based coloring
		animatedRenderer =
			AnimatedSimulationCellRenderer(
				CELL_WIDTH,
				CELL_HEIGHT,
				animationController!!
			)

		// Start animation loop (30 FPS rendering)
		animationController?.start()
	}

	/**
	 * Stop animation controller if running (Issue #202).
	 *
	 * Cleans up animation resources when switching away from simulation mode
	 * or when switching between different simulation contexts.
	 *
	 * **Scenarios:**
	 * - Switching from simulation mode to editing mode
	 * - Switching between different simulation contexts
	 * - Frame is closing (explicit cleanup via cleanupAnimation())
	 *
	 * **Idempotent:** Safe to call multiple times (no-op if already stopped).
	 *
	 * **Must be called from EDT.**
	 */
	private fun stopAnimation() {
		animationController?.stop()
		animationController = null
		animatedRenderer = null
	}

	/**
	 * Clean up animation resources explicitly.
	 * Should be called when canvas is being disposed or parent Frame is closing.
	 * Safe to call multiple times (idempotent).
	 *
	 * **Must be called from EDT.**
	 */
	fun cleanupAnimation() {
		stopAnimation()
	}

	/**
	 * Set the event timeline panel for event logging during simulation (Issue #205).
	 *
	 * This panel receives simulation events (path settings, train movements, etc.)
	 * from the [AnimationController]. Must be called before switching to simulation mode
	 * to ensure events are properly logged.
	 *
	 * **Must be called from EDT.**
	 *
	 * @param panel The event timeline panel to receive events, or null to disable event logging
	 */
	fun setEventTimelinePanel(panel: cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel?) {
		eventTimelinePanel = panel
	}

	// Change mouse listeners based on mode
	private fun changeListeners(
		oldListener: GridMouseAdapter,
		newListener: GridMouseAdapter
	) {
		removeListener(oldListener)
		addListener(newListener)
	}

	private fun addListener(listener: GridMouseAdapter) {
		addMouseMotionListener(listener)
		addMouseListener(listener)
	}

	private fun removeListener(listener: GridMouseAdapter) {
		removeMouseMotionListener(listener)
		removeMouseListener(listener)
	}

	// Update context and recalculate display
	private fun changeContext(cont: Context<*, *>) {
		if (context != null) {
			context!!.removePropertyChangeListener(this)
		}
		cont.addPropertyChangeListener(this)
		context = cont
		val grid = cont.getRailWayNetGrid()
		preferredSize = Dimension(CELL_WIDTH * grid.cols, CELL_HEIGHT * grid.rows)
		size = preferredSize
		revalidate()
	}

	/**
	 * Auto-center the viewport on the populated area of the grid.
	 * Helps to immediately show the active track area when starting simulation.
	 */
	fun autoCenterViewport() {
		val ctx = context ?: return
		val grid = ctx.getRailWayNetGrid()

		var minX = Int.MAX_VALUE
		var minY = Int.MAX_VALUE
		var maxX = Int.MIN_VALUE
		var maxY = Int.MIN_VALUE

		for (entry in grid) {
			val key = entry.key
			if (key.x < minX) minX = key.x
			if (key.y < minY) minY = key.y
			if (key.x > maxX) maxX = key.x
			if (key.y > maxY) maxY = key.y
		}

		// Check if we actually found any cells
		if (minX == Int.MAX_VALUE) return

		val padding = VIEWPORT_PADDING_CELLS
		val startX = kotlin.math.max(0, (minX - padding) * CELL_WIDTH)
		val startY = kotlin.math.max(0, (minY - padding) * CELL_HEIGHT)
		val endX = ((maxX + 1 + padding) * CELL_WIDTH)
		val endY = ((maxY + 1 + padding) * CELL_HEIGHT)

		val rect = Rectangle(startX, startY, endX - startX, endY - startY)

		javax.swing.SwingUtilities.invokeLater {
			scrollRectToVisible(rect)
		}
	}

	// Painting methods
	override fun paintComponent(g: Graphics) {
		requireEditor(g is Graphics2D) { "Graphics context must be Graphics2D" }
		paint(g)
	}

	/**
	 * Paint all cells in the railway grid
	 *
	 * ## Animation Rendering (Issue #202, #203)
	 *
	 * When in simulation mode with animation enabled, uses [AnimatedSimulationCellRenderer]
	 * for state-based color rendering and train overlays. Falls back to static renderer otherwise.
	 *
	 * ## Rendering Order
	 *
	 * 1. Grid cells (tracks, signals, switches) - bottom layer
	 * 2. Train overlays - top layer (only in animation mode)
	 * 3. Grid lines (if enabled) - guide layer
	 * 4. Selected cell highlight (if any) - interaction feedback
	 */
	private fun paint(g: Graphics2D) {
		if (context == null) return
		cancelClip(g)

		// Use animated renderer if available (simulation mode with animation),
		// otherwise use static renderer from state enum
		val renderer = animatedRenderer ?: state.cellRenderer

		// Render all grid cells (bottom layer)
		val grid = context!!.getRailWayNetGrid()
		for (entry in grid) {
			val key = entry.key
			val cell = entry.value

			val x = key.x * CELL_WIDTH
			val y = key.y * CELL_HEIGHT

			g.translate(x, y)
			g.clipRect(0, 0, CELL_WIDTH + 1, CELL_HEIGHT + 1)
			renderer?.draw(g, cell)
			g.translate(-x, -y)
			cancelClip(g)
		}

		// Render train overlays (top layer, only in animation mode)
		val animRenderer = animatedRenderer
		if (animRenderer is AnimatedSimulationCellRenderer) {
			cancelClip(g)
			animRenderer.drawAllTrains(g, CELL_WIDTH, CELL_HEIGHT)
		}

		// Render grid lines and selection highlight
		if (showGrid) paintGrid(g)
		if (selectedKey != null) paintMarkSelected(g)
		if (previewHighlights.isNotEmpty()) paintPathHighlights(g)
		if (warningHighlights.isNotEmpty()) paintWarningHighlights(g)
	}

	// Highlight the selected cell for connection
	private fun paintMarkSelected(g: Graphics2D) {
		val cell = context!!.getRailWayNetGrid()[selectedKey!!]
		if (cell !is NodeCell) {
			selectedKey = null
			return
		}
		cancelClip(g)
		g.color = Color.RED
		g.drawRect(selectedKey!!.x * CELL_WIDTH, selectedKey!!.y * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)
	}

	private fun cancelClip(g: Graphics2D) {
		g.clip = visibleRect
	}

	/**
	 * Set the candidate paths to highlight as a path preview overlay (Issue #596).
	 *
	 * Computes grid positions for each route's track blocks and stores them for
	 * overlay rendering on top of the regular cell layer. Call with null or empty
	 * routes to clear the preview.
	 *
	 * The primary route ([selectedIndex]) is rendered in solid blue; all other
	 * alternative routes are rendered in a semi-transparent cyan.
	 *
	 * **Must be called from the EDT.**
	 *
	 * @param routes candidate routes from RouteFinder, or null/empty to clear
	 * @param selectedIndex index of the primary (selected) route; -1 if none
	 */
	fun setPathPreview(
		routes: List<Route>?,
		selectedIndex: Int
	) {
		if (routes.isNullOrEmpty()) {
			previewHighlights = emptyMap()
			selectedPreviewIndex = -1
			repaint(100)
			return
		}
		val highlights = mutableMapOf<Int, Set<GridPoint>>()
		routes.forEachIndexed { index, route ->
			val pts = buildHighlightPoints(route)
			if (pts.isNotEmpty()) highlights[index] = pts
		}
		previewHighlights = highlights
		selectedPreviewIndex = selectedIndex
		repaint(100)
	}

	/**
	 * Clear the path preview overlay (Issue #596).
	 *
	 * Equivalent to calling [setPathPreview] with null routes. Safe to call when
	 * no preview is active.
	 *
	 * **Must be called from the EDT.**
	 */
	fun clearPathPreview() {
		previewHighlights = emptyMap()
		selectedPreviewIndex = -1
		repaint(100)
	}

	/**
	 * Highlight the track block involved in [warning] with a red overlay (Issue #616).
	 *
	 * - [CollisionWarning.BlockEntryViolation]: highlights [CollisionWarning.BlockEntryViolation.block].
	 * - [CollisionWarning.ReservationConflict]: highlights [CollisionWarning.ReservationConflict.conflictingBlock]
	 *   if non-null.
	 * - [CollisionWarning.PredictiveCollision]: no block reference → clears any existing highlight.
	 *
	 * The method iterates the current context grid to find [TrackBlockPart] cells whose
	 * block name matches the warning block name, then triggers a repaint.
	 *
	 * **Must be called from the EDT.**
	 *
	 * @since Issue #616 (Goal 3 SP6)
	 */
	fun highlightWarningBlock(warning: CollisionWarning) {
		val blockName: String? =
			when (warning) {
				is CollisionWarning.BlockEntryViolation -> warning.block.name
				is CollisionWarning.ReservationConflict -> warning.conflictingBlock?.name
				is CollisionWarning.PredictiveCollision -> null
			}

		if (blockName == null) {
			warningHighlights = emptySet()
			repaint(100)
			return
		}

		val ctx = context
		if (ctx == null) {
			warningHighlights = emptySet()
			return
		}

		val grid = ctx.getRailWayNetGrid()
		val points = mutableSetOf<GridPoint>()
		for (entry in grid) {
			val cell = entry.value
			if (cell is TrackBlockPart && cell.getTrackBlock().name == blockName) {
				points.add(entry.key)
			}
		}
		warningHighlights = points
		repaint(100)
	}

	/**
	 * Collect grid positions that represent the cells belonging to [route]'s track
	 * blocks. Includes both [TrackBlockPart] intermediate cells and the [NodeCell]
	 * endpoint separators of each segment.
	 */
	private fun buildHighlightPoints(route: Route): Set<GridPoint> {
		val ctx = context as? EditingContext ?: return emptySet()
		val grid = ctx.getRailWayNetGrid()
		val points = mutableSetOf<GridPoint>()

		val blocks = route.segments.map { it.getTrackBlock() }.toSet()

		for (entry in grid) {
			val cell = entry.value
			when {
				cell is TrackBlockPart && cell.getTrackBlock() in blocks -> points.add(entry.key)
				cell is NodeCell && isRouteEndpoint(cell, route) -> points.add(entry.key)
			}
		}
		return points
	}

	/**
	 * Return true if [nodeCell] is one of the separator endpoints of any segment in [route].
	 */
	private fun isRouteEndpoint(
		nodeCell: NodeCell,
		route: Route
	): Boolean =
		route.segments.any { section ->
			section.getTrackBlock().ends().any { sep -> sep === nodeCell }
		}

	/**
	 * Draw semi-transparent color overlays for path preview routes (Issue #596).
	 *
	 * - Primary route ([selectedPreviewIndex]): solid blue overlay
	 * - Alternative routes: semi-transparent cyan overlay
	 */
	private fun paintPathHighlights(g: Graphics2D) {
		cancelClip(g)
		val oldComposite = g.composite

		previewHighlights.forEach { (routeIndex, points) ->
			if (routeIndex == selectedPreviewIndex) {
				// Primary route: solid blue at 50% opacity
				g.composite =
					AlphaComposite.getInstance(
						AlphaComposite.SRC_OVER,
						0.5f
					)
				g.color = PATH_PRIMARY_COLOR
			} else {
				// Alternative routes: cyan at 30% opacity
				g.composite =
					AlphaComposite.getInstance(
						AlphaComposite.SRC_OVER,
						0.3f
					)
				g.color = PATH_ALTERNATIVE_COLOR
			}
			for (pt in points) {
				g.fillRect(pt.x * CELL_WIDTH, pt.y * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)
			}
		}

		g.composite = oldComposite
	}

	/**
	 * Draw a semi-transparent red overlay over the block grid positions stored in
	 * [warningHighlights] (Issue #616, Goal 3 SP6).
	 */
	private fun paintWarningHighlights(g: Graphics2D) {
		cancelClip(g)
		val oldComposite = g.composite
		g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
		g.color = WARNING_HIGHLIGHT_COLOR
		for (pt in warningHighlights) {
			g.fillRect(pt.x * CELL_WIDTH, pt.y * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)
		}
		g.composite = oldComposite
	}

	// Draw grid lines for alignment
	private fun paintGrid(g: Graphics2D) {
		val grid = context!!.getRailWayNetGrid()
		g.color = Color.GRAY
		// Vertical lines
		for (i in 0..grid.cols) {
			val x = i * CELL_WIDTH
			g.drawLine(x, 0, x, height)
		}

		// Horizontal lines
		for (i in 0..grid.rows) {
			val y = i * CELL_HEIGHT
			g.drawLine(0, y, width, y)
		}
	}

	// Status bar support
	override fun getStatus(e: MouseEvent): String {
		val cell = cellOn(e.x, e.y)
		return cell?.toString() ?: ""
	}

	// Mouse coordinate to grid coordinate conversion
	private fun currentKey(e: MouseEvent): GridPoint = GridPoint(e.x / CELL_WIDTH, e.y / CELL_HEIGHT)

	private fun cellOn(
		x: Int,
		y: Int
	): Cell? = context?.getRailWayNetGrid()?.getCellAt(x / CELL_WIDTH, y / CELL_HEIGHT)

	private fun cellOn(e: MouseEvent): Cell? = cellOn(e.x, e.y)

	// Scrollable interface implementation
	override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

	override fun getScrollableBlockIncrement(
		visibleRect: Rectangle,
		orientation: Int,
		direction: Int
	): Int =
		when (orientation) {
			SwingConstants.HORIZONTAL -> visibleRect.width - MAX_UNIT_INCREMENT
			else -> visibleRect.height - MAX_UNIT_INCREMENT
		}

	override fun getScrollableTracksViewportHeight(): Boolean = false

	override fun getScrollableTracksViewportWidth(): Boolean = false

	override fun getScrollableUnitIncrement(
		visibleRect: Rectangle,
		orientation: Int,
		direction: Int
	): Int {
		val currentPosition =
			when (orientation) {
				SwingConstants.HORIZONTAL -> visibleRect.x
				else -> visibleRect.y
			}

		return if (direction < 0) {
			val newPosition = currentPosition - (currentPosition / MAX_UNIT_INCREMENT) * MAX_UNIT_INCREMENT
			if (newPosition == 0) MAX_UNIT_INCREMENT else newPosition
		} else {
			((currentPosition / MAX_UNIT_INCREMENT) + 1) * MAX_UNIT_INCREMENT - currentPosition
		}
	}

	// MouseMotionListener implementation
	override fun mouseDragged(ev: MouseEvent) {
		mouseMoveScroll(ev)
	}

	override fun mouseMoved(ev: MouseEvent) {
		// EXTENSION - In simulation mode, enable scrolling
		if (state == State.SIMULATION) {
			mouseMoveScroll(ev)
		}
	}

	private fun mouseMoveScroll(ev: MouseEvent) {
		val r = Rectangle(ev.x, ev.y, 1, 1)
		scrollRectToVisible(r)
	}

	// Context access methods with type safety

	/**
	 * Get the current context as EditingContext.
	 *
	 * @return EditingContext if current context is an EditingContext
	 * @throws IllegalArgumentException if no context is set or context is not an EditingContext
	 */
	fun getEditingContext(): EditingContext {
		val ctx = context
		require(ctx != null) {
			"No context is currently set"
		}
		require(state == State.EDITING) {
			"Cannot get EditingContext when in $state state"
		}
		require(ctx is EditingContext) {
			"Current context is not an EditingContext: ${ctx.javaClass.simpleName}"
		}
		return ctx
	}

	/**
	 * Get the current context as SimulationContext.
	 *
	 * @return SimulationContext if current context is a SimulationContext
	 * @throws IllegalArgumentException if no context is set or context is not a SimulationContext
	 */
	fun getSimulationContext(): SimulationContext {
		val ctx = context
		require(ctx != null) {
			"No context is currently set"
		}
		require(state == State.SIMULATION) {
			"Cannot get SimulationContext when in $state state"
		}
		require(ctx is SimulationContext) {
			"Current context is not a SimulationContext: ${ctx.javaClass.simpleName}"
		}
		return ctx
	}

	// Toolbar management
	fun setNodeOnToolbar(
		cellClass: Class<out NodeCell>?,
		args: Array<Any?>?
	) {
		toolbarArgs = args
		toolbarCellClass = cellClass
	}

	// ContextPropertyChangeListener for context updates
	override fun propertyChange(event: ContextChangeEvent) {
		val newValue = event.newValue
		if (newValue is GridPoint) {
			repaint(10, newValue.x * CELL_WIDTH, newValue.y * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)
		} else {
			repaint(100)
		}
	}

	// Helper to get editing context factory
	private fun getEditingContextFactory(): JvmEditingContextFactory = getKoin().get<JvmEditingContextFactory>()

	companion object {
		private val logger = KotlinLogging.logger {}
		private const val MAX_UNIT_INCREMENT = 35
		private const val CELL_WIDTH = 16
		private const val CELL_HEIGHT = 16

		/** Color used for the primary (selected) path preview route. */
		private val PATH_PRIMARY_COLOR = Color(0x00, 0x80, 0xFF)

		/** Color used for alternative (non-selected) path preview routes. */
		private val PATH_ALTERNATIVE_COLOR = Color(0x00, 0xFF, 0xFF)

		/**
		 * Color used for the warning block highlight overlay (Issue #616, Goal 3 SP6).
		 * Matches [GuiConstants.WARNING_BADGE_COLOR].
		 */
		private val WARNING_HIGHLIGHT_COLOR = GuiConstants.WARNING_BADGE_COLOR

		/**
		 * Extra padding in grid cells to add around the populated track area
		 * when automatically centering the viewport.
		 */
		private const val VIEWPORT_PADDING_CELLS = 2

		// Public accessors for cell dimensions (used by other components)
		fun getCellHeight(): Int = CELL_HEIGHT

		fun getCellWidth(): Int = CELL_WIDTH
	}
}
