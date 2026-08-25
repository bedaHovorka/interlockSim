/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory

/**
 * Loads `vyhybna.xml` into a fresh [DefaultSimulationContext] through the non-injected
 * dispatcher-agent wiring ([XMLContextFactory] + [DefaultSimulationContext.fromEditingContext]).
 *
 * Every dispatcher-agent test that drives the shunting loop needs exactly this two-step chain,
 * and before Issue #955 each one carried its own private copy of it together with a private
 * `XMLContextFactory` / `DefaultSimulationProcessFactory` pair. The defaults here reproduce that
 * wiring verbatim, so call sites keep their previous behaviour while the chain lives in one place.
 *
 * Call [DefaultSimulationContext.getInOuts] on the result before constructing a
 * `ShuntingLoop` — the dynamic wrapper map must be initialised first.
 *
 * The caller owns the returned context and must close it (`use { }` or an `@AfterEach` teardown).
 */
fun newShuntingLoopContext(
	xmlContextFactory: XMLContextFactory = XMLContextFactory(),
	processFactory: DefaultSimulationProcessFactory = DefaultSimulationProcessFactory()
): DefaultSimulationContext =
	TestFixtures.loadShuntingXml().use { xmlStream ->
		val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
		DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
	}
