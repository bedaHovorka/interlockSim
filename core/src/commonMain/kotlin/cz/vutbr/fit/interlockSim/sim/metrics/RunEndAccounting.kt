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

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.ExpiryTotalsQuery
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.SweeperReleaseKind
import cz.vutbr.fit.interlockSim.sim.TrainJourneyEvent
import cz.vutbr.fit.interlockSim.sim.onRunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.onRunEnded
import cz.vutbr.fit.interlockSim.sim.onTrainJourneyEvent
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The run-end counters of [DefaultMetricsCollectionService] (Issue #1304, Goal 6 SP6), kept in a
 * collaborator the service owns so the service itself only delegates.
 *
 * Counts from events: [TrainJourneyEvent]s, [RunAccountingEvent]s and [RunEnded]; the capacity comes
 * through [recordCapacity] at wiring time, before the kernel runs. The two expiry totals are not counted
 * from the `ReservationExpired` events: [summary] reads them from the expiry's own counters through
 * [expiryTotals] (Issue #1360), so a raw subscriber that throws before the metrics' own cannot hide an
 * expiry from the run end.
 * Always on (the CORE metric group): it subscribes for itself and does not depend on the journey
 * records. One instance counts one run: the service is created per simulation context and subscribes once, so
 * it is not reset for a second `run()`. Train names must be unique within a run (the set of trains on the
 * network is keyed by name). Single-threaded, like the service: driven by the simulation thread, read on it or after
 * the run.
 *
 * @param expiryTotals The expiry totals of the run, resolved on each [summary]; `null` when there are
 *   none (a service built without an environment): no expiry ran, so both totals read zero.
 */
internal class RunEndAccounting(
	private val expiryTotals: () -> ExpiryTotalsQuery? = { null }
) {
	private var trainsEntered = 0
	private var trainsExited = 0
	private var wrongExits = 0
	private var maxConcurrent = 0
	private var blockTransitions = 0
	private var staleReleases = 0
	private var partialReleases = 0
	private var stall: RunStall? = null
	private var capacityShown: Int? = null
	private var topologyCapacity: Int? = null
	private var runEnded: RunEnded? = null

	/** Planned exit per train, from its queue step; read by its exit for the wrong-exit count. */
	private val plannedExits: MutableMap<String, String> = mutableMapOf()

	/** Admitted trains that have not exited: the trains on the network. */
	private val onNetwork: MutableSet<String> = mutableSetOf()

	private val failedByKind: MutableMap<FailedReservationKind, Int> = mutableMapOf()
	private val entryFailuresByKind: MutableMap<MultiTrainLoop.EntryFailureKind, Int> = mutableMapOf()

	/** Subscribe every input stream of [env]; call once, before `run()`. */
	fun subscribe(env: SimulationEnvironment) {
		env.onTrainJourneyEvent(::onJourney)
		env.onRunAccountingEvent(::onAccounting)
		env.onRunEnded(::onRunEnded)
	}

	fun onJourney(event: TrainJourneyEvent) {
		when (event) {
			is TrainJourneyEvent.Queued -> {
				trainsEntered++
				plannedExits[event.trainId] = event.plannedExit
			}
			is TrainJourneyEvent.Admitted -> {
				onNetwork += event.trainId
				if (onNetwork.size > maxConcurrent) maxConcurrent = onNetwork.size
			}
			is TrainJourneyEvent.Exited -> {
				trainsExited++
				onNetwork -= event.trainId
				val planned = plannedExits.remove(event.trainId)
				if (planned != null && planned != event.actualExitInOut) wrongExits++
			}
		}
	}

	fun onAccounting(event: RunAccountingEvent) {
		when (event) {
			is RunAccountingEvent.RouteGranted -> blockTransitions++
			is RunAccountingEvent.ReservationRefused -> failedByKind.increment(event.kind)
			is RunAccountingEvent.EntryFailed -> entryFailuresByKind.increment(event.failure.kind)
			is RunAccountingEvent.Stalled -> onStalled(event)
			is RunAccountingEvent.SweeperReleased ->
				when (event.kind) {
					SweeperReleaseKind.STALE -> staleReleases++
					SweeperReleaseKind.PARTIAL -> partialReleases++
				}
			// Carried to the run end by RunEnded.endTimeReached, which the context derives from it.
			is RunAccountingEvent.EndTimeReached -> Unit
			// Performance facts (Issue #1306): counted by RunPerformanceAccounting, not run-end figures.
			is RunAccountingEvent.ReservePathCalled, is RunAccountingEvent.RouteSearchBudgetExceeded -> Unit
		}
	}

	fun onRunEnded(event: RunEnded) {
		runEnded = event
		val classification = classification(event)
		logger.info {
			"Run ended at t=${event.time}: $classification (end time reached ${event.endTimeReached}, " +
				"$trainsEntered entered, $trainsExited exited, ${onNetwork.size} still on the network, " +
				"stall ${stall != null}) (Issue #1304)"
		}
		// Issue #1457: the run-end residue of refused entries. INFO, not WARN: the worker already logged
		// the one WARNING per train when the wait passed its threshold.
		if (event.refusedEntries.isNotEmpty()) {
			logger.info {
				"Run ended with ${event.refusedEntries.size} train(s) waiting on a refused entry route: " +
					event.refusedEntries.joinToString { it.describe() }
			}
		}
	}

	/** The capacity the dispatcher is given next to the station's topology capacity (Issue #1276). */
	fun recordCapacity(
		shownToDispatcher: Int,
		topologyCapacity: Int
	) {
		capacityShown = shownToDispatcher
		this.topologyCapacity = topologyCapacity
	}

	/**
	 * The capacity recorded by [recordCapacity], or `null`. Written once at wiring time, before the run
	 * starts, so any thread that starts or follows the run reads it without the map copies [summary] makes.
	 */
	fun capacityShown(): Int? = capacityShown

	/**
	 * The figures so far, with the snapshot-owned ones taken from [snapshot] and the performance row,
	 * counted elsewhere (Issue #1306), from [performance].
	 */
	fun summary(
		snapshot: MetricsSnapshot,
		performance: RunPerformance? = null
	): RunEndSummary {
		val ended = runEnded
		val expiries = expiryTotals()
		return RunEndSummary(
			journeysCompleted = snapshot.completedTrains,
			trainsEntered = trainsEntered,
			trainsExited = trainsExited,
			wrongExits = wrongExits,
			maxConcurrentTrains = maxConcurrent,
			blockTransitions = blockTransitions,
			conflicts = snapshot.conflictCount,
			failedReservations = failedByKind.values.sum(),
			failedReservationsByKind = failedByKind.toMap(),
			unresolvedAtRunEnd = snapshot.unresolvedAtRunEndCount,
			entryFailures = entryFailuresByKind.values.sum(),
			entryFailuresByKind = entryFailuresByKind.toMap(),
			stall = stall,
			expiredHoldingCount = expiries?.expiredHoldingCount ?: 0,
			expiredTailCount = expiries?.expiredTailCount ?: 0,
			staleReleaseCount = staleReleases,
			partialReleaseCount = partialReleases,
			capacityShownToDispatcher = capacityShown,
			topologyCapacity = topologyCapacity,
			unfinishedJourneys = onNetwork.size,
			endTime = ended?.time,
			endTimeReached = ended?.endTimeReached ?: false,
			classification = ended?.let(::classification),
			performance = performance,
			refusedEntriesAtRunEnd = ended?.refusedEntries.orEmpty()
		)
	}

	private fun classification(ended: RunEnded): RunEndClassification =
		RunEndClassification.classify(
			stalled = stall != null,
			endTimeReached = ended.endTimeReached,
			unfinishedJourneys = onNetwork.size
		)

	private fun onStalled(event: RunAccountingEvent.Stalled) {
		// The watchdog reports once and then stops the run; keep the first report if ever a second came.
		if (stall == null) stall = RunStall(event.cycle, event.knot, event.persistedTicks, event.time)
	}

	private fun <K> MutableMap<K, Int>.increment(key: K) {
		this[key] = (this[key] ?: 0) + 1
	}

	private companion object {
		private val logger = KotlinLogging.logger {}
	}
}
