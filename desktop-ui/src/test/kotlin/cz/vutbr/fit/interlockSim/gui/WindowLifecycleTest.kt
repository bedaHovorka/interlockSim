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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * The close path and the geometry restore of [WindowLifecycle] (PR #1430 review). A clean close
 * tears down, saves the window state and exits through the injected [WindowLifecycle.exit] seam,
 * so the test watches the terminal call instead of leaving the test worker (the real
 * implementation is `System.exit`, which would end the JVM).
 *
 * The dirty branch shows the modal unsaved-changes dialog — not automatable here without a robot —
 * so only the clean close is driven. The store rules live in [WindowStateStoreTest].
 */
@Tag("integration-test")
@DisplayName("WindowLifecycle: close path and restored geometry")
class WindowLifecycleTest {
	private val windows = mutableListOf<JFrame>()

	@BeforeEach
	fun setUp() {
		// JFrame needs an X11 display; the message matches the frame suites' skip text
		assumeFalse(
			GraphicsEnvironment.isHeadless(),
			"Skipping WindowLifecycle test - headless environment (no X11 display)"
		)
	}

	@AfterEach
	fun tearDown() {
		SwingUtilities.invokeAndWait {
			windows.forEach { frame -> if (frame.isDisplayable) frame.dispose() }
		}
		windows.clear()
	}

	/** Creates a JFrame parked offscreen (registered for disposal) and records it. */
	private fun newHiddenFrame(): JFrame {
		val frameRef =
			java.util.concurrent.atomic
				.AtomicReference<JFrame>()
		SwingUtilities.invokeAndWait {
			frameRef.set(
				JFrame("WindowLifecycleTest").apply {
					setSize(400, 300)
					setLocation(OFFSCREEN_X, OFFSCREEN_X) // parked so nothing flashes on the user's display
				}
			)
		}
		val frame = frameRef.get()
		windows.add(frame)
		return frame
	}

	private fun newBottomPane(): BottomPane = BottomPane(RailwayNetGridCanvas(), PathPreviewPanel(), WarningPanel())

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a clean close tears down, saves the window state and exits through the seam")
	fun cleanCloseTearsDownSavesAndExits() {
		val events = mutableListOf<String>()
		val frame = newHiddenFrame()
		val store = mockk<WindowStateStore>(relaxed = true)
		every { store.save(any()) } answers { events.add("save") }

		SwingUtilities.invokeAndWait {
			val lifecycle =
				WindowLifecycle(
					frame = frame,
					store = store,
					bottomPane = newBottomPane(),
					isDirty = { false },
					save = { false },
					teardown = { events.add("teardown") }
				)
			lifecycle.exit = { code -> events.add("exit:$code") }
			lifecycle.requestClose()
			events.add("displayable:" + frame.isDisplayable)
		}

		assertThat(events.joinToString(",")).isEqualTo("teardown,save,exit:0,displayable:false")
		verify(exactly = 1) { store.save(any()) }
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the saved layout round trips through the store")
	fun windowStateRoundTripsThroughTheStore() {
		val node = throwawayNode()
		val bottomPane = newBottomPane()
		val frame = newHiddenFrame()

		SwingUtilities.invokeAndWait {
			val lifecycle =
				WindowLifecycle(
					frame = frame,
					store = WindowStateStore(node),
					bottomPane = bottomPane,
					isDirty = { false },
					save = { false },
					teardown = {}
				)
			frame.bounds = Rectangle(10, 20, 800, 600)
			bottomPane.splitPane.setSize(800, 600)
			bottomPane.setInitialBottomHeight(600)
			bottomPane.splitPane.doLayout()
			lifecycle.saveWindowState()
		}

		val restoredBottomPane = newBottomPane()
		val restoredFrame = newHiddenFrame()
		SwingUtilities.invokeAndWait {
			val restored =
				WindowLifecycle(
					frame = restoredFrame,
					store = WindowStateStore(node),
					bottomPane = restoredBottomPane,
					isDirty = { false },
					save = { false },
					teardown = {}
				)
			restored.applyInitialGeometry()
		}
		assertThat(restoredFrame.bounds).isEqualTo(Rectangle(10, 20, 800, 600))
		assertThat(restoredBottomPane.bottomTabs.preferredSize.height > 0).isTrue()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a collapsed tab area is saved with no height key, so a fresh start uses the mode share")
	fun collapsedTabAreaRemovesTheSavedHeightKey() {
		val node = throwawayNode()
		val frame = newHiddenFrame()
		val bottomPane = newBottomPane()

		SwingUtilities.invokeAndWait {
			val lifecycle =
				WindowLifecycle(
					frame = frame,
					store = WindowStateStore(node),
					bottomPane = bottomPane,
					isDirty = { false },
					save = { false },
					teardown = {}
				)
			bottomPane.splitPane.setSize(1600, 900)
			bottomPane.splitPane.doLayout()
			// The user folded the tab area up: the divider sits below its last open position.
			bottomPane.splitPane.dividerLocation = bottomPane.splitPane.height - bottomPane.splitPane.dividerSize
			bottomPane.splitPane.doLayout()
			assertThat(bottomPane.rememberBottomHeight()).isNull()
			lifecycle.saveWindowState()
		}

		assertThat(node.getInt("bottomHeight", -1)).isEqualTo(-1)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a restored tab-area height is clamped so the canvas keeps its minimum")
	fun restoredBottomHeightClampsToTheRestoredWindow() {
		val node = throwawayNode()
		node.putInt("x", 10)
		node.putInt("y", 20)
		node.putInt("width", 800)
		node.putInt("height", 900)
		node.putInt("bottomHeight", 900)
		node.putBoolean("maximized", false)
		val frame = newHiddenFrame()
		val bottomPane = newBottomPane()

		SwingUtilities.invokeAndWait {
			val lifecycle =
				WindowLifecycle(
					frame = frame,
					store = WindowStateStore(node),
					bottomPane = bottomPane,
					isDirty = { false },
					save = { false },
					teardown = {}
				)
			lifecycle.applyInitialGeometry()
		}

		val restored = bottomPane.bottomTabs.preferredSize.height
		assertThat(restored).isGreaterThanOrEqualTo(BottomPane.MIN_BOTTOM_HEIGHT)
		assertThat(restored).isLessThanOrEqualTo(900 - BottomPane.MIN_CANVAS_HEIGHT - BottomPane.MIN_BOTTOM_HEIGHT)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a restore with no saved height uses the mode share of the window, not the flat default")
	fun restoredWithoutSavedHeightUsesTheModeShare() {
		val node = throwawayNode()
		node.putInt("x", 10)
		node.putInt("y", 20)
		node.putInt("width", 800)
		node.putInt("height", 1000)
		node.putBoolean("maximized", false)
		val frame = newHiddenFrame()
		val bottomPane = newBottomPane()

		SwingUtilities.invokeAndWait {
			val lifecycle =
				WindowLifecycle(
					frame = frame,
					store = WindowStateStore(node),
					bottomPane = bottomPane,
					isDirty = { false },
					save = { false },
					teardown = {}
				)
			lifecycle.applyInitialGeometry()
		}

		assertThat(bottomPane.bottomTabs.preferredSize.height).isEqualTo(BottomPane.defaultBottomHeight(1000))
	}

	/** A fresh throwaway preferences node (the same shape the store tests use). */
	private fun throwawayNode() = Preferences.userRoot().node("interlockSim-test/window-${UUID.randomUUID()}")

	private companion object {
		/** Offscreen position no real display reaches, so a probe window never flashes. */
		const val OFFSCREEN_X = 40000
	}
}
