/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Tests for navigation service bindings (TopologyNavigator, PathReservationService,
 * TrainNavigationService) resolved from simulation and editing context Koin scopes.
 *
 * Verifies that all three navigation services can be obtained from their
 * respective context scopes and perform basic operations correctly.
 *
 * Runs on both JVM and linuxX64 via commonTest.
 *
 * @since 2026-03-24 (Issue #410 — increase native test coverage)
 */
class NavigationServiceBindingsTest : KoinComponent {

	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	// ========================================================================
	// TopologyNavigator — from EditingContext
	// ========================================================================

	@Test
	fun topologyNavigatorResolvesFromEditingContext() {
		CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { ctx ->
			val navigator = ctx.getTopologyNavigator()
			assertThat(navigator).isNotNull()
			assertThat(navigator).isInstanceOf<TopologyNavigator>()
		}
	}

	@Test
	fun topologyNavigatorFromEditingContextFindsLinearPath() {
		CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { ctx ->
			val navigator = ctx.getTopologyNavigator()
			val inOuts = ctx.getInOuts().toList()
			assertThat(inOuts).hasSize(2)

			val paths = navigator.findAllTopologicalPaths(inOuts[0], inOuts[1])
			assertThat(paths.size).isGreaterThan(0)
		}
	}

	@Test
	fun topologyNavigatorGetNextTrackBlockFromInOut() {
		CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { ctx ->
			val navigator = ctx.getTopologyNavigator()
			val inOuts = ctx.getInOuts().toList()
			val firstInOut = inOuts[0]

			val nextBlock = navigator.getNextTrackBlock(firstInOut, null)
			assertThat(nextBlock).isNotNull()
		}
	}

	// ========================================================================
	// TopologyNavigator — from SimulationContext
	// ========================================================================

	@Test
	fun topologyNavigatorResolvesFromSimulationContext() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val navigator = simCtx.getTopologyNavigator()
			assertThat(navigator).isNotNull()
			assertThat(navigator).isInstanceOf<TopologyNavigator>()
		}
	}

	@Test
	fun topologyNavigatorFromSimulationContextFindsVyhybnaPath() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.VYHYBNA_XML,
			processFactory
		).use { simCtx ->
			val navigator = simCtx.getTopologyNavigator()
			val inOuts = simCtx.getInOuts().toList()
			assertThat(inOuts).hasSize(2)

			val paths = navigator.findAllTopologicalPaths(inOuts[0], inOuts[1])
			assertThat(paths.size).isGreaterThan(0)
		}
	}

	@Test
	fun topologyNavigatorFromSwitchNetworkFindsMultiplePaths() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.SWITCH_BASIC_XML,
			processFactory
		).use { simCtx ->
			val navigator = simCtx.getTopologyNavigator()
			val inOuts = simCtx.getInOuts().toList()
			assertThat(inOuts).hasSize(3)

			// From IN to OUT_PLUS should find at least one path
			val inOutIn = inOuts.first { it.name == "IN" }
			val inOutPlus = inOuts.first { it.name == "OUT_PLUS" }
			val paths = navigator.findAllTopologicalPaths(inOutIn, inOutPlus)
			assertThat(paths.size).isGreaterThan(0)
		}
	}

	// ========================================================================
	// PathReservationService — from SimulationContext
	// ========================================================================

	@Test
	fun pathReservationServiceResolvesFromSimulationContext() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getPathReservationService()
			assertThat(service).isNotNull()
			assertThat(service).isInstanceOf<PathReservationService>()
		}
	}

	@Test
	fun pathReservationServiceReportsPathAvailable() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getPathReservationService()
			val inOuts = simCtx.getInOuts().toList()
			assertThat(inOuts).hasSize(2)

			// All blocks should be FREE initially
			val available = service.isPathAvailable(inOuts[0], inOuts[1])
			assertThat(available).isTrue()
		}
	}

	@Test
	fun pathReservationServiceReservesLinearPath() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getPathReservationService()
			val inOuts = simCtx.getInOuts().toList()

			val result = service.reservePath("testTrain", inOuts[0], inOuts[1])
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

			val success = result as PathReservationService.ReservationResult.Success
			assertThat(success.reservedBlocks.size).isGreaterThan(0)
		}
	}

	@Test
	fun pathReservationServiceReleasesReservedPath() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getPathReservationService()
			val inOuts = simCtx.getInOuts().toList()

			// Reserve
			service.reservePath("testTrain", inOuts[0], inOuts[1])

			// Release
			val released = service.releasePath("testTrain")
			assertThat(released.size).isGreaterThan(0)

			// Should be available again
			val available = service.isPathAvailable(inOuts[0], inOuts[1])
			assertThat(available).isTrue()
		}
	}

	@Test
	fun pathReservationServiceGetReservedBlocksForUnknownTrainIsEmpty() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getPathReservationService()
			val blocks = service.getReservedBlocks("nonexistentTrain")
			assertThat(blocks).isEmpty()
		}
	}

	// ========================================================================
	// TrainNavigationService — from SimulationContext
	// ========================================================================

	@Test
	fun trainNavigationServiceResolvesFromSimulationContext() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getTrainNavigationService()
			assertThat(service).isNotNull()
			assertThat(service).isInstanceOf<TrainNavigationService>()
		}
	}

	@Test
	fun trainNavigationServiceReportsNoPathForUnreservedTrain() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx ->
			val service = simCtx.getTrainNavigationService()
			val inOuts = simCtx.getInOuts().toList()

			// No path reserved for this train, so navigation should not find reserved path
			val reserved = service.isPathReservedForTrain("unknownTrain", inOuts[0])
			assertThat(reserved).isFalse()
		}
	}

	// ========================================================================
	// Cross-service integration — all three services from same context
	// ========================================================================

	@Test
	fun allThreeServicesResolveFromSameContext() {
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.VYHYBNA_XML,
			processFactory
		).use { simCtx ->
			val topoNav = simCtx.getTopologyNavigator()
			val pathService = simCtx.getPathReservationService()
			val trainNav = simCtx.getTrainNavigationService()

			assertThat(topoNav).isNotNull()
			assertThat(pathService).isNotNull()
			assertThat(trainNav).isNotNull()
		}
	}

	@Test
	fun servicesFromDifferentNetworksAreIndependent() {
		// Create first context and reserve a path
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.LINEAR_TRACK_XML,
			processFactory
		).use { simCtx1 ->
			val service1 = simCtx1.getPathReservationService()
			val inOuts1 = simCtx1.getInOuts().toList()
			service1.reservePath("train1", inOuts1[0], inOuts1[1])

			// Create second context — should be independent
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.SWITCH_BASIC_XML,
				processFactory
			).use { simCtx2 ->
				val service2 = simCtx2.getPathReservationService()
				// Second context should have no reservations
				val blocks = service2.getReservedBlocks("train1")
				assertThat(blocks).isEmpty()
			}
		}
	}
}
