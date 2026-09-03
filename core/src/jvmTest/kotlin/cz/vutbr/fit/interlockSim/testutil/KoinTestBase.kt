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
 * Register every Context created in @BeforeEach with [tracked]; [tearDownKoin] closes them all
 * through a shared [ContextTracker] (reverse order, failures reported after cleanup).
 *
 * @since 2026 (core module extraction)
 * @since 2026-09-03 (Multi-context tracked() registration via ContextTracker — Issue #1038)
 */
abstract class KoinTestBase : KoinTest {
	private val tracker = ContextTracker()

	/** Number of contexts registered with [tracked] and not yet closed by [tearDownKoin]. */
	protected val trackedContextCount: Int
		get() = tracker.size

	/**
	 * Registers this context for automatic close in [tearDownKoin] and returns it, so the call
	 * reads fluently at the creation site: `context = factory.createEmptyContext().tracked()`.
	 * Call once per context; a context closed by hand earlier is simply closed again.
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = tracker.track(this)

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

	/** Closes every tracked context, then stops Koin (always); the first close failure is rethrown afterwards. */
	@AfterEach
	fun tearDownKoin() {
		try {
			tracker.closeAll()
		} finally {
			stopKoin()
		}
	}
}
