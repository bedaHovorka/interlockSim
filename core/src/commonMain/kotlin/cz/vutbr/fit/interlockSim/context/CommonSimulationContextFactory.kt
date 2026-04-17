/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

/**
 * Multiplatform factory for creating SimulationContext instances.
 *
 * Provides the subset of SimulationContextFactory methods that are
 * platform-agnostic (no File/InputStream dependencies). Used by
 * commonTest and commonMain code that constructs simulation contexts
 * programmatically from an existing EditingContext.
 *
 * JVM code should use SimulationContextFactory (which extends this interface)
 * to get additional createContext(File) / createContext(InputStream) methods.
 */
interface CommonSimulationContextFactory {
	fun createContext(editingContext: EditingContext): SimulationContext

	fun createEmptyContext(): SimulationContext
}
