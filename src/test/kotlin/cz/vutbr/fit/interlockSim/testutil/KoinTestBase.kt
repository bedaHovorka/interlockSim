/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Base class for tests using Koin dependency injection
 */
package cz.vutbr.fit.interlockSim.testutil

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.test.KoinTest

/**
 * Base class for tests that use Koin dependency injection.
 *
 * Automatically starts Koin before each test and stops it after each test,
 * eliminating boilerplate setup/teardown code in individual test classes.
 *
 * By default, uses testModuleLightweight which excludes GUI components for better performance.
 * GUI tests can override getTestModule() to return testModuleFull.
 *
 * Usage (default - no GUI):
 * ```kotlin
 * class MyTest : KoinTestBase() {
 *     private val factory: XMLContextFactory by inject()
 *
 *     @Test
 *     fun myTest() {
 *         // factory is available here, no Frame overhead
 *     }
 * }
 * ```
 *
 * Usage (GUI tests):
 * ```kotlin
 * class MyGUITest : KoinTestBase() {
 *     override fun getTestModule(): Module = testModuleFull
 *
 *     @Test
 *     fun myTest() {
 *         // Frame is available via Koin
 *     }
 * }
 * ```
 *
 * Note: This class handles Koin lifecycle management for tests that need to
 * access injected properties in @BeforeEach methods. KoinTest alone doesn't
 * start Koin until test methods run, so accessing `by inject()` properties
 * in @BeforeEach would fail without this base class.
 *
 * @since 2026-01-12 (Koin migration)
 * @since 2026-01-16 (Performance optimization - lightweight module by default)
 */
abstract class KoinTestBase : KoinTest {
	/**
	 * Override this method to use a different test module.
	 * Default: testModuleLightweight (no GUI, faster)
	 * For GUI tests: return testModuleFull
	 * For integration tests: return integrationTestModule
	 */
	protected open fun getTestModule(): Module = testModuleLightweight

	@BeforeEach
	fun setUpKoin() {
		startKoin {
			modules(getTestModule())
		}
	}

	@AfterEach
	fun tearDownKoin() {
		stopKoin()
	}
}
