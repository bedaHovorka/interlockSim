/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.ports.DispatchLoopActuatorPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of [DispatchLoopActuatorPort] backed by the
 * [ActuatorCommandQueue] inter-thread handoff mechanism.
 *
 * Posts a [DispatchDecision.ApproveTrain] to [commandQueue] (fire-and-forget) on the
 * caller's thread; [DispatchDecisionApplier] drains and applies it on the kDisco
 * simulation thread. This satisfies the threading contract: callers on the agent driver
 * thread never touch live simulation state.
 *
 * ## Typical wiring
 *
 * ```kotlin
 * val queue = ActuatorCommandQueue()
 * val actuatorPort: DispatchLoopActuatorPort = DefaultDispatchLoopActuatorPort(queue)
 * ```
 *
 * @param commandQueue Thread-safe queue to which decisions are posted (one per context).
 *
 * @see DispatchLoopActuatorPort
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class DefaultDispatchLoopActuatorPort(
	private val commandQueue: ActuatorCommandQueue
) : DispatchLoopActuatorPort {

	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override fun approveTrain(trainId: String): Boolean {
		require(trainId.isNotBlank()) { "trainId must not be blank" }
		val decision = DispatchDecision.ApproveTrain(trainId)
		logger.debug { "DefaultDispatchLoopActuatorPort.approveTrain: posting ApproveTrain(trainId=$trainId)" }
		return commandQueue.postAll(listOf(decision))
	}
}
