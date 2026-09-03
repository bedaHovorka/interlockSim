/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.gui.animation.TrainPositionCalculator
import org.koin.test.inject

/**
 * Shared scaffolding for the heading regression tests (`gui.animation`): the injected process
 * factory, the [TrainPositionCalculator] they read positions and headings through, and the
 * [HeadingFlipSampler] that records heading flips.
 *
 * [startSamplerContext] registers the created context with [tracked] (Issue #1026), so
 * `KoinTestBase.tearDownKoin()` closes its Koin scope after each test — the caller does
 * NOT own the context and must not close it.
 *
 * Mock-only tests need no context; they build their own sampler and never call
 * [startSamplerContext], leaving [calculator] and [sampler] unset.
 */
abstract class HeadingSamplerTestBase : KoinTestBase() {
	protected val processFactory: SimulationProcessFactory by inject()

	protected lateinit var calculator: TrainPositionCalculator
	protected lateinit var sampler: HeadingFlipSampler

	/** Build the shunting-loop context for a sim-level test and point [calculator]/[sampler] at it. */
	protected fun startSamplerContext(): DefaultSimulationContext {
		val context =
			TestFixtures
				.newShuntingSimulationContext(processFactory = processFactory, initializeDynamicMapping = true)
				.tracked()
		calculator = TrainPositionCalculator(context, context.separatorPositionCache)
		sampler = HeadingFlipSampler(calculator)
		return context
	}
}
