package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.di.interlockSimModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Test module for InterlockSim
 *
 * Includes all main application modules to provide
 * a complete DI environment for tests.
 *
 * Place for test utilities and test-specific overrides if needed.
 */
val testModule: Module =
	module {
		// Include all app - main module
		includes(interlockSimModule)
		// Provide a new instance of TestContextBuilder for each injection
		factory { TestContextBuilder() }
	}
