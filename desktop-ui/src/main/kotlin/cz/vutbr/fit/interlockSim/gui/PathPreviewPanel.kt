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

import cz.vutbr.fit.interlockSim.context.ContextChangeListener
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.paths.Route
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Path preview panel for the editor.
 *
 * Allows operators to select a source and target [InOut] element and preview
 * candidate routes before committing a reservation. Uses the static
 * [cz.vutbr.fit.interlockSim.context.RouteFinder] exposed by [EditingContext]
 * so the preview never touches dynamic simulation state.
 *
 * ## Layout
 *
 * ```
 * ┌─────────────────────────────────────────────────────────┐
 * │ From: [combo]  To: [combo]  [Find]  [Clear]             │
 * ├─────────────────────────────────────────────────────────┤
 * │ Route 1 – cost 3.0 – length 1500 m  ← primary (blue)   │
 * │ Route 2 – cost 5.0 – length 2800 m  ← alternative      │
 * │ …                                                       │
 * └─────────────────────────────────────────────────────────┘
 * ```
 *
 * Selecting a route in the list calls [onRouteSelected] so the canvas can
 * redraw the corresponding highlight. Clicking **Clear** calls [onClear] and
 * removes the highlight.
 *
 * ## Thread Safety
 *
 * All public methods must be called from the EDT.
 *
 * @since Issue #596 (Goal 2 SP4)
 */
class PathPreviewPanel :
	JPanel(),
	ContextPropertyChangeListener {
	private val logger = KotlinLogging.logger {}

	/** Currently bound editing context (may be null when no context is loaded). */
	private var editingContext: EditingContext? = null

	/** Source InOut combo box model. */
	private val fromModel = DefaultComboBoxModel<InOut>()

	/** Target InOut combo box model. */
	private val toModel = DefaultComboBoxModel<InOut>()

	/** Source InOut combo box. */
	private val fromCombo = JComboBox(fromModel)

	/** Target InOut combo box. */
	private val toCombo = JComboBox(toModel)

	/** Route list model, populated after a Find operation. */
	private val routeListModel = DefaultListModel<String>()

	/** Route list component. */
	private val routeList = JList(routeListModel)

	/** Find button. */
	private val findButton = JButton("Find")

	/** Clear button. */
	private val clearButton = JButton("Clear")

	/** Status label showing result count or error. */
	private val statusLabel = JLabel(" ")

	/** Cached routes from the last successful Find operation. */
	private var foundRoutes: List<Route> = emptyList()

	/**
	 * Callback invoked when the user selects a route from the list.
	 *
	 * The callback receives the list of all found routes and the index of the
	 * selected one. The canvas uses this to update the path highlight overlay.
	 *
	 * Set by [Frame] during initialization.
	 */
	var onRouteSelected: ((routes: List<Route>, selectedIndex: Int) -> Unit)? = null

	/**
	 * Callback invoked when the user clicks Clear or the panel is reset.
	 *
	 * The canvas uses this to remove the path highlight overlay.
	 *
	 * Set by [Frame] during initialization.
	 */
	var onClear: (() -> Unit)? = null

	init {
		layout = BorderLayout()
		border = BorderFactory.createEtchedBorder()

		// ── Top bar: source, target selectors and action buttons ──────────────
		val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
		topBar.add(JLabel("From:"))
		fromCombo.preferredSize = fromCombo.preferredSize.apply { width = 100 }
		topBar.add(fromCombo)
		topBar.add(JLabel("To:"))
		toCombo.preferredSize = toCombo.preferredSize.apply { width = 100 }
		topBar.add(toCombo)
		topBar.add(findButton)
		topBar.add(clearButton)
		topBar.add(statusLabel)
		add(topBar, BorderLayout.NORTH)

		// ── Route list ────────────────────────────────────────────────────────
		routeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
		routeList.visibleRowCount = 3
		val scrollPane = JScrollPane(routeList)
		scrollPane.preferredSize = Dimension(scrollPane.preferredSize.width, 60)
		add(scrollPane, BorderLayout.CENTER)

		// ── Button actions ────────────────────────────────────────────────────
		findButton.addActionListener { onFindClicked() }
		clearButton.addActionListener { onClearClicked() }

		// ── Route list selection listener ─────────────────────────────────────
		routeList.addListSelectionListener { e ->
			if (!e.valueIsAdjusting) {
				val idx = routeList.selectedIndex
				if (idx >= 0 && idx < foundRoutes.size) {
					onRouteSelected?.invoke(foundRoutes, idx)
				}
			}
		}

		// Initial state: buttons enabled only when context is set
		findButton.isEnabled = false
		clearButton.isEnabled = false
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Public API
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Bind this panel to the given [EditingContext].
	 *
	 * Updates the combo boxes with the context's current InOut list and registers
	 * a property-change listener to keep them in sync when cells are added or removed.
	 *
	 * Must be called from the EDT.
	 *
	 * @param ctx the editing context to bind, or null to clear the binding
	 */
	fun setEditingContext(ctx: EditingContext?) {
		val old = editingContext
		if (old != null) {
			old.removePropertyChangeListener(this)
		}
		editingContext = ctx
		if (ctx != null) {
			ctx.addPropertyChangeListener(this)
			refreshInOutCombos(ctx)
			findButton.isEnabled = true
			clearButton.isEnabled = true
		} else {
			fromModel.removeAllElements()
			toModel.removeAllElements()
			findButton.isEnabled = false
			clearButton.isEnabled = false
		}
		clearResults()
	}

	// ──────────────────────────────────────────────────────────────────────────
	// ContextPropertyChangeListener
	// ──────────────────────────────────────────────────────────────────────────

	override fun propertyChange(event: ContextChangeEvent) {
		// Refresh combos whenever cells are added/removed or modified
		when (event.propertyName) {
			ContextChangeListener.CELL_ADDED,
			ContextChangeListener.CELL_REMOVED,
			ContextChangeListener.CELL_MODIFIED -> {
				val ctx = editingContext ?: return
				SwingUtilities.invokeLater { refreshInOutCombos(ctx) }
			}
			else -> { /* other events not relevant */ }
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Private helpers
	// ──────────────────────────────────────────────────────────────────────────

	private fun onFindClicked() {
		val ctx = editingContext ?: return
		val from = fromCombo.selectedItem as? InOut
		val to = toCombo.selectedItem as? InOut

		if (from == null || to == null) {
			statusLabel.text = "Select source and target first."
			return
		}

		val routes =
			try {
				ctx.getRouteFinder().findRoutes(from, to, ctx)
			} catch (e: Exception) {
				logger.warn(e) { "Path finding failed" }
				statusLabel.text = "Path finding error."
				return
			}

		foundRoutes = routes
		routeListModel.clear()

		if (routes.isEmpty()) {
			statusLabel.text = "No path found."
			clearButton.isEnabled = true
			onClear?.invoke()
			return
		}

		routes.forEachIndexed { index, route ->
			val label =
				"Route %d  –  cost %.1f  –  length %.0f m  –  %d elements"
					.format(index + 1, route.cost, route.totalLength, route.elementCount)
			routeListModel.addElement(label)
		}

		statusLabel.text = "${routes.size} route(s) found."
		clearButton.isEnabled = true

		// Auto-select primary (cheapest) route
		routeList.selectedIndex = 0
		onRouteSelected?.invoke(foundRoutes, 0)

		logger.debug { "Path preview: found ${routes.size} route(s) from '${from.getName()}' to '${to.getName()}'" }
	}

	private fun onClearClicked() {
		clearResults()
		onClear?.invoke()
	}

	private fun clearResults() {
		foundRoutes = emptyList()
		routeListModel.clear()
		statusLabel.text = " "
		routeList.clearSelection()
	}

	private fun refreshInOutCombos(ctx: EditingContext) {
		val inOuts = ctx.getInOuts()
		val prevFrom = fromCombo.selectedItem
		val prevTo = toCombo.selectedItem

		fromModel.removeAllElements()
		toModel.removeAllElements()
		inOuts.forEach { io ->
			fromModel.addElement(io)
			toModel.addElement(io)
		}

		// Restore previous selection if still valid
		if (prevFrom != null && inOuts.contains(prevFrom)) {
			fromCombo.selectedItem = prevFrom
		} else if (inOuts.isNotEmpty()) {
			fromCombo.selectedIndex = 0
		}

		if (prevTo != null && inOuts.contains(prevTo)) {
			toCombo.selectedItem = prevTo
		} else if (inOuts.size > 1) {
			toCombo.selectedIndex = 1
		} else if (inOuts.isNotEmpty()) {
			toCombo.selectedIndex = 0
		}

		// Clear stale results when topology changes
		clearResults()
		onClear?.invoke()
	}
}
