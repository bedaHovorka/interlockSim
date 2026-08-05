/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Wiring regression test for [OrphanReservationSweeper] (Issue #847 round 3, PR #891 defect B).
 *
 * The sweeper is only useful if it actually runs each simulation tick. A class that exists, is
 * unit-tested, and is never constructed in production is precisely the failure mode PR #891 already
 * found twice on this code path — `ActionValidator` and `DispatchTickLoop` are both fully tested and
 * never built outside tests, and round 1's whole defect was a `ThrottlingSimulationController` that
 * was constructed but never reached the kernel.
 *
 * So this asserts both halves: the sweeper is reachable from the context scope (so the run can read
 * its counters afterwards), and driving the loop's `ControlStepListener` — the exact callback
 * `ShuntingLoop.iteration()` invokes once per tick — actually sweeps.
 *
 * Builds the example reflectively, the same technique [ExampleRegistryCollisionWiringTest] and
 * [ExampleLoadingTest] use. No Ollama is required: `KoogAgentPlanAdapter` construction performs no
 * network I/O, and this test never runs the simulation.
 *
 * @since Issue #847 (round 3)
 */
@DisplayName("ExampleRegistry wires the orphan-reservation sweeper into the per-tick control step")
class ExampleRegistryOrphanSweeperWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun createShuntingLoopAIContext(): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		val createMethod =
			ExampleRegistry::class.java.getDeclaredMethod(
				"createShuntingLoopAIExample",
				SimulationContextFactory::class.java,
				Array<String>::class.java
			)
		createMethod.isAccessible = true
		return createMethod.invoke(
			registry,
			get<SimulationContextFactory>(),
			arrayOf("example", "shuntingLoopAI", "60")
		) as DefaultSimulationContext
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the sweeper is declared in the context scope so the run can report its counters")
	fun sweeperIsReachableFromTheScope() {
		val context = createShuntingLoopAIContext()

		assertThat(context.scope.getOrNull<OrphanReservationSweeper>()).isNotNull()
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the per-tick control step drives a sweep")
	fun controlStepDrivesASweep() {
		val context = createShuntingLoopAIContext()
		val loop = context.getMainProcess() as ShuntingLoop
		val sweeper = checkNotNull(context.scope.getOrNull<OrphanReservationSweeper>())
		val before = sweeper.sweepCount

		// Exactly what ShuntingLoop.iteration() calls once per simulated second.
		checkNotNull(loop.controlStepListener) { "no ControlStepListener was wired" }.onControlStep()

		assertThat(sweeper.sweepCount).isGreaterThanOrEqualTo(before + 1)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a freshly built network has nothing to reclaim")
	fun freshNetworkReleasesNothing() {
		val context = createShuntingLoopAIContext()
		val loop = context.getMainProcess() as ShuntingLoop
		val sweeper = checkNotNull(context.scope.getOrNull<OrphanReservationSweeper>())

		checkNotNull(loop.controlStepListener).onControlStep()

		assertThat(sweeper.phantomReleaseCount).isEqualTo(0)
		assertThat(sweeper.staleReleaseCount).isEqualTo(0)
	}

	/**
	 * Issue #847 round 4 (R4-3): the sweeper is only able to reclaim an un-travelled tail if a
	 * [cz.vutbr.fit.interlockSim.dispatcher.PartialRouteReleaser] is actually wired into it. With
	 * none, `evaluateOccupyingTrain` deliberately does nothing — which is round 3's behaviour and
	 * indistinguishable, from the counters alone, from a run where nothing was ever stranded.
	 *
	 * Asserted through the public counter rather than the private field: a fresh network has no
	 * stranded tail, so the observable consequence of the wiring is that the counter exists and
	 * starts clean. The release behaviour itself is covered by `OrphanReservationSweeperTest` (the
	 * decision) and `RegistryPartialRouteReleaserTest` (the interlocking safety).
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("the sweeper is built with a partial releaser so un-travelled tails can be reclaimed")
	fun sweeperHasAPartialReleaser() {
		val context = createShuntingLoopAIContext()
		val sweeper = checkNotNull(context.scope.getOrNull<OrphanReservationSweeper>())

		val releaserField = OrphanReservationSweeper::class.java.getDeclaredField("partialReleaser")
		releaserField.isAccessible = true

		assertThat(releaserField.get(sweeper)).isNotNull()
		assertThat(sweeper.partialReleaseCount).isEqualTo(0)
	}
}
