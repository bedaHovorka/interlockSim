/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Test Utility: JVM-only MockSimulationContext factory (InputStream overload)

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test infrastructure: 2025
*/

package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import java.io.InputStream
import org.koin.mp.KoinPlatformTools

/**
 * JVM-only factory for [MockSimulationContext] that accepts an [InputStream].
 *
 * InputStream is a JVM-only API so this helper lives in jvmMain. The portable class
 * and String/empty overloads are in core-test/commonMain. Existing jvmTest callers
 * of `createMockSimulationContext(TestFixtures.loadShuntingXml())` continue to compile
 * unchanged — they resolve to this extension.
 */
fun createMockSimulationContext(xml: InputStream): MockSimulationContext {
	val processFactory = KoinPlatformTools.defaultContext().get().get<SimulationProcessFactory>()
	val xmlString = xml.use { it.readBytes().decodeToString() }
	val defaultContext = CommonTestFixtures.parseSimulationContext(xmlString, processFactory)
	return MockSimulationContext(defaultContext)
}
