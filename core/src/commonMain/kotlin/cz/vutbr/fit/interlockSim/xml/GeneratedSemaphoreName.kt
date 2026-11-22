/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.xml

import cz.vutbr.fit.interlockSim.util.Point

/**
 * The name [XmlContextReader] gives a `RailSemaphore` element that has no `name` attribute
 * (Issue #1273, Goal 1B SP3 demand D6).
 *
 * The interlocking facade and the dispatcher address signals by name only, so an unnamed signal
 * could never be the endpoint of a route. The generated name is a pure function of the grid
 * position: stable across loads, independent of document order, and free of the global state of
 * the editor's `AutoNameGenerator` (whose names are `S1`, `S2`, ..., so the two never meet).
 *
 * [XmlContextWriter] omits a semaphore name equal to [at] for the semaphore's own position, unless
 * another node in the context writes the same name, so saving a loaded network does not write
 * names the user never wrote; the next load regenerates the omitted ones. Switches are not
 * auto-named (their names are written by hand, Issue #1279).
 *
 * The format is a file-format contract (Issue #1332): saved files with omitted names depend on the
 * next load regenerating the same name, so changing [at] would rename their signals.
 */
internal object GeneratedSemaphoreName {
	/** The name format, for log and error messages. */
	const val PATTERN = "S_<X>_<Y>"

	/** The generated name of an unnamed semaphore at [position], for example `S_15_10`. */
	fun at(position: Point): String = "S_${position.x}_${position.y}"
}
