/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultConflictResolver
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.InstanceCreationException
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests the defensive source check shared by every simulation-scoped binding in
 * [navigationModule].
 *
 * Each of these bindings starts by reading the scope's source object (the editing-scope
 * [TopologyNavigator] does the same with [DefaultEditingContext]):
 *
 * ```kotlin
 * val context = getSource<DefaultSimulationContext>()
 *     ?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
 * ```
 *
 * In production [DefaultSimulationContext] always opens its scope with `source = this`,
 * so the branch never fires. These tests open a scope that carries the right qualifier
 * but no source at all, which is the only way the check can trigger. That guards the
 * contract: a caller who opens a simulation scope by hand and forgets the source gets a
 * named, actionable failure instead of a confusing downstream NullPointerException.
 *
 * The real [navigationModule] is loaded on purpose. The test-fixture modules
 * (`commonCoreTestModule`, `coreTestModule`) re-provide these bindings via
 * `sharedSimulationTestScopesModule` (Issue #1029), so loading one of those would exercise
 * that shared copy rather than the shipped module.
 *
 * Until Issue #1455 (Goal 9B SP2.3) one case pinned the check for the scoped
 * `TemporalConflictDetector`; that inert detector was deleted (its design is kept in #1497),
 * and the case now covers the editing-scope [TopologyNavigator], the other binding with the
 * same check.
 *
 * Runs on both JVM and linuxX64 via commonTest.
 */
class CoreModuleScopeSourceTest : KoinComponent {
	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(navigationModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	/**
	 * Opens a scope with [qualifier] and no source, runs [block] against it, and
	 * always closes the scope again.
	 */
	private fun withSourcelessScope(
		qualifier: Qualifier,
		block: (Scope) -> Unit
	) {
		val scope =
			getKoin().createScope(
				scopeId = "sourceless-scope",
				qualifier = qualifier
			)
		try {
			block(scope)
		} finally {
			scope.close()
		}
	}

	/**
	 * Asserts that resolving [T] from a sourceless scope with [qualifier] fails with
	 * [expectedMessage], by default the shared simulation "no source" message.
	 *
	 * Koin wraps a definition's own failure in an [InstanceCreationException], so the
	 * check looks at the cause, which is the [IllegalStateException] the binding threw.
	 */
	private inline fun <reified T : Any> assertMissingSourceIsReported(
		qualifier: Qualifier = named<DefaultSimulationContext>(),
		expectedMessage: String = EXPECTED_MESSAGE
	) {
		withSourcelessScope(qualifier) { scope ->
			val failure = assertFailsWith<InstanceCreationException> { scope.get<T>() }
			val cause = failure.cause
			assertThat(cause).isNotNull().isInstanceOf(IllegalStateException::class)
			assertThat(cause?.message).isEqualTo(expectedMessage)
		}
	}

	@Test
	fun topologyNavigatorReportsMissingSource() = assertMissingSourceIsReported<TopologyNavigator>()

	@Test
	fun pathReservationRegistryReportsMissingSource() = assertMissingSourceIsReported<PathReservationRegistry>()

	@Test
	fun pathInfoBuilderReportsMissingSource() = assertMissingSourceIsReported<PathInfoBuilder>()

	@Test
	fun pathReservationServiceReportsMissingSource() = assertMissingSourceIsReported<PathReservationService>()

	@Test
	fun trainNavigationServiceReportsMissingSource() = assertMissingSourceIsReported<TrainNavigationService>()

	@Test
	fun collisionDetectionServiceReportsMissingSource() = assertMissingSourceIsReported<CollisionDetectionService>()

	@Test
	fun editingTopologyNavigatorReportsMissingSource() =
		assertMissingSourceIsReported<TopologyNavigator>(
			qualifier = named<DefaultEditingContext>(),
			expectedMessage = EXPECTED_EDITING_MESSAGE
		)

	@Test
	fun conflictResolverReportsMissingSource() = assertMissingSourceIsReported<ConflictResolver>()

	/**
	 * The success path of the shipped `scoped<ConflictResolver>` binding: a real simulation
	 * context opens its scope with itself as the source, so the binding builds a
	 * [DefaultConflictResolver] through `forEnvironment(context)`.
	 */
	@Test
	fun conflictResolverIsBuiltFromTheContextSource() {
		val context = CommonTestFixtures.createEmptySimulationContext(DefaultSimulationProcessFactory())
		try {
			val resolver = context.scope.get<ConflictResolver>()

			assertThat(resolver).isInstanceOf(DefaultConflictResolver::class)
			assertThat(context.scope.get<ConflictResolver>()).isSameInstanceAs(resolver)
		} finally {
			context.close()
		}
	}

	@Test
	fun metricsCollectionServiceReportsMissingSource() = assertMissingSourceIsReported<MetricsCollectionService>()

	@Test
	fun interlockingFacadeReportsMissingSource() = assertMissingSourceIsReported<InterlockingFacade>()

	private companion object {
		const val EXPECTED_MESSAGE = "DefaultSimulationContext source not found in scope"
		const val EXPECTED_EDITING_MESSAGE = "DefaultEditingContext source not found in scope"
	}
}
