/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP5: Headless auto-resolution Koin wiring (Issue #568).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Integration tests for the SP5 Koin wiring of the headless auto-resolution API —
 * Goal 9 SP5 (#568).
 *
 * `CoreModule`/`coreTestModule` register [ConflictResolver], [DispatcherPreferenceStore]
 * and [AutoConflictResolutionService] as **scoped per [DefaultSimulationContext]**. These
 * tests build a real simulation context from vyhybna.xml and resolve each binding from the
 * context's own Koin scope, proving the wiring is not dead DSL — the whole chain resolves
 * and cooperates end-to-end.
 *
 * @since Issue #568 (Goal 9 → Goal 10 prereq)
 */
@Tag("integration-test")
@DisplayName("AutoConflictResolutionService Koin wiring — Goal 9 SP5 (#568)")
class AutoConflictResolutionWiringTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun createShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

	@Test
	@DisplayName("scope resolves ConflictResolver, DispatcherPreferenceStore and AutoConflictResolutionService")
	fun scopeResolvesAllThreeBindings() {
		createShuntingLoopContext().use { simCtx ->
			val resolver = simCtx.scope.get<ConflictResolver>()
			val store = simCtx.scope.get<DispatcherPreferenceStore>()
			val service = simCtx.scope.get<AutoConflictResolutionService>()

			assertThat(resolver).isInstanceOf<DefaultConflictResolver>()
			assertThat(store).isInstanceOf<DefaultDispatcherPreferenceStore>()
			assertThat(service).isInstanceOf<DefaultAutoConflictResolutionService>()
		}
	}

	@Test
	@DisplayName("scoped AutoConflictResolutionService runs end-to-end and records in the scoped store")
	fun scopedServiceAppliesTopRankedAndRecords() {
		createShuntingLoopContext().use { simCtx ->
			val inOuts = simCtx.getInOuts().toList()
			val allRoutes =
				simCtx.getRouteFinder().findRoutes(inOuts[0].staticRef, inOuts[1].staticRef, simCtx)
			val contested =
				allRoutes[0].segments.first { it !in allRoutes[1].segments.toSet() }
					as DynamicTrackBlock

			val event =
				ConflictDetectedEvent(
					block = contested,
					trainId = "T1",
					conflictingTrainId = "T2",
					time = 0.0
				)

			val service = simCtx.scope.get<AutoConflictResolutionService>()
			val store = simCtx.scope.get<DispatcherPreferenceStore>()

			val resolution = service.applyTopRanked(event)

			assertThat(resolution).isNotNull()
			val choices = store.getChoices()
			assertThat(choices).hasSize(1)
			assertThat(choices[0].applied).isEqualTo(resolution)
			assertThat(choices[0].source).isEqualTo(DispatcherPreferenceStore.ApplicationSource.AUTO)
		}
	}
}
