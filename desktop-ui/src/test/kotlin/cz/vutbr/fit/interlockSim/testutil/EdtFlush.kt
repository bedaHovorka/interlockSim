/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: draining the Swing event dispatch thread
 */
package cz.vutbr.fit.interlockSim.testutil

import javax.swing.SwingUtilities

/**
 * Flushes the EDT queue by calling [SwingUtilities.invokeAndWait] [times] times.
 *
 * Two flushes (the default) are needed when a background thread fires an event handled by
 * [SwingUtilities.invokeLater]: the first flush dispatches the invokeLater task, and the second
 * ensures any nested EDT work queued by that task has also completed. Seven GUI test classes each
 * carried a private copy of this helper (Issue #1124).
 *
 * Must not be called on the EDT itself — [SwingUtilities.invokeAndWait] throws there.
 */
fun flushEDT(times: Int = 2) {
	repeat(times) { SwingUtilities.invokeAndWait { /* flush */ } }
}
