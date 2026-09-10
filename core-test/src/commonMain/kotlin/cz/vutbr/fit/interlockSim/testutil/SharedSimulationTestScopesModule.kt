package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTrainNavigationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.pathfinding.AutomaticPathFindingService
import cz.vutbr.fit.interlockSim.pathfinding.DefaultAutomaticPathFindingService
import cz.vutbr.fit.interlockSim.pathfinding.DefaultRouteFinder
import cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultAutoConflictResolutionService
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultDispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.TemporalConflictDetector
import cz.vutbr.fit.interlockSim.sim.metrics.DefaultMetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Shared Koin scope bindings for [DefaultEditingContext] and [DefaultSimulationContext],
 * common to every flavor of the core test module: `coreTestModule` (JVM-only,
 * `core/src/jvmTest`) and [commonCoreTestModule] (native-compatible, `core-test/src/commonMain`).
 *
 * These two test modules used to hand-duplicate this entire scope block. The duplication let
 * them drift — [commonCoreTestModule] was missing the [CollisionDetectionService] binding until
 * `ApprovedTrainsSnapshotContractTest` (commonTest) tripped over a `NoBeanDefFoundException`
 * three layers deep. Extracting the shared scope here means a new binding is added once, via
 * `includes(sharedSimulationTestScopesModule)`, and is automatically available to both flavors.
 *
 * Besides the scopes, it carries the two non-scope bindings the flavors also duplicated
 * ([SimulationProcessFactory], [TestContextBuilder]), so those cannot drift either.
 *
 * @since 2026-09-09 (Issue #1029 — ends the CoreTestModule/CommonCoreTestModule scope-binding drift)
 */
val sharedSimulationTestScopesModule: Module =
	module {
		// Non-scope bindings both test module flavors carry identically (Issue #1029).
		single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }
		factory { TestContextBuilder() }

		// Define editingScope for per-context lifecycle management
		scope<DefaultEditingContext> {
			scoped<TopologyNavigator> {
				val context =
					getSource<DefaultEditingContext>()
						?: throw IllegalStateException("DefaultEditingContext source not found in scope")
				DefaultTopologyNavigator(context)
			}

			scoped<AutomaticPathFindingService> {
				DefaultAutomaticPathFindingService(get<TopologyNavigator>() as DefaultTopologyNavigator)
			}

			scoped<RouteFinder> {
				DefaultRouteFinder(get<AutomaticPathFindingService>())
			}
		}

		// Define simulationScope for per-context lifecycle management
		scope<DefaultSimulationContext> {
			scoped<TopologyNavigator> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultTopologyNavigator(context)
			}

			scoped<PathReservationRegistry> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				PathReservationRegistry(context)
			}

			scoped<PathInfoBuilder> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				PathInfoBuilder(context)
			}

			scoped<PathReservationService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				val navigator: TopologyNavigator = get()
				val registry: PathReservationRegistry = get()
				val pathInfoBuilder: PathInfoBuilder = get()
				val routeFinder: RouteFinder = get()
				DefaultPathReservationService(navigator, context, registry, pathInfoBuilder, routeFinder)
			}

			scoped<TrainNavigationService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				val registry: PathReservationRegistry = get()
				DefaultTrainNavigationService(context, registry)
			}

			scoped<AutomaticPathFindingService> {
				DefaultAutomaticPathFindingService(get<TopologyNavigator>() as DefaultTopologyNavigator)
			}

			scoped<RouteFinder> {
				DefaultRouteFinder(get<AutomaticPathFindingService>())
			}

			scoped<CollisionDetectionService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultCollisionDetectionService(context, context)
			}

			scoped<TemporalConflictDetector> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				TemporalConflictDetector(context)
			}

			scoped<ConflictResolver> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultConflictResolver.forEnvironment(
					context,
					preferenceStore = get<StrategyPreferenceStore>()
				)
			}

			scoped<StrategyPreferenceStore> { StrategyPreferenceStore() }

			scoped<DispatcherPreferenceStore> { DefaultDispatcherPreferenceStore() }

			scoped<AutoConflictResolutionService> {
				DefaultAutoConflictResolutionService(get<ConflictResolver>(), get<DispatcherPreferenceStore>())
			}

			scoped<MetricsCollectionService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultMetricsCollectionService(context)
			}

			// SP3.5 (Issue #573): InterlockingFacade — same binding as production CoreModule.
			// Required so tests that wire DefaultNetworkActuatorPort via the facade chokepoint
			// can resolve InterlockingFacade from the context scope.
			scoped<InterlockingFacade> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				val registry: PathReservationRegistry = get()
				DefaultInterlockingFacade(context, registry)
			}
		}
	}
