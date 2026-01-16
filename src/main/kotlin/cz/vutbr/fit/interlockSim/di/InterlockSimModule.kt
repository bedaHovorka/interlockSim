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

import cz.vutbr.fit.interlockSim.Main
import cz.vutbr.fit.interlockSim.MyResourceBundle
import cz.vutbr.fit.interlockSim.ExampleRegistry
import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.gui.Frame
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
val utilModule: Module = module {
	single<MyResourceBundle> { MyResourceBundle() }
}

/**
 * Domain objects module
 *
 * Currently empty - domain objects will be migrated incrementally
 * Candidates for DI:
 * - Factories for path/cell creation (if code-based creation is added)
 */
val objectsModule: Module = module {
	// Empty for now - if code-based path/cell creation is added, this is where factories go
}

/**
 * XML module
 *
 * Manages XML parsing and context factory singletons
 * Primary target for initial Koin migration implementation
 */
val xmlModule: Module = module {
	single<XMLContextFactory> { XMLContextFactory() }
}

/**
 * Editing module
 *
 *
 * @see EditingContextFactory
 * @see XMLContextFactory
 */
val editingModule: Module = module {
	// XMLContextFactory is now defined in xmlModule as a singleton, but not in future
	// Bind factory interfaces to the singleton XMLContextFactory instance
	single<EditingContextFactory> { get<XMLContextFactory>() }
}

/**
 * Simulation module
 *
 *
 * @see SimulationContextFactory
 * @see XMLContextFactory
 */
val simulationModule: Module = module {
	// XMLContextFactory is now defined in xmlModule as a singleton, but not in future
	// Bind factory interfaces to the singleton XMLContextFactory instance
	single<SimulationContextFactory> { get<XMLContextFactory>() }
	single<ExampleRegistry> { ExampleRegistry() }
}

/**
 * GUI module
 *
 * Manages Swing components, editor, UI elements
 */
val guiModule: Module = module {
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
val interlockSimModule: Module = module {
	// Include all sub-modules
	includes(
		utilModule,
		objectsModule,     // Domain objects (minimal - see design decision)
		xmlModule,
		editingModule,
		simulationModule
		// NOTE: guiModule is NOT included by default
		// Load it explicitly when GUI is needed (edit mode)
	)
}
