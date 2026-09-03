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
 * Register every Context created in [afterKoinSetUp] with [tracked]; [tearDownKoin] closes them
 * all through a shared [ContextTracker] (reverse order, failures reported after cleanup).
 *
 * @since 2026 (commonTest migration — Task 3c)
 * @since 2026-09-03 (Multi-context tracked() registration via ContextTracker — Issue #1038)
 */
abstract class CommonKoinTestBase : KoinComponent {
	private val tracker = ContextTracker()

	/** Number of contexts registered with [tracked] and not yet closed by [tearDownKoin]. */
	protected val trackedContextCount: Int
		get() = tracker.size

	/**
	 * Registers this context for automatic close in [tearDownKoin] and returns it, so the call
	 * reads fluently at the creation site: `context = buildMinimalSimulation().tracked()`.
	 * Call once per context; a context closed by hand earlier is simply closed again.
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = tracker.track(this)

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

	/** Closes every tracked context, then stops Koin (always); the first close failure is rethrown afterwards. */
	@AfterTest
	fun tearDownKoin() {
		try {
			tracker.closeAll()
		} finally {
			stopKoin()
		}
	}
}
