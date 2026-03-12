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
	 * **Example usage:**
	 * ```kotlin
	 * val staticSep = unwrapToStatic(dynamicSeparator)
	 * if (staticSep1 === staticSep2) {
	 *     // Same underlying configuration
	 * }
	 * ```
	 *
	 * @param separator The separator to unwrap (can be dynamic wrapper, static object, or null)
	 * @return The static PathSeparator reference (unwrapped if input was dynamic, unchanged otherwise, null if input was null)
	 */
	fun unwrapToStatic(separator: PathSeparator?): PathSeparator? =
		when (separator) {
			null -> null
			is DynamicInOut -> separator.staticRef
			is DynamicRailSemaphore -> separator.staticRef
			is DynamicRailSwitch -> separator.staticRef
			else -> separator
		}
}
