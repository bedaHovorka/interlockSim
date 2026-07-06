package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.CommonSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTrainNavigationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.pathfinding.AutomaticPathFindingService
import cz.vutbr.fit.interlockSim.pathfinding.DefaultAutomaticPathFindingService
import cz.vutbr.fit.interlockSim.pathfinding.DefaultRouteFinder
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.conflict.AutoConflictResolutionService
import cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultAutoConflictResolutionService
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultConflictResolver
import cz.vutbr.fit.interlockSim.sim.conflict.DefaultDispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.DispatcherPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.StrategyPreferenceStore
import cz.vutbr.fit.interlockSim.sim.conflict.TemporalConflictDetector
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Core test module for native-compatible (commonTest) tests.
 *
 * Uses programmatic context construction (no XML, no java.* imports).
 * Safe to use on all KMP targets including linuxX64.
 *
 * @since 2026-03-20 (KMP Step 4 — split commonTest for linuxX64)
 */
/**
 * Minimal EditingContextFactory for commonTest: only supports createEmptyContext().
 */
private class CommonTestEditingContextFactory : EditingContextFactory {
	override fun createEmptyContext(): EditingContext = DefaultEditingContext(100, 100)
}

/**
 * Minimal CommonSimulationContextFactory for commonTest: builds contexts programmatically.
 */
private class CommonTestSimulationContextFactory(
	private val processFactory: SimulationProcessFactory,
	private val editingContextFactory: EditingContextFactory,
) : CommonSimulationContextFactory {
	override fun createContext(editingContext: EditingContext): SimulationContext =
		DefaultSimulationContext.fromEditingContext(editingContext, processFactory)

	override fun createEmptyContext(): SimulationContext =
		editingContextFactory.createEmptyContext().use { editingContext ->
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}
}

val commonCoreTestModule: Module =
	module {
		single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }

		// Provide EditingContextFactory (common-only, no XML/file I/O)
		single<EditingContextFactory> { CommonTestEditingContextFactory() }

		// Provide CommonSimulationContextFactory (common-only, no XML/file I/O)
		single<CommonSimulationContextFactory> { CommonTestSimulationContextFactory(get(), get()) }

		factory { TestContextBuilder() }

		// Default editing context factory: creates a minimal linear-track context
		factory<DefaultEditingContext> {
			val ctx = DefaultEditingContext(30, 30)
			val inA = InOut("A", false, Cell.SpatialType.HORIZONTAL)
			val inB = InOut("B", true, Cell.SpatialType.HORIZONTAL)
			val track = SimpleTrackBlock(inA, inB, 100.0, 80.0)
			ctx.putCell(Point(1, 1), inA)
			ctx.putCell(Point(5, 5), inB)
			ctx.joinCells(Point(1, 1), Point(5, 5), track)
			ctx
		}

		factory<DefaultSimulationContext> {
			val processFactory = get<SimulationProcessFactory>()
			get<DefaultEditingContext>().use { editingCtx ->
				DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
			}
		}

		// Define editingScope for per-context lifecycle management
		scope<DefaultEditingContext> {
			scoped<TopologyNavigator> {
				val context =
					getSource<DefaultEditingContext>()
						?: throw IllegalStateException("DefaultEditingContext source not found in scope")
				DefaultTopologyNavigator(context)
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
		}
	}
