package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.di.editingModule
import cz.vutbr.fit.interlockSim.di.guiModule
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.di.objectsModule
import cz.vutbr.fit.interlockSim.di.simulationDesktopModule
import cz.vutbr.fit.interlockSim.di.xmlModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Lightweight test module (DEFAULT for most tests)
 *
 * Excludes guiModule to prevent Frame initialization overhead.
 * Use this for all tests that don't need GUI components.
 *
 * Performance: ~1-2s faster per test due to no Frame initialization.
 */
val testModuleLightweight: Module =
	module {
		includes(
			objectsModule,
			xmlModule,
			editingModule,
			coreModule, // simulationCoreModule + navigationModule (commonMain, KMP-clean)
			simulationDesktopModule // JVM-only: ContextTransformer, DefaultSimulationContextFactory, ExampleRegistry
			// NOTE: guiModule excluded to prevent Frame overhead
		)
		// Provide a new instance of TestContextBuilder for each injection
		factory { TestContextBuilder() }
	}

/**
 * Full test module (for GUI tests only)
 *
 * Includes all application modules including guiModule.
 * Use this only for tests that require Frame or GUI components.
 *
 * To use: Override getTestModule() in your test class to return testModuleFull.
 */
val testModuleFull: Module =
	module {
		includes(interlockSimModule)
		includes(guiModule) // GUI components and Main coordinator
		// Provide a new instance of TestContextBuilder for each injection
		factory { TestContextBuilder() }
	}

/**
 * Integration test module
 *
 * Same as testModuleLightweight but can be customized for integration tests
 * if different configuration is needed in the future.
 */
val integrationTestModule: Module =
	module {
		includes(
			objectsModule,
			xmlModule,
			editingModule,
			coreModule, // simulationCoreModule + navigationModule (commonMain, KMP-clean)
			simulationDesktopModule // JVM-only: ContextTransformer, DefaultSimulationContextFactory, ExampleRegistry
			// NOTE: guiModule excluded to prevent Frame overhead
		)
		// Provide a new instance of TestContextBuilder for each injection
		factory { TestContextBuilder() }
	}

/**
 * Legacy alias for backward compatibility
 * @deprecated Use testModuleLightweight or testModuleFull instead
 */
@Deprecated(
	"Use testModuleLightweight (default) or testModuleFull (GUI tests) instead",
	ReplaceWith("testModuleLightweight")
)
val testModule: Module = testModuleLightweight
