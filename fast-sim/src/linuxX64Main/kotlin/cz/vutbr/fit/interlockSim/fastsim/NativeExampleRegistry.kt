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

import cz.vutbr.fit.interlockSim.ExampleNames
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
	/** Name strings come from [ExampleNames] (`:core`) — one home for both registries. */
	val AVAILABLE: Set<String> = setOf(ExampleNames.SHUNTING_LOOP, ExampleNames.CERVENY_UJEZD)

	fun create(
		name: String,
		endTime: Long,
		factory: NativeContextFactory
	): DefaultSimulationContext =
		when (name) {
			ExampleNames.SHUNTING_LOOP -> createShuntingLoop(endTime, factory, EmbeddedResources.VYHYBNA_XML)
			ExampleNames.CERVENY_UJEZD -> createShuntingLoop(endTime, factory, EmbeddedResources.CERVENY_UJEZD_XML)
			else -> throw IllegalArgumentException("Unknown example: '$name'. Available: ${AVAILABLE.sorted().joinToString()}")
		}

	@Suppress("TooGenericExceptionCaught")
	private fun createShuntingLoop(
		endTime: Long,
		factory: NativeContextFactory,
		xml: String
	): DefaultSimulationContext {
		val ctx = factory.createFromXml(xml)
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
