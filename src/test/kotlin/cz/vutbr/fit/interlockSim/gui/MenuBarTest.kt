/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for MenuBar GUI component
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import javax.swing.JMenu
import javax.swing.JMenuItem

/**
 * Tests for MenuBar GUI component.
 *
 * Tests menu creation, save action, exit action, and info dialogs.
 *
 * These tests extend AbstractFrameTestBase to ensure proper Frame disposal
 * and headless environment handling.
 */
@DisplayName("MenuBar")
class MenuBarTest : AbstractFrameTestBase() {
	private lateinit var menuBar: MenuBar
	private lateinit var frame: Frame

	@BeforeEach
	override fun setUp() {
		super.setUp()

		// Create Frame and MenuBar on EDT
		runOnEDT {
			frame = Frame()
			frames.add(frame)
			menuBar = MenuBar()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("menu bar has correct number of menus")
	fun menuBarHasCorrectNumberOfMenus() {
		runOnEDT {
			// Verify menu bar has 2 menus (File and Help)
			assertThat(menuBar.menuCount).isEqualTo(2)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("menu bar has File menu")
	fun menuBarHasFileMenu() {
		runOnEDT {
			// Get first menu (File)
			val fileMenu = menuBar.getMenu(0) as JMenu
			assertThat(fileMenu.text).isEqualTo("File")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("menu bar has Help menu")
	fun menuBarHasHelpMenu() {
		runOnEDT {
			// Get second menu (Help)
			val helpMenu = menuBar.getMenu(1) as JMenu
			assertThat(helpMenu.text).isEqualTo("Help")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("File menu has Save action")
	fun fileMenuHasSaveAction() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu

			// Get menu items (excluding separators)
			val menuItems =
				(0 until fileMenu.itemCount)
					.map { fileMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			// Find Save action
			val saveItem = menuItems.find { it.text == "Save as..." }
			assertThat(saveItem!!.text).isEqualTo("Save as...")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("File menu has Exit action")
	fun fileMenuHasExitAction() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu

			// Get menu items (excluding separators)
			val menuItems =
				(0 until fileMenu.itemCount)
					.map { fileMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			// Find Exit action
			val exitItem = menuItems.find { it.text == "Exit" }
			assertThat(exitItem!!.text).isEqualTo("Exit")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("File menu has separator")
	fun fileMenuHasSeparator() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu

			// Count separators
			val separatorCount =
				(0 until fileMenu.itemCount)
					.count { fileMenu.getItem(it) == null }

			assertThat(separatorCount).isEqualTo(1)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Help menu has Usage action")
	fun helpMenuHasUsageAction() {
		runOnEDT {
			val helpMenu = menuBar.getMenu(1) as JMenu

			// Get menu items
			val menuItems =
				(0 until helpMenu.itemCount)
					.map { helpMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			// Find Usage action
			val usageItem = menuItems.find { it.text == "Usage" }
			assertThat(usageItem!!.text).isEqualTo("Usage")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Help menu has About action")
	fun helpMenuHasAboutAction() {
		runOnEDT {
			val helpMenu = menuBar.getMenu(1) as JMenu

			// Get menu items
			val menuItems =
				(0 until helpMenu.itemCount)
					.map { helpMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			// Find About action
			val aboutItem = menuItems.find { it.text == "About" }
			assertThat(aboutItem!!.text).isEqualTo("About")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Help menu has exactly 2 items")
	fun helpMenuHasExactlyTwoItems() {
		runOnEDT {
			val helpMenu = menuBar.getMenu(1) as JMenu

			// Get menu items
			val menuItems =
				(0 until helpMenu.itemCount)
					.map { helpMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			assertThat(menuItems).hasSize(2)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Save action has correct text")
	fun saveActionHasCorrectText() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu
			// Changed: Now "Open..." is at index 0, "Save" at index 1, "Save as..." at index 2 (Issue #258, #259)
			val saveAsItem = fileMenu.getItem(2) as JMenuItem

			assertThat(saveAsItem.text).isEqualTo("Save as...")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Exit action has correct text")
	fun exitActionHasCorrectText() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu

			// Get last item (Exit, after separator)
			val menuItems =
				(0 until fileMenu.itemCount)
					.map { fileMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()
			val exitItem = menuItems.last()

			assertThat(exitItem.text).isEqualTo("Exit")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("menu actions are enabled by default")
	fun menuActionsAreEnabledByDefault() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu
			val helpMenu = menuBar.getMenu(1) as JMenu

			// Verify File menu items are enabled
			val fileItems =
				(0 until fileMenu.itemCount)
					.map { fileMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()
			fileItems.forEach { item ->
				assertThat(item.isEnabled).isEqualTo(true)
			}

			// Verify Help menu items are enabled
			val helpItems =
				(0 until helpMenu.itemCount)
					.map { helpMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()
			helpItems.forEach { item ->
				assertThat(item.isEnabled).isEqualTo(true)
			}
		}
	}
}
