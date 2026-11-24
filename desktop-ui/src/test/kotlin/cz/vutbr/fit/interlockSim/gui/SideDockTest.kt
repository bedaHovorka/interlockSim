package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.gui.conflict.ConflictResolutionPanel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder

/** Tests for [SideDock], the right-hand column of the main window (Issue #1421). */
@DisplayName("SideDock")
class SideDockTest {
	private lateinit var speed: SimulationControlPanel
	private lateinit var dashboard: MetricsDashboardPanel
	private lateinit var dispatcher: DispatcherControlPanel
	private lateinit var conflict: ConflictResolutionPanel
	private lateinit var dock: SideDock

	@BeforeEach
	fun setUp() {
		SwingUtilities.invokeAndWait {
			speed = SimulationControlPanel()
			dashboard = MetricsDashboardPanel()
			dispatcher = DispatcherControlPanel()
			conflict = ConflictResolutionPanel()
			dock = SideDock(speed, dashboard, dispatcher, conflict)
		}
	}

	@Test
	@DisplayName("holds the four panels, the dashboard under the speed controls")
	fun holdsThePanels() {
		SwingUtilities.invokeAndWait {
			assertThat(SwingUtilities.isDescendingFrom(speed, dock)).isTrue()
			assertThat(SwingUtilities.isDescendingFrom(dashboard, dock)).isTrue()
			assertThat(dashboard.parent).isSameInstanceAs(speed.parent)
			val order = speed.parent.components.toList()
			assertThat(order.indexOf(dashboard)).isEqualTo(order.indexOf(speed) + 1)
			assertThat(SwingUtilities.isDescendingFrom(dispatcher, dock)).isTrue()
			assertThat(SwingUtilities.isDescendingFrom(conflict, dock)).isTrue()
			assertThat((speed.border as TitledBorder).title).isEqualTo("Simulation Speed")
			assertThat((dispatcher.border as TitledBorder).title).isEqualTo("Dispatcher")
		}
	}

	@Test
	@DisplayName("has the conflict panel's width and scrolls vertically only")
	fun widthAndScrolling() {
		SwingUtilities.invokeAndWait {
			assertThat(dock.preferredSize.width).isEqualTo(GuiConstants.CONFLICT_RESOLUTION_PANEL_WIDTH)
			assertThat(dock.horizontalScrollBarPolicy).isEqualTo(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
			assertThat(dock.verticalScrollBarPolicy).isEqualTo(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)
		}
	}

	@Test
	@DisplayName("setSimulationMode switches the speed controls")
	fun simulationModeSwitchesSpeedControls() {
		SwingUtilities.invokeAndWait {
			dock.setSimulationMode(false)
			assertThat(speed.speedControlsEnabled).isFalse()
			dock.setSimulationMode(true)
			assertThat(speed.speedControlsEnabled).isTrue()
		}
	}
}
