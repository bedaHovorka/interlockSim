/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Frame wiring of the KPI dashboard (Issue #675, Goal 6 SP3)
	Tests require a non-headless display; skipped automatically in CI.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.testutil.FakeMetricsCollectionService
import cz.vutbr.fit.interlockSim.testutil.createMockShuntingContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * The dashboard is docked in the south panel in simulation mode only, and does not poll outside a run.
 */
@DisplayName("Frame metrics dashboard")
class FrameMetricsDashboardTest : AbstractFrameTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private lateinit var frame: Frame

	@BeforeEach
	override fun setUp() {
		super.setUp()
		runOnEDT {
			frame = Frame()
			frames.add(frame)
		}
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("stays in the side dock in both modes, and is idle before a run and after editing")
	fun dockedInSimulationModeOnly() {
		val simulation = createMockShuntingContext()
		runOnEDT {
			// Issue #1421: the dashboard lives in the side dock, under the speed controls, in both modes
			assertThat(frame.sideDock.dashboardPanel).isSameInstanceAs(frame.metricsDashboardPanel)
			assertThat(SwingUtilities.isDescendingFrom(frame.metricsDashboardPanel, frame.sideDock)).isTrue()

			frame.setContext(simulation)

			assertThat(SwingUtilities.isDescendingFrom(frame.metricsDashboardPanel, frame.sideDock)).isTrue()
			assertThat(frame.metricsDashboardPanel.isPolling).isFalse()
			assertThat(frame.metricsDashboardPanel.textOf(MetricsDashboardPanel.CONFLICTS_NAME))
				.isEqualTo(DashboardText.EMPTY.conflicts)
		}

		editingContextFactory.createEmptyContext().use { editing ->
			runOnEDT {
				frame.setContext(editing)

				assertThat(SwingUtilities.isDescendingFrom(frame.metricsDashboardPanel, frame.sideDock)).isTrue()
				assertThat(frame.metricsDashboardPanel.isPolling).isFalse()
				assertThat(frame.metricsDashboardPanel.textOf(MetricsDashboardPanel.CONFLICTS_NAME))
					.isEqualTo(DashboardText.EMPTY.conflicts)
			}
		}
		simulation.close()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("switching straight to another simulation context clears the previous run's figures")
	fun simulationToSimulationSwitchClears() {
		val first = createMockShuntingContext()
		val second = createMockShuntingContext()
		val fake = FakeMetricsCollectionService()
		fake.published = fake.getSnapshot().copy(conflictCount = 42)
		runOnEDT {
			frame.setContext(first)
			frame.metricsDashboardPanel.start(fake)
			assertThat(frame.metricsDashboardPanel.isPolling).isTrue()

			frame.setContext(second)

			assertThat(frame.metricsDashboardPanel.isPolling).isFalse()
			assertThat(frame.metricsDashboardPanel.textOf(MetricsDashboardPanel.CONFLICTS_NAME))
				.isEqualTo(DashboardText.EMPTY.conflicts)
		}
		first.close()
		second.close()
	}
}
