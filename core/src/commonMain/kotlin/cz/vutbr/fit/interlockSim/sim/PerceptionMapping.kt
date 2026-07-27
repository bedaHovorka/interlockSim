package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.anti

/**
 * Shared SP2a.1 perception mapping from an [OrientedPathSeparator] ahead of a train to the
 * signal name/aspect a reactive train agent perceives (Issue #552).
 *
 * Centralises the `when (sep) { is DynamicRailSemaphore -> …; is DynamicInOut -> … }` branching so
 * the live perception port ([cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]) and the
 * [Train] read-only perception properties share a single implementation, and so the port can read
 * the next semaphore **once** per perception capture (M1) instead of once per facet.
 *
 * A `null` [sep] (no reserved path) maps to `null` on both facets.
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 */
internal fun separatorName(sep: OrientedPathSeparator?): String? =
	when (sep) {
		is DynamicRailSemaphore -> sep.name.takeIf { it.isNotBlank() }
		is DynamicInOut -> sep.name.takeIf { it.isNotBlank() }
		else -> null
	}

/**
 * Signal aspect perceived at [sep] **from the canonical forward approach direction**:
 * the semaphore's aspect for a [DynamicRailSemaphore] (only when the signal authorizes
 * travel in the forward direction `anti(direction()) → direction()`), or the
 * [DynamicInOut.outSemaphore] aspect for an InOut endpoint.
 *
 * Returns [Signal.STOP] when the semaphore is present but its current signal was not
 * authorized for the forward approach direction — preventing a train (or the reactive
 * decision policy) from treating another train's grant in the opposite direction as a
 * proceed indication for itself.
 *
 * `null` when [sep] is `null` (no reserved path ahead).
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 * @since Issue #812 (direction-aware signal display — replaced raw `signal` reads)
 */
internal fun separatorAspect(sep: OrientedPathSeparator?): Signal? =
	when (sep) {
		is DynamicRailSemaphore ->
			if (sep.isAllowingFor(anti(sep.direction()), sep.direction())) sep.signal else Signal.STOP
		is DynamicInOut ->
			if (sep.outSemaphore.isAllowingFor(anti(sep.direction()), sep.direction())) {
				sep.outSemaphore.signal
			} else {
				Signal.STOP
			}
		else -> null
	}
