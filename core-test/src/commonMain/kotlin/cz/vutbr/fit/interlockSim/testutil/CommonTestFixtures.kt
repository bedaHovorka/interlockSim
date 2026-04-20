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

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.xml.XmlContextReader

/**
 * Cross-platform test fixture helpers for common parsing and context-creation patterns.
 *
 * XML strings live in [NetworkResources] — use that directly for raw XML.
 * This object provides only parsing/factory helpers that are used across modules.
 * It is intentionally in commonMain of :core-test so it is available to both JVM
 * and linuxX64 test code without duplication.
 */
object CommonTestFixtures {

	private val reader = XmlContextReader()

	fun parseEditingContext(xml: String): DefaultEditingContext = reader.parse(xml)

	fun parseEditingContext(
		xml: String,
		skipStructuralValidation: Boolean,
	): DefaultEditingContext = reader.parse(xml, skipStructuralValidation)

	fun parseSimulationContext(
		xml: String,
		processFactory: SimulationProcessFactory,
	): DefaultSimulationContext {
		return reader.parse(xml).use { editingCtx ->
			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
		}
	}

	fun createEmptySimulationContext(
		processFactory: SimulationProcessFactory,
	): DefaultSimulationContext {
		return DefaultEditingContext(100, 100).use { editingCtx ->
			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
		}
	}
}
