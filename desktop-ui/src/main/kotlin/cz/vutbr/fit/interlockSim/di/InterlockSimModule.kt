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
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContextFactory
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationProcessFactory
import cz.vutbr.fit.interlockSim.gui.Frame
import cz.vutbr.fit.interlockSim.gui.animation.AnimationStateCapture
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/*
 * Koin Dependency Injection module for InterlockSim
 *
 * Koin migration implementation (2026-01-12 and later)
 */

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
		single<JvmEditingContextFactory> { get<XMLContextFactory>() }
		// Also bind as the portable supertype so commonTest/JVM-agnostic code can inject EditingContextFactory
		single<EditingContextFactory> { get<JvmEditingContextFactory>() }
	}

/**
 * Simulation desktop module (JVM-only bindings)
 *
 * Extends [coreModule] (which provides [SimulationProcessFactory] and [GridTransformer]) with
 * JVM-only bindings that cannot live in commonMain:
 * - ContextTransformer (jvmMain) for EditingContext → SimulationContext transformation
 * - DefaultSimulationContextFactory (jvmMain) for creating simulation contexts from files/streams
 * - ExampleRegistry (desktop-ui) for managing simulation examples
 *
 * [simulationCoreModule] (included via [coreModule]) already registers [SimulationProcessFactory]
 * and [GridTransformer], so they are NOT repeated here.
 *
 * @see SimulationContextFactory
 * @see DefaultSimulationContextFactory
 * @see ContextTransformer
 */
val simulationDesktopModule: Module =
	module {
		// Context transformer for EditingContext → SimulationContext transformation
		// ContextTransformer is a Kotlin object (singleton), we provide it via Koin for DI consistency
		single { ContextTransformer }

		// Factory for creating simulation contexts from files/streams/editing contexts
		// Singleton as factory is stateless, receives both editing factory and process factory via DI
		single<SimulationContextFactory> {
			DefaultSimulationContextFactory(
				get<JvmEditingContextFactory>(),
				get<SimulationProcessFactory>()
			)
		}

		single<ExampleRegistry> { ExampleRegistry() }
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
			objectsModule, // Domain objects (minimal - see design decision)
			xmlModule,
			editingModule,
			coreModule, // simulationCoreModule + navigationModule (commonMain, KMP-clean)
			simulationDesktopModule // JVM-only: ContextTransformer, DefaultSimulationContextFactory, ExampleRegistry
			// NOTE: guiModule and animationModule are NOT included by default
			// Load them explicitly when GUI is needed (edit mode, animated sim mode)
		)
	}
