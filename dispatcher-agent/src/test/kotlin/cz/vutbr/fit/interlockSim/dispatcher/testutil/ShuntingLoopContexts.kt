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
import cz.vutbr.fit.interlockSim.testutil.TestFixtures

/**
 * Loads `vyhybna.xml` into a fresh [DefaultSimulationContext] **without Koin**, through
 * [TestFixtures.newShuntingSimulationContext] with its default `XMLContextFactory` /
 * `DefaultSimulationProcessFactory` pair — the same wiring a default-constructed
 * [LiftedStackFixture.loadShuntingLoopContext] uses. Replaces the private copies that eight
 * dispatcher-agent test classes used to carry (Issue #1124).
 *
 * Call [DefaultSimulationContext.getInOuts] on the result before constructing a `ShuntingLoop`.
 * The caller owns the returned context and must close it (`use { … }`, or `tracked()` in a
 * [DispatcherKoinTestBase] subclass).
 */
fun loadShuntingLoopContext(): DefaultSimulationContext = TestFixtures.newShuntingSimulationContext()
