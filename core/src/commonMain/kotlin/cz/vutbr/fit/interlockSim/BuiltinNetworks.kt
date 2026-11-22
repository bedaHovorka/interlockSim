/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import cz.vutbr.fit.interlockSim.util.Resources

/**
 * Access to built-in railway network XML examples.
 *
 * Source of truth is the resource file at
 * `core/src/commonMain/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml`.
 * Loaded via multiplatform [Resources.read] so both JVM (classpath) and
 * linuxX64 (filesystem) see identical content.
 *
 * @since Issue #415 (fast-sim native CLI)
 */
object BuiltinNetworks {
	/** Classpath location of the built-in shunting-loop network (vyhybna.xml). Single source of truth. */
	const val VYHYBNA_XML_PATH = "cz/vutbr/fit/interlockSim/resource/vyhybna.xml"

	/** Shunting loop network (vyhybna.xml). Used by ShuntingLoop simulation and fast-sim embedded example. */
	val VYHYBNA_XML: String by lazy {
		Resources.read(VYHYBNA_XML_PATH)
	}

	/** Classpath location of the built-in Cerveny Ujezd station network (Goal 1B SP3.2, Issue #1164). */
	const val CERVENY_UJEZD_XML_PATH = "cz/vutbr/fit/interlockSim/resource/cerveny_ujezd.xml"

	/**
	 * Cerveny Ujezd station (4 InOuts, 12 switches, 12 signals). Used by the `cervenyUjezd` example of the
	 * desktop CLI and GUI and of the `:fast-sim` native binary. Sole XML copy since the duplicate-file
	 * relink of PR #1287 — `NetworkResources.CERVENY_UJEZD_XML` delegates here.
	 */
	val CERVENY_UJEZD_XML: String by lazy {
		Resources.read(CERVENY_UJEZD_XML_PATH)
	}

	/**
	 * Classpath location of the Praha hlavni nadrazi Station variant (Goal 1B SP10.2, Issue #1366):
	 * derived from the hand-tuned `praha-hlavni-nadrazi.xml` test fixture so that ShuntingLoop can
	 * carry it. Not the real station plan; see the file's header comment.
	 */
	const val PRAHA_HLAVNI_NADRAZI_STATION_XML_PATH =
		"cz/vutbr/fit/interlockSim/resource/praha-hlavni-nadrazi-station.xml"

	/**
	 * Praha hlavni nadrazi Station variant (11 InOuts, 50 switches, 29 signals, capacity 9). Used by
	 * the `prahaHlavniNadrazi` examples of the desktop CLI and GUI; the sole XML copy --
	 * `NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML` delegates here.
	 */
	val PRAHA_HLAVNI_NADRAZI_STATION_XML: String by lazy {
		Resources.read(PRAHA_HLAVNI_NADRAZI_STATION_XML_PATH)
	}
}
