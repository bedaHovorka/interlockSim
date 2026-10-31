/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.util.Util
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory

/**
 * Loads any network [xml] (a `NetworkResources` / `BuiltinNetworks` constant) into a fresh
 * [DefaultSimulationContext] **without Koin**, through the same `XMLContextFactory` +
 * [DefaultSimulationContext.fromEditingContext] chain [loadShuntingLoopContext] uses for
 * `vyhybna.xml`. Issue #1155 renders the dispatcher prompt for stations other than vyhybna.
 *
 * The caller owns the returned context and must close it (`use { … }`).
 */
fun loadNetworkContext(xml: String): DefaultSimulationContext =
	xml.byteInputStream().use { stream ->
		Util.assertInstanceOf<EditingContext>(XMLContextFactory().createContext(stream)).use { editingContext ->
			DefaultSimulationContext.fromEditingContext(editingContext, DefaultSimulationProcessFactory())
		}
	}
