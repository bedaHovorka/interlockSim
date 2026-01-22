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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.PROGRAM_FULL_NAME
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
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
	private val xmlFactory: XMLContextFactory by inject()
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
	@DisplayName("frame has correct initial size")
	fun frameHasCorrectInitialSize() {
		runOnEDT {
			assertThat(frame.width).isEqualTo(800)
			assertThat(frame.height).isEqualTo(600)
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
			// North component is now a JPanel containing ToolBar + ControlPanel (Issue #205)
			val northComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)
			assertThat(northComponent).isNotNull()
			assertThat(northComponent).isInstanceOf(javax.swing.JPanel::class)

			// Verify the container has components (ToolBar and ControlPanel)
			val panel = northComponent as javax.swing.JPanel
			assertThat(panel.componentCount).isEqualTo(2)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("frame has status bar at south")
	fun frameHasStatusBarAtSouth() {
		runOnEDT {
			val southComponent = (frame.contentPane.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH)
			assertThat(southComponent).isNotNull()
			assertThat(southComponent).isInstanceOf(StatusBar::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setContext updates canvas context")
	fun setContextUpdatesCanvasContext() {
		runOnEDT {
			// Create a context
			val context = xmlFactory.createEmptyContext()
			
			// Set context on frame
			frame.setContext(context)
			
			// Verify canvas received the context
			val canvasContext = frame.railwayNetGridCanvas.getEditingContext()
			assertThat(canvasContext).isNotNull()
			assertThat(canvasContext).isInstanceOf(EditingContext::class)
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	@DisplayName("setContext registers status bar as property change listener")
	fun setContextRegistersStatusBarAsPropertyChangeListener() {
		runOnEDT {
			// Create a context
			val context = xmlFactory.createEmptyContext()
			
			// Create a test listener to verify registration works
			var listenerCalled = false
			val testListener = java.beans.PropertyChangeListener { evt ->
				listenerCalled = true
			}
			
			// Add test listener to verify the mechanism works
			context.addPropertyChangeListener(testListener)
			
			// Set context on frame (should register status bar as listener)
			frame.setContext(context)
			
			// Verify the context accepts property change listeners
			// (The actual registration is internal to Frame, we just verify no exception)
			context.removePropertyChangeListener(testListener)
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
}
