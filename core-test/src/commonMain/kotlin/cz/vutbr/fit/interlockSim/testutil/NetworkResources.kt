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

	/** Network with a horizontal semaphore. */
	val SEMAPHORE_BASIC_XML: String by lazy { fixture("semaphore-basic.xml") }

	/** Two parallel tracks with independent InOut pairs. */
	val TWO_TRACKS_PARALLEL_XML: String by lazy { fixture("two-tracks-parallel.xml") }

	/** Small grid with two InOut elements (used for grid/cell tests). */
	val EMPTY_GRID_XML: String by lazy { fixture("empty-grid.xml") }
}
