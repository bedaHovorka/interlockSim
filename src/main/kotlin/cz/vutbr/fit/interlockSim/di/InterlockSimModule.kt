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
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.GridTransformer
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
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
 * Provides navigation services for path finding and reservation:
 * - TopologyNavigator for static topology navigation
 * - PathReservationRegistry for tracking train ownership of blocks
 * - PathReservationService for atomic path reservation
 *
 * All services use factory scope (NOT singleton) to ensure:
 * - Fresh instances per context (TopologyNavigator)
 * - Isolated state per simulation run (PathReservationRegistry)
 * - Proper dependency injection (PathReservationService)
 *
 * ## Usage Patterns
 *
 * Services require context-specific parameters via Koin parameter passing:
 *
 * ```kotlin
 * // TopologyNavigator requires Context parameter
 * val navigator: TopologyNavigator = getKoin().get { parametersOf(context) }
 *
 * // PathReservationService requires navigator and environment
 * val service: PathReservationService = getKoin().get {
 *     parametersOf(navigator, environment)
 * }
 *
 * // PathReservationRegistry created automatically (no parameters)
 * val registry: PathReservationRegistry = getKoin().get()
 * ```
 *
 * @see TopologyNavigator
 * @see PathReservationRegistry
 * @see PathReservationService
 * @since Issue #294 (Phase 2 DI Integration)
 */
val navigationModule: Module =
	module {
		// Factory for TopologyNavigator (requires context parameter)
		// Each context gets its own navigator instance
		factory<TopologyNavigator> { (context: Context<Cell, out TrackBlock>) ->
			DefaultTopologyNavigator(context)
		}

		// Factory for PathReservationRegistry (fresh instance per simulation)
		// Prevents state bleeding between simulation runs
		factory<PathReservationRegistry> {
			PathReservationRegistry()
		}

		// Factory for PathReservationService (requires navigator + environment parameters)
		// Registry is created automatically by Koin and injected
		factory<PathReservationService> { (navigator: TopologyNavigator, environment: SimulationEnvironment) ->
			val registry: PathReservationRegistry = get()
			DefaultPathReservationService(navigator, environment, registry)
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
 * Main application module - combines all sub-modules
 *
 * This is the module that gets passed to startKoin()
 *
 * NOTE: guiModule is NOT included by default to prevent Frame initialization overhead.
 * Load guiModule explicitly when GUI is needed (see Main.kt for conditional loading).
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
			// NOTE: guiModule is NOT included by default
			// Load it explicitly when GUI is needed (edit mode)
		)
	}
