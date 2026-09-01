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
	private val trackedContexts = mutableListOf<Context<*, *>>()

	/**
	 * Optional context tracking for automatic cleanup — **deprecated single-slot alias**.
	 *
	 * Assigning this field registers the context for automatic close in [tearDownKoin],
	 * identical to calling [tracked]. New code should call [tracked] directly.
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
	 * Call once per context created in @BeforeEach.  Close is wrapped in [runCatching] so a
	 * context closed by hand before teardown does not abort the remaining cleanup.
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = also { trackedContexts.add(it) }

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
		trackedContexts.forEach { runCatching { it.close() } }
		trackedContexts.clear()
		@Suppress("DEPRECATION")
		testContext = null
		stopKoin()
	}
}
