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
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
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
			// Verify menu bar has 3 menus (File, Simulation and Help)
			assertThat(menuBar.menuCount).isEqualTo(3)
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
	@DisplayName("menu bar has Simulation menu")
	fun menuBarHasSimulationMenu() {
		runOnEDT {
			// Simulation is the second menu (index 1)
			val simulationMenu = menuBar.getMenu(1) as JMenu
			assertThat(simulationMenu.text).isEqualTo("Simulation")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("menu bar has Help menu")
	fun menuBarHasHelpMenu() {
		runOnEDT {
			// Help is now the third menu (index 2)
			val helpMenu = menuBar.getMenu(2) as JMenu
			assertThat(helpMenu.text).isEqualTo("Help")
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Simulation menu has Start action")
	fun simulationMenuHasStartAction() {
		runOnEDT {
			val simMenu = menuBar.getMenu(1) as JMenu

			val menuItems =
				(0 until simMenu.itemCount)
					.map { simMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			val startItem = menuItems.find { it.text == "Start..." }
			assertThat(startItem).isNotNull()
			assertThat(startItem!!.isEnabled).isEqualTo(true)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Simulation menu has Stop action")
	fun simulationMenuHasStopAction() {
		runOnEDT {
			val simMenu = menuBar.getMenu(1) as JMenu

			val menuItems =
				(0 until simMenu.itemCount)
					.map { simMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			val stopItem = menuItems.find { it.text == "Stop" }
			assertThat(stopItem).isNotNull()
			assertThat(stopItem!!.isEnabled).isEqualTo(true)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Simulation menu has separator")
	fun simulationMenuHasSeparator() {
		runOnEDT {
			val simMenu = menuBar.getMenu(1) as JMenu

			val separatorCount =
				(0 until simMenu.itemCount)
					.count { simMenu.getItem(it) == null }

			assertThat(separatorCount).isEqualTo(1)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Simulation menu has Speed submenu")
	fun simulationMenuHasSpeedSubmenu() {
		runOnEDT {
			val simMenu = menuBar.getMenu(1) as JMenu

			val speedMenu =
				(0 until simMenu.itemCount)
					.mapNotNull { simMenu.getItem(it) }
					.filterIsInstance<JMenu>()
					.find { it.text == "Speed" }

			assertThat(speedMenu).isNotNull()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Speed submenu has 7 preset items")
	fun speedSubmenuHas7Items() {
		runOnEDT {
			val simMenu = menuBar.getMenu(1) as JMenu
			val speedMenu =
				(0 until simMenu.itemCount)
					.mapNotNull { simMenu.getItem(it) }
					.filterIsInstance<JMenu>()
					.first { it.text == "Speed" }

			val speedItems =
				(0 until speedMenu.itemCount)
					.mapNotNull { speedMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()

			assertThat(speedItems).hasSize(7)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("Speed submenu items have correct labels")
	fun speedSubmenuItemsHaveCorrectLabels() {
		runOnEDT {
			val simMenu = menuBar.getMenu(1) as JMenu
			val speedMenu =
				(0 until simMenu.itemCount)
					.mapNotNull { simMenu.getItem(it) }
					.filterIsInstance<JMenu>()
					.first { it.text == "Speed" }

			val labels =
				(0 until speedMenu.itemCount)
					.mapNotNull { speedMenu.getItem(it) }
					.filterIsInstance<JMenuItem>()
					.map { it.text }

			assertThat(labels).isEqualTo(listOf("0.1x", "0.5x", "1x", "2x", "5x", "10x", "50x"))
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
			assertThat(saveItem).isNotNull()
			assertThat(saveItem!!.text).isEqualTo("Save as...")
			// Verify menu item properties
			assertThat(saveItem.isEnabled).isEqualTo(true)
			assertThat(saveItem.action).isNotNull()
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
			assertThat(exitItem).isNotNull()
			assertThat(exitItem!!.text).isEqualTo("Exit")
			// Verify menu item properties
			assertThat(exitItem.isEnabled).isEqualTo(true)
			assertThat(exitItem.action).isNotNull()
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
			val helpMenu = menuBar.getMenu(2) as JMenu

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
			val helpMenu = menuBar.getMenu(2) as JMenu

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
			val helpMenu = menuBar.getMenu(2) as JMenu

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

	// ── loadSimulationContext ─────────────────────────────────────────────────

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("loadSimulationContext returns a SimulationContext for a valid XML file")
	fun loadSimulationContextWithValidFileReturnsContext() {
		val tempFile = File.createTempFile("vyhybna", ".xml").apply {
			outputStream().use { out -> TestFixtures.loadShuntingXml().copyTo(out) }
			deleteOnExit()
		}
		// Must be called off-EDT (SwingWorker's doInBackground thread)
		val context = menuBar.loadSimulationContext(tempFile)
		assertThat(context).isNotNull()
		context.close()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("loadSimulationContext throws for a non-existent file")
	fun loadSimulationContextWithMissingFileThrows() {
		assertThrows<Exception> {
			menuBar.loadSimulationContext(File("this-file-does-not-exist-9x3f.xml"))
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("menu actions are enabled by default")
	fun menuActionsAreEnabledByDefault() {
		runOnEDT {
			val fileMenu = menuBar.getMenu(0) as JMenu
			val helpMenu = menuBar.getMenu(2) as JMenu

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
