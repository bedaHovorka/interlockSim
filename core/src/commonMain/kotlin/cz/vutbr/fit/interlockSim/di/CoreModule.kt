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

import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.GridTransformer
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
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.collision.CollisionDetectionService
import cz.vutbr.fit.interlockSim.sim.collision.DefaultCollisionDetectionService
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Simulation core module (commonMain bindings only)
 *
 * Provides:
 * - SimulationProcessFactory / DefaultSimulationProcessFactory (stateless singleton)
 * - GridTransformer (Kotlin object singleton, registered for DI consistency)
 *
 * NOT included here (JVM-only, stay in desktop-ui):
 * - ContextTransformer (jvmMain)
 * - DefaultSimulationContextFactory (jvmMain)
 * - ExampleRegistry (desktop-ui)
 *
 * @see SimulationProcessFactory
 * @see DefaultSimulationProcessFactory
 * @see GridTransformer
 */
val simulationCoreModule: Module =
	module {
		// Factory for creating simulation processes (Generator, InOutWorker)
		// Singleton as factory is stateless
		single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }

		// Grid transformer for static-to-dynamic cell conversion (grid parameterization)
		// GridTransformer is a Kotlin object (singleton), registered via Koin for DI consistency
		single { GridTransformer }
	}

/**
 * Navigation module
 *
 * Provides navigation services for path finding and reservation using **scope-per-context** pattern:
 * - TopologyNavigator for static topology navigation
 * - PathReservationRegistry for tracking train ownership of blocks
 * - PathInfoBuilder for building entry direction metadata
 * - PathReservationService for atomic path reservation
 * - TrainNavigationService for train-specific path following
 *
 * ## Scope-per-Context Architecture
 *
 * Each SimulationContext creates its own Koin scope ("simulationScope") that manages:
 * - **One shared PathReservationRegistry** for the entire simulation
 * - All navigation services within that context share the same registry
 * - Different contexts have isolated registries (no state bleeding)
 * - Scope is closed when context is destroyed, cleaning up resources
 *
 * ## Usage Patterns
 *
 * Services are retrieved from the context's scope (NOT global Koin):
 *
 * ```kotlin
 * // In DefaultSimulationContext constructor:
 * private val scope = getKoin().createScope<DefaultSimulationContext>()
 *
 * // TopologyNavigator requires Context parameter
 * val navigator: TopologyNavigator = scope.get { parametersOf(this) }
 *
 * // PathReservationService requires navigator and environment
 * val service: PathReservationService = scope.get {
 *     parametersOf(navigator, this as SimulationEnvironment)
 * }
 *
 * // TrainNavigationService requires environment parameter
 * // Registry is SHARED with PathReservationService (same scoped instance)
 * val trainNavService: TrainNavigationService = scope.get {
 *     parametersOf(this as SimulationEnvironment)
 * }
 *
 * // In context cleanup:
 * scope.close()  // Destroys scoped PathReservationRegistry
 * ```
 *
 * ## Why Scoped, Not Singleton or Factory?
 *
 * - **singleton**: ❌ State bleeding between simulation runs
 * - **factory**: ❌ Each get() creates new instance, components don't share state
 * - **scoped**: ✅ One registry per context, shared by all components, isolated between contexts
 *
 * @see TopologyNavigator
 * @see PathReservationRegistry
 * @see PathReservationService
 * @see TrainNavigationService
 * @see org.koin.core.scope.Scope
 * @since Issue #294 (Phase 2 DI Integration), Issue #295 (Phase 3 TrainNavigationService)
 * @since Issue #296 (Phase 4 Scope-per-Context Pattern)
 */
val navigationModule: Module =
	module {
		// Define editingScope for per-context lifecycle management
		scope<DefaultEditingContext> {
			// TopologyNavigator: scoped to this editing context
			// Context is automatically provided via getSource()
			scoped<TopologyNavigator> {
				val context =
					getSource<DefaultEditingContext>()
						?: throw IllegalStateException("DefaultEditingContext source not found in scope")
				DefaultTopologyNavigator(context)
			}

			// AutomaticPathFindingService: scoped to editing context
			// Requires DefaultTopologyNavigator (concrete) for internal graph-expansion primitive
			scoped<AutomaticPathFindingService> {
				DefaultAutomaticPathFindingService(get<TopologyNavigator>() as DefaultTopologyNavigator)
			}

			// RouteFinder: scoped to editing context
			// Builds on AutomaticPathFindingService (same scope)
			scoped<RouteFinder> {
				DefaultRouteFinder(get<AutomaticPathFindingService>())
			}
		}

		// Define simulationScope for per-context lifecycle management
		scope<DefaultSimulationContext> {
			// TopologyNavigator: scoped to this simulation context
			// Context is automatically provided via getSource()
			scoped<TopologyNavigator> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultTopologyNavigator(context)
			}

			// PathReservationRegistry: scoped, ONE instance per simulation context
			// All services within this scope share the same registry
			// Different scopes (contexts) have isolated registries
			// Issue #296 Phase 8: Now requires SimulationContext for PathInfo merging
			scoped<PathReservationRegistry> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				PathReservationRegistry(context)
			}

			// PathInfoBuilder: scoped to this simulation context (Issue #295/#296 Phase 4)
			// Used by PathReservationService to build entry direction metadata
			scoped<PathInfoBuilder> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				PathInfoBuilder(context)
			}

			// PathReservationService: scoped to this simulation context
			// Navigator, registry, pathInfoBuilder and routeFinder are injected from the same scope (shared instances)
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

			// TrainNavigationService: scoped to this simulation context
			// Registry is SHARED with PathReservationService (same scoped instance)
			scoped<TrainNavigationService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				val registry: PathReservationRegistry = get()
				DefaultTrainNavigationService(context, registry)
			}

			// AutomaticPathFindingService: scoped to simulation context
			// Requires DefaultTopologyNavigator (concrete) for internal graph-expansion primitive
			scoped<AutomaticPathFindingService> {
				DefaultAutomaticPathFindingService(get<TopologyNavigator>() as DefaultTopologyNavigator)
			}

			// RouteFinder: scoped to simulation context
			// Builds on AutomaticPathFindingService (same scope)
			scoped<RouteFinder> {
				DefaultRouteFinder(get<AutomaticPathFindingService>())
			}

			// CollisionDetectionService: scoped to simulation context (Issue #611 Goal 3 SP1)
			// Subscribes to block events and calls pause controller when a hazard is detected.
			scoped<CollisionDetectionService> {
				val context =
					getSource<DefaultSimulationContext>()
						?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultCollisionDetectionService(context, context)
			}
		}
	}

/**
 * Core Koin module — aggregates commonMain-only sub-modules.
 *
 * This module must remain free of JVM-only (java.*, javax.*, JAXP, …) imports
 * so that it compiles cleanly for all KMP targets.
 *
 * IMPORTANT: `coreModule` is declared LAST in this file so that `simulationCoreModule`
 * and `navigationModule` are initialized before it. Kotlin top-level vals in the same
 * file are initialized in declaration order; declaring `coreModule` first would store
 * null references in its `includes()` list (JVM static init ordering issue).
 */
val coreModule: Module =
	module {
		includes(simulationCoreModule, navigationModule)
	}
