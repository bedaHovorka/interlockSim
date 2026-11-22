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
import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.deliverContained
import cz.vutbr.fit.interlockSim.context.guardedRecipient
import cz.vutbr.fit.interlockSim.context.rethrowProcessControl
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * One step of a train's journey through the network (Issue #673, Goal 6 SP2): queued at its entry
 * InOut, admitted (its process started), exited at an InOut.
 *
 * Delivered as a kDisco `SimulationEvent.Custom` payload, synchronously on the simulation thread at
 * the moment of the step; subscribe through [onTrainJourneyEvent] before `run()`. A listener must
 * only record: it runs inside the train or loop process that emitted the step.
 *
 * InOuts are carried by name. A scenario may report [Admitted] and [Exited] without a prior
 * [Queued]: only `ShuntingLoop` and (since Issue #1304) `MultiTrainLoop` emit [Queued], while
 * [Admitted] and [Exited] come from [Train] itself and therefore fire for the trains of every
 * scenario.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
sealed interface TrainJourneyEvent {
	/**
	 * Name of the train ([Train.name]).
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	val trainId: String

	/**
	 * Simulation time of the step.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	val time: Double

	/**
	 * The train was placed in the queue at its entry InOut, before admission.
	 *
	 * @property entryInOut Name of the InOut the train enters at ([Timetable.getIn]).
	 * @property plannedExit Name of the InOut its timetable sends it to ([Timetable.getOut]).
	 * @since Issue #673 (Goal 6 SP2)
	 */
	data class Queued(
		override val trainId: String,
		val entryInOut: String,
		val plannedExit: String,
		override val time: Double
	) : TrainJourneyEvent

	/**
	 * The train's process was activated: the train was admitted to the network.
	 *
	 * @since Issue #673 (Goal 6 SP2)
	 */
	data class Admitted(
		override val trainId: String,
		override val time: Double
	) : TrainJourneyEvent

	/**
	 * The train's front reached an InOut after travelling at least one block: it left the network.
	 *
	 * @property actualExitInOut Name of the InOut the train left through.
	 * @since Issue #673 (Goal 6 SP2)
	 */
	data class Exited(
		override val trainId: String,
		val actualExitInOut: String,
		override val time: Double
	) : TrainJourneyEvent
}

private val logger = KotlinLogging.logger {}

/**
 * Send [event] on the kDisco event bus through [emitCustomGuarded]: announcing a journey step never
 * changes the run.
 */
internal fun emitTrainJourneyEvent(event: TrainJourneyEvent) = emitCustomGuarded(event)

/**
 * Send [payload] on the kDisco event bus with the top-level kDisco `emitCustom`, a no-op outside a
 * running simulation. Shared by every record-only announcement (journey steps of Issue #673, the
 * run-end accounting events of Issue #1304, the reservation kernel's `ReservationExpired` of Issue
 * #1310).
 *
 * Guarded so that announcing never changes the run. kDisco's bus calls its raw listeners with no
 * guard of its own, so a raw [SimulationEnvironment.onSimulationEvent] subscriber that throws would
 * otherwise end the process that emitted the payload -- and before Issue #673 nothing was emitted
 * there. Such a failure is logged at WARN and the process carries on. In a context run each listener
 * sits behind [deliverContained] (Issue #1361), which contains the failure first, so the listeners
 * after the failing one still get the payload; this guard catches only what reaches the emit on a bus
 * without that wrap (a bare kDisco `Simulation`), where the listeners after the failing one miss the
 * payload (kDisco stops its delivery loop).
 *
 * kDisco's process control is not swallowed: [rethrowProcessControl] rethrows a
 * `CancellationException` and kDisco's `ProcessTerminatedException`, and an [Error] is never caught
 * (ruling of the traffic-simulation-expert, 2026-10-31, option A).
 */
internal fun emitCustomGuarded(payload: Any) {
	try {
		emitCustom(payload)
	} catch (e: Exception) {
		rethrowProcessControl(e)
		logger.warn(e) {
			"A raw simulation event listener failed on $payload; the emitting process continues and the " +
				"listeners after the failing one on this unguarded bus miss this event (Issue #673)"
		}
	}
}

/**
 * The raw kDisco listener behind [onTrainJourneyEvent]: the typed lane's [guardedRecipient], so a
 * [listener] that throws is logged at WARN and the event still reaches the subscribers after it -- a
 * faulty recorder never breaks the train process that emitted the step -- while kDisco's process control
 * passes, so terminating the running process from a listener still works (Issue #1324).
 *
 * Kept as a named function so tests can wire the guarded typed path onto a bare kDisco `Simulation`,
 * without a [SimulationEnvironment].
 *
 * @since Issue #673 (Goal 6 SP2)
 */
internal fun trainJourneyRecipient(listener: (TrainJourneyEvent) -> Unit): (SimulationEvent) -> Unit =
	guardedRecipient(listener)

/**
 * Subscribe to [TrainJourneyEvent]s (Issue #673). Sugar over [SimulationEnvironment.onSimulationEvent],
 * with its contract: register before `run()`, delivered synchronously on the simulation thread.
 *
 * Each subscriber registered here is isolated — the full contract is on [trainJourneyRecipient].
 *
 * @since Issue #673 (Goal 6 SP2)
 */
fun SimulationEnvironment.onTrainJourneyEvent(listener: (TrainJourneyEvent) -> Unit) =
	onSimulationEvent(trainJourneyRecipient(listener))
