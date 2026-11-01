/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.FlowLayout
import java.util.Locale
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingWorker
import javax.swing.Timer

/**
 * One open blocked-path contention as the dashboard shows it: who waits, for whom, since when.
 *
 * Only the train names and the time are kept; the dynamic block of the published contention is
 * never touched off the simulation thread (Issue #1305, Goal 6 SP7).
 *
 * @property trainId the waiting train
 * @property owner the train holding the block, or `null` when nobody holds it any more
 * @property since simulation time the contention was first observed
 * @since Issue #675 (Goal 6 SP3)
 */
internal data class ContentionLine(
	val trainId: String,
	val owner: String?,
	val since: Double
)

/**
 * Everything one poll of the dashboard read from the metrics service: the last published snapshot,
 * the last published open contentions and -- on the final poll after the run ended -- the run-end
 * summary.
 *
 * @since Issue #675 (Goal 6 SP3)
 */
internal data class DashboardReading(
	val snapshot: MetricsSnapshot,
	val contentions: List<ContentionLine>,
	val runEnd: RunEndSummary? = null
)

/**
 * The texts of the dashboard, computed from a [DashboardReading] -- pure formatting, no Swing.
 *
 * The stall and the end cause come from [RunEndSummary], which may only be read once the run has
 * ended; until then they show a dash.
 *
 * @since Issue #675 (Goal 6 SP3)
 */
internal data class DashboardText(
	val averageWait: String,
	val conflicts: String,
	val throughput: String,
	val utilization: String,
	val queued: String,
	val blocked: String,
	val openContentions: String,
	val contentionDetail: String?,
	val stall: String,
	val endCause: String,
	val time: String
) {
	/** @since Issue #675 (Goal 6 SP3) */
	companion object {
		private const val SECONDS_PER_HOUR = 3600.0
		private const val PERCENT = 100.0
		private const val NONE = "–"

		/** The dashboard before any run: every figure a dash. @since Issue #675 (Goal 6 SP3) */
		val EMPTY: DashboardText =
			DashboardText(
				averageWait = "Avg wait: $NONE",
				conflicts = "Conflicts: $NONE",
				throughput = "Throughput: $NONE",
				utilization = "Utilization: $NONE",
				queued = "Queued: $NONE",
				blocked = "Blocked: $NONE",
				openContentions = "Open contentions: $NONE",
				contentionDetail = null,
				stall = "Stall: $NONE",
				endCause = "End: $NONE",
				time = "t = $NONE"
			)

		/**
		 * Format [reading].
		 *
		 * @since Issue #675 (Goal 6 SP3)
		 */
		fun of(reading: DashboardReading): DashboardText {
			val s = reading.snapshot
			val perHour = s.throughput * SECONDS_PER_HOUR
			return DashboardText(
				averageWait = "Avg wait: ${fixed(s.averageWaitSeconds, 1)} s",
				conflicts = "Conflicts: ${s.conflictCount}",
				throughput = "Throughput: ${fixed(perHour, 1)} trains/h (${s.completedTrains} done)",
				utilization =
					"Utilization: ${fixed(s.utilization * PERCENT, 0)} % (${s.occupiedBlocks}/${s.totalBlocks} blocks)",
				queued = "Queued: ${s.queuedTrains}",
				blocked = "Blocked: ${s.blockedTrains}",
				openContentions = "Open contentions: ${s.openContentions}",
				contentionDetail = contentionDetail(reading.contentions),
				stall = stallText(reading.runEnd),
				endCause = endCauseText(reading.runEnd),
				time = "t = ${fixed(s.time, 1)} s"
			)
		}

		private fun fixed(
			value: Double,
			digits: Int
		): String = String.format(Locale.ROOT, "%.${digits}f", value)

		private fun contentionDetail(lines: List<ContentionLine>): String? =
			lines
				.takeIf { it.isNotEmpty() }
				?.joinToString("<br>", prefix = "<html>", postfix = "</html>") {
					val owner = it.owner?.let { name -> "waits for $name" } ?: "owner has left the block"
					"${it.trainId} $owner since ${fixed(it.since, 1)} s"
				}

		private fun stallText(summary: RunEndSummary?): String =
			when {
				summary == null -> "Stall: $NONE"
				summary.stall == null -> "Stall: none"
				else -> "Stall: yes (${summary.stall!!.knot.sorted().joinToString(", ")})"
			}

		private fun endCauseText(summary: RunEndSummary?): String = "End: ${summary?.classification?.name ?: NONE}"
	}
}

/**
 * Live KPI dashboard of a running simulation (Issue #675, Goal 6 SP3).
 *
 * Shows the four KPIs (average wait, conflicts, throughput, utilization) next to the capacity
 * pressure of Issue #1305 (queued trains, blocked trains, open contentions), the watchdog stall and
 * -- once the run ended -- how it ended.
 *
 * ## Threading
 *
 * The panel reads only [MetricsCollectionService.latestSnapshot] and
 * [MetricsCollectionService.latestOpenContentions], the two calls that are safe from any thread
 * (one immutable value behind a volatile reference), never an object of the simulation thread.
 * A [Timer] ticks on the EDT; each tick starts a [SwingWorker] that does the reads off the EDT and
 * hands the result to `done()` on the EDT (the pattern of `PathPreviewPanel`). At most one poll runs
 * at a time. [stop] makes one last poll that also reads the run-end summary -- the one read that is
 * only valid after the run ended -- and then ends the polling.
 *
 * All public methods must be called from the EDT.
 *
 * @since Issue #675 (Goal 6 SP3)
 */
class MetricsDashboardPanel : JPanel() {
	private val logger = KotlinLogging.logger {}

	private val labels: Map<String, JLabel> =
		listOf(
			AVERAGE_WAIT_NAME,
			CONFLICTS_NAME,
			THROUGHPUT_NAME,
			UTILIZATION_NAME,
			QUEUED_NAME,
			BLOCKED_NAME,
			OPEN_CONTENTIONS_NAME,
			STALL_NAME,
			END_CAUSE_NAME,
			TIME_NAME
		).associateWith { name -> JLabel().apply { this.name = name } }

	private val timer = Timer(POLL_INTERVAL_MS) { poll(final = false) }

	private var service: MetricsCollectionService? = null
	private var inFlight = false
	private var finalPending = false

	init {
		name = PANEL_NAME
		layout = BoxLayout(this, BoxLayout.Y_AXIS)
		border = BorderFactory.createTitledBorder("Metrics")
		add(row(AVERAGE_WAIT_NAME, CONFLICTS_NAME, THROUGHPUT_NAME, UTILIZATION_NAME))
		add(row(QUEUED_NAME, BLOCKED_NAME, OPEN_CONTENTIONS_NAME, STALL_NAME, END_CAUSE_NAME, TIME_NAME))
		show(DashboardText.EMPTY)
	}

	private fun row(vararg names: String): JPanel =
		JPanel(FlowLayout(FlowLayout.LEFT, GAP, 0)).apply {
			names.forEach { add(labels.getValue(it)) }
		}

	/**
	 * Begin polling [metrics] for a run that has just started; the previous run's figures are cleared.
	 *
	 * @since Issue #675 (Goal 6 SP3)
	 */
	fun start(metrics: MetricsCollectionService) {
		detach()
		service = metrics
		timer.start()
		poll(final = false)
	}

	/**
	 * End the polling after the run ended: one last poll that also reads the run-end summary, then the
	 * service is released. The last figures stay on screen. No-op when not polling.
	 *
	 * @since Issue #675 (Goal 6 SP3)
	 */
	fun stop() {
		timer.stop()
		if (service != null) poll(final = true)
	}

	/**
	 * Stop polling at once, drop the service and clear the figures (a new context, or the editor).
	 *
	 * @since Issue #675 (Goal 6 SP3)
	 */
	fun detach() {
		timer.stop()
		service = null
		finalPending = false
		show(DashboardText.EMPTY)
	}

	/** Whether the polling timer is running. @since Issue #675 (Goal 6 SP3) */
	val isPolling: Boolean get() = timer.isRunning

	/** The text currently shown by the label named [labelName]. @since Issue #675 (Goal 6 SP3) */
	fun textOf(labelName: String): String = labels.getValue(labelName).text

	/** Start one poll now (the timer's tick), unless one is still running. @since Issue #675 (Goal 6 SP3) */
	internal fun pollNow() = poll(final = false)

	private fun poll(final: Boolean) {
		val metrics = service ?: return
		if (inFlight) {
			if (final) finalPending = true
			return
		}
		inFlight = true
		object : SwingWorker<DashboardReading, Void>() {
			// Background thread: only the two cross-thread-safe reads (plus the summary once ended).
			override fun doInBackground(): DashboardReading =
				DashboardReading(
					snapshot = metrics.latestSnapshot(),
					contentions =
						metrics.latestOpenContentions().map { ContentionLine(it.trainId, it.owner, it.since) },
					runEnd = if (final) metrics.runEndSummary() else null
				)

			// EDT.
			override fun done() {
				inFlight = false
				val reading =
					try {
						get()
					} catch (e: ExecutionException) {
						logger.warn(e.cause ?: e) { "Metrics dashboard poll failed" }
						null
					} catch (e: InterruptedException) {
						Thread.currentThread().interrupt()
						null
					}
				if (service !== metrics) return // detached or restarted while this poll ran
				reading?.let { show(DashboardText.of(it)) }
				if (final) {
					service = null
				} else if (finalPending) {
					finalPending = false
					poll(final = true)
				}
			}
		}.execute()
	}

	private fun show(text: DashboardText) {
		labels.getValue(AVERAGE_WAIT_NAME).text = text.averageWait
		labels.getValue(CONFLICTS_NAME).text = text.conflicts
		labels.getValue(THROUGHPUT_NAME).text = text.throughput
		labels.getValue(UTILIZATION_NAME).text = text.utilization
		labels.getValue(QUEUED_NAME).text = text.queued
		labels.getValue(BLOCKED_NAME).text = text.blocked
		labels.getValue(OPEN_CONTENTIONS_NAME).apply {
			this.text = text.openContentions
			toolTipText = text.contentionDetail
		}
		labels.getValue(STALL_NAME).text = text.stall
		labels.getValue(END_CAUSE_NAME).text = text.endCause
		labels.getValue(TIME_NAME).text = text.time
	}

	/** Component and label names, for tests. @since Issue #675 (Goal 6 SP3) */
	companion object {
		private const val POLL_INTERVAL_MS = 500
		private const val GAP = 16
		const val PANEL_NAME = "metricsDashboardPanel"
		const val AVERAGE_WAIT_NAME = "dashboardAverageWait"
		const val CONFLICTS_NAME = "dashboardConflicts"
		const val THROUGHPUT_NAME = "dashboardThroughput"
		const val UTILIZATION_NAME = "dashboardUtilization"
		const val QUEUED_NAME = "dashboardQueued"
		const val BLOCKED_NAME = "dashboardBlocked"
		const val OPEN_CONTENTIONS_NAME = "dashboardOpenContentions"
		const val STALL_NAME = "dashboardStall"
		const val END_CAUSE_NAME = "dashboardEndCause"
		const val TIME_NAME = "dashboardTime"
	}
}
