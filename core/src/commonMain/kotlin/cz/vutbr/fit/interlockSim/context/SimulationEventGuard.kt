/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import cz.ksimulantenbande.kdisco.SimulationEvent as KDiscoSimulationEvent

/*
 * The one listener-guard contract of the simulation event bus (Issue #1324), next to
 * SimulationEnvironment.onSimulationEvent, the subscription it guards: a listener's plain failure is
 * contained -- logged at WARN, the listeners after it still get the event, the emitting process carries on
 * -- while kDisco's process control passes and an Error is never caught. Every typed lane, the context's
 * delivery wrap (Issue #1361), the run-end delivery and the temporal-conflict fan-out go through it.
 */

private val logger = KotlinLogging.logger {}

/**
 * Qualified name of kDisco's `internal` `ProcessTerminatedException`, which `Process.terminate()` always
 * throws to unwind the terminated process. interlockSim cannot name the type, so [isProcessControl]
 * recognises it by name; `ListenerGuardContractTest` (commonTest, so on the JVM and on linuxX64) pins that a
 * real termination is recognised, so a kDisco rename fails a test instead of being contained.
 */
private const val KDISCO_PROCESS_TERMINATED = "cz.ksimulantenbande.kdisco.ProcessTerminatedException"

/**
 * Whether [e] is kDisco's process control -- a [CancellationException] or kDisco's
 * `ProcessTerminatedException` (a listener terminating the running process) -- rather than a failure. Takes
 * a Throwable so `LoopProcess` can share it while its net covers any non-control Throwable (Issue #1361).
 *
 * @since Issue #1361 (moved here by Issue #1324)
 */
internal fun isProcessControl(e: Throwable): Boolean =
	e is CancellationException || e::class.qualifiedName == KDISCO_PROCESS_TERMINATED

/**
 * Rethrows kDisco's process control ([isProcessControl]) from a guard around a listener: it must unwind
 * the emitting process, never be logged and continued. The first line of every catch of the contract.
 *
 * @since Issue #673 (Goal 6 SP2; moved here by Issue #1324)
 */
internal fun rethrowProcessControl(e: Exception) {
	if (isProcessControl(e)) throw e
}

/**
 * The delivery guard (Issue #1361): hands [event] to [listener] and contains a plain failure of the
 * listener -- logged at WARN -- so the listeners after it still get the event and the process that emitted
 * it carries on. kDisco's process control is rethrown ([rethrowProcessControl]) and an [Error] is never
 * caught.
 *
 * `DefaultSimulationContext` wraps with it each raw listener for `SimulationEvent.Custom` payloads (the
 * built-in kDisco events stay unguarded), each typed listener of a payload type, and the block listeners of
 * the run-end contention flush; the typed lanes deliver through it by [guardedRecipient]. Without it
 * kDisco's delivery ends at the first listener that throws.
 *
 * @since Issue #1361 (moved here by Issue #1324)
 */
internal fun <T> deliverContained(
	listener: (T) -> Unit,
	event: T
) {
	try {
		listener(event)
	} catch (e: Exception) {
		rethrowProcessControl(e)
		logger.warn(e) { "A simulation event listener failed on $event (Issue #1324)" }
	}
}

/**
 * The raw kDisco listener of a typed lane (Issue #1324): carries a `SimulationEvent.Custom` payload of type
 * [T] to [listener] behind [deliverContained] and drops everything else. The typed helpers --
 * `onTrainJourneyEvent`, `onRunAccountingEvent`, `onReservationExpired`, `onSwitchClaimsReleased`,
 * `onRunEnded` -- all hand one of these to [SimulationEnvironment.onSimulationEvent], so they share one
 * contract. Each lane keeps a named recipient function built on this one, so tests pin the lane on a bare
 * kDisco `Simulation`, without a [SimulationEnvironment].
 *
 * @since Issue #1324
 */
internal inline fun <reified T : Any> guardedRecipient(
	noinline listener: (T) -> Unit
): (KDiscoSimulationEvent) -> Unit =
	{ event ->
		val payload = (event as? KDiscoSimulationEvent.Custom)?.payload
		if (payload is T) deliverContained(listener, payload)
	}
