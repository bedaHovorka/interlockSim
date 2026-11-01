/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for MetricsDashboardPanel (Issue #675, Goal 6 SP3)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunStall
import cz.vutbr.fit.interlockSim.testutil.FakeMetricsCollectionService
import cz.vutbr.fit.interlockSim.testutil.flushEDT
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

@DisplayName("MetricsDashboardPanel")
class MetricsDashboardPanelTest {
	private val snapshot =
		MetricsSnapshot(
			time = 123.45,
			conflictCount = 2,
			completedTrains = 12,
			throughput = 0.006,
			totalWaitSeconds = 30.0,
			averageWaitSeconds = 3.25,
			occupiedBlocks = 3,
			totalBlocks = 12,
			utilization = 0.25,
			queuedTrains = 1,
			blockedTrains = 2,
			openContentions = 3
		)

	private var panel: MetricsDashboardPanel? = null

	@AfterEach
	fun tearDown() {
		panel?.let { SwingUtilities.invokeAndWait { it.detach() } }
	}

	private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

	private fun newPanel(): MetricsDashboardPanel {
		var created: MetricsDashboardPanel? = null
		onEdt { created = MetricsDashboardPanel() }
		return created!!.also { panel = it }
	}

	/** Waits (EDT flushes) until [label] of [p] shows a text that contains [expected]. */
	private fun awaitText(
		p: MetricsDashboardPanel,
		label: String,
		expected: String
	) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		var text = ""
		while (System.nanoTime() < deadline) {
			flushEDT()
			onEdt { text = p.textOf(label) }
			if (expected in text) return
			Thread.sleep(20)
		}
		assertThat(text).contains(expected)
	}

	@Nested
	@DisplayName("DashboardText formatting")
	inner class Formatting {
		@Test
		@DisplayName("formats the four KPIs and the capacity pressure of a snapshot")
		fun formatsSnapshot() {
			val text = DashboardText.of(DashboardReading(snapshot, emptyList()))

			assertThat(text.averageWait).isEqualTo("Avg wait: 3.3 s")
			assertThat(text.conflicts).isEqualTo("Conflicts: 2")
			assertThat(text.throughput).isEqualTo("Throughput: 21.6 trains/h (12 done)")
			assertThat(text.utilization).isEqualTo("Utilization: 25 % (3/12 blocks)")
			assertThat(text.queued).isEqualTo("Queued: 1")
			assertThat(text.blocked).isEqualTo("Blocked: 2")
			assertThat(text.openContentions).isEqualTo("Open contentions: 3")
			assertThat(text.time).isEqualTo("t = 123.5 s")
		}

		@Test
		@DisplayName("stall and end cause are dashes until the run ended")
		fun runEndFiguresAreDashesMidRun() {
			val text = DashboardText.of(DashboardReading(snapshot, emptyList()))

			assertThat(text.stall).isEqualTo(DashboardText.EMPTY.stall)
			assertThat(text.endCause).isEqualTo(DashboardText.EMPTY.endCause)
		}

		@Test
		@DisplayName("after the run it shows the end classification and the stalled trains")
		fun showsEndCauseAndStall() {
			val fake = FakeMetricsCollectionService()
			val clean = fake.runEndSummary()
			val stalled =
				clean.copy(
					stall = RunStall(listOf("t1", "t2"), setOf("t2", "t1"), 3, 99.0),
					classification = RunEndClassification.STALLED
				)

			assertThat(DashboardText.of(DashboardReading(snapshot, emptyList(), stalled)).stall)
				.isEqualTo("Stall: yes (t1, t2)")
			assertThat(DashboardText.of(DashboardReading(snapshot, emptyList(), stalled)).endCause)
				.isEqualTo("End: STALLED")
			val natural = clean.copy(classification = RunEndClassification.NATURAL_COMPLETION)
			val naturalText = DashboardText.of(DashboardReading(snapshot, emptyList(), natural))
			assertThat(naturalText.stall).isEqualTo("Stall: none")
			assertThat(naturalText.endCause).isEqualTo("End: NATURAL_COMPLETION")
		}

		@Test
		@DisplayName("open contentions are listed in the tooltip, an owner that left is said so")
		fun contentionDetail() {
			val lines = listOf(ContentionLine("t2", "t1", 10.0), ContentionLine("t3", null, 12.5))

			val detail = DashboardText.of(DashboardReading(snapshot, lines)).contentionDetail

			assertThat(detail!!).contains("t2 waits for t1 since 10.0 s")
			assertThat(detail).contains("t3 owner has left the block since 12.5 s")
			assertThat(DashboardText.of(DashboardReading(snapshot, emptyList())).contentionDetail).isNull()
		}
	}

	@Nested
	@DisplayName("polling")
	inner class Polling {
		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("shows the published snapshot, read off the EDT")
		fun showsPublishedSnapshotOffEdt() {
			val fake = FakeMetricsCollectionService()
			fake.published = snapshot
			val p = newPanel()

			onEdt { p.start(fake) }
			awaitText(p, MetricsDashboardPanel.CONFLICTS_NAME, "Conflicts: 2")

			assertThat(p.textOf(MetricsDashboardPanel.AVERAGE_WAIT_NAME)).isEqualTo("Avg wait: 3.3 s")
			assertThat(fake.latestSnapshotThreads).isNotEmpty()
			assertThat(fake.latestSnapshotThreads.any { it.contains("AWT-EventQueue") }).isFalse()
		}

		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("follows the service while it runs and lists the open contentions")
		fun followsService() {
			val fake = FakeMetricsCollectionService()
			fake.published = snapshot
			fake.contentions = listOf(OpenContention("t2", mockk<DynamicTrackBlock>(relaxed = true), "t1", 10.0))
			val p = newPanel()
			onEdt { p.start(fake) }
			awaitText(p, MetricsDashboardPanel.CONFLICTS_NAME, "Conflicts: 2")

			fake.published = snapshot.copy(conflictCount = 5, queuedTrains = 4)
			awaitText(p, MetricsDashboardPanel.CONFLICTS_NAME, "Conflicts: 5")

			assertThat(p.textOf(MetricsDashboardPanel.QUEUED_NAME)).isEqualTo("Queued: 4")
		}

		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("stop makes a last poll with the run-end summary and ends the polling")
		fun stopReadsRunEndAndStopsPolling() {
			val fake = FakeMetricsCollectionService()
			fake.published = snapshot
			val p = newPanel()
			onEdt { p.start(fake) }
			awaitText(p, MetricsDashboardPanel.CONFLICTS_NAME, "Conflicts: 2")
			fake.published = snapshot.copy(conflictCount = 7)
			fake.runEnd = fake.runEndSummary().copy(classification = RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)

			onEdt { p.stop() }
			awaitText(p, MetricsDashboardPanel.END_CAUSE_NAME, "CUTOFF_WITH_UNFINISHED_JOURNEYS")

			assertThat(p.textOf(MetricsDashboardPanel.CONFLICTS_NAME)).isEqualTo("Conflicts: 7")
			assertThat(p.isPolling).isFalse()
			val reads = fake.latestSnapshotThreads.size
			Thread.sleep(700)
			flushEDT()
			assertThat(fake.latestSnapshotThreads.size).isEqualTo(reads)
		}

		@Test
		@Timeout(value = 15, unit = TimeUnit.SECONDS)
		@DisplayName("detach stops the timer and clears the figures")
		fun detachClears() {
			val fake = FakeMetricsCollectionService()
			fake.published = snapshot
			val p = newPanel()
			onEdt { p.start(fake) }
			awaitText(p, MetricsDashboardPanel.CONFLICTS_NAME, "Conflicts: 2")
			assertThat(p.isPolling).isTrue()

			onEdt { p.detach() }

			assertThat(p.isPolling).isFalse()
			assertThat(p.textOf(MetricsDashboardPanel.CONFLICTS_NAME)).doesNotContain("2")
			assertThat(p.textOf(MetricsDashboardPanel.CONFLICTS_NAME)).isEqualTo(DashboardText.EMPTY.conflicts)
		}
	}
}
