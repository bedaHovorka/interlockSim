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
 * The name an unnamed `RailSwitch` gets from its grid position (Issue #1473, Goal 9B SP4.7), the
 * switch twin of [GeneratedSemaphoreName].
 *
 * A command, a snapshot and a typed denial can only name an element that has a name. The
 * generated name is a pure function of the grid position: stable across loads, equal on JVM and
 * native, independent of document order, and free of the global counter of the editor's
 * `AutoNameGenerator` (`SW1`, `SW2`, ..., which depends on the order switches were drawn).
 *
 * - [XmlContextReader] gives an unnamed switch the [switchAt] form of its position at load, with
 *   the same clash check against explicit names as for semaphores; the name is never written
 *   back: [XmlContextWriter] omits a switch name equal to the switch's own [switchAt] value.
 * - A context built in code (tests, the editor) keeps the blank name; a consumer that addresses
 *   switches by name falls back through [switchName].
 *
 * Like [GeneratedSemaphoreName.at], the format is a file-format contract: saved files with omitted
 * names depend on the next load regenerating the same name.
 */
internal object GeneratedElementName {
	/** The switch name format, for log and error messages. */
	const val SWITCH_PATTERN = "SW_<X>_<Y>"

	/** The generated name of an unnamed switch at [position], for example `SW_20_10`. */
	fun switchAt(position: Point): String = "SW_${position.x}_${position.y}"

	/** The switch's own [name] when it has one, else the generated name of its [position]. */
	fun switchName(
		name: String,
		position: Point
	): String = name.ifBlank { switchAt(position) }
}
