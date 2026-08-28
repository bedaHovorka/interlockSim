/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * Base class for `:dispatcher-agent` tests that need a Koin container.
 *
 * `:core` and `:desktop-ui` each already have a `KoinTestBase`; `:dispatcher-agent` had none, so
 * ~20 test classes repeated the same `@BeforeEach { startKoin { modules(dispatcherAgentTestModule) } }`
 * plus `@AfterEach { stopKoin() }` pair verbatim (Issue #955, cluster D1). This class holds that pair
 * once.
 *
 * A subclass that needs different modules overrides [getTestModules].
 *
 * A subclass may keep its own `@AfterEach` for extra teardown (detaching a log appender, closing a
 * context). JUnit 5 runs a subclass `@AfterEach` **before** the superclass one, so such teardown
 * still sees a live Koin container.
 */
abstract class DispatcherKoinTestBase {
	/** Override to start Koin with different modules. Default: `dispatcherAgentTestModule`. */
	protected open fun getTestModules(): List<Module> = listOf(dispatcherAgentTestModule)

	@BeforeEach
	fun setUpKoin() {
		startKoin {
			modules(getTestModules())
		}
	}

	@AfterEach
	fun tearDownKoin() {
		stopKoin()
	}
}
