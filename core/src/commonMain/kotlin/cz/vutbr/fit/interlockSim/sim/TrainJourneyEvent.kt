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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

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
 * Qualified name of kDisco's `internal` `ProcessTerminatedException`, which `Process.terminate()` always
 * throws to unwind the terminated process. interlockSim cannot name the type, so
 * [rethrowProcessControl] recognises it by name; `TrainJourneyEventEmitTest` pins that a real
 * termination still passes both guards, so a kDisco rename fails a test instead of being swallowed.
 */
private const val KDISCO_PROCESS_TERMINATED = "cz.ksimulantenbande.kdisco.ProcessTerminatedException"

/**
 * Rethrows kDisco's process control from a guard around a kDisco bus listener: a
 * [CancellationException] and kDisco's `ProcessTerminatedException` (a listener terminating the
 * running process) must unwind the emitting process — the guards of [emitTrainJourneyEvent] and
 * [trainJourneyGuard] never swallow them.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
internal fun rethrowProcessControl(e: Exception) {
	if (e is CancellationException || e::class.qualifiedName == KDISCO_PROCESS_TERMINATED) throw e
}

/**
 * Send [event] on the kDisco event bus through [emitCustomGuarded]: announcing a journey step never
 * changes the run.
 */
internal fun emitTrainJourneyEvent(event: TrainJourneyEvent) = emitCustomGuarded(event)

/**
 * Send [payload] on the kDisco event bus with the top-level kDisco `emitCustom`, a no-op outside a
 * running simulation. Shared by every record-only announcement (journey steps of Issue #673, the
 * run-end accounting events of Issue #1304).
 *
 * Guarded so that announcing never changes the run. kDisco's bus calls its raw listeners with no
 * guard of its own, so a raw [SimulationEnvironment.onSimulationEvent] subscriber that throws would
 * otherwise end the process that emitted the payload -- and before Issue #673 nothing was emitted
 * there. Such a failure is logged at WARN and the process carries on; the raw subscribers after the
 * failing one miss this payload (kDisco stops its delivery loop).
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
				"listeners after the failing one miss this step (Issue #673)"
		}
	}
}

/**
 * The guard behind [onTrainJourneyEvent]: a [listener] that throws is logged at WARN and the event
 * still reaches the subscribers after it, so a faulty recorder never breaks the train process that
 * emitted the step — except kDisco's process control, which [rethrowProcessControl] passes through,
 * so terminating the running process from a listener still works.
 *
 * Kept as a named function so tests pin its contract on a bare kDisco `Simulation`, without a
 * [SimulationEnvironment].
 *
 * @since Issue #673 (Goal 6 SP2)
 */
internal fun trainJourneyGuard(listener: (TrainJourneyEvent) -> Unit): (TrainJourneyEvent) -> Unit =
	{ payload ->
		try {
			listener(payload)
		} catch (e: Exception) {
			rethrowProcessControl(e)
			logger.warn(e) { "A TrainJourneyEvent listener failed for '${payload.trainId}' (Issue #673)" }
		}
	}

/**
 * The raw kDisco listener behind [onTrainJourneyEvent]: carries the [TrainJourneyEvent] payload of
 * a [SimulationEvent.Custom] to the guarded [listener] and drops everything else. Creates the
 * guarded listener once, so each event stays one allocation.
 *
 * Kept as a named function so tests can wire the guarded typed path onto a bare kDisco `Simulation`,
 * without a [SimulationEnvironment].
 *
 * @since Issue #673 (Goal 6 SP2)
 */
internal fun trainJourneyRecipient(listener: (TrainJourneyEvent) -> Unit): (SimulationEvent) -> Unit {
	val guarded = trainJourneyGuard(listener)
	return { event ->
		val payload = (event as? SimulationEvent.Custom)?.payload
		if (payload is TrainJourneyEvent) guarded(payload)
	}
}

/**
 * Subscribe to [TrainJourneyEvent]s (Issue #673). Sugar over [SimulationEnvironment.onSimulationEvent],
 * with its contract: register before `run()`, delivered synchronously on the simulation thread.
 *
 * Each subscriber registered here is isolated — the full contract is on [trainJourneyGuard].
 *
 * @since Issue #673 (Goal 6 SP2)
 */
fun SimulationEnvironment.onTrainJourneyEvent(listener: (TrainJourneyEvent) -> Unit) =
	onSimulationEvent(trainJourneyRecipient(listener))
