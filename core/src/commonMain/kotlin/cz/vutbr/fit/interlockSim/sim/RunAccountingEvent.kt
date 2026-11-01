/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.SimulationEvent
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.exceptions.SimulationStalled
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * A fact about the run that the metrics service counts toward its run-end accounting
 * (Issue #1304, Goal 6 SP6), emitted where no event existed before: the counters that lived on the
 * loop processes (route grants, refused reservations, entry failures), the watchdog stall, the
 * orphan-sweeper releases and the loop's end-time branch.
 *
 * Delivered as a kDisco `SimulationEvent.Custom` payload, synchronously on the simulation thread;
 * send it with [emitRunAccountingEvent] and subscribe through [onRunAccountingEvent] before `run()`.
 * A listener must only record: it runs inside the process that emitted the event.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
sealed interface RunAccountingEvent {
	/**
	 * Simulation time of the fact.
	 *
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	val time: Double

	/**
	 * A reservation request was granted: one dispatcher route grant in `ShuntingLoop` (counted there
	 * as a block transition), one entry-to-exit route in `MultiTrainLoop`.
	 *
	 * @property trainId Name of the train the route was granted to.
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	data class RouteGranted(
		val trainId: String,
		override val time: Double
	) : RunAccountingEvent

	/**
	 * A reservation request was refused.
	 *
	 * @property trainId Name of the train that asked.
	 * @property kind Why it was refused.
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	data class ReservationRefused(
		val trainId: String,
		val kind: FailedReservationKind,
		override val time: Double
	) : RunAccountingEvent

	/**
	 * `MultiTrainLoop` gave up the entry of a train (Issue #1148); [time] is the failure's own time.
	 *
	 * @property failure The structured record the loop also keeps in `getEntryFailures`.
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	data class EntryFailed(
		val failure: MultiTrainLoop.EntryFailure
	) : RunAccountingEvent {
		override val time: Double
			get() = failure.simTime
	}

	/**
	 * The deadlock watchdog is about to abort the run (Issue #1154): the data of [SimulationStalled].
	 *
	 * @property cycle Names of the trains in the wait-for cycle.
	 * @property knot Names of all deadlocked trains (Issue #1270).
	 * @property persistedTicks Dispatcher ticks the deadlock persisted.
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	data class Stalled(
		val cycle: List<String>,
		val knot: Set<String>,
		val persistedTicks: Int,
		override val time: Double
	) : RunAccountingEvent {
		/** @since Issue #1304 (Goal 6 SP6) */
		companion object {
			/**
			 * The event for a watchdog report.
			 *
			 * @since Issue #1304 (Goal 6 SP6)
			 */
			fun of(report: SimulationStalled): Stalled =
				Stalled(report.cycle, report.knot, report.persistedTicks, report.simTime)
		}
	}

	/**
	 * The dispatcher-agent orphan sweeper released a holding (Issue #847).
	 *
	 * @property trainId Owner of the released holding.
	 * @property kind Which of its counters the release adds to.
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	data class SweeperReleased(
		val trainId: String,
		val kind: SweeperReleaseKind,
		override val time: Double
	) : RunAccountingEvent

	/**
	 * The main loop reached its end time and is about to stop the kernel.
	 *
	 * @since Issue #1304 (Goal 6 SP6)
	 */
	data class EndTimeReached(
		override val time: Double
	) : RunAccountingEvent
}

/**
 * Which orphan-sweeper counter a [RunAccountingEvent.SweeperReleased] adds to.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
enum class SweeperReleaseKind {
	/** A whole un-travelled route held unchanged past the threshold (`staleReleaseCount`). */
	STALE,

	/** The un-travelled tail of a train that occupies the rest of its route (`partialReleaseCount`). */
	PARTIAL
}

/**
 * The run has ended: the kDisco kernel has stopped (Issue #1304, Goal 6 SP6).
 *
 * Not emitted on the bus -- `emitCustom` is a no-op once the kernel has stopped. The simulation
 * context delivers it directly to the raw listeners registered before `run()`, as a
 * `SimulationEvent.Custom` payload, after the end-of-run contention flush; subscribe through
 * [onRunEnded]. Delivered on the thread that ran the simulation.
 *
 * @property time Kernel clock at the end of the run.
 * @property endTimeReached `true` when the main loop announced [RunAccountingEvent.EndTimeReached]:
 *   it stopped the run at its end time, rather than the run ending early (queue drained, watchdog,
 *   manual or error stop).
 * @since Issue #1304 (Goal 6 SP6)
 */
data class RunEnded(
	val time: Double,
	val endTimeReached: Boolean
)

private val logger = KotlinLogging.logger {}

/**
 * Send [event] on the kDisco event bus, guarded so that announcing it never changes the run (see
 * [emitCustomGuarded]); a no-op outside a running simulation. Public so the `:dispatcher-agent`
 * orphan sweeper can announce its releases without a kDisco dependency of its own.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
fun emitRunAccountingEvent(event: RunAccountingEvent) = emitCustomGuarded(event)

/**
 * The raw kDisco listener behind [onRunAccountingEvent]: carries the [RunAccountingEvent] payload of a
 * [SimulationEvent.Custom] to [listener] and drops everything else. A [listener] that throws an
 * ordinary exception is logged at WARN; kDisco's process control (`CancellationException`, kDisco's
 * `ProcessTerminatedException`) is rethrown by [rethrowProcessControl], consistently with
 * [trainJourneyGuard], so [emitCustomGuarded] can unwind the emitting process.
 *
 * Kept as a named function so tests can wire it onto a bare kDisco `Simulation`.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
internal fun runAccountingRecipient(listener: (RunAccountingEvent) -> Unit): (SimulationEvent) -> Unit =
	{ event ->
		val payload = (event as? SimulationEvent.Custom)?.payload
		if (payload is RunAccountingEvent) {
			try {
				listener(payload)
			} catch (e: Exception) {
				rethrowProcessControl(e)
				logger.warn(e) { "A RunAccountingEvent listener failed on $payload (Issue #1304)" }
			}
		}
	}

/**
 * Subscribe to [RunAccountingEvent]s. Sugar over [SimulationEnvironment.onSimulationEvent], with its
 * contract: register before `run()`, delivered synchronously on the simulation thread. A [listener]
 * that throws is logged at WARN and the event still reaches the subscribers after it, except for
 * kDisco's process control, which [runAccountingRecipient] rethrows.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
fun SimulationEnvironment.onRunAccountingEvent(listener: (RunAccountingEvent) -> Unit) =
	onSimulationEvent(runAccountingRecipient(listener))

/**
 * Subscribe to [RunEnded]. Sugar over [SimulationEnvironment.onSimulationEvent]: register before
 * `run()`. A [listener] that throws is logged at WARN.
 *
 * @since Issue #1304 (Goal 6 SP6)
 */
fun SimulationEnvironment.onRunEnded(listener: (RunEnded) -> Unit) =
	onSimulationEvent { event ->
		val payload = (event as? SimulationEvent.Custom)?.payload
		if (payload is RunEnded) {
			try {
				listener(payload)
			} catch (e: Exception) {
				logger.warn(e) { "A RunEnded listener failed on $payload (Issue #1304)" }
			}
		}
	}
