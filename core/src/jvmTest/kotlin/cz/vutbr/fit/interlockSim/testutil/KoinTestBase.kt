/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Base class for tests using Koin dependency injection (core module version)
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.test.KoinTest

/**
 * Base class for core module tests that use Koin dependency injection.
 *
 * Uses coreTestModule which provides all core dependencies including
 * a reflection-based EditingContextFactory that delegates to XMLContextFactory
 * when available on the classpath.
 *
 * @since 2026 (core module extraction)
 */
abstract class KoinTestBase : KoinTest {
	/**
	 * Optional context tracking for automatic cleanup.
	 * Tests that create a context in @BeforeEach should set this field.
	 * Will be closed automatically in tearDownKoin().
	 */
	protected var testContext: Context<*, *>? = null

	/**
	 * Override this method to use a different test module.
	 * Default: coreTestModule
	 */
	protected open fun getTestModule(): Module = coreTestModule

	@BeforeEach
	fun setUpKoin() {
		startKoin {
			modules(getTestModule())
		}
	}

	@AfterEach
	fun tearDownKoin() {
		testContext?.close()
		testContext = null
		stopKoin()
	}
}
