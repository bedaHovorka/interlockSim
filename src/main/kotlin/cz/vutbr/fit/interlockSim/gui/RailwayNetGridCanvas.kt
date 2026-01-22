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
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
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
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.mp.KoinPlatform.getKoin
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants

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
 */
class RailwayNetGridCanvas :
	JComponent(),
	Scrollable,
	MouseMotionListener,
	StatusProducer,
	PropertyChangeListener {
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

		protected open fun middleMouseClicked(e: MouseEvent) {}

		protected open fun leftMouseClicked(e: MouseEvent) {}

		override fun mouseEntered(e: MouseEvent) {}

		override fun mouseExited(e: MouseEvent) {}

		override fun mousePressed(e: MouseEvent) {}

		override fun mouseReleased(e: MouseEvent) {}
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
						when (newCell) {
							is InOut -> {
								// Auto-generate sequential names for InOuts (IO1, IO2, ...)
								// Users can customize via toolbar nameString or rename dialog afterward
								val name = if (editingContext.currentNameString.isNotEmpty()) {
									logger.debug { "InOut: Using toolbar name: '${editingContext.currentNameString}'" }
									editingContext.currentNameString
								} else {
									val autoName = AutoNameGenerator.generateName(newCell.javaClass, editingContext)
									logger.debug { "InOut: Auto-generated name: '$autoName'" }
									autoName
								}
								newCell.setName(name)
								logger.debug { "InOut: After setName(), getName() returns: '${newCell.getName()}'" }
							}
							is RailSemaphore, is RailSwitch -> {
								// Auto-generate sequential names for semaphores and switches
								val autoName = AutoNameGenerator.generateName(
									newCell.javaClass,
									editingContext
								)
								newCell.setName(autoName)
								logger.debug {
									"${newCell.javaClass.simpleName}: Auto-named as '$autoName', " +
										"getName() returns: '${newCell.getName()}'"
								}
							}
							// Other NodeCell types could be added here in the future
						}

						editingContext.putCell(clickKey, newCell)
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
	private var showGrid: Boolean = false
	private var context: Context<*, *>? = null
	private val editListener = GridMouseEditListener()
	private val simulationControlListener = GridMouseSimulationControlListener()
	private var state = State.EDITING
	private var toolbarCellClass: Class<out NodeCell>? = null
	private var toolbarArgs: Array<Any?>? = null
	private var selectedKey: cz.vutbr.fit.interlockSim.util.Point? = null

	// Animation support (Issue #202)
	private var animationController: AnimationController? = null
	private var animatedRenderer: CellRenderer? = null

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
		// Create animation controller
		animationController = AnimationController(simulationContext, this)

		// Create animated renderer with state-based coloring
		animatedRenderer = AnimatedSimulationCellRenderer(
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
	 * **Must be called from EDT.**
	 */
	private fun stopAnimation() {
		animationController?.stop()
		animationController = null
		animatedRenderer = null
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
		preferredSize = Dimension(CELL_WIDTH * grid.getCols(), CELL_HEIGHT * grid.getRows())
		size = preferredSize
		revalidate()
	}

	// Painting methods
	override fun paintComponent(g: Graphics) {
		requireEditor(g is Graphics2D) { "Graphics context must be Graphics2D" }
		paint(g as Graphics2D)
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
	}

	// Highlight the selected cell for connection
	private fun paintMarkSelected(g: Graphics2D) {
		val cell = context!!.getRailWayNetGrid().get(selectedKey!!)
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

	// Draw grid lines for alignment
	private fun paintGrid(g: Graphics2D) {
		val grid = context!!.getRailWayNetGrid()
		g.color = Color.GRAY
		// Vertical lines
		for (i in 0..grid.getCols()) {
			val x = i * CELL_WIDTH
			g.drawLine(x, 0, x, height)
		}

		// Horizontal lines
		for (i in 0..grid.getRows()) {
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
	private fun currentKey(e: MouseEvent): cz.vutbr.fit.interlockSim.util.Point =
		cz.vutbr.fit.interlockSim.util
			.Point(e.x / CELL_WIDTH, e.y / CELL_HEIGHT)

	private fun cellOn(
		x: Int,
		y: Int
	): Cell? = context?.getRailWayNetGrid()?.getCellAt(x / CELL_WIDTH, y / CELL_HEIGHT)

	private fun cellOn(e: MouseEvent): Cell? = cellOn(e.x, e.y)

	// Deprecated methods - kept for backwards compatibility
	@Deprecated("Use setContext instead")
	fun setEditing(editingContext: EditingContext) {
		setContext(editingContext)
	}

	@Deprecated("Use setContext instead")
	fun setSimulation(context: Context<*, *>) {
		setContext(context)
	}

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
		if (false) mouseMoveScroll(ev)
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

	// Grid display options
	fun isShowGrid(): Boolean = showGrid

	fun setShowGrid(b: Boolean) {
		showGrid = b
	}

	// Toolbar management
	fun setNodeOnToolbar(
		cellClass: Class<out NodeCell>?,
		args: Array<Any?>?
	) {
		toolbarArgs = args
		toolbarCellClass = cellClass
	}

	// Internal test accessors for toolbar state
	internal fun getToolbarCellClass(): Class<out NodeCell>? = toolbarCellClass

	internal fun getToolbarArgs(): Array<Any?>? = toolbarArgs

	// PropertyChangeListener for context updates
	override fun propertyChange(evt: PropertyChangeEvent) {
		val newValue = evt.newValue
		if (newValue is Point) {
			repaint(10, newValue.x * CELL_WIDTH, newValue.y * CELL_HEIGHT, CELL_WIDTH, CELL_HEIGHT)
		} else {
			repaint(100)
		}
	}

	// Helper to get editing context factory
	private fun getEditingContextFactory(): EditingContextFactory = getKoin().get<EditingContextFactory>()

	companion object {
		private val logger = KotlinLogging.logger {}
		private const val MAX_UNIT_INCREMENT = 35
		private const val CELL_WIDTH = 16
		private const val CELL_HEIGHT = 16

		// Public accessors for cell dimensions (used by other components)
		fun getCellHeight(): Int = CELL_HEIGHT

		fun getCellWidth(): Int = CELL_WIDTH
	}
}
