/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: build an example's context through ExampleRegistry's private factories
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.ExampleRegistry
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory

/**
 * Builds the [DefaultSimulationContext] an example would run on, by calling [ExampleRegistry]'s
 * private `createXxxExample` factory reflectively.
 *
 * The wiring under test — which listeners, ports and recorders an example attaches — lives inside
 * those private factories, so a test that wants to assert on it has to reach in. Eleven test
 * classes each carried the same `getDeclaredMethod` + `isAccessible = true` + `invoke` block to do
 * that, seventeen copies in all (Issue #955, cluster U1).
 *
 * The context is fully wired but **not run**, and the caller owns it: close it, or hand it to
 * `KoinTestBase.tracked()`.
 *
 * @param registry the registry instance to call, normally `get<ExampleRegistry>()`
 * @param contextFactory the factory the example should build its context with
 * @param factoryMethod the private method's name, for example `"createShuntingLoopAIExample"`
 * @param exampleName the example name passed through in the argument array
 * @param endTime the end-time argument, as the string form `main` would have parsed
 */
fun createExampleContext(
	registry: ExampleRegistry,
	contextFactory: SimulationContextFactory,
	factoryMethod: String,
	exampleName: String,
	endTime: String = "60"
): DefaultSimulationContext {
	val createMethod =
		ExampleRegistry::class.java.getDeclaredMethod(
			factoryMethod,
			SimulationContextFactory::class.java,
			Array<String>::class.java
		)
	createMethod.isAccessible = true
	return createMethod.invoke(
		registry,
		contextFactory,
		arrayOf("example", exampleName, endTime)
	) as DefaultSimulationContext
}

/**
 * Builds the [DefaultSimulationContext] an example would run on by resolving [exampleName] through
 * [map] — the same lookup the `example <name>` mode does — and invoking the registered factory.
 *
 * [createExampleContext] reaches a private factory by method name and bypasses the registry maps;
 * this twin exercises the map itself, which is exactly what a "map resolves name to arm X" test
 * pins. Added in the PR #1282 review round (Issue #955's one-shared-copy rule): the two map-driven
 * Cerveny Ujezd test classes had identical private copies.
 *
 * The context is fully wired but **not run**, and the caller owns it: close it, or hand it to
 * `KoinTestBase.tracked()`.
 *
 * @param map the registry map the example name must resolve through
 * @param contextFactory the factory the example should build its context with
 * @param exampleName the example name, normally an `ExampleNames` constant
 * @param endTime the end-time argument, as the string form `main` would have parsed
 * @throws IllegalStateException if the example name is not registered in [map]
 */
fun createExampleContextFromMap(
	map: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext>,
	contextFactory: SimulationContextFactory,
	exampleName: String,
	endTime: String = "60"
): DefaultSimulationContext {
	val factory = checkNotNull(map[exampleName]) { "$exampleName is not registered" }
	return factory(contextFactory, arrayOf("example", exampleName, endTime)) as DefaultSimulationContext
}
