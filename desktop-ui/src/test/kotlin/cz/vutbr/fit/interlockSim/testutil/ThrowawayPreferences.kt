/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import java.util.UUID
import java.util.prefs.Preferences

/**
 * A fresh throwaway preferences node for window-state tests (PR #1430 cleanups): the tests that
 * persist the main window's layout never touch the user's real settings, so the store tests give
 * them a node of their own. The caller owns the cleanup: remove the node in the test's teardown
 * (`node.removeNode()`, wrapped for [java.util.prefs.BackingStoreException]) or every run orphans
 * a node under the user root.
 */
fun throwawayWindowPrefsNode(): Preferences =
	Preferences.userRoot().node("interlockSim-test/window-${UUID.randomUUID()}")
