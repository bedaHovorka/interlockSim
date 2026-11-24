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

import cz.vutbr.fit.interlockSim.sim.metrics.MetricGroup
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BorderLayout
import java.util.Locale
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
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
 * the last published open contentions and -- on the final poll -- the run record, when the run has
 * reached `RunEnded` (Issue #1326).
 *
 * @property stoppedManually the run was stopped manually and the final poll found no record, even after
 *   its bounded retries: the run was stopped before it ended
 * @since Issue #675 (Goal 6 SP3)
 */
internal data class DashboardReading(
	val snapshot: MetricsSnapshot,
	val contentions: List<ContentionLine>,
	val runEnd: RunRecord? = null,
	val stoppedManually: Boolean = false,
	val trainWaitEnabled: Boolean = true
)

/**
 * The texts of the dashboard, computed from a [DashboardReading] -- pure formatting, no Swing.
 *
 * The stall and the end cause come from the [RunRecord] the final poll read; until then they show a
 * dash. With the train-wait metric group off the capacity-pressure figures (queued, blocked, open
 * contentions) are not collected and show a dash, never a misleading zero. When a manual stop's final
 * poll found no record, the end cause reads "stopped manually"; a natural stop without a record shows a
 * dash (Issue #1326).
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
				queued = "Queued: ${pressure(reading, s.queuedTrains)}",
				blocked = "Blocked: ${pressure(reading, s.blockedTrains)}",
				openContentions = "Open contentions: ${pressure(reading, s.openContentions)}",
				contentionDetail = if (reading.trainWaitEnabled) contentionDetail(reading.contentions) else null,
				stall = stallText(reading.runEnd),
				endCause = if (reading.stoppedManually) "End: stopped manually" else endCauseText(reading.runEnd),
				time = "t = ${fixed(s.time, 1)} s"
			)
		}

		private fun pressure(
			reading: DashboardReading,
			value: Int
		): String = if (reading.trainWaitEnabled) value.toString() else NONE

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

		private fun stallText(record: RunRecord?): String =
			if (record == null) {
				"Stall: $NONE"
			} else {
				record.stall?.let { "Stall: yes (${it.knot.sorted().joinToString(", ")})" } ?: "Stall: none"
			}

		private fun endCauseText(record: RunRecord?): String = "End: ${record?.classification?.name ?: NONE}"
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
 * The panel reads only [MetricsCollectionService.latestSnapshot],
 * [MetricsCollectionService.latestOpenContentions] and [MetricsCollectionService.runRecord], never an
 * object of the simulation thread. The first two are safe from any thread in every service (one
 * immutable value behind a volatile reference). [MetricsCollectionService.runRecord] is safe from
 * any thread only where it is overridden as such a read -- `DefaultMetricsCollectionService`, the
 * service of every run -- because the interface default rebuilds the record from
 * [MetricsCollectionService.runEndSummary], which is not thread-safe.
 * A [Timer] ticks on the EDT; each tick starts a [SwingWorker] that does the reads off the EDT and
 * hands the result to `done()` on the EDT (the pattern of `PathPreviewPanel`). At most one poll runs
 * at a time. [stop] makes one last poll that also reads the run record, set at `RunEnded`, and then
 * ends the polling. A manual stop reports STOPPED before the simulation thread delivers `RunEnded`,
 * so a final poll without a record reads it again from a one-shot [Timer], up to [RECORD_RETRIES]
 * times [RECORD_RETRY_MS] apart, before it gives up (Issue #1326).
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

	private val scroll: JScrollPane

	private val timer = Timer(POLL_INTERVAL_MS) { run?.let { poll(it, final = false) } }

	/**
	 * The state of one run's polling. Every [start] makes a new one, so a worker that outlives its
	 * run can only touch its own, obsolete, state (its completion is rejected by identity).
	 */
	private class Run(
		val service: MetricsCollectionService,
		val trainWaitEnabled: Boolean
	) {
		var inFlight = false
		var finalPending = false
		var manualStop = false
		var recordRetriesLeft = RECORD_RETRIES
		var retryTimer: Timer? = null
	}

	private var run: Run? = null

	init {
		name = PANEL_NAME
		layout = BorderLayout()
		border = BorderFactory.createTitledBorder("Metrics")
		// One figure per line, so the panel fits the narrow side dock of the main window (Issue #1421).
		// A figure wider than the panel scrolls sideways instead of being clipped.
		val rows =
			JPanel().apply {
				layout = BoxLayout(this, BoxLayout.Y_AXIS)
				border = BorderFactory.createEmptyBorder(0, GAP, 0, GAP)
				labels.values.forEach { label ->
					label.alignmentX = LEFT_ALIGNMENT
					add(label)
				}
			}
		scroll =
			JScrollPane(rows, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
				.apply {
					border = BorderFactory.createEmptyBorder()
					name = SCROLL_NAME
				}
		add(scroll, BorderLayout.CENTER)
		show(DashboardText.EMPTY)
	}

	/**
	 * Begin polling [metrics] for a run that has just started; the previous run's figures are cleared.
	 *
	 * @param trainWaitEnabled whether the run collects [MetricGroup.TRAIN_WAIT]; when not, the
	 *   capacity-pressure figures show a dash instead of the zero the snapshot carries
	 * @since Issue #675 (Goal 6 SP3)
	 */
	fun start(
		metrics: MetricsCollectionService,
		trainWaitEnabled: Boolean = true
	) {
		detach()
		val started = Run(metrics, trainWaitEnabled)
		run = started
		timer.start()
		poll(started, final = false)
	}

	/**
	 * End the polling after the run stopped, naturally or manually: one last poll, then the service is
	 * released. The last figures stay on screen. No-op when not polling.
	 *
	 * The last poll reads the run record after every stop: the stall and the end cause come from it
	 * whenever the run reached `RunEnded`. A final poll without a record retries the read (both stop
	 * kinds; [stop] runs before the simulation thread has delivered `RunEnded` after a manual stop).
	 * Without a record after the retries, the end cause reads "stopped manually" after a manual stop
	 * and a dash otherwise (Issue #1326).
	 *
	 * @param manual whether the user stopped the run; it only chooses the label when no record exists
	 * @since Issue #675 (Goal 6 SP3)
	 */
	fun stop(manual: Boolean) {
		timer.stop()
		val current = run ?: return
		current.manualStop = manual
		poll(current, final = true)
	}

	/**
	 * Stop polling at once, drop the service and clear the figures (a new context, or the editor).
	 *
	 * @since Issue #675 (Goal 6 SP3)
	 */
	fun detach() {
		timer.stop()
		run?.retryTimer?.stop()
		run = null
		show(DashboardText.EMPTY)
	}

	/** Whether the polling timer is running. @since Issue #675 (Goal 6 SP3) */
	val isPolling: Boolean get() = timer.isRunning

	/** The text currently shown by the label named [labelName]. @since Issue #675 (Goal 6 SP3) */
	fun textOf(labelName: String): String = labels.getValue(labelName).text

	/** Start one poll now (the timer's tick), unless one is still running. @since Issue #675 (Goal 6 SP3) */
	internal fun pollNow() {
		run?.let { poll(it, final = false) }
	}

	private fun poll(
		current: Run,
		final: Boolean
	) {
		if (current.inFlight) {
			if (final) current.finalPending = true
			return
		}
		current.inFlight = true
		val metrics = current.service
		object : SwingWorker<DashboardReading, Void>() {
			// Background thread: only the cross-thread-safe reads (plus the run record on the final poll).
			override fun doInBackground(): DashboardReading {
				val record = if (final) metrics.runRecord() else null
				return DashboardReading(
					snapshot = metrics.latestSnapshot(),
					contentions =
						metrics.latestOpenContentions().map { ContentionLine(it.trainId, it.owner, it.since) },
					runEnd = record,
					trainWaitEnabled = current.trainWaitEnabled
				)
			}

			// EDT.
			override fun done() {
				current.inFlight = false
				val reading = readOrNull(this)
				if (run !== current) return // detached or restarted while this poll ran
				if (final && reading?.runEnd == null && current.recordRetriesLeft > 0) {
					reading?.let { show(DashboardText.of(it)) }
					retryRecord(current)
					return
				}
				val shown = if (final) reading?.copy(stoppedManually = current.manualStop && reading.runEnd == null) else reading
				shown?.let { show(DashboardText.of(it)) }
				afterPoll(current, final)
			}
		}.execute()
	}

	/** Read the run record again after [RECORD_RETRY_MS], unless the run was detached or restarted. */
	private fun retryRecord(current: Run) {
		current.recordRetriesLeft--
		current.retryTimer =
			Timer(RECORD_RETRY_MS) { if (run === current) poll(current, final = true) }.apply {
				isRepeats = false
				start()
			}
	}

	/** The finished worker's reading, or `null` when the poll failed or was interrupted. */
	private fun readOrNull(worker: SwingWorker<DashboardReading, Void>): DashboardReading? =
		try {
			worker.get()
		} catch (e: ExecutionException) {
			logger.warn(e.cause ?: e) { "Metrics dashboard poll failed" }
			null
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			null
		}

	/** After a poll of the current run: a final poll ends it, a queued final poll starts now. */
	private fun afterPoll(
		current: Run,
		final: Boolean
	) {
		if (final) {
			run = null
		} else if (current.finalPending) {
			current.finalPending = false
			poll(current, final = true)
		}
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

		/** How often a final poll without a run record reads it again (Issue #1326). */
		internal const val RECORD_RETRIES = 10

		/** The pause between two reads of a missing run record, in milliseconds (Issue #1326). */
		private const val RECORD_RETRY_MS = 100
		const val PANEL_NAME = "metricsDashboardPanel"
		const val SCROLL_NAME = "metricsDashboardScroll"
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
