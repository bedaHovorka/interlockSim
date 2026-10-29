/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.BuiltinNetworks
import cz.vutbr.fit.interlockSim.util.Resources

/**
 * Canonical XML network definitions shared across test fixtures.
 *
 * Source of truth is the fixture files under
 * `core-test/src/commonMain/resources/cz/vutbr/fit/interlockSim/xml/fixtures/`.
 * No XML string literals live in this file — both JVM (classpath) and
 * linuxX64 (filesystem) load the same content via [Resources.read].
 */
object NetworkResources {
	private fun fixture(name: String): String =
		Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/$name")

	/** Shunting loop — delegates to [BuiltinNetworks.VYHYBNA_XML] (production asset). */
	val VYHYBNA_XML: String get() = BuiltinNetworks.VYHYBNA_XML

	/** Two-node linear track connecting InOut A and InOut B. */
	val LINEAR_TRACK_XML: String by lazy { fixture("linear-track.xml") }

	/** Minimal two-InOut network without any track blocks. */
	val MINIMAL_NETWORK_XML: String by lazy { fixture("minimal-network.xml") }

	/** Network with a single InOut element (used for InOut validation tests). */
	val SINGLE_INOUT_XML: String by lazy { fixture("single-inout.xml") }

	/** Empty network with no InOut elements (used for validation error tests). */
	val ZERO_INOUTS_XML: String by lazy { fixture("zero-inouts.xml") }

	/** Network with a simple right-diverging switch. */
	val SWITCH_BASIC_XML: String by lazy { fixture("switch-basic.xml") }

	/** Network with a switch flanked by a semaphore and an InOut on each side (no platform track). */
	val SWITCH_BETWEEN_SEMAPHORES_XML: String by lazy { fixture("switch-between-semaphores.xml") }

	/** Network with a horizontal semaphore. */
	val SEMAPHORE_BASIC_XML: String by lazy { fixture("semaphore-basic.xml") }

	/** Two parallel tracks with independent InOut pairs. */
	val TWO_TRACKS_PARALLEL_XML: String by lazy { fixture("two-tracks-parallel.xml") }

	/** Small grid with two InOut elements (used for grid/cell tests). */
	val EMPTY_GRID_XML: String by lazy { fixture("empty-grid.xml") }

	/** Legacy network with unnamed semaphores/switches and no track blocks (cell-only graph). */
	val LEGACY_NETWORK_NO_NAMES_XML: String by lazy { fixture("legacy-network-no-names.xml") }

	/** Station-scale Praha hlavní nádraží network (117 blocks, 50 switches, 11 InOuts). */
	val PRAHA_HLAVNI_NADRAZI_XML: String by lazy { fixture("praha-hlavni-nadrazi.xml") }

	/** Červený Újezd station network. */
	val CERVENY_UJEZD_XML: String by lazy { fixture("cerveny_ujezd.xml") }

	/**
	 * Vyhybna with an automatic block section on the open line (signals facing the same way) and a
	 * two-block-deep throat; only its two real station tracks count (Issue #1192).
	 */
	val STATION_THROAT_AND_AUTOMATIC_BLOCK_XML: String by lazy {
		fixture("station-throat-and-automatic-block.xml")
	}

	/** Vyhybna whose signal next to InOut A faces away from an incoming train (Issue #1192). */
	val STATION_HOME_SIGNAL_FACING_AWAY_XML: String by lazy { fixture("station-home-signal-facing-away.xml") }
}
