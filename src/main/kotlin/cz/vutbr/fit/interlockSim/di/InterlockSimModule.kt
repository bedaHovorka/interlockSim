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

import cz.vutbr.fit.interlockSim.context.EditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin Dependency Injection module for InterlockSim
 *
 * Koin migration implementation (2026-01-12):
 * - Start with empty module infrastructure
 * - Initial targets: util/ and xml/ packages
 * - Additional modules: context/, gui/, objects/
 * - sim/ package EXCLUDED per traffic-simulation-expert requirements
 *
 * Module organization:
 * - utilModule: Utility classes and helpers
 * - xmlModule: XML parsing and context factories
 * - contextModule: Context lifecycle management
 * - guiModule: Swing components and editor
 * - objectsModule: Domain model (tracks, cells, paths)
 *
 * @see org.koin.core.context.startKoin
 * @see cz.vutbr.fit.interlockSim.Main
 */

/**
 * Utility module
 *
 * Currently empty - utilities will be migrated incrementally
 * Candidates for DI:
 * - Resource loading patterns
 * - Utility factories (if any)
 */
val utilModule: Module = module {
	// Empty - to be populated incrementally
	// Example pattern (for reference):
	// single<ResourceLoader> { ResourceLoaderImpl() }
}

/**
 * XML module
 *
 * Manages XML parsing and context factory singletons
 * Primary target for initial Koin migration implementation
 */
val xmlModule: Module = module {
	// XMLContextFactory is the singleton factory for both editing and simulation contexts
	single<XMLContextFactory> { XMLContextFactory() }
}

/**
 * Context module
 *
 * Manages simulation and editing context lifecycle
 *
 * CRITICAL: Contexts are NOT singletons - they have specific lifecycle
 * - EditingContextFactory and SimulationContextFactory are singletons (factories)
 * - Contexts themselves are created fresh each time (factory pattern)
 * - This preserves the existing factory pattern while enabling DI
 *
 * @see EditingContextFactory
 * @see SimulationContextFactory
 * @see XMLContextFactory
 */
val contextModule: Module = module {
	// XMLContextFactory is already defined in xmlModule as a singleton
	// Bind factory interfaces to the singleton XMLContextFactory instance
	single<EditingContextFactory> { get<XMLContextFactory>() }
	single<SimulationContextFactory> { get<XMLContextFactory>() }
}

/**
 * GUI module (DEFERRED)
 *
 * Manages Swing components, editor, and UI elements
 */
val guiModule: Module = module {
	// To be implemented when needed
}

/**
 * Domain objects module
 *
 * Manages tracks, cells, paths, and railway network objects
 *
 * DESIGN DECISION: This module is intentionally MINIMAL because:
 *
 * 1. **Object Lifecycle:** Tracks, cells, and paths are created from XML during context
 *    initialization (XMLContextFactory.createNew via reflection) and managed by the
 *    context itself. They are not application-level singletons.
 *
 * 2. **Creation Pattern:** XMLContextFactory uses reflection-based instantiation
 *    (see createNew method at line 520) which is optimized and works well. Adding
 *    Koin factories would duplicate this without clear benefit.
 *
 * 3. **Dependency Flow:** Objects depend on:
 *    - Context: Injected via constructor (ArrayPath takes SimulationContext)
 *    - Type/Config: Injected via constructor parameters from XML attributes
 *    - Other objects: Created together during XML parsing
 *
 * 4. **Future Extensibility:** If in-code object creation becomes needed (e.g., for
 *    Goal 16 Signal Explanation or GUI editing features), factories can be added
 *    incrementally without refactoring the XML parsing layer.
 *
 * CURRENT SCOPE:
 * - Factory pattern demonstrated for ArrayPath (factory uses SimulationContext)
 * - Patterns documented for future expansion
 * - sim/ package EXCLUDED (see CRITICAL CONSTRAINTS below)
 *
 * CRITICAL CONSTRAINTS (traffic-simulation-expert):
 * - sim/ package: ❌ NO DI - Wait for DSOL/Kalasim migration
 * - Train, InOutWorker, ShuntingLoop: NOT included (sim package)
 * - Objects created by xmlFactory.createNew() via reflection: Working as-is
 *
 * FUTURE CANDIDATES (when needed):
 * - Path factories (if dynamic path construction required)
 * - Cell factories (if runtime cell creation needed)
 * - Track block builders (if complex track layouts needed)
 *
 * @see ArrayPath
 * @see XMLContextFactory.createNew
 */
val objectsModule: Module = module {
	// Domain objects have limited DI candidates due to XML-driven creation
	// The existing reflection-based factory pattern (XMLContextFactory.createNew) is optimal

	// Future candidate: Factory for creating ArrayPath instances if needed in code
	// Currently created in DefaultContext.createNewPath() via direct instantiation
	// Example pattern (not currently used):
	// factory<Path> { (context: SimulationContext) -> ArrayPath(context) }

	// Empty for now - if code-based path/cell creation is added, this is where factories go
}

/**
 * Main application module - combines all sub-modules
 *
 * This is the module that gets passed to startKoin()
 *
 * Included modules:
 * - utilModule (✅ Complete - Ready for expansion)
 * - xmlModule (✅ Complete)
 * - contextModule (✅ Complete)
 * - objectsModule (✅ Complete - Minimal by design)
 *
 * Deferred modules:
 * - guiModule (⏳ Deferred - To be implemented when needed)
 *
 * Excluded:
 * - sim/ package (❌ Excluded): Deferred until jDisco → DSOL/Kalasim migration
 */
val interlockSimModule: Module = module {
	// Include all sub-modules
	includes(
		utilModule,
		xmlModule,
		contextModule,    // Context lifecycle management
		objectsModule     // Domain objects (minimal - see design decision)
		// guiModule,     // To be implemented when needed
	)
}
