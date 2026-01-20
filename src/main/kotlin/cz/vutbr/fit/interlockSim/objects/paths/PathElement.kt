/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths

/**
 * Element of {@link Path}
 *
 */
interface PathElement {
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
