package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.testutil.throwawayWindowPrefsNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.util.prefs.Preferences

/**
 * Tests for [WindowStateStore] (Issue #1421). Every test uses its own throwaway preferences node, so
 * the user's real window settings are never read or written.
 */
@DisplayName("WindowStateStore")
class WindowStateStoreTest {
	private lateinit var node: Preferences
	private lateinit var store: WindowStateStore

	@BeforeEach
	fun setUp() {
		node = throwawayWindowPrefsNode()
		store = WindowStateStore(node)
	}

	@AfterEach
	fun tearDown() {
		node.removeNode()
	}

	@Test
	@DisplayName("an empty node loads nothing")
	fun emptyNodeLoadsNothing() {
		assertThat(store.load()).isNull()
	}

	@Test
	@DisplayName("a saved state loads back unchanged")
	fun savedStateRoundTrips() {
		val state = WindowStateStore.WindowState(Rectangle(10, 20, 1300, 950), maximized = true, bottomHeight = 240)

		store.save(state)

		assertThat(store.load()).isEqualTo(state)
	}

	@Test
	@DisplayName("a saved state without a bottom height keeps it unknown")
	fun missingBottomHeightStaysUnknown() {
		store.save(WindowStateStore.WindowState(Rectangle(0, 0, 1100, 850), maximized = false, bottomHeight = null))

		assertThat(store.load()?.bottomHeight).isNull()
	}

	@Test
	@DisplayName("a node with broken size values loads nothing")
	fun brokenSizeLoadsNothing() {
		store.save(WindowStateStore.WindowState(Rectangle(0, 0, 1100, 850), maximized = false, bottomHeight = 500))
		node.putInt("width", -5)

		assertThat(store.load()).isNull()
	}
}
