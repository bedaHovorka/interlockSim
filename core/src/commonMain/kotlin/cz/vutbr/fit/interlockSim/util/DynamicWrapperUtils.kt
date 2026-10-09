/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator

/**
 * Utility functions for working with dynamic wrapper objects.
 *
 * Part of Phase 4: Static/Dynamic property separation (bedaHovorka/interlockSim#92)
 *
 * These utilities help unwrap dynamic wrapper objects to their static references,
 * enabling identity-based comparison and caching operations.
 *
 * @since 2026-01-24
 */
object DynamicWrapperUtils {
	/**
	 * Unwrap a PathSeparator to its static reference for identity comparison.
	 *
	 * This function handles the common pattern of extracting static references from
	 * dynamic wrappers (DynamicInOut, DynamicRailSemaphore, DynamicRailSwitch).
	 * If the separator is already static (not a dynamic wrapper), it returns the input unchanged.
	 * If the input is null, returns null.
	 *
	 * **Use case:** Identity-based comparison and caching where we need to compare the
	 * underlying static configuration objects, not the runtime wrapper instances.
	 *
	 * For known-non-null input prefer [staticRefOf], which cannot return null.
	 *
	 * @param separator The separator to unwrap (can be dynamic wrapper, static object, or null)
	 * @return The static PathSeparator reference (unwrapped if input was dynamic, unchanged otherwise, null if input was null)
	 */
	fun unwrapToStatic(separator: PathSeparator?): PathSeparator? = separator?.let { staticRefOf(it) }

	/**
	 * Unwrap a non-null PathSeparator to its static reference (see [unwrapToStatic]).
	 *
	 * This is the non-null-input variant of [unwrapToStatic]: a static separator is
	 * returned unchanged, so the result is never null and call sites over non-null input
	 * need no null handling (no `?: x` fallback — that branch would be unreachable dead
	 * code).
	 *
	 * @param separator The separator to unwrap (dynamic wrapper or static object)
	 * @return The static PathSeparator reference (unwrapped if input was dynamic, unchanged otherwise)
	 */
	fun staticRefOf(separator: PathSeparator): PathSeparator =
		when (separator) {
			is DynamicInOut -> separator.staticRef
			is DynamicRailSemaphore -> separator.staticRef
			is DynamicRailSwitch -> separator.staticRef
			else -> separator
		}
}
