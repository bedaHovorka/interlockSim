package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.di.guiModule
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Lightweight test module (DEFAULT for most tests)
 *
 * Excludes guiModule (Frame) to prevent GUI initialization overhead.
 * Includes Main coordinator for CLI tests.
 * Use this for all tests that don't need GUI Frame components.
 *
 * Performance: ~1-2s faster per test due to no Frame initialization.
 */
val testModuleLightweight: Module =
	module {
		includes(interlockSimModule) // Includes Main but not Frame
		// Provide a new instance of TestContextBuilder for each injection
		factory { TestContextBuilder() }
	}

/**
 * Full test module (for GUI tests only)
 *
 * Includes all application modules including guiModule (Frame).
 * Use this only for tests that require Frame or GUI components.
 *
 * To use: Override getTestModule() in your test class to return testModuleFull.
 */
val testModuleFull: Module =
	module {
		includes(interlockSimModule) // Includes Main
		includes(guiModule) // Adds Frame
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
		includes(interlockSimModule) // Includes Main but not Frame
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
