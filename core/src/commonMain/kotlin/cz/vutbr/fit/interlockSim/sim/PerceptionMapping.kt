package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator

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
 * Signal aspect perceived at [sep]: the semaphore's own aspect for a [DynamicRailSemaphore],
 * or the [DynamicInOut.outSemaphore] aspect for an InOut endpoint. `null` when [sep] is `null`.
 *
 * @since Issue #552 (SP2a.1 — Goal 10 train perception)
 */
internal fun separatorAspect(sep: OrientedPathSeparator?): Signal? =
	when (sep) {
		is DynamicRailSemaphore -> sep.signal
		is DynamicInOut -> sep.outSemaphore.signal
		else -> null
	}
