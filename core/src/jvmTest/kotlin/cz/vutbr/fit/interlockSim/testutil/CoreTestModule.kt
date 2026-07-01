package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.GridTransformer
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
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
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * EditingContextFactory for core tests — delegates to XMLContextFactory directly.
 */
private class CoreTestEditingContextFactory : JvmEditingContextFactory {
	private val xmlFactory = XMLContextFactory()

	override fun createEmptyContext(): EditingContext = xmlFactory.createEmptyContext()

	override fun createNew(
		context: EditingContext,
		clazz: Class<*>,
		vararg arguments: Any
	): Any = xmlFactory.createNew(context, clazz, *arguments)

	override fun createContext(file: File): cz.vutbr.fit.interlockSim.context.Context<*, *> =
		xmlFactory.createContext(file)

	override fun createContext(stream: InputStream): cz.vutbr.fit.interlockSim.context.Context<*, *> =
		xmlFactory.createContext(stream)

	override fun saveContext(
		context: cz.vutbr.fit.interlockSim.context.Context<*, *>,
		file: File
	): Boolean = xmlFactory.saveContext(context, file)

	override fun saveContext(
		context: cz.vutbr.fit.interlockSim.context.Context<*, *>,
		stream: OutputStream
	): Boolean = xmlFactory.saveContext(context, stream)
}

/**
 * Core test module - provides all dependencies needed for core module tests.
 *
 * Uses reflection to access XMLContextFactory if available on the classpath.
 * This allows core tests that load XML fixtures to work when the app module is on the test classpath.
 */
val coreTestModule: Module =
	module {
		single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }
		single { GridTransformer }
		single { ContextTransformer }
		single<JvmEditingContextFactory> { CoreTestEditingContextFactory() }
		single<EditingContextFactory> { get<JvmEditingContextFactory>() }
		single<SimulationContextFactory> {
			DefaultSimulationContextFactory(
				get<JvmEditingContextFactory>(),
				get<SimulationProcessFactory>()
			)
		}

		// Provide a new instance of TestContextBuilder for each injection
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
		}
	}
