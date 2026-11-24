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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.testutil.throwawayWindowPrefsNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.awt.Rectangle
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/**
 * The close path and the geometry restore of [WindowLifecycle] (PR #1430 review) on the
 * [WindowHandle] seam, headless: a clean close tears down, saves the window state and exits
 * through the injected [WindowLifecycle.exit] seam, so the test watches the terminal call
 * instead of leaving the test worker (the real implementation is `System.exit`, which would
 * end the JVM).
 *
 * The dirty branch shows the modal unsaved-changes dialog — not automatable here without a
 * robot — so only the clean close is driven. The store rules live in [WindowStateStoreTest];
 * the split-pane collapse lives in [BottomPaneTest].
 */
@DisplayName("WindowLifecycle: close path and restored geometry (headless)")
class WindowLifecycleTest {
	private val prefNodes = mutableListOf<Preferences>()

	@AfterEach
	fun tearDown() {
		prefNodes.forEach { node -> runCatching { node.removeNode() } }
		prefNodes.clear()
	}

	/** A throwaway store node, registered so the teardown removes it. */
	private fun newStore(): WindowStateStore = WindowStateStore(throwawayWindowPrefsNode().also { prefNodes.add(it) })

	/** Records every call on the window the lifecycle drives, deterministic in a plain class. */
	private class FakeHandle(
		initial: Rectangle
	) : WindowHandle {
		private val current = Rectangle(initial)
		val setBoundsCalls = mutableListOf<Rectangle>()
		var disposed = false
			private set

		override var windowBounds: Rectangle
			get() = Rectangle(current)
			set(value) {
				setBoundsCalls.add(Rectangle(value))
				current.setBounds(value)
			}

		override var windowState: Int = 0

		override val windowShowing: Boolean = true

		override fun dispose() {
			disposed = true
		}
	}

	private fun newBottomPane(): BottomPane = BottomPane(RailwayNetGridCanvas(), PathPreviewPanel(), WarningPanel())

	private fun newLifecycle(
		handle: WindowHandle,
		store: WindowStateStore? = null,
		bottomPane: BottomPane = newBottomPane(),
		teardown: () -> Unit = {}
	): WindowLifecycle =
		WindowLifecycle(
			handle = handle,
			store = store,
			bottomPane = bottomPane,
			dialogParent = null,
			isDirty = { false },
			save = { false },
			teardown = teardown
		)

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a clean close tears down, saves the window state, disposes and exits through the seam")
	fun cleanCloseTearsDownSavesAndExits() {
		val events = mutableListOf<String>()
		val handle = FakeHandle(Rectangle(10, 10, 1600, 900))
		val node = throwawayWindowPrefsNode().also { prefNodes.add(it) }
		val store = WindowStateStore(node)

		SwingUtilities.invokeAndWait {
			val lifecycle = newLifecycle(handle, store, teardown = { events.add("teardown") })
			lifecycle.exit = { code -> events.add("exit:$code") }
			lifecycle.requestClose()
		}

		assertThat(events.joinToString(",")).isEqualTo("teardown,exit:0")
		assertThat(handle.disposed).isTrue()
		// The saved layout carries the handle's bounds through the real store.
		assertThat(node.getInt("width", -1)).isEqualTo(1600)
		assertThat(node.getInt("height", -1)).isEqualTo(900)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the saved layout round trips through the store")
	fun windowStateRoundTripsThroughTheStore() {
		val handle = FakeHandle(Rectangle(10, 20, 800, 600))
		val bottomPane = newBottomPane()
		val store = newStore()

		SwingUtilities.invokeAndWait {
			val lifecycle = newLifecycle(handle, store, bottomPane)
			bottomPane.splitPane.setSize(800, 600)
			bottomPane.setRestoredBottomHeight(null, 600)
			bottomPane.splitPane.doLayout()
			lifecycle.saveWindowState()
		}

		val restoredHandle = FakeHandle(Rectangle(0, 0, 100, 100))
		val restoredBottomPane = newBottomPane()

		SwingUtilities.invokeAndWait {
			newLifecycle(restoredHandle, store, restoredBottomPane).applyInitialGeometry()
		}

		assertThat(restoredHandle.disposed).isFalse()
		assertThat(restoredHandle.setBoundsCalls).isEqualTo(listOf(Rectangle(10, 20, 800, 600)))
		assertThat(restoredBottomPane.bottomTabs.preferredSize.height).isGreaterThan(0)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a restored tab-area height is clamped so the canvas keeps its minimum")
	fun restoredBottomHeightClampsToTheRestoredWindow() {
		val node = throwawayWindowPrefsNode().also { prefNodes.add(it) }
		node.putInt("x", 10)
		node.putInt("y", 20)
		node.putInt("width", 800)
		node.putInt("height", 900)
		node.putInt("bottomHeight", 900)
		node.putBoolean("maximized", false)
		val handle = FakeHandle(Rectangle(0, 0, 100, 100))
		val bottomPane = newBottomPane()

		SwingUtilities.invokeAndWait {
			newLifecycle(handle, WindowStateStore(node), bottomPane).applyInitialGeometry()
		}

		val restored = bottomPane.bottomTabs.preferredSize.height
		assertThat(restored).isGreaterThan(BottomPane.MIN_BOTTOM_HEIGHT - 1)
		assertThat(restored).isLessThanOrEqualTo(900 - BottomPane.MIN_CANVAS_HEIGHT - BottomPane.MIN_BOTTOM_HEIGHT)
		assertThat(handle.setBoundsCalls).isEqualTo(listOf(Rectangle(10, 20, 800, 900)))
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a restore with no saved height uses the mode share of the window, not the flat default")
	fun restoredWithoutSavedHeightUsesTheModeShare() {
		val node = throwawayWindowPrefsNode().also { prefNodes.add(it) }
		node.putInt("x", 10)
		node.putInt("y", 20)
		node.putInt("width", 800)
		node.putInt("height", 1000)
		node.putBoolean("maximized", false)
		val handle = FakeHandle(Rectangle(0, 0, 100, 100))
		val bottomPane = newBottomPane()

		SwingUtilities.invokeAndWait {
			newLifecycle(handle, WindowStateStore(node), bottomPane).applyInitialGeometry()
		}

		assertThat(bottomPane.bottomTabs.preferredSize.height).isEqualTo(BottomPane.defaultBottomHeight(1000))
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a maximized window saves its last normal bounds and the maximized flag")
	fun maximizedWindowSavesLastNormalBounds() {
		val node = throwawayWindowPrefsNode().also { prefNodes.add(it) }
		val handle = FakeHandle(Rectangle(7, 8, 900, 600))
		val lifecycle = newLifecycle(handle, WindowStateStore(node))

		SwingUtilities.invokeAndWait {
			// Pre-maximize the window's normal bounds; the maximize step itself is not headless.
			lifecycle.rememberNormalBounds()
			handle.windowState = java.awt.Frame.MAXIMIZED_BOTH
			handle.windowBounds = Rectangle(0, 0, 4000, 2400)
			// The second remember runs while maximized and must record nothing.
			lifecycle.rememberNormalBounds()
			lifecycle.saveWindowState()
		}

		assertThat(node.getBoolean("maximized", false)).isTrue()
		assertThat(node.getInt("width", -1)).isEqualTo(900)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the store keeps no height key after a collapsed save")
	fun collapsedTabAreaRemovesTheSavedHeightKey() {
		val node = throwawayWindowPrefsNode().also { prefNodes.add(it) }
		val handle = FakeHandle(Rectangle(10, 10, 1600, 900))
		val bottomPane = newBottomPane()
		val lifecycle = newLifecycle(handle, WindowStateStore(node), bottomPane)

		SwingUtilities.invokeAndWait {
			bottomPane.splitPane.setSize(1600, 900)
			bottomPane.splitPane.doLayout()
			bottomPane.splitPane.dividerLocation = bottomPane.splitPane.height - bottomPane.splitPane.dividerSize
			bottomPane.splitPane.doLayout()
			assertThat(bottomPane.rememberBottomHeight()).isNull()
			lifecycle.saveWindowState()
		}

		assertThat(node.getInt("bottomHeight", -1)).isEqualTo(-1)
	}
}
