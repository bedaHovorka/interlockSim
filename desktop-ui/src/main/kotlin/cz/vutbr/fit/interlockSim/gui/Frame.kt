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

import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.ControlPanel
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer

/**
 * Main application window for Railway Interlocking Simulator.
 *
 * Provides dynamic layout that adapts based on context type:
 * - **Editing Mode** ([EditingContext]): StatusBar visible, ControlPanel hidden
 * - **Simulation Mode** ([SimulationContext]): ControlPanel and EventTimelinePanel visible, StatusBar hidden
 *
 * ## Layout Structure
 *
 * ### Editing Mode
 * ```
 * ┌─────────────────────────────────┐
 * │ MenuBar                         │
 * ├─────────────────────────────────┤
 * │ ToolBar                         │
 * ├─────────────────────────────────┤
 * │ RailwayNetGridCanvas            │
 * │ (scrollable)                    │
 * ├─────────────────────────────────┤
 * │ StatusBar                       │
 * └─────────────────────────────────┘
 * ```
 *
 * ### Simulation Mode
 * ```
 * ┌─────────────────────────────────┐
 * │ MenuBar                         │
 * ├─────────────────────────────────┤
 * │ ToolBar                         │
 * ├─────────────────────────────────┤
 * │ ControlPanel (NEW - Issue #205) │
 * │ [Time] [Status]                 │
 * ├─────────────────────────────────┤
 * │ RailwayNetGridCanvas            │
 * │ (animated, scrollable)          │
 * ├─────────────────────────────────┤
 * │ EventTimelinePanel (NEW)        │
 * │ [Filters] [Event log...]        │
 * └─────────────────────────────────┘
 * ```
 *
 * ## Animation Integration (Issue #205)
 *
 * When a [SimulationContext] is set, the frame:
 * 1. Creates [EventTimelinePanel] (lazy, reused across simulations)
 * 2. Wires EventTimelinePanel to [RailwayNetGridCanvas] → [cz.vutbr.fit.interlockSim.gui.animation.AnimationController]
 * 3. Starts 10 Hz timer for [ControlPanel] time updates
 * 4. Shows ControlPanel and EventTimelinePanel, hides StatusBar
 *
 * When an [EditingContext] is set, the frame:
 * 1. Hides ControlPanel and EventTimelinePanel
 * 2. Shows StatusBar for mouse position feedback
 * 3. Stops animation timer (cleanup)
 *
 * ## Thread Safety
 *
 * All public methods must be called from the Event Dispatch Thread (EDT).
 * Mode switching methods enforce EDT requirement with `require()` checks.
 *
 * @since 2006-2007
 * @see setContext
 * @see startSimulation
 * @see stopSimulation
 * @see switchToEditingMode
 * @see switchToSimulationMode
 */
class Frame : JFrame(PROGRAM_FULL_NAME) {
	val railwayNetGridCanvas: RailwayNetGridCanvas = RailwayNetGridCanvas()
	internal val statusBar: StatusBar = StatusBar()
	private val toolBar: ToolBar = ToolBar()

	// Animation UI components (Issue #205)
	private val controlPanel: ControlPanel = ControlPanel()
	private var eventTimelinePanel: cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel? = null
	private var animationUpdateTimer: Timer? = null

	// Simulation lifecycle delegated to SimulationController for testability (Issue #189)
	internal val simulationController: SimulationController = SimulationController(controlPanel)
	private var currentSimulationContext: SimulationContext? = null

	/**
	 * Tracks modification state for unsaved changes warning.
	 */
	val modificationTracker: ModificationTracker =
		ModificationTracker { isDirty ->
			updateTitle()
		}

	init {
		setSize(800, 600)
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE) // Handle close event manually
		setLayout(BorderLayout())
		jMenuBar = MenuBar()
		contentPane.add(JScrollPane(railwayNetGridCanvas), BorderLayout.CENTER)

		// Create north container with ToolBar + ControlPanel (Issue #205)
		val northContainer = JPanel()
		northContainer.layout = BoxLayout(northContainer, BoxLayout.PAGE_AXIS)
		northContainer.add(toolBar)
		controlPanel.isVisible = false // Initially hidden (shown only in simulation mode)
		northContainer.add(controlPanel)
		contentPane.add(northContainer, BorderLayout.NORTH)

		statusBar.registerProducer(railwayNetGridCanvas)
		contentPane.add(statusBar, BorderLayout.SOUTH)

		// Add component listener to refresh canvas when frame is resized
		addComponentListener(
			object : ComponentAdapter() {
				override fun componentResized(e: ComponentEvent) {
					// When frame is resized, refresh grid canvas to handle scrollbar appearance/disappearance
					railwayNetGridCanvas.revalidate()
				}
			}
		)

		// Add window listener to handle close event with unsaved changes warning
		addWindowListener(
			object : WindowAdapter() {
				override fun windowClosing(e: WindowEvent) {
					handleWindowClosing()
				}
			}
		)
	}

	/**
	 * Switch UI layout to simulation mode (Issue #205).
	 *
	 * - Hides StatusBar
	 * - Shows EventTimelinePanel (if created)
	 * - Shows ControlPanel
	 * - Disables editing ToolBar
	 *
	 * **Must be called from EDT.**
	 */
	private fun switchToSimulationMode() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"switchToSimulationMode must be called from EDT"
		}

		// Hide StatusBar, show EventTimelinePanel
		statusBar.isVisible = false
		contentPane.remove(statusBar)
		eventTimelinePanel?.let {
			contentPane.add(it, BorderLayout.SOUTH)
		}

		// Show ControlPanel
		controlPanel.isVisible = true
		controlPanel.updateStatus("Ready")

		// Disable editing toolbar in simulation mode
		toolBar.setToolsEnabled(false)

		contentPane.revalidate()
		contentPane.repaint()
	}

	/**
	 * Switch UI layout to editing mode (Issue #205).
	 *
	 * - Shows StatusBar
	 * - Hides EventTimelinePanel
	 * - Hides ControlPanel
	 * - Enables editing ToolBar
	 *
	 * **Must be called from EDT.**
	 */
	private fun switchToEditingMode() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"switchToEditingMode must be called from EDT"
		}

		// Show StatusBar, hide EventTimelinePanel
		eventTimelinePanel?.let {
			contentPane.remove(it)
		}
		contentPane.add(statusBar, BorderLayout.SOUTH)
		statusBar.isVisible = true

		// Hide ControlPanel
		controlPanel.isVisible = false

		// Enable editing toolbar in editing mode
		toolBar.setToolsEnabled(true)

		contentPane.revalidate()
		contentPane.repaint()
	}

	/**
	 * Set the railway network context and switch UI mode accordingly.
	 *
	 * - [EditingContext]: Switches to editing mode with StatusBar
	 * - [SimulationContext]: Switches to simulation mode with EventTimelinePanel and ControlPanel
	 *
	 * **Animation Integration (Issue #205):**
	 * When switching to SimulationContext, this method:
	 * 1. Lazy-creates EventTimelinePanel (reused across multiple simulations)
	 * 2. Wires EventTimelinePanel to RailwayNetGridCanvas → AnimationController
	 * 3. Starts 10 Hz timer for ControlPanel time updates
	 *
	 * **Must be called from EDT.**
	 */
	fun setContext(context: Context<*, *>) {
		stopSimulation() // Stop any running simulation before switching context
		stopAnimationUpdates() // Cleanup existing timer

		when (context) {
			is SimulationContext -> {
				currentSimulationContext = context

				// Lazy-create event timeline panel (reused across simulations)
				if (eventTimelinePanel == null) {
					eventTimelinePanel = EventTimelinePanel()
				}

				switchToSimulationMode()
				railwayNetGridCanvas.setEventTimelinePanel(eventTimelinePanel)
				railwayNetGridCanvas.setContext(context)
				startAnimationUpdates()

				// Wire stop button to stopSimulation()
				controlPanel.onStop = { stopSimulation() }
				controlPanel.setStopEnabled(false) // enabled only after startSimulation()
			}
			is EditingContext -> {
				currentSimulationContext = null
				controlPanel.onStop = null
				switchToEditingMode()
				railwayNetGridCanvas.setContext(context)
				context.addPropertyChangeListener(modificationTracker)
			}
			else -> {
				currentSimulationContext = null
				controlPanel.onStop = null
				// Unknown context type - default to simulation mode (read-only)
				switchToSimulationMode()
				railwayNetGridCanvas.setContext(context)
			}
		}

		context.addPropertyChangeListener(statusBar)
	}

	/**
	 * Start timer for ControlPanel time updates (Issue #205).
	 *
	 * Creates a 10 Hz (100ms) Swing Timer that reads the current simulation time
	 * from [AnimationController] and updates [ControlPanel].
	 *
	 * **Timer Frequency Rationale:**
	 * - 100ms interval (10 Hz) provides responsive display without excessive CPU overhead
	 * - Much less frequent than 30 FPS rendering, but frequent enough for smooth time display
	 * - Swing Timer automatically runs on EDT (thread-safe)
	 *
	 * **Must be called from EDT.**
	 */
	private fun startAnimationUpdates() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"startAnimationUpdates must be called from EDT"
		}

		animationUpdateTimer =
			Timer(100) {
				// 10 Hz update rate
				val controller = railwayNetGridCanvas.getAnimationController()
				controller?.getCurrentState()?.let { state ->
					controlPanel.updateTime(state.simulationTime)
				}
			}
		animationUpdateTimer?.start()
	}

	/**
	 * Stop ControlPanel update timer and clean up resources (Issue #205).
	 *
	 * Prevents memory leaks when switching between contexts or exiting simulation mode.
	 * Safe to call multiple times or when timer is not running.
	 *
	 * **Must be called from EDT.**
	 */
	private fun stopAnimationUpdates() {
		animationUpdateTimer?.stop()
		animationUpdateTimer = null
	}

	/**
	 * Launch the simulation on a background thread via [SimulationController] (Issue #189).
	 *
	 * Delegates to [SimulationController.start]. Idempotent: if a simulation is already
	 * running this call is a no-op.
	 *
	 * **Must be called from EDT.**
	 */
	fun startSimulation() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"startSimulation must be called from EDT"
		}

		val context = currentSimulationContext ?: run {
			logger.warn { "startSimulation called without a SimulationContext — ignoring" }
			return
		}

		simulationController.start(context)
	}

	/**
	 * Request immediate simulation shutdown (Issue #189).
	 *
	 * Delegates to [SimulationController.stop]. Safe to call when no simulation is
	 * running (no-op in that case).
	 *
	 * **Must be called from EDT.**
	 */
	fun stopSimulation() {
		require(javax.swing.SwingUtilities.isEventDispatchThread()) {
			"stopSimulation must be called from EDT"
		}
		simulationController.stop()
	}

	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Updates the window title to reflect current file and dirty state.
	 */
	private fun updateTitle() {
		val fileName = modificationTracker.getDisplayFileName()
		val suffix = modificationTracker.getTitleSuffix()

		title =
			if (fileName != null) {
				"$PROGRAM_FULL_NAME - $fileName$suffix"
			} else {
				PROGRAM_FULL_NAME
			}
	}

	/**
	 * Handles window closing event.
	 * Shows confirmation dialog if there are unsaved changes.
	 *
	 * Thread safety: Ensures execution on EDT for Swing component access.
	 */
	private fun handleWindowClosing() {
		// Defensive programming: ensure we're on the Event Dispatch Thread
		if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
			javax.swing.SwingUtilities.invokeLater { handleWindowClosing() }
			return
		}

		if (modificationTracker.isDirty()) {
			val result =
				JOptionPane.showConfirmDialog(
					this,
					"The railway network has unsaved changes.\n\n" +
						"Do you want to save your changes before closing?",
					"Unsaved Changes",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.WARNING_MESSAGE
				)

			when (result) {
				JOptionPane.YES_OPTION -> {
					// Trigger save action
					saveAndExit()
				}

				JOptionPane.NO_OPTION -> {
					// Exit without saving
					exitWithoutSaving()
				}

				JOptionPane.CANCEL_OPTION -> {
					// Do nothing - keep window open
					return
				}
			}
		} else {
			// No unsaved changes - exit immediately
			exitWithoutSaving()
		}
	}

	/**
	 * Attempts to save the current context and then exits.
	 * If save fails, the window remains open.
	 */
	private fun saveAndExit() {
		// Get the save action from menu bar and trigger it
		val menuBar = jMenuBar as MenuBar
		val saved = menuBar.triggerSave()

		// Only exit if save was successful
		if (saved) {
			stopAnimationUpdates() // Stop Frame's 10 Hz timer
			railwayNetGridCanvas.cleanupAnimation() // Stop AnimationController - CRITICAL for GC
			exitWithoutSaving()
		}
	}

	/**
	 * Exits the application without saving.
	 */
	private fun exitWithoutSaving() {
		stopSimulation() // Stop any running simulation before exit
		currentSimulationContext?.close() // Release simulation resources before JVM exit
		stopAnimationUpdates() // Stop Frame's 10 Hz timer
		railwayNetGridCanvas.cleanupAnimation() // Stop AnimationController - CRITICAL for GC
		dispose()
		System.exit(0)
	}
}
