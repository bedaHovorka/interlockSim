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

/**
 * One step of a train's journey through the network (Issue #673, Goal 6 SP2): queued at its entry
 * InOut, admitted (its process started), exited at an InOut.
 *
 * Delivered as a kDisco `SimulationEvent.Custom` payload, synchronously on the simulation thread at
 * the moment of the step; subscribe through [onTrainJourneyEvent] before `run()`. A listener must
 * only record: it runs inside the train or loop process that emitted the step.
 *
 * InOuts are carried by name. A scenario may report [Admitted] and [Exited] without a prior
 * [Queued]: only `ShuntingLoop` emits [Queued] today, while [Admitted] and [Exited] come from
 * [Train] itself and therefore fire for the trains of every scenario.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
sealed interface TrainJourneyEvent {
	/** Name of the train ([Train.name]). */
	val trainId: String

	/** Simulation time of the step. */
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
 * Send [event] on the kDisco event bus. The top-level kDisco `emitCustom`, so a no-op outside a
 * running simulation and never a failure for the emitting process.
 */
internal fun emitTrainJourneyEvent(event: TrainJourneyEvent) = emitCustom(event)

/**
 * Subscribe to [TrainJourneyEvent]s (Issue #673). Sugar over [SimulationEnvironment.onSimulationEvent],
 * with its contract: register before `run()`, delivered synchronously on the simulation thread.
 *
 * Each subscriber registered here is isolated: a [listener] that throws is logged at WARN and the
 * event still reaches the subscribers after it, so a faulty recorder never breaks the train process
 * that emitted the step.
 *
 * @since Issue #673 (Goal 6 SP2)
 */
fun SimulationEnvironment.onTrainJourneyEvent(listener: (TrainJourneyEvent) -> Unit) =
	onSimulationEvent { event ->
		val payload = (event as? SimulationEvent.Custom)?.payload
		if (payload is TrainJourneyEvent) {
			try {
				listener(payload)
			} catch (e: Exception) {
				logger.warn(e) { "A TrainJourneyEvent listener failed for '${payload.trainId}' (Issue #673)" }
			}
		}
	}
