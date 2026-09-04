/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for Frame GUI component
	Phase 4: GUI and Simulation Tests - 2026
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.PROGRAM_LLM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.gui.warning.WarningPanel
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.awt.BorderLayout
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JScrollPane

/**
 * Tests for Frame GUI component.
 *
 * Tests frame initialization, component layout, context setting,
 * and component listener registration.
 *
 * These tests extend AbstractFrameTestBase to ensure proper Frame disposal
 * and headless environment handling.
 */
@DisplayName("Frame")
class FrameTest : AbstractFrameTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private lateinit var frame: Frame

	@BeforeEach
	override fun setUp() {
		super.setUp()

		// Create Frame on EDT
		runOnEDT {
			frame = Frame()
			frames.add(frame)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has correct title")
	fun frameHasCorrectTitle() {
		runOnEDT {
			assertThat(frame.title).isEqualTo(PROGRAM_FULL_NAME)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("appTitle replaces the base window title")
	fun appTitleReplacesBaseWindowTitle() {
		// Issue #839: LLM-driven runs show a different base title.
		runOnEDT {
			frame.appTitle = PROGRAM_LLM_FULL_NAME

			assertThat(frame.title).isEqualTo(PROGRAM_LLM_FULL_NAME)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("appTitle defaults to the plain program title")
	fun appTitleDefaultsToPlainProgramTitle() {
		runOnEDT {
			assertThat(frame.appTitle).isEqualTo(PROGRAM_FULL_NAME)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has correct initial size")
	fun frameHasCorrectInitialSize() {
		runOnEDT {
			assertThat(frame.width).isEqualTo(1024)
			// Height reserves room for the DispatcherControlPanel line so station tracks stay
			// visible (PR #801 review comment, Issue #561).
			assertThat(frame.height).isEqualTo(818)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has correct default close operation")
	fun frameHasCorrectDefaultCloseOperation() {
		runOnEDT {
			// Changed to DO_NOTHING_ON_CLOSE to allow unsaved changes handling (Issue #259)
			assertThat(frame.defaultCloseOperation).isEqualTo(JFrame.DO_NOTHING_ON_CLOSE)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame uses BorderLayout")
	fun frameUsesBorderLayout() {
		runOnEDT {
			assertThat(frame.contentPane.layout).isInstanceOf(BorderLayout::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has menu bar")
	fun frameHasMenuBar() {
		runOnEDT {
			assertThat(frame.jMenuBar).isNotNull()
			assertThat(frame.jMenuBar).isInstanceOf(MenuBar::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has railway net grid canvas")
	fun frameHasRailwayNetGridCanvas() {
		runOnEDT {
			assertThat(frame.railwayNetGridCanvas).isNotNull()
			assertThat(frame.railwayNetGridCanvas).isInstanceOf(RailwayNetGridCanvas::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame canvas is in scroll pane")
	fun frameCanvasIsInScrollPane() {
		runOnEDT {
			// Get center component (should be JScrollPane)
			val centerComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
			assertThat(centerComponent).isNotNull()
			assertThat(centerComponent).isInstanceOf(JScrollPane::class)

			// Verify canvas is in scroll pane
			val scrollPane = centerComponent as JScrollPane
			assertThat(scrollPane.viewport.view).isInstanceOf(RailwayNetGridCanvas::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has toolbar container at north")
	fun frameHasToolbarContainerAtNorth() {
		runOnEDT {
			// North component is now a JPanel containing ToolBar + ControlPanel + SimulationControlPanel
			// (Issues #205, #190) + DispatcherControlPanel (Issue #561, Goal 10 SP2b.6)
			val northComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
			assertThat(northComponent).isNotNull()
			assertThat(northComponent).isInstanceOf(javax.swing.JPanel::class)

			// Verify the container has components (ToolBar, ControlPanel, SimulationControlPanel, DispatcherControlPanel)
			val panel = northComponent as javax.swing.JPanel
			assertThat(panel.componentCount).isEqualTo(4)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has status bar in south panel")
	fun frameHasStatusBarAtSouth() {
		runOnEDT {
			// SOUTH now contains a southPanel (JPanel) that wraps StatusBar and EventTimelinePanel
			val southComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH)
			assertThat(southComponent).isNotNull()
			assertThat(southComponent).isInstanceOf(javax.swing.JPanel::class)
			// StatusBar must be accessible and correctly initialised
			assertThat(frame.statusBar).isInstanceOf(StatusBar::class)
			assertThat(frame.statusBar.isVisible).isTrue()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setContext updates canvas context")
	fun setContextUpdatesCanvasContext() {
		runOnEDT {
			// Create a context
			editingContextFactory.createEmptyContext().use { context ->
				// Set context on frame
				frame.setContext(context)

				// Verify canvas received the context
				val canvasContext = frame.railwayNetGridCanvas.getEditingContext()
				assertThat(canvasContext).isNotNull()
				assertThat(canvasContext).isInstanceOf(EditingContext::class)
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setContext registers status bar as property change listener")
	fun setContextRegistersStatusBarAsPropertyChangeListener() {
		runOnEDT {
			// Create a context
			editingContextFactory.createEmptyContext().use { context ->
				// Create a test listener to verify registration works
				val testListener =
					ContextPropertyChangeListener { _ -> }

				// Add test listener to verify the mechanism works
				context.addPropertyChangeListener(testListener)

				// Set context on frame (should register status bar as listener)
				frame.setContext(context)

				// Verify the context accepts property change listeners
				// (The actual registration is internal to Frame, we just verify no exception)
				context.removePropertyChangeListener(testListener)
			}
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has component listener for resize handling")
	fun frameHasComponentListenerForResizeHandling() {
		runOnEDT {
			// Verify frame has component listeners
			val listeners = frame.componentListeners
			assertThat(listeners.size).isEqualTo(1)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame layout is consistent")
	fun frameLayoutIsConsistent() {
		runOnEDT {
			val layout = frame.contentPane.layout as BorderLayout

			// Verify all expected components are present
			assertThat(layout.getLayoutComponent(BorderLayout.CENTER)).isNotNull()
			assertThat(layout.getLayoutComponent(BorderLayout.NORTH)).isNotNull()
			assertThat(layout.getLayoutComponent(BorderLayout.SOUTH)).isNotNull()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("south panel has PathPreviewPanel and StatusBar in editing mode")
	fun southPanelHasPathPreviewAndStatusBarInEditingMode() {
		runOnEDT {
			// Default state after Frame construction is editing mode — PathPreviewPanel + StatusBar
			val southPanel =
				(frame.contentPane.layout as BorderLayout)
					.getLayoutComponent(BorderLayout.SOUTH) as javax.swing.JPanel
			assertThat(southPanel.componentCount).isEqualTo(2)
			assertThat(southPanel.getComponent(0)).isInstanceOf(PathPreviewPanel::class)
			assertThat((southPanel.getComponent(0) as PathPreviewPanel).isVisible).isTrue()
			assertThat(southPanel.getComponent(1)).isInstanceOf(StatusBar::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("south panel gains EventTimelinePanel when switching to simulation mode")
	fun southPanelGainsTimelinePanelInSimulationMode() {
		val context =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context)
			// Simulation mode: WarningPanel and EventTimelinePanel are added above
			// PathPreviewPanel and StatusBar (Goal 3 SP6, Issue #616)
			val southPanel =
				(frame.contentPane.layout as BorderLayout)
					.getLayoutComponent(BorderLayout.SOUTH) as javax.swing.JPanel
			assertThat(southPanel.componentCount).isEqualTo(4)
			assertThat(southPanel.getComponent(0)).isInstanceOf(WarningPanel::class)
			assertThat((southPanel.getComponent(0) as WarningPanel).isVisible).isTrue()
			assertThat(southPanel.getComponent(1)).isInstanceOf(EventTimelinePanel::class)
			assertThat(southPanel.getComponent(2)).isInstanceOf(PathPreviewPanel::class)
			assertThat((southPanel.getComponent(2) as PathPreviewPanel).isVisible).isFalse()
			assertThat(southPanel.getComponent(3)).isInstanceOf(StatusBar::class)
		}
		runOnEDT { frame.stopSimulation() }
		context.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("south panel returns to PathPreviewPanel and StatusBar when switching back to editing mode")
	fun southPanelRestoresPathPreviewAndStatusBarAfterSwitchingToEditingMode() {
		val simContext =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		val editContext = editingContextFactory.createEmptyContext()
		runOnEDT {
			frame.setContext(simContext)
			// switch back to editing — EventTimelinePanel removed, PathPreviewPanel + StatusBar remain.
			// WarningPanel stays attached (hidden) just like PathPreviewPanel does in simulation mode
			// (Goal 3 SP6, Issue #616).
			frame.setContext(editContext)
			val southPanel =
				(frame.contentPane.layout as BorderLayout)
					.getLayoutComponent(BorderLayout.SOUTH) as javax.swing.JPanel
			assertThat(southPanel.componentCount).isEqualTo(3)
			assertThat(southPanel.getComponent(0)).isInstanceOf(WarningPanel::class)
			assertThat((southPanel.getComponent(0) as WarningPanel).isVisible).isFalse()
			assertThat(southPanel.getComponent(1)).isInstanceOf(PathPreviewPanel::class)
			assertThat((southPanel.getComponent(1) as PathPreviewPanel).isVisible).isTrue()
			assertThat(southPanel.getComponent(2)).isInstanceOf(StatusBar::class)
			// StatusBar must still be visible after returning to editing mode
			assertThat(frame.statusBar.isVisible).isTrue()
		}
		simContext.close()
		editContext.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setContext closes previous SimulationContext when switching to a new one")
	fun setContextClosesPreviousSimulationContext() {
		val context1 =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		val context2 =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context1)
			frame.setContext(context2)
		}
		assertThat(context1.closeCount).isEqualTo(1)
		runOnEDT { frame.stopSimulation() }
		context2.close()
	}

	@Test
	@DisplayName("autoPauseOnCriticalWarning and soundOnCriticalWarning are volatile (cross-thread contract)")
	fun collisionResponseFieldsAreVolatile() {
		val autoPauseField = Frame::class.java.getDeclaredField("autoPauseOnCriticalWarning")
		val soundField = Frame::class.java.getDeclaredField("soundOnCriticalWarning")

		assertThat(
			java.lang.reflect.Modifier
				.isVolatile(autoPauseField.modifiers)
		).isTrue()
		assertThat(
			java.lang.reflect.Modifier
				.isVolatile(soundField.modifiers)
		).isTrue()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("conflictResolutionPanel is in the EAST slot and initially hidden")
	fun conflictResolutionPanelIsInEastSlotAndInitiallyHidden() {
		runOnEDT {
			val eastComponent =
				(frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.EAST)
			assertThat(eastComponent).isNotNull()
			assertThat(eastComponent).isInstanceOf(ConflictResolutionPanel::class)
			assertThat(eastComponent.isVisible).isFalse()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("conflictResolutionPanel is hidden when entering simulation mode")
	fun conflictResolutionPanelIsHiddenInSimulationMode() {
		val context =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context)
			assertThat(frame.conflictResolutionPanel.isVisible).isFalse()
		}
		runOnEDT { frame.stopSimulation() }
		context.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("conflictResolutionPanel is cleared and hidden when switching to editing mode")
	fun conflictResolutionPanelIsClearedWhenSwitchingToEditingMode() {
		val simContext =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		val editContext = editingContextFactory.createEmptyContext()
		runOnEDT {
			frame.setContext(simContext)
			frame.setContext(editContext)
			assertThat(frame.conflictResolutionPanel.isVisible).isFalse()
			assertThat(frame.conflictResolutionPanel.listModel.hasNoResolutions).isTrue()
		}
		simContext.close()
		editContext.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("conflictResolutionPanel is cleared and hidden when stopSimulation is called")
	fun conflictResolutionPanelIsClearedOnStopSimulation() {
		val context =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context)
			frame.stopSimulation()
			assertThat(frame.conflictResolutionPanel.isVisible).isFalse()
			assertThat(frame.conflictResolutionPanel.listModel.hasNoResolutions).isTrue()
		}
		context.close()
	}
}
