/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Base class for tests using Koin dependency injection (commonTest / multiplatform version)
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.Context
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * Base class for commonTest tests that use Koin dependency injection.
 *
 * kotlin.test-native equivalent of the JVM-only [KoinTestBase] (which uses
 * JUnit5 @BeforeEach/@AfterEach). Subclasses can override [getTestModule] to
 * supply a different Koin module; the default is [commonCoreTestModule].
 *
 * Uses [KoinComponent] instead of koin-test's KoinTest so no additional
 * dependency beyond koin-core is required in commonMain.
 *
 * @since 2026 (commonTest migration — Task 3c)
 */
abstract class CommonKoinTestBase : KoinComponent {

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
	 * Call once per context created in @BeforeTest.  Close is wrapped in [runCatching] so a
	 * context closed by hand before teardown does not abort the remaining cleanup.
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = also { trackedContexts.add(it) }

	/**
	 * Override this method to use a different test module.
	 * Default: [commonCoreTestModule]
	 */
	protected open fun getTestModule(): Module = commonCoreTestModule

	@BeforeTest
	fun setUpKoin() {
		startKoin {
			modules(getTestModule())
		}
		afterKoinSetUp()
	}

	/**
	 * Hook called after Koin is started. Subclasses that need to initialize
	 * fields using injected dependencies should override this method instead
	 * of annotating their own setUp with [@BeforeTest], since on Kotlin/Native
	 * the ordering of [@BeforeTest] between superclass and subclass is undefined.
	 */
	protected open fun afterKoinSetUp() {}

	@AfterTest
	fun tearDownKoin() {
		trackedContexts.forEach { runCatching { it.close() } }
		trackedContexts.clear()
		@Suppress("DEPRECATION")
		testContext = null
		stopKoin()
	}
}
