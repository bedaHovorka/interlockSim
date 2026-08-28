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
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher

/**
 * Registry of built-in simulation examples for the native CLI binary.
 *
 * Mirrors [cz.vutbr.fit.interlockSim.ExampleRegistry] from :desktop-ui but console-only
 * (no GUI examples). Uses [EmbeddedResources] for XML content and [NativeContextFactory]
 * to build [DefaultSimulationContext] instances without JVM/file-system access.
 *
 * @since Issue #415 (fast-sim native CLI)
 */
internal object NativeExampleRegistry {
	private const val EXAMPLE_SHUNTING_LOOP = "shuntingLoop"

	val AVAILABLE: Set<String> = setOf(EXAMPLE_SHUNTING_LOOP)

	fun create(
		name: String,
		endTime: Long,
		factory: NativeContextFactory
	): DefaultSimulationContext =
		when (name) {
			EXAMPLE_SHUNTING_LOOP -> createShuntingLoop(endTime, factory)
			else -> throw IllegalArgumentException("Unknown example: '$name'. Available: ${AVAILABLE.sorted().joinToString()}")
		}

	@Suppress("TooGenericExceptionCaught")
	private fun createShuntingLoop(
		endTime: Long,
		factory: NativeContextFactory
	): DefaultSimulationContext {
		val ctx = factory.createFromXml(EmbeddedResources.VYHYBNA_XML)
		try {
			val loop = ShuntingLoop(ctx, endTime)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)
		} catch (e: Exception) {
			ctx.close()
			throw e
		}
		return ctx
	}
}
