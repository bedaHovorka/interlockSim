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

import cz.vutbr.fit.interlockSim.context.Context
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
 * Context Resource Management:
 * Register every Context created in @BeforeEach (or in a helper) with [tracked] so it is
 * automatically closed in [tearDownKoin]. Any number of contexts per test is supported; they are
 * closed in reverse registration order by a shared [ContextTracker], and a failing close is
 * reported after the remaining cleanup ran (see its KDoc for the full contract).
 *
 * Pattern A - One or more contexts registered at creation:
 * ```kotlin
 * class MyTest : KoinTestBase() {
 *     private lateinit var editing: EditingContext
 *     private lateinit var sim: SimulationContext
 *
 *     @BeforeEach
 *     fun setUp() {
 *         editing = factory.createEmptyContext().tracked()
 *         sim = simFactory.createContext(editing).tracked()
 *     }
 *     // Both are closed automatically in tearDownKoin()
 * }
 * ```
 *
 * Pattern B - Context per test method:
 * ```kotlin
 * @Test
 * fun myTest() {
 *     factory.createContext().use { context ->
 *         // ... test code ...
 *     } // Automatic close()
 * }
 * ```
 *
 * @since 2026-01-12 (Koin migration)
 * @since 2026-01-16 (Performance optimization - lightweight module by default)
 * @since 2026-01-27 (Context cleanup pattern)
 * @since 2026-09-03 (Multi-context tracked() registration via ContextTracker — Issue #1038)
 */
abstract class KoinTestBase : KoinTest {
	private val tracker = ContextTracker()

	/** Number of contexts registered with [tracked] and not yet closed by [tearDownKoin]. */
	protected val trackedContextCount: Int
		get() = tracker.size

	/**
	 * Registers this context for automatic close in [tearDownKoin] and returns it, so the call
	 * reads fluently at the creation site:
	 *
	 * ```kotlin
	 * editingContext = factory.createEmptyContext().tracked()
	 * simulationContext = simFactory.createContext(editingContext).tracked()
	 * ```
	 *
	 * Call once per context. A context closed by hand before teardown is simply closed again —
	 * `Context.close()` is idempotent.
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = tracker.track(this)

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

	/**
	 * Closes every tracked context, then stops Koin. Koin is stopped even when a close fails; the
	 * first failure is rethrown afterwards so a broken teardown is reported, not swallowed.
	 */
	@AfterEach
	fun tearDownKoin() {
		try {
			tracker.closeAll()
		} finally {
			stopKoin()
		}
	}
}
