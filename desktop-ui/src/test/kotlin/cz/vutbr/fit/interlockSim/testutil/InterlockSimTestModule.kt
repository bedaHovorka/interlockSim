package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.di.coreModule
import cz.vutbr.fit.interlockSim.di.editingModule
import cz.vutbr.fit.interlockSim.di.guiModule
import cz.vutbr.fit.interlockSim.di.interlockSimModule
import cz.vutbr.fit.interlockSim.di.objectsModule
import cz.vutbr.fit.interlockSim.di.simulationDesktopModule
import cz.vutbr.fit.interlockSim.di.xmlModule
import cz.vutbr.fit.interlockSim.dispatcher.di.dispatcherAgentModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.ModelProfileRecord
import cz.vutbr.fit.interlockSim.dispatcher.executor.ModelfileHashSource
import org.koin.core.module.Module
import org.koin.dsl.module

/** The Modelfile hash the test modules' [ModelfileHashSource] reports for every tag (Issue #1342). */
const val TEST_MODELFILE_SHA256: String = "0000000000000000000000000000000000000000000000000000000000001342"

/**
 * A [ModelfileHashSource] that never calls Ollama (Issue #1342): an LLM example built by a test
 * records this profile instead of reading `/api/show` from whatever Ollama the machine runs.
 */
val testModelfileHashSource: ModelfileHashSource =
	ModelfileHashSource { tag -> ModelProfileRecord(tag, TEST_MODELFILE_SHA256, numPredict = null) }

/**
 * Overrides `dispatcherAgentModule`'s Ollama-backed [ModelfileHashSource] with
 * [testModelfileHashSource]. [KoinTestBase] loads it after every test module, so its definition
 * wins; a test that starts Koin itself and builds an LLM example adds it the same way.
 */
val testModelfileHashSourceModule: Module =
	module {
		single<ModelfileHashSource> { testModelfileHashSource }
	}

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
			simulationDesktopModule, // JVM-only: ContextTransformer, DefaultSimulationContextFactory, ExampleRegistry
			dispatcherAgentModule // Goal 10 Dispatcher/ActuatorCommandQueue bindings (SP0.11 review follow-up)
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
			simulationDesktopModule, // JVM-only: ContextTransformer, DefaultSimulationContextFactory, ExampleRegistry
			dispatcherAgentModule // Goal 10 Dispatcher/ActuatorCommandQueue bindings (SP0.11 review follow-up)
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
