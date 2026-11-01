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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.testutil.createMockShuntingContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

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
	@DisplayName("is docked in simulation mode, removed in editing mode, and idle before a run")
	fun dockedInSimulationModeOnly() {
		val simulation = createMockShuntingContext()
		runOnEDT {
			assertThat(frame.metricsDashboardPanel.parent).isNull()

			frame.setContext(simulation)

			assertThat(frame.metricsDashboardPanel.parent).isNotNull()
			assertThat(frame.metricsDashboardPanel.isPolling).isFalse()
			assertThat(frame.metricsDashboardPanel.textOf(MetricsDashboardPanel.CONFLICTS_NAME))
				.isEqualTo(DashboardText.EMPTY.conflicts)
		}

		editingContextFactory.createEmptyContext().use { editing ->
			runOnEDT {
				frame.setContext(editing)

				assertThat(frame.metricsDashboardPanel.parent).isNull()
				assertThat(frame.metricsDashboardPanel.isPolling).isFalse()
			}
		}
		simulation.close()
	}
}
