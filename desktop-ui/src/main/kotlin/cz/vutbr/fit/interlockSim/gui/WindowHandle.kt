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

import java.awt.Rectangle

/**
 * The window state [WindowLifecycle] needs: the normal-bounds tracking and the close path talk
 * to the frame through this seam, so the headless tests drive the lifecycle on a fake instead of
 * a real [javax.swing.JFrame] (which cannot exist headless) — PR #1430 coverage.
 *
 * [Frame] implements it against its own inherited [java.awt.Component] state.
 */
internal interface WindowHandle {
	/**
	 * The window's current bounds: read while remembering and restored while applying. The
	 * `window` prefix keeps the getters' JVM signatures away from the inherited bean methods.
	 */
	var windowBounds: Rectangle

	/** The window's extended state, read for the maximized and iconified checks. */
	var windowState: Int

	/** Whether the window is currently shown; [WindowLifecycle.rememberNormalBounds] requires it. */
	val windowShowing: Boolean

	/** Releases the window during the teardown exit. */
	fun dispose()
}
