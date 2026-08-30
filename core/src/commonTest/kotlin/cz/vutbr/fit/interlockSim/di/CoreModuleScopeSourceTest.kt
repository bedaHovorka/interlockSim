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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.TemporalConflictDetector
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.InstanceCreationException
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
 * Each of these bindings starts by reading the scope's source object:
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
 * (`commonCoreTestModule`, `coreTestModule`) hand-duplicate these bindings, so loading
 * one of those would exercise the copy rather than the shipped module.
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
	 * Opens a simulation-qualified scope with no source, runs [block] against it, and
	 * always closes the scope again.
	 */
	private fun withSourcelessSimulationScope(block: (Scope) -> Unit) {
		val scope =
			getKoin().createScope(
				scopeId = "sourceless-simulation-scope",
				qualifier = named<DefaultSimulationContext>()
			)
		try {
			block(scope)
		} finally {
			scope.close()
		}
	}

	/**
	 * Asserts that resolving [T] from a sourceless simulation scope fails with the
	 * shared "no source" message.
	 *
	 * Koin wraps a definition's own failure in an [InstanceCreationException], so the
	 * check looks at the cause, which is the [IllegalStateException] the binding threw.
	 */
	private inline fun <reified T : Any> assertMissingSourceIsReported() {
		withSourcelessSimulationScope { scope ->
			val failure = assertFailsWith<InstanceCreationException> { scope.get<T>() }
			val cause = failure.cause
			assertThat(cause).isNotNull().isInstanceOf(IllegalStateException::class)
			assertThat(cause?.message).isEqualTo(EXPECTED_MESSAGE)
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
	fun temporalConflictDetectorReportsMissingSource() = assertMissingSourceIsReported<TemporalConflictDetector>()

	@Test
	fun conflictResolverReportsMissingSource() = assertMissingSourceIsReported<ConflictResolver>()

	@Test
	fun metricsCollectionServiceReportsMissingSource() = assertMissingSourceIsReported<MetricsCollectionService>()

	@Test
	fun interlockingFacadeReportsMissingSource() = assertMissingSourceIsReported<InterlockingFacade>()

	private companion object {
		const val EXPECTED_MESSAGE = "DefaultSimulationContext source not found in scope"
	}
}
