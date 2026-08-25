package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * Shared SP2a.1 perception mapping from a [PathSeparator] ahead of a train to the
 * signal name a reactive train agent perceives (Issue #552).
 *
 * Centralises the `when (sep) { is DynamicRailSemaphore -> …; is DynamicInOut -> …;
 * is DynamicRailSwitch -> … }` branching so the live perception port
 * ([cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]) and the [Train] read-only
 * perception properties share a single implementation, and so the port can read the next
 * semaphore **once** per perception capture (M1) instead of once per facet.
 *
 * Also consumed by the dispatcher observation layer
 * ([cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector]) to name
 * path-reservation endpoints ([cz.vutbr.fit.interlockSim.objects.paths.PathInfo.start] /
 * [cz.vutbr.fit.interlockSim.objects.paths.PathInfo.target]) — those are typed as
 * [cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator] and may be
 * [DynamicRailSwitch] instances.
 *
 * A `null` [sep] (no reserved path) maps to `null`.
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 * @since Issue #958 (promoted to `public`, parameter widened to [PathSeparator],
 *   [DynamicRailSwitch] branch added so the dispatcher can reuse it)
 */
fun separatorName(sep: PathSeparator?): String? =
	when (sep) {
		is DynamicRailSemaphore -> sep.name.takeIf { it.isNotBlank() }
		is DynamicInOut -> sep.name.takeIf { it.isNotBlank() }
		is DynamicRailSwitch -> sep.name.takeIf { it.isNotBlank() }
		else -> null
	}

/**
 * Signal aspect perceived at [sep] — the train's **own next-separator** perception.
 *
 * [sep] is always a separator on the train's own reserved path ([Train.nextSemaphore] /
 * [Train.secondSemaphoreAhead] both read `reservedSeparatorsAhead`), so the train is the
 * reservation holder and is authorized to proceed whenever the signal is lit. This mapping
 * therefore returns the raw `signal` (proceed-when-lit), with **no** direction guard.
 *
 * Direction-awareness (Issue #812) is deliberately NOT applied here: a guard keyed to the
 * semaphore's static orientation would be a no-op (the query always matches the forward
 * direction), and one keyed to the stored reservation direction would incorrectly report STOP to
 * a reverse-travelling holder at its own semaphore. The display-truthfulness fix lives in the
 * **global** views instead — the canvas (`AnimationStateCapture.captureSignalState`,
 * `SimulationCellRenderer`) and the LLM dispatcher's `all_signal_aspects`
 * ([cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort.toReading] via
 * [cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore.authorizedDirection]).
 *
 * `null` when [sep] is `null` (no reserved path ahead).
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 * @since Issue #812 (direction-aware signal display — kept raw here; fix moved to canvas/port)
 */
internal fun separatorAspect(sep: OrientedPathSeparator?): Signal? =
	when (sep) {
		is DynamicRailSemaphore -> sep.signal
		is DynamicInOut -> sep.outSemaphore.signal
		else -> null
	}
