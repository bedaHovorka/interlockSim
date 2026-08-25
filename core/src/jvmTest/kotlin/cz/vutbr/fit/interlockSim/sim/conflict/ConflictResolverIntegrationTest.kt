/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP3: ConflictResolution data model and candidate generation (Issue #585).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
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
 * Integration tests for [DefaultConflictResolver] on a real simulation context — Goal 9
 * SP3 (#585).
 *
 * The unit tests in [ConflictResolverTest] mock [cz.vutbr.fit.interlockSim.context.RouteFinder]
 * and the contested block, so they cannot verify the one subtle mechanism the reroute
 * filter depends on: **sim-scoped routes carry the same [DynamicTrackBlock] wrapper
 * instances that the reservation layer puts into [ConflictDetectedEvent], and wrapper
 * equality is based on static-reference identity.** If a future routing-layer refactor
 * made [cz.vutbr.fit.interlockSim.objects.paths.Route.segments] carry *static* sections
 * again, the resolver's contested-block filter would silently stop excluding anything.
 * These tests pin that invariant against a real [DefaultSimulationContext] built from
 * the shunting-loop network (vyhybna.xml), whose passing loop provides two alternative
 * routes between the same InOut pair.
 *
 * @since Issue #585 (Goal 9 SP3)
 */
@Tag("integration-test")
@DisplayName("DefaultConflictResolver integration — Goal 9 SP3 (#585)")
class ConflictResolverIntegrationTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun createShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

	/**
	 * Invariant part 1: routes found by a sim-scoped RouteFinder carry [DynamicTrackBlock]
	 * wrappers (not static sections) — the precondition for the resolver's `==` filter to
	 * ever match a [ConflictDetectedEvent.block].
	 */
	@Test
	@DisplayName("sim-scoped routes carry DynamicTrackBlock segments")
	fun simScopedRoutesCarryDynamicTrackBlockSegments() {
		createShuntingLoopContext().use { simCtx ->
			val inOuts = simCtx.getInOuts().toList()
			val routes =
				simCtx.getRouteFinder().findRoutes(inOuts[0].staticRef, inOuts[1].staticRef, simCtx)

			assertThat(routes).isNotEmpty()
			routes.flatMap { it.segments }.forEach { segment ->
				assertThat(segment).isInstanceOf<DynamicTrackBlock>()
			}
		}
	}

	/**
	 * Invariant part 2 (the teeth of this test class): with a contested block on one loop
	 * branch, the resolver generates at least one reroute candidate, and no candidate's
	 * route touches the contested block **by static identity** — a ground truth
	 * independent of the production `==` filter being verified.
	 */
	@Test
	@DisplayName("reroute candidates avoid the contested block by static identity")
	fun rerouteCandidatesAvoidContestedBlockByStaticIdentity() {
		createShuntingLoopContext().use { simCtx ->
			val inOuts = simCtx.getInOuts().toList()
			val allRoutes =
				simCtx.getRouteFinder().findRoutes(inOuts[0].staticRef, inOuts[1].staticRef, simCtx)

			// Precondition: the shunting loop offers at least two alternative routes
			assertThat(allRoutes.size).isGreaterThan(1)

			// Contested block: a segment exclusive to the first route (one loop branch)
			val branchSegment =
				allRoutes[0].segments.first { it !in allRoutes[1].segments.toSet() }
			assertThat(branchSegment).isInstanceOf<DynamicTrackBlock>()
			val contested = branchSegment as DynamicTrackBlock

			val conflict =
				ConflictDetectedEvent(
					block = contested,
					trainId = "T1",
					conflictingTrainId = "T2",
					time = 0.0
				)
			val resolver = DefaultConflictResolver.forEnvironment(simCtx)

			val resolutions = resolver.generateResolutions(conflict)

			val rerouteCandidates = resolutions.filterIsInstance<ConflictResolution.Reroute>()
			assertThat(rerouteCandidates).isNotEmpty()

			// Ground truth: no candidate segment matches the contested block by static identity
			rerouteCandidates.forEach { candidate ->
				val touchesContestedBlock =
					candidate.alternativeRoute.segments.any {
						(it as? DynamicTrackBlock)?.staticRef === contested.staticRef
					}
				assertThat(!touchesContestedBlock).isTrue()
			}

			// The other loop branch must actually be offered as an alternative
			val otherBranchStaticRefs =
				allRoutes[1].segments.map { (it as DynamicTrackBlock).staticRef }.toSet()
			val otherBranchOffered =
				rerouteCandidates.any { candidate ->
					candidate.alternativeRoute.segments
						.map { (it as DynamicTrackBlock).staticRef }
						.toSet() == otherBranchStaticRefs
				}
			assertThat(otherBranchOffered).isTrue()
		}
	}

	/**
	 * [DefaultConflictResolver.forEnvironment] wires all collaborators from one
	 * environment and produces all three strategies on the loop network.
	 */
	@Test
	@DisplayName("forEnvironment factory produces all three strategies on the loop network")
	fun forEnvironmentProducesAllThreeStrategies() {
		createShuntingLoopContext().use { simCtx ->
			val inOuts = simCtx.getInOuts().toList()
			val allRoutes =
				simCtx.getRouteFinder().findRoutes(inOuts[0].staticRef, inOuts[1].staticRef, simCtx)
			val contested =
				allRoutes[0].segments.first { it !in allRoutes[1].segments.toSet() }
					as DynamicTrackBlock

			val conflict =
				ConflictDetectedEvent(
					block = contested,
					trainId = "T1",
					conflictingTrainId = "T2",
					time = 0.0
				)
			val resolver = DefaultConflictResolver.forEnvironment(simCtx)

			val strategies = resolver.generateResolutions(conflict).map { it.strategy }.toSet()

			assertThat(strategies.contains(ConflictResolution.Strategy.HOLD_TRAIN)).isTrue()
			assertThat(strategies.contains(ConflictResolution.Strategy.REROUTE)).isTrue()
			assertThat(strategies.contains(ConflictResolution.Strategy.SPEED_ADJUST)).isTrue()
		}
	}
}
