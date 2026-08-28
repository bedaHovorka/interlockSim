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

/**
 * Shared seed constants for `@Tag("ollama-test")` tests that drive real inference through a
 * local Ollama instance and need a fixed, reproducible sampling seed.
 *
 * Consolidates what were previously ad-hoc literal seed values scattered across
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.SeededOllamaJsonClientTest],
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaFormatToolsCoexistenceOllamaTest], and
 * [cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaRuntimeContractOllamaTest] into one place.
 *
 * @see cz.vutbr.fit.interlockSim.dispatcher.executor.SeededOllamaJsonClient
 */
object OllamaTestSeeds {
	/** The default fixed seed for tests needing one reproducible sampling seed. */
	const val PRIMARY: Long = 42L

	/** A second, distinct fixed seed for tests that must exercise two different seed values. */
	const val SECONDARY: Long = 43L
}
