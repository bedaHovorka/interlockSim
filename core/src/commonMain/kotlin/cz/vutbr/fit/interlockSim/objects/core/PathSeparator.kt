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
 * "Oddelovac oddilu (i automaticky řízené prvky) uzel cesty z pohledu vlaku"
 */
interface PathSeparator :
	PathElement,
	Cell {
	/**
	 * @param from
	 * @return following segments - static
	 */
	fun possibleFollowers(from: Cell.Segment): Set<Cell.Segment>

	/**
	 * Returns true if this is a switch (RailSwitch), false for semaphores/InOut.
	 * Eliminates instanceof checks in AbstractPath for switch-specific logic.
	 *
	 * Used for path speed calculation where switches may affect allowed speed.
	 *
	 * @return true if this is a RailSwitch, false otherwise
	 */
	fun isSwitch(): Boolean = false

	/**
	 * PathSeparators update the previous separator reference but don't affect path speed.
	 *
	 * Overrides default [PathElement.contributeToPathMaxSpeed] to update the
	 * previous separator reference (used for directional speed calculations by Tracks).
	 *
	 * @param previousSeparator Ignored (will be replaced with this separator)
	 * @param currentMinSpeed Current minimum speed (returned unchanged)
	 * @return Contribution with this separator as new previous, speed unchanged
	 * @since 2026-02 (Issue #93 - eliminate instanceof checks)
	 */
	override fun contributeToPathMaxSpeed(
		previousSeparator: PathSeparator?,
		currentMinSpeed: Double
	): PathMaxSpeedContribution = PathMaxSpeedContribution(currentMinSpeed, this)
}
