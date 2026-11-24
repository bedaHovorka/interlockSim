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
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.PROGRAM_LLM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.gui.animation.EventTimelinePanel
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import cz.vutbr.fit.interlockSim.objects.cells.AbstractCell
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.sim.collision.CollisionWarning
import cz.vutbr.fit.interlockSim.testutil.createMockTrackBlock
import cz.vutbr.fit.interlockSim.testutil.createMockTrackBlockPart
import cz.vutbr.fit.interlockSim.util.Point
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.awt.BorderLayout
import java.util.AbstractMap.SimpleEntry
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

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

	// The advisory/critical fixture pair, as WarningPanelTest declares them.
	private val advisory =
		CollisionWarning.ReservationConflict(
			trainId = "Train #19",
			conflictingTrainId = "Train #18",
			time = 601.0,
			severity = CollisionWarning.Severity.WARNING
		)

	private val critical = CollisionWarning.ReservationConflict("Train-A", "Train-B", time = 10.0)

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
	@DisplayName("an advisory run-end warning is listed without the critical indicator (Issue #1331)")
	fun advisoryWarningIsListedWithoutCriticalIndicator() {
		runOnEDT {
			frame.showCollisionWarning(advisory, autoPause = true, sound = false)

			assertThat(frame.warningPanel.listModel.size).isEqualTo(1)
			assertThat(frame.warningPanel.hasCriticalWarning()).isFalse()
			assertThat(frame.statusBar.isWarningIndicatorVisible()).isFalse()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a critical warning is listed and lights the status-bar indicator")
	fun criticalWarningLightsIndicator() {
		runOnEDT {
			frame.showCollisionWarning(critical, autoPause = true, sound = false)

			assertThat(frame.warningPanel.listModel.size).isEqualTo(1)
			assertThat(frame.warningPanel.hasCriticalWarning()).isTrue()
			assertThat(frame.statusBar.isWarningIndicatorVisible()).isTrue()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a selected critical row lights its block and a selected advisory clears it (Issue #1331)")
	fun warningSelectionHighlightFollowsSeverity() {
		// The fixture blocks are unnamed in their sim contexts, and a warning with a null block
		// name clears the highlight by design, so the scan runs against a mock context with one
		// named block part (TrackTestMocks factories; DynamicTrackBlock stub style as in
		// WarningPanelTest). The grid scaffold is local to this test.
		val blockPart = createMockTrackBlockPart(createMockTrackBlock(blockName = "k4"))
		val blockPoint = Point(0, 0)
		val grid =
			mockk<RailwayNetGrid<AbstractCell>>(relaxed = true) {
				every { iterator() } returns
					listOf<Map.Entry<Point, AbstractCell>>(
						SimpleEntry(blockPoint, blockPart)
					).iterator()
			}
		val canvasContext =
			mockk<Context<AbstractCell, TrackBlock>>(relaxed = true) {
				every { getRailWayNetGrid() } returns grid
			}
		val block = mockk<DynamicTrackBlock>(relaxed = true)
		every { block.name } returns "k4"

		runOnEDT {
			frame.railwayNetGridCanvas.setContext(canvasContext)
			requireNotNull(frame.warningPanel.onWarningSelected).invoke(critical.copy(conflictingBlock = block))
		}
		assertThat(frame.railwayNetGridCanvas.highlightedGridPoints).contains(blockPoint)

		runOnEDT {
			requireNotNull(frame.warningPanel.onWarningSelected).invoke(advisory.copy(conflictingBlock = block))
		}
		assertThat(frame.railwayNetGridCanvas.highlightedGridPoints).isEmpty()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("with auto-pause off, only a critical warning resumes a paused run (Issue #1331)")
	fun onlyCriticalWarningResumesPausedRunner() {
		val runner = SimulationRunner(mockk(relaxed = true))
		runner.isPaused = true

		runOnEDT {
			frame.simulationController.runner = runner
			frame.showCollisionWarning(advisory, autoPause = false, sound = false)
		}
		assertThat(runner.isPaused).isTrue()

		runOnEDT {
			frame.showCollisionWarning(critical, autoPause = false, sound = false)
			frame.simulationController.runner = null
		}
		assertThat(runner.isPaused).isFalse()
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
	@DisplayName("frame starts at the size WindowGeometry gives for this screen")
	fun frameHasCorrectInitialSize() {
		runOnEDT {
			// Issue #1421: no fixed 1024x818 any more; the test frame has no WindowStateStore, so it
			// always starts at the screen-based size (never smaller than 1024x818 unless the screen is).
			val expected = WindowGeometry.initialBounds(WindowGeometry.usableScreen())
			assertThat(frame.width).isEqualTo(expected.width)
			assertThat(frame.height).isEqualTo(expected.height)
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
	@DisplayName("frame canvas is in a scroll pane above the bottom tabs in a split pane")
	fun frameCanvasIsInScrollPane() {
		runOnEDT {
			// Issue #1421: CENTER is a vertical split pane; canvas scroll pane on top, tabs below
			val centerComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
			assertThat(centerComponent).isInstanceOf(JSplitPane::class)
			val split = centerComponent as JSplitPane
			assertThat(split.orientation).isEqualTo(JSplitPane.VERTICAL_SPLIT)
			assertThat(split.resizeWeight).isEqualTo(1.0)
			assertThat(split.topComponent).isInstanceOf(JScrollPane::class)
			assertThat((split.topComponent as JScrollPane).viewport.view).isInstanceOf(RailwayNetGridCanvas::class)
			assertThat(split.bottomComponent).isSameInstanceAs(frame.bottomTabs)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has toolbar container at north")
	fun frameHasToolbarContainerAtNorth() {
		runOnEDT {
			// North component is a JPanel with ToolBar + ControlPanel (Issue #205); the speed and
			// dispatcher panels moved to the side dock (Issue #1421)
			val northComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
			assertThat(northComponent).isNotNull()
			assertThat(northComponent).isInstanceOf(javax.swing.JPanel::class)

			val panel = northComponent as javax.swing.JPanel
			assertThat(panel.componentCount).isEqualTo(2)
			assertThat(panel.getComponent(0)).isInstanceOf(ToolBar::class)
			// The run bar holds the step controls next to the ControlPanel's Stop (Issue #1421)
			assertThat(
				SwingUtilities.isDescendingFrom(frame.simulationControlPanel.stepControls, panel.getComponent(1))
			).isTrue()
			assertThat(SwingUtilities.isDescendingFrom(frame.controlPanel, panel.getComponent(1))).isTrue()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has the status bar alone at south")
	fun frameHasStatusBarAtSouth() {
		runOnEDT {
			// Issue #1421: the other bottom panels are tabs, so SOUTH holds only the StatusBar
			val southComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH)
			assertThat(southComponent).isSameInstanceAs(frame.statusBar)
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
			assertThat(layout.getLayoutComponent(BorderLayout.EAST)).isNotNull()
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("bottom tabs hold three panels in a fixed order; editing mode enables only Path Preview")
	fun southPanelHasPathPreviewAndStatusBarInEditingMode() {
		runOnEDT {
			// Default state after Frame construction is editing mode (Issue #1421)
			val tabs = frame.bottomTabs
			assertThat(tabs.tabCount).isEqualTo(3)
			assertThat(tabs.getComponentAt(Frame.TAB_PATH_PREVIEW)).isInstanceOf(PathPreviewPanel::class)
			assertThat(tabs.getComponentAt(Frame.TAB_WARNINGS)).isSameInstanceAs(frame.warningPanel)
			assertThat(tabs.getComponentAt(Frame.TAB_TIMELINE)).isInstanceOf(JLabel::class)
			assertThat(tabs.getTitleAt(Frame.TAB_PATH_PREVIEW)).isEqualTo(Frame.TAB_TITLE_PATH_PREVIEW)
			assertThat(tabs.getTitleAt(Frame.TAB_WARNINGS)).isEqualTo(Frame.TAB_TITLE_WARNINGS)
			assertThat(tabs.getTitleAt(Frame.TAB_TIMELINE)).isEqualTo(Frame.TAB_TITLE_TIMELINE)
			assertTabMode(simulation = false)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("simulation mode enables Warnings and Timeline, selects Timeline and shows the timeline")
	fun southPanelGainsTimelinePanelInSimulationMode() {
		val context =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context)
			assertTabMode(simulation = true)
			// The EventTimelinePanel replaces the placeholder (Issue #1421)
			assertThat(frame.bottomTabs.getComponentAt(Frame.TAB_TIMELINE)).isInstanceOf(EventTimelinePanel::class)
		}
		runOnEDT { frame.stopSimulation() }
		context.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("switching back to editing mode keeps all tabs and enables only Path Preview")
	fun southPanelRestoresPathPreviewAndStatusBarAfterSwitchingToEditingMode() {
		val simContext =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		val editContext = editingContextFactory.createEmptyContext()
		runOnEDT {
			frame.setContext(simContext)
			frame.setContext(editContext)
			// Same frame in both modes (Issue #1421): no tab comes or goes
			assertThat(frame.bottomTabs.tabCount).isEqualTo(3)
			assertThat(frame.bottomTabs.getComponentAt(Frame.TAB_TIMELINE)).isInstanceOf(EventTimelinePanel::class)
			assertTabMode(simulation = false)
			// StatusBar must still be visible after returning to editing mode
			assertThat(frame.statusBar.isVisible).isTrue()
		}
		simContext.close()
		editContext.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the Warnings tab title shows the number of listed warnings")
	fun warningsTabTitleShowsCount() {
		val context =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context)
			frame.showCollisionWarning(advisory, autoPause = true, sound = false)
			frame.showCollisionWarning(advisory, autoPause = true, sound = false)
			assertThat(frame.bottomTabs.getTitleAt(Frame.TAB_WARNINGS)).isEqualTo("Warnings (2)")
			frame.warningPanel.clearWarnings()
			assertThat(frame.bottomTabs.getTitleAt(Frame.TAB_WARNINGS)).isEqualTo(Frame.TAB_TITLE_WARNINGS)
		}
		runOnEDT { frame.stopSimulation() }
		context.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("a critical warning selects the Warnings tab; an advisory one does not")
	fun criticalWarningSelectsWarningsTab() {
		val context =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		runOnEDT {
			frame.setContext(context)
			frame.bottomTabs.selectedIndex = Frame.TAB_TIMELINE
			frame.showCollisionWarning(advisory, autoPause = true, sound = false)
			assertThat(frame.bottomTabs.selectedIndex).isEqualTo(Frame.TAB_TIMELINE)
			frame.showCollisionWarning(critical, autoPause = true, sound = false)
			assertThat(frame.bottomTabs.selectedIndex).isEqualTo(Frame.TAB_WARNINGS)
		}
		runOnEDT { frame.stopSimulation() }
		context.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the side dock is at EAST in both modes with the KPI dashboard; editing disables speed controls")
	fun sideDockStaysInBothModes() {
		val simContext =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		val editContext = editingContextFactory.createEmptyContext()
		runOnEDT {
			val east = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.EAST)
			assertThat(east).isSameInstanceAs(frame.sideDock)
			assertThat(SwingUtilities.isDescendingFrom(frame.metricsDashboardPanel, frame.sideDock)).isTrue()
			assertThat(frame.sideDock.isVisible).isTrue()
			assertThat(frame.simulationControlPanel.speedControlsEnabled).isFalse()
			assertThat(frame.simulationControlPanel.stepControls.isVisible).isFalse()
			frame.setContext(simContext)
			assertThat(frame.sideDock.isVisible).isTrue()
			assertThat(frame.simulationControlPanel.speedControlsEnabled).isTrue()
			assertThat(frame.simulationControlPanel.stepControls.isVisible).isTrue()
			frame.setContext(editContext)
			assertThat(frame.sideDock.isVisible).isTrue()
			assertThat(frame.simulationControlPanel.speedControlsEnabled).isFalse()
			assertThat(frame.simulationControlPanel.stepControls.isVisible).isFalse()
		}
		simContext.close()
		editContext.close()
	}

	/** Checks the bottom tabs' enabled and selected state for one mode (Issue #1421). */
	private fun assertTabMode(simulation: Boolean) {
		val tabs = frame.bottomTabs
		assertThat(tabs.isEnabledAt(Frame.TAB_PATH_PREVIEW)).isEqualTo(!simulation)
		assertThat(tabs.isEnabledAt(Frame.TAB_WARNINGS)).isEqualTo(simulation)
		assertThat(tabs.isEnabledAt(Frame.TAB_TIMELINE)).isEqualTo(simulation)
		assertThat(tabs.selectedIndex).isEqualTo(if (simulation) Frame.TAB_TIMELINE else Frame.TAB_PATH_PREVIEW)
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
	@DisplayName("conflictResolutionPanel is in the side dock and initially hidden")
	fun conflictResolutionPanelIsInEastSlotAndInitiallyHidden() {
		runOnEDT {
			// Issue #1421: the panel moved from the EAST slot into the side dock, which is at EAST
			assertThat(frame.sideDock.conflictPanel).isSameInstanceAs(frame.conflictResolutionPanel)
			assertThat(SwingUtilities.isDescendingFrom(frame.conflictResolutionPanel, frame.sideDock)).isTrue()
			assertThat(frame.conflictResolutionPanel).isInstanceOf(ConflictResolutionPanel::class)
			assertThat(frame.conflictResolutionPanel.isVisible).isFalse()
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

	@Test
	@DisplayName("the tab area starts at 30 percent of the window height, at least 200 px")
	fun defaultBottomHeightFollowsWindowHeight() {
		assertThat(Frame.defaultBottomHeight(2000)).isEqualTo(600)
		assertThat(Frame.defaultBottomHeight(500)).isEqualTo(Frame.DEFAULT_BOTTOM_HEIGHT)
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the control panel clock shows the simulated time from the run's events, not 0")
	fun controlPanelClockFollowsSimulationEvents() {
		val simContext =
			cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext(
				cz.vutbr.fit.interlockSim.testutil.TestFixtures
					.loadShuntingXml()
			)
		val editContext = editingContextFactory.createEmptyContext()
		runOnEDT {
			frame.setContext(simContext)
			// Issue #1421: the clock read kDisco's thread-local time off the simulation thread and
			// always showed 0. It now follows the simulated time the run reports.
			val tracker = frame.simulationTimeTracker
			assertThat(tracker).isNotNull()
			tracker!!.propertyChange(
				ContextChangeEvent(
					SimulationContext.ReportType.PATH_SETTING.name,
					null,
					"75.5 Train-1 leaves block kB"
				)
			)
			frame.refreshSimulationTime()
			assertThat(frame.controlPanel.timeText).isEqualTo("Time: 00:01:15.500")

			frame.setContext(editContext)
			assertThat(frame.simulationTimeTracker).isNull()
		}
		simContext.close()
		editContext.close()
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("the canvas can take keyboard focus, so the shortcuts are not lost to the Delta field")
	fun canvasIsFocusable() {
		runOnEDT {
			// Issue #1421: SimulationKeyBindings ignore keys while a text field has focus
			assertThat(frame.railwayNetGridCanvas.isFocusable).isTrue()
		}
	}
}
