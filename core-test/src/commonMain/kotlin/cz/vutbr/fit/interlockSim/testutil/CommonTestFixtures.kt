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
 * XML strings are sourced from [NetworkResources] — the single source of truth.
 * This object is intentionally in commonMain of :core-test so it is available
 * to both JVM test code and linuxX64 test code without duplication.
 */
object CommonTestFixtures {

	/** Shunting loop — canonical test fixture (vyhybna.xml). @see NetworkResources.VYHYBNA_XML */
	val VYHYBNA_XML = NetworkResources.VYHYBNA_XML

	val LINEAR_TRACK_XML = NetworkResources.LINEAR_TRACK_XML
	val MINIMAL_NETWORK_XML = NetworkResources.MINIMAL_NETWORK_XML
	val SINGLE_INOUT_XML = NetworkResources.SINGLE_INOUT_XML
	val ZERO_INOUTS_XML = NetworkResources.ZERO_INOUTS_XML
	val SWITCH_BASIC_XML = NetworkResources.SWITCH_BASIC_XML
	val SEMAPHORE_BASIC_XML = NetworkResources.SEMAPHORE_BASIC_XML
	val TWO_TRACKS_PARALLEL_XML = NetworkResources.TWO_TRACKS_PARALLEL_XML
	val EMPTY_GRID_XML = NetworkResources.EMPTY_GRID_XML

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
		val editingCtx = reader.parse(xml)
		try {
			return DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
		} finally {
			editingCtx.close()
		}
	}

	fun createEmptySimulationContext(
		processFactory: SimulationProcessFactory,
	): DefaultSimulationContext {
		val editingCtx = DefaultEditingContext(100, 100)
		try {
			return DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
		} finally {
			editingCtx.close()
		}
	}
}
