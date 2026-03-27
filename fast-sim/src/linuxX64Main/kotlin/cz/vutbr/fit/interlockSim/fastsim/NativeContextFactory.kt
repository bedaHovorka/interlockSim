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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.xml.XmlContextReader
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Creates [DefaultSimulationContext] from XML string or file path.
 *
 * Inlines the [cz.vutbr.fit.interlockSim.context.ContextTransformer] logic (jvmMain) for native,
 * using [XmlContextReader] (commonMain) + [DefaultSimulationContext.fromEditingContext] (commonMain).
 *
 * Note: [DefaultSimulationContext.initializeDynamicMapping] is `internal` to `:core` and must NOT
 * be called here. [DefaultSimulationContext.run] calls it internally, which is sufficient for CLI use.
 *
 * @since Issue #415 (fast-sim native CLI)
 */
internal class NativeContextFactory : KoinComponent {
	private val processFactory: SimulationProcessFactory by inject()
	private val reader = XmlContextReader()

	/**
	 * Create a [DefaultSimulationContext] from an XML string.
	 *
	 * The intermediate [cz.vutbr.fit.interlockSim.context.DefaultEditingContext] is always closed
	 * (its per-context Koin scope is released) even if transformation fails.
	 *
	 * @param xml Railway network XML content (conforming to data.xsd)
	 * @return Simulation context ready for [DefaultSimulationContext.run]
	 */
	fun createFromXml(xml: String): DefaultSimulationContext {
		val editingCtx = reader.parse(xml)
		return try {
			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
		} finally {
			editingCtx.close()
		}
	}

	/**
	 * Create a [DefaultSimulationContext] from an XML file path.
	 *
	 * The intermediate [cz.vutbr.fit.interlockSim.context.DefaultEditingContext] is always closed
	 * (its per-context Koin scope is released) even if transformation fails.
	 *
	 * @param path Absolute or relative path to the XML file
	 * @return Simulation context ready for [DefaultSimulationContext.run]
	 */
	fun createFromFile(path: String): DefaultSimulationContext {
		val editingCtx = reader.parseFile(path)
		return try {
			DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
		} finally {
			editingCtx.close()
		}
	}
}
