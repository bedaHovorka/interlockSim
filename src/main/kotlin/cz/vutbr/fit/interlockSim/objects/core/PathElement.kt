/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

/**
 * Element of {@link Path}
 *
 * Path elements can be either:
 * - **Tracks**: Physical track sections that contribute length and have speed limits
 * - **PathSeparators**: Junction points (switches, semaphores, InOut) that don't have physical length
 *
 * To avoid instanceof checks, PathElement provides polymorphic methods for path calculations.
 * Implementations provide appropriate behavior (Tracks contribute values, PathSeparators return neutral values).
 *
 * @since 2006-2007 (original thesis)
 * @see Track for track-specific behavior
 * @see PathSeparator for separator-specific behavior
 */
interface PathElement {
	/**
	 * Contributes this element's length to cumulative path length calculation.
	 *
	 * **Implementation contract:**
	 * - Tracks: Return physical length in meters
	 * - PathSeparators: Return 0.0 (no physical length)
	 *
	 * This method enables polymorphic path length calculation without instanceof checks.
	 *
	 * @return Length contribution in meters (0.0 for non-Track elements)
	 * @since 2026-02 (Issue #93 - eliminate instanceof checks)
	 * @see Track.length for actual track length
	 */
	fun contributeToPathLength(): Double = 0.0

	/**
	 * Contributes to cumulative path max speed calculation.
	 *
	 * Path max speed is the minimum of all track speeds along the path, considering
	 * direction (via previousSeparator).
	 *
	 * **Implementation contract:**
	 * - Tracks: Return track's max speed for given direction, considering previous separator
	 * - PathSeparators: Update previousSeparator reference, don't affect cumulative min speed
	 *
	 * This method enables polymorphic path speed calculation without instanceof checks.
	 *
	 * **Algorithm:**
	 * ```
	 * var minSpeed = Double.MAX_VALUE
	 * var prevSep: PathSeparator? = null
	 * for (element in path) {
	 *     val contribution = element.contributeToPathMaxSpeed(prevSep, minSpeed)
	 *     minSpeed = contribution.minSpeed
	 *     prevSep = contribution.updatedPreviousSeparator
	 * }
	 * return minSpeed
	 * ```
	 *
	 * @param previousSeparator Previous path separator for directional speed (null at path start)
	 * @param currentMinSpeed Current minimum speed along path so far
	 * @return Contribution result with updated min speed and separator reference
	 * @since 2026-02 (Issue #93 - eliminate instanceof checks)
	 * @see Track.maxSpeed for actual track speed limits
	 */
	fun contributeToPathMaxSpeed(
		previousSeparator: PathSeparator?,
		currentMinSpeed: Double
	): PathMaxSpeedContribution = PathMaxSpeedContribution(currentMinSpeed, previousSeparator)

	companion object {
		/**
		 * model constant
		 * @deprecated Use [cz.vutbr.fit.interlockSim.domain.COMMON_MAX_SPEED] from domain.PhysicsConstants
		 */
		@Deprecated(
			"Use COMMON_MAX_SPEED from domain.PhysicsConstants",
			ReplaceWith("COMMON_MAX_SPEED", "cz.vutbr.fit.interlockSim.domain.COMMON_MAX_SPEED")
		)
		const val COMMON_MAX_SPEED = cz.vutbr.fit.interlockSim.domain.COMMON_MAX_SPEED

		/**
		 * model constant
		 * @deprecated Use [cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED] from domain.PhysicsConstants
		 */
		@Deprecated(
			"Use ABSOLUTE_MAX_SPEED from domain.PhysicsConstants",
			ReplaceWith("ABSOLUTE_MAX_SPEED", "cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED")
		)
		const val ABSOLUTE_MAX_SPEED = cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED

		/**
		 * model constant
		 * @deprecated Use [cz.vutbr.fit.interlockSim.domain.MINIMAL_MAX_SPEED] from domain.PhysicsConstants
		 */
		@Deprecated(
			"Use MINIMAL_MAX_SPEED from domain.PhysicsConstants",
			ReplaceWith("MINIMAL_MAX_SPEED", "cz.vutbr.fit.interlockSim.domain.MINIMAL_MAX_SPEED")
		)
		const val MINIMAL_MAX_SPEED = cz.vutbr.fit.interlockSim.domain.MINIMAL_MAX_SPEED
	}
}

/**
 * Result of [PathElement.contributeToPathMaxSpeed] calculation.
 *
 * Encapsulates both the updated minimum speed and the updated previous separator reference,
 * allowing path speed calculation to proceed without instanceof checks.
 *
 * @property minSpeed Updated minimum speed after considering this element
 * @property updatedPreviousSeparator Updated previous separator reference (used for directional speed)
 * @since 2026-02 (Issue #93 - eliminate instanceof checks)
 * @see PathElement.contributeToPathMaxSpeed
 */
data class PathMaxSpeedContribution(
	val minSpeed: Double,
	val updatedPreviousSeparator: PathSeparator?
)
