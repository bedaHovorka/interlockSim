/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.testutil.NetworkResources

/**
 * XML resources embedded in the :fast-sim native binary.
 *
 * All strings are sourced from [NetworkResources] in :core-test — the single source of truth.
 * This ensures the embedded example XMLs stay in sync with test fixtures.
 *
 * @since Issue #415 (fast-sim native CLI)
 */
internal object EmbeddedResources {
	val VYHYBNA_XML: String = NetworkResources.VYHYBNA_XML
}
