package cz.vutbr.fit.interlockSim.gui

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Rectangle
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

private val logger = KotlinLogging.logger {}

/**
 * Remembers the main window's bounds, its maximized state and the height of the tab area under the
 * split bar between runs (Issue #1421), in the [java.util.prefs] node [node]. A height, not a bar
 * position, so it stays right when the window opens maximized at another size.
 *
 * The node is a constructor argument so tests use a throwaway node and never touch the user's real
 * settings. Production uses [defaultNode]. A failing preferences backend is logged and ignored: the
 * window then simply starts at [WindowGeometry.initialBounds].
 */
internal class WindowStateStore(
	private val node: Preferences = defaultNode()
) {
	/**
	 * One saved window state. [bottomHeight] is the height of the tab area under the split bar,
	 * `null` when none was saved.
	 */
	data class WindowState(
		val bounds: Rectangle,
		val maximized: Boolean,
		val bottomHeight: Int?
	)

	/** The saved state, or `null` when nothing (or nothing valid) was saved. */
	fun load(): WindowState? {
		val width = node.getInt(KEY_WIDTH, -1)
		val height = node.getInt(KEY_HEIGHT, -1)
		if (width <= 0 || height <= 0) return null
		val bottomHeight = node.getInt(KEY_BOTTOM_HEIGHT, -1)
		return WindowState(
			bounds = Rectangle(node.getInt(KEY_X, 0), node.getInt(KEY_Y, 0), width, height),
			maximized = node.getBoolean(KEY_MAXIMIZED, false),
			bottomHeight = bottomHeight.takeIf { it >= 0 }
		)
	}

	/** Saves [state] and flushes it, so it survives the `System.exit` that follows a window close. */
	fun save(state: WindowState) {
		node.putInt(KEY_X, state.bounds.x)
		node.putInt(KEY_Y, state.bounds.y)
		node.putInt(KEY_WIDTH, state.bounds.width)
		node.putInt(KEY_HEIGHT, state.bounds.height)
		node.putBoolean(KEY_MAXIMIZED, state.maximized)
		if (state.bottomHeight != null) {
			node.putInt(KEY_BOTTOM_HEIGHT, state.bottomHeight)
		} else {
			node.remove(KEY_BOTTOM_HEIGHT)
		}
		try {
			node.flush()
		} catch (e: BackingStoreException) {
			logger.warn(e) { "Could not save the main window layout; the next start uses the default size" }
		}
	}

	companion object {
		private const val KEY_X = "x"
		private const val KEY_Y = "y"
		private const val KEY_WIDTH = "width"
		private const val KEY_HEIGHT = "height"
		private const val KEY_MAXIMIZED = "maximized"
		private const val KEY_BOTTOM_HEIGHT = "bottomHeight"

		/** The user node of the GUI package: `cz/vutbr/fit/interlockSim/gui`. */
		fun defaultNode(): Preferences = Preferences.userNodeForPackage(WindowStateStore::class.java)
	}
}
