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
 * Register every Context created in @BeforeEach with [tracked] so it is automatically closed
 * in [tearDownKoin]. Multiple contexts per test are fully supported.
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
 * @since 2026-09-01 (Multi-context tracked() registration — Issue #1038)
 */
abstract class KoinTestBase : KoinTest {
	private val trackedContexts = mutableListOf<Context<*, *>>()

	/**
	 * Optional context tracking for automatic cleanup — **deprecated single-slot alias**.
	 *
	 * Assigning this field registers the context for automatic close in [tearDownKoin],
	 * identical to calling [tracked]. New code should call [tracked] directly; this field
	 * exists to avoid a big-bang migration of the existing call sites.
	 */
	@Deprecated(
		"Use tracked() instead. Assign the context via .tracked() at the creation site.",
		ReplaceWith("context.tracked()")
	)
	protected var testContext: Context<*, *>? = null
		set(value) {
			if (value != null) trackedContexts.add(value)
			field = value
		}

	/**
	 * Registers this context for automatic close in [tearDownKoin].
	 *
	 * Call once per context created in @BeforeEach (or in a helper).  The list is closed and
	 * cleared in [tearDownKoin], so each test starts with an empty registry.  Close is wrapped in
	 * [runCatching] so a context that was already closed by hand does not abort teardown.
	 *
	 * ```kotlin
	 * editingContext = factory.createEmptyContext().tracked()
	 * simulationContext = simFactory.createContext(editingContext).tracked()
	 * ```
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = also { trackedContexts.add(it) }

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
		trackedContexts.forEach { runCatching { it.close() } }
		trackedContexts.clear()
		@Suppress("DEPRECATION")
		testContext = null
		stopKoin()
	}
}
