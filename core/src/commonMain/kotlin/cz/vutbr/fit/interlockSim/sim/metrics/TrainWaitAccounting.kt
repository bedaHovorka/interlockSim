/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.ContentionQuery
import cz.vutbr.fit.interlockSim.context.navigation.OpenContention
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictDetectedEvent
import cz.vutbr.fit.interlockSim.sim.onRunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.onRunEnded
import cz.vutbr.fit.interlockSim.sim.onTrainJourneyEvent

/**
 * The capacity pressure at one instant (Issue #1305, Goal 6 SP7), sampled into every published
 * [MetricsSnapshot]: [MetricsSnapshot.queuedTrains], [MetricsSnapshot.blockedTrains] and
 * [MetricsSnapshot.openContentions].
 *
 * @since Issue #1305 (Goal 6 SP7)
 */
internal data class CapacityPressure(
	val queuedTrains: Int,
	val blockedTrains: Int,
	val openContentions: Int
) {
	companion object {
		val NONE = CapacityPressure(0, 0, 0)
	}
}

/**
 * The per-train wait figures and the capacity pressure of [DefaultMetricsCollectionService]
 * (Issue #1305, Goal 6 SP7): the [MetricGroup.TRAIN_WAIT] group, a collaborator the service owns
 * only when that group is on, so with it off nothing is subscribed, sampled or queried.
 *
 * From events alone ([TrainJourneyEvent]s, [RunAccountingEvent]s, [ReservationExpired], [RunEnded])
 * it keeps, per train:
 * - **queue time** -- queue step to admission, entry failure or run end;
 * - **time standing with no route** -- from a refusal of kind [FailedReservationKind.ALL_PATHS_BLOCKED]
 *   or [FailedReservationKind.NO_ROUTE] of an admitted train that has not exited, until its next
 *   route grant, its exit or the run end (ruling of the traffic-simulation-expert, 2026-11-01: derived
 *   from the existing events, no new event). A refusal before admission is queue time, not standing;
 *   further refusals inside an open interval do not restart it;
 * - **expiries** by kind.
 * The **blocked time** is read from the [contentions] query ([ContentionQuery.blockedSeconds]), which
 * closes each blocked episode exactly when the reservation service forgets it.
 *
 * Every handled block event samples the [CapacityPressure] and publishes the open contentions. The
 * end-of-run flush's events ([BlockEvent.ReservationConflictDetected.atRunEnd]) do not, so the last
 * sample keeps what was open just before the run ended. Conflict and run-accounting events refresh the
 * published contention list too, so a run with no block traffic still shows who waits.
 *
 * Single-threaded like the service, except [latestOpenContentions]: an immutable list behind a
 * volatile reference, safe from any thread.
 *
 * @param contentions The contention query of the run, resolved on each use; `null` when there is none
 *   (a service built without an environment), in which case blocked time and contentions read zero.
 * @since Issue #1305 (Goal 6 SP7)
 */
internal class TrainWaitAccounting(
	private val contentions: () -> ContentionQuery?
) {
	/** Queue-step time of the trains queued and not yet admitted or given up. */
	private val queuedSince: MutableMap<String, Double> = mutableMapOf()

	/** Time a queued train was given up by its loop (entry failure); its queue time ends there. */
	private val queueGivenUpAt: MutableMap<String, Double> = mutableMapOf()

	/** Admitted trains that have not exited. */
	private val onNetwork: MutableSet<String> = mutableSetOf()

	/** Start of the open no-route standing interval of a train. */
	private val standingSince: MutableMap<String, Double> = mutableMapOf()

	/** Closed no-route standing seconds per train. */
	private val standingSeconds: MutableMap<String, Double> = mutableMapOf()

	private val expiredHoldings: MutableMap<String, Int> = mutableMapOf()
	private val expiredTails: MutableMap<String, Int> = mutableMapOf()

	/** Simulation time of the latest event seen; open intervals are measured up to it before the run end. */
	private var latestTime = 0.0
	private var runEndTime: Double? = null

	private var lastPressure: CapacityPressure = CapacityPressure.NONE

	@kotlin.concurrent.Volatile
	private var publishedContentions: List<OpenContention> = emptyList()

	/** Subscribe every input stream of [env]; call once, before `run()`. */
	fun subscribe(env: SimulationEnvironment) {
		env.onTrainJourneyEvent(::onJourney)
		env.onRunAccountingEvent(::onAccounting)
		env.onReservationExpired(::onExpired)
		env.onRunEnded(::onRunEnded)
		// Through the raw stream, so the conflict-listener wiring of the dispatcher stays as it is.
		env.onSimulationEvent { event ->
			val payload = (event as? SimulationEvent.Custom)?.payload
			if (payload is ConflictDetectedEvent) onConflict(payload)
		}
	}

	/**
	 * A contention began: note its time, so open intervals are measured up to it, and republish the
	 * open contentions.
	 */
	fun onConflict(event: ConflictDetectedEvent) {
		see(event.time)
		refreshOpenContentions()
	}

	fun onJourney(event: TrainJourneyEvent) {
		see(event.time)
		when (event) {
			is TrainJourneyEvent.Queued -> queuedSince[event.trainId] = event.time
			is TrainJourneyEvent.Admitted -> {
				queuedSince -= event.trainId
				onNetwork += event.trainId
			}
			is TrainJourneyEvent.Exited -> {
				closeStanding(event.trainId, event.time)
				onNetwork -= event.trainId
			}
		}
	}

	fun onAccounting(event: RunAccountingEvent) {
		see(event.time)
		when (event) {
			is RunAccountingEvent.ReservationRefused -> onRefused(event)
			is RunAccountingEvent.RouteGranted -> closeStanding(event.trainId, event.time)
			is RunAccountingEvent.EntryFailed -> {
				val trainId = event.failure.trainName
				if (trainId != null && queuedSince.remove(trainId) != null) queueGivenUpAt[trainId] = event.time
			}
			else -> Unit
		}
		refreshOpenContentions()
	}

	fun onExpired(event: ReservationExpired) {
		see(event.time)
		val counts =
			when (event.kind) {
				ReservationExpired.Kind.WHOLE_HOLDING -> expiredHoldings
				ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL -> expiredTails
			}
		counts[event.trainId] = (counts[event.trainId] ?: 0) + 1
	}

	fun onRunEnded(event: RunEnded) {
		runEndTime = event.time
		standingSince.keys.toList().forEach { closeStanding(it, event.time) }
	}

	/** Sample the pressure for the snapshot about to be published; the end-of-run flush keeps the last sample. */
	fun onBlockEvent(event: BlockEvent) {
		if (event is BlockEvent.ReservationConflictDetected && event.atRunEnd) return
		see(event.time)
		val open = refreshOpenContentions()
		lastPressure =
			CapacityPressure(
				queuedTrains = queuedSince.size,
				blockedTrains = open.map { it.trainId }.distinct().size,
				openContentions = open.size
			)
	}

	/** Re-read the open contentions and publish them for other threads; returns what was published. */
	fun refreshOpenContentions(): List<OpenContention> {
		val open = contentions()?.openContentions().orEmpty()
		publishedContentions = open
		return open
	}

	/** The capacity pressure of the latest sample. */
	fun pressure(): CapacityPressure = lastPressure

	/** The open contentions as last published; safe from any thread. */
	fun latestOpenContentions(): List<OpenContention> = publishedContentions

	/**
	 * [records] with the wait figures filled in: queue time per journey, the per-train figures on each
	 * train's latest journey. Open intervals count up to the run end, or to the latest event seen.
	 */
	fun decorate(records: List<TrainJourneyRecord>): List<TrainJourneyRecord> {
		val now = runEndTime ?: latestTime
		val blocked = contentions()?.blockedSeconds(now).orEmpty()
		val latestJourney = records.withIndex().associate { (index, record) -> record.trainId to index }
		return records.mapIndexed { index, record ->
			val withQueue = record.copy(queueSeconds = queueSeconds(record, now))
			if (latestJourney[record.trainId] != index) return@mapIndexed withQueue
			val trainId = record.trainId
			val holdings = expiredHoldings[trainId] ?: 0
			val tails = expiredTails[trainId] ?: 0
			withQueue.copy(
				blockedSeconds = blocked[trainId] ?: 0.0,
				noRouteStandingSeconds = standing(trainId, now),
				expiredHoldingCount = holdings,
				expiredTailCount = tails,
				expiries = holdings + tails
			)
		}
	}

	private fun onRefused(event: RunAccountingEvent.ReservationRefused) {
		val noRoute = event.kind == FailedReservationKind.ALL_PATHS_BLOCKED || event.kind == FailedReservationKind.NO_ROUTE
		if (noRoute && event.trainId in onNetwork && event.trainId !in standingSince) {
			standingSince[event.trainId] = event.time
		}
	}

	private fun closeStanding(
		trainId: String,
		until: Double
	) {
		val since = standingSince.remove(trainId) ?: return
		standingSeconds[trainId] = (standingSeconds[trainId] ?: 0.0) + (until - since).coerceAtLeast(0.0)
	}

	private fun standing(
		trainId: String,
		now: Double
	): Double {
		val open = standingSince[trainId]?.let { (now - it).coerceAtLeast(0.0) } ?: 0.0
		return (standingSeconds[trainId] ?: 0.0) + open
	}

	private fun queueSeconds(
		record: TrainJourneyRecord,
		now: Double
	): Double? {
		val entry = record.entryTime ?: return null
		val end = record.admissionTime ?: queueGivenUpAt[record.trainId] ?: now
		return (end - entry).coerceAtLeast(0.0)
	}

	private fun see(time: Double) {
		if (time > latestTime) latestTime = time
	}
}
