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

import cz.vutbr.fit.interlockSim.BuiltinNetworks

/**
 * XML network resources embedded in the native linuxX64 binary.
 *
 * Delegates to [BuiltinNetworks] — the single source of truth in :core/commonMain.
 * :core-test mirrors the same constants via [cz.vutbr.fit.interlockSim.testutil.NetworkResources].
 *
 * @since Issue #415 (fast-sim native CLI)
 */
internal object EmbeddedResources {
	val VYHYBNA_XML = BuiltinNetworks.VYHYBNA_XML
}
