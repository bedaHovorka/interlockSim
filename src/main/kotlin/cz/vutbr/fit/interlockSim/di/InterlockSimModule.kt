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

import cz.vutbr.fit.interlockSim.ExampleRegistry
import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.MyResourceBundle
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.GridTransformer
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
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.gui.animation.AnimationStateCapture
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin Dependency Injection module for InterlockSim
 *
 * Koin migration implementation (2026-01-12 and later)
 *
 * @see org.koin.core.context.startKoin
 * @see cz.vutbr.fit.interlockSim.Main
 */

/**
 * Utility module
 *
 * Manages utilities and example registry
 * - MyResourceBundle for resource loading
 * - ExampleRegistry for simulation examples
 */
val utilModule: Module =
	module {
		single<MyResourceBundle> { MyResourceBundle() }
	}

/**
 * Domain objects module
 *
 * Currently empty - domain objects will be migrated incrementally
 * Candidates for DI:
 * - Factories for path/cell creation (if code-based creation is added)
 */
val objectsModule: Module =
	module {
		// Empty for now - if code-based path/cell creation is added, this is where factories go
	}

/**
 * XML module
 *
 * Manages XML parsing and context factory singletons
 * Primary target for initial Koin migration implementation
 */
val xmlModule: Module =
	module {
		single<XMLContextFactory> { XMLContextFactory() }
	}

/**
 * Editing module
 *
 *
 * @see EditingContextFactory
 * @see XMLContextFactory
 */
val editingModule: Module =
	module {
		// XMLContextFactory is now defined in xmlModule as a singleton, but not in future
		// Bind factory interfaces to the singleton XMLContextFactory instance
		single<EditingContextFactory> { get<XMLContextFactory>() }
	}

/**
 * Simulation module
 *
 * Provides simulation-related dependencies:
 * - SimulationProcessFactory for creating simulation processes
 * - SimulationContextFactory for creating simulation contexts
 * - GridTransformer for static-to-dynamic grid transformation
 * - ContextTransformer for EditingContext → SimulationContext transformation
 * - ExampleRegistry for managing simulation examples
 *
 * @see SimulationContextFactory
 * @see DefaultSimulationContextFactory
 * @see SimulationProcessFactory
 * @see GridTransformer
 * @see ContextTransformer
 */
val simulationModule: Module =
	module {
		// Factory for creating simulation processes (Generator, InOutWorker)
		// Singleton as factory is stateless
		single<SimulationProcessFactory> { DefaultSimulationProcessFactory() }

		// Grid transformer for static-to-dynamic cell conversion (grid parameterization)
		// GridTransformer is a Kotlin object (singleton), we provide it via Koin for DI consistency
		single { GridTransformer }

		// Context transformer for EditingContext → SimulationContext transformation
		// ContextTransformer is a Kotlin object (singleton), we provide it via Koin for DI consistency
		single { ContextTransformer }

		// Factory for creating simulation contexts from files/streams/editing contexts
		// Singleton as factory is stateless, receives both editing factory and process factory via DI
		single<SimulationContextFactory> {
			DefaultSimulationContextFactory(
				get<EditingContextFactory>(),
				get<SimulationProcessFactory>()
			)
		}

		single<ExampleRegistry> { ExampleRegistry() }
	}

/**
 * Navigation module
 *
 * Provides navigation services for path finding and reservation using **scope-per-context** pattern:
 * - TopologyNavigator for static topology navigation
 * - PathReservationRegistry for tracking train ownership of blocks
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
				val context = getSource<DefaultEditingContext>()
					?: throw IllegalStateException("DefaultEditingContext source not found in scope")
				DefaultTopologyNavigator(context)
			}
		}

		// Define simulationScope for per-context lifecycle management
		scope<DefaultSimulationContext> {
			// TopologyNavigator: scoped to this simulation context
			// Context is automatically provided via getSource()
			scoped<TopologyNavigator> {
				val context = getSource<DefaultSimulationContext>()
					?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				DefaultTopologyNavigator(context)
			}

			// PathReservationRegistry: scoped, ONE instance per simulation context
			// All services within this scope share the same registry
			// Different scopes (contexts) have isolated registries
			// Issue #296 Phase 8: Now requires SimulationContext for PathInfo merging
			scoped<PathReservationRegistry> {
				val context = getSource<DefaultSimulationContext>()
					?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				PathReservationRegistry(context)
			}

			// PathInfoBuilder: scoped to this simulation context (Issue #295/#296 Phase 4)
			// Used by PathReservationService to build entry direction metadata
			scoped<PathInfoBuilder> {
				val context = getSource<DefaultSimulationContext>()
					?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				PathInfoBuilder(context)
			}

			// PathReservationService: scoped to this simulation context
			// Navigator, registry, and pathInfoBuilder are injected from the same scope (shared instances)
			scoped<PathReservationService> {
				val context = getSource<DefaultSimulationContext>()
					?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				val navigator: TopologyNavigator = get()
				val registry: PathReservationRegistry = get()
				val pathInfoBuilder: PathInfoBuilder = get()
				DefaultPathReservationService(navigator, context, registry, pathInfoBuilder)
			}

			// TrainNavigationService: scoped to this simulation context
			// Registry is SHARED with PathReservationService (same scoped instance)
			// TopologyNavigator is used for pure topology traversal (no recursion)
			scoped<TrainNavigationService> {
				val context = getSource<DefaultSimulationContext>()
					?: throw IllegalStateException("DefaultSimulationContext source not found in scope")
				val registry: PathReservationRegistry = get()
				val navigator: TopologyNavigator = get()
				DefaultTrainNavigationService(context, registry, navigator)
			}
		}
	}

/**
 * GUI module
 *
 * Manages Swing components, editor, UI elements
 */
val guiModule: Module =
	module {
		single<Frame> { Frame() }
		// Main application launcher as singleton
		single<Main> { Main() }
	}

/**
 * Animation module
 *
 * Manages animation infrastructure for animated GUI simulation.
 * Provides animation state capture utility.
 *
 * NOTE: AnimationController is NOT provided here because it requires
 * specific context and canvas instances (use direct instantiation).
 *
 * @see cz.vutbr.fit.interlockSim.gui.animation.AnimationController
 * @see cz.vutbr.fit.interlockSim.gui.animation.AnimationStateCapture
 */
val animationModule: Module =
	module {
		// AnimationStateCapture is a stateless object singleton
		single { AnimationStateCapture }

		// AnimationController is NOT a singleton - it depends on specific
		// SimulationContext and Component instances, which vary per use case.
		// Instantiate directly: AnimationController(context, canvas)
	}

/**
 * Main application module - combines all sub-modules
 *
 * This is the module that gets passed to startKoin()
 *
 * NOTE: guiModule and animationModule are NOT included by default to prevent overhead.
 * Load them explicitly when GUI is needed (see Main.kt for conditional loading).
 */
val interlockSimModule: Module =
	module {
		// Include all sub-modules
		includes(
			utilModule,
			objectsModule, // Domain objects (minimal - see design decision)
			xmlModule,
			editingModule,
			simulationModule,
			navigationModule // Navigation services (Issue #294)
			// NOTE: guiModule and animationModule are NOT included by default
			// Load them explicitly when GUI is needed (edit mode, animated sim mode)
		)
	}
