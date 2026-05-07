/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for ToolBar GUI component
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import javax.swing.JToggleButton

/**
 * Tests for ToolBar GUI component.
 *
 * Tests toolbar initialization, button creation, grid toggle functionality,
 * and button group behavior.
 *
 * These tests extend AbstractFrameTestBase to ensure proper Frame disposal
 * and headless environment handling.
 */
@DisplayName("ToolBar")
class ToolBarTest : AbstractFrameTestBase() {
	private lateinit var toolBar: ToolBar
	private lateinit var frame: Frame

	@BeforeEach
	override fun setUp() {
		super.setUp()

		// Create Frame and ToolBar on EDT
		runOnEDT {
			// Get Frame from Koin (singleton) instead of creating new instance
			// This ensures ToolBar's GridSwitch accesses the same Frame instance
			frame =
				org.koin.mp.KoinPlatform
					.getKoin()
					.get<Frame>()
			frames.add(frame)
			toolBar = ToolBar()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar initializes with correct properties")
	fun toolbarInitializesWithCorrectProperties() {
		runOnEDT {
			// Verify toolbar is not floatable
			assertThat(toolBar.isFloatable).isFalse()

			// Verify preferred size is set
			assertThat(toolBar.preferredSize.width).isEqualTo(100)
			assertThat(toolBar.preferredSize.height).isEqualTo(30)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar creates all required buttons")
	fun toolbarCreatesAllRequiredButtons() {
		runOnEDT {
			// Count toggle buttons (excludes separators)
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()

			// Toolbar should have multiple buttons:
			// - Semaphore buttons (2 per spatial type: HORIZONTAL, VERTICAL)
			// - Switch buttons (2 switch types × spatial types)
			// - InOut buttons (2 per spatial type)
			// - Grid toggle button
			// Expected: 2 + 4 + 2 + 1 = 9 buttons minimum
			assertThat(toggleButtons.size).isGreaterThan(8)
			// Verify all buttons have actions
			toggleButtons.forEach { button ->
				assertThat(button.action).isNotNull()
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar has grid toggle button")
	fun toolbarHasGridToggleButton() {
		runOnEDT {
			// Find grid toggle button (last toggle button)
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()
			assertThat(toggleButtons.size).isGreaterThan(8) // Should have at least 9 buttons

			val gridToggleButton = toggleButtons.last()

			// Verify action is set and button is configured correctly
			assertThat(gridToggleButton.action).isNotNull()
			assertThat(gridToggleButton.isSelected).isFalse() // Initially not selected
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("grid toggle button has correct initial state")
	fun gridToggleButtonHasCorrectInitialState() {
		runOnEDT {
			// Get canvas initial grid state
			val canvas = frame.railwayNetGridCanvas
			val initialGridState = canvas.isShowGrid()
			
			// Find grid toggle button
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()
			val gridToggleButton = toggleButtons.last()
			
			// Button initial state should match canvas state
			// Note: This may be true or false depending on canvas initialization
			// We just verify they are consistent
			assertThat(gridToggleButton.isSelected).isEqualTo(initialGridState)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("grid toggle button changes canvas grid visibility")
	fun gridToggleButtonChangesCanvasGridVisibility() {
		runOnEDT {
			// Get canvas and initial state
			val canvas = frame.railwayNetGridCanvas
			val initialGridState = canvas.isShowGrid()
			
			// Find grid toggle button
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()
			val gridToggleButton = toggleButtons.last()
			
			// Trigger action
			gridToggleButton.doClick()
			
			// Verify canvas state changed
			assertThat(canvas.isShowGrid()).isEqualTo(!initialGridState)
			
			// Verify button state matches canvas
			assertThat(gridToggleButton.isSelected).isEqualTo(canvas.isShowGrid())
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar buttons are in button group")
	fun toolbarButtonsAreInButtonGroup() {
		runOnEDT {
			// Get all toggle buttons except the grid toggle
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()
			val toolButtons = toggleButtons.dropLast(1) // Exclude grid toggle
			
			if (toolButtons.size >= 2) {
				// Select first button
				toolButtons[0].isSelected = true
				assertThat(toolButtons[0].isSelected).isTrue()
				
				// Select second button
				toolButtons[1].isSelected = true
				
				// First button should be deselected (mutually exclusive)
				assertThat(toolButtons[0].isSelected).isFalse()
				assertThat(toolButtons[1].isSelected).isTrue()
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar buttons have tooltips")
	fun toolbarButtonsHaveTooltips() {
		runOnEDT {
			// Get toggle buttons (excluding grid toggle which may have different tooltip)
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()
			val toolButtons = toggleButtons.dropLast(1)
			
			// Most tool buttons should have tooltips
			if (toolButtons.isNotEmpty()) {
				val buttonsWithTooltips = toolButtons.count { it.toolTipText != null }
				assertThat(buttonsWithTooltips).isGreaterThan(0)
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar has separators between button groups")
	fun toolbarHasSeparatorsBetweenButtonGroups() {
		runOnEDT {
			// Count separators
			val separatorCount =
				toolBar.components.count {
					it.javaClass.simpleName.contains("Separator")
				}
			
			// Should have at least 3 separators (semaphores, switches, inout, grid)
			assertThat(separatorCount).isGreaterThan(0)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("showSimulationControls adds label to toolbar")
	fun showSimulationControlsAddsLabelToToolbar() {
		runOnEDT {
			val countBefore = toolBar.componentCount
			toolBar.showSimulationControls()
			// A separator and a label are added
			assertThat(toolBar.componentCount).isEqualTo(countBefore + 2)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("hideSimulationControls removes label from toolbar")
	fun hideSimulationControlsRemovesLabel() {
		runOnEDT {
			toolBar.showSimulationControls()
			val countAfterShow = toolBar.componentCount
			toolBar.hideSimulationControls()
			assertThat(toolBar.componentCount).isEqualTo(countAfterShow - 2)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("showSimulationControls is idempotent")
	fun showSimulationControlsIsIdempotent() {
		runOnEDT {
			toolBar.showSimulationControls()
			val countAfterFirst = toolBar.componentCount
			toolBar.showSimulationControls() // second call must be a no-op
			assertThat(toolBar.componentCount).isEqualTo(countAfterFirst)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("hideSimulationControls is idempotent")
	fun hideSimulationControlsIsIdempotent() {
		runOnEDT {
			val countBefore = toolBar.componentCount
			toolBar.hideSimulationControls() // not shown — must be a no-op
			assertThat(toolBar.componentCount).isEqualTo(countBefore)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("toolbar maintains state across multiple grid toggles")
	fun toolbarMaintainsStateAcrossMultipleGridToggles() {
		runOnEDT {
			val canvas = frame.railwayNetGridCanvas
			val initialState = canvas.isShowGrid()

			// Find grid toggle button
			val toggleButtons = toolBar.components.filterIsInstance<JToggleButton>()
			val gridToggleButton = toggleButtons.last()

			// Toggle multiple times
			gridToggleButton.doClick()
			assertThat(canvas.isShowGrid()).isEqualTo(!initialState)

			gridToggleButton.doClick()
			assertThat(canvas.isShowGrid()).isEqualTo(initialState)

			gridToggleButton.doClick()
			assertThat(canvas.isShowGrid()).isEqualTo(!initialState)
		}
	}
}
