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

import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.testutil.ContextTracker
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
 * Register every context created in a test with [tracked]; [tearDownKoin] closes them all through
 * a shared [ContextTracker] (reverse order, failures reported after cleanup) — the same idiom as
 * `:core` and `:desktop-ui`'s `KoinTestBase` and `CommonKoinTestBase` (Issue #1042).
 *
 * A subclass may keep its own `@AfterEach` for extra teardown that is not a context close — for
 * example, detaching a log appender. JUnit 5 runs a subclass `@AfterEach` **before** [tearDownKoin],
 * so such teardown still sees a live Koin container and open contexts. Prefer [tracked] over a
 * manual `context.close()` in a subclass `@AfterEach`: it survives an early test failure and is
 * closed in a deterministic order alongside every other tracked context.
 *
 * @since 2026-09-06 (Multi-context tracked() registration via ContextTracker — Issue #1042)
 */
abstract class DispatcherKoinTestBase {
	private val tracker = ContextTracker()

	/** Number of contexts registered with [tracked] and not yet closed by [tearDownKoin]. */
	protected val trackedContextCount: Int
		get() = tracker.size

	/**
	 * Registers this context for automatic close in [tearDownKoin] and returns it, so the call
	 * reads fluently at the creation site: `context = TestFixtures.newShuntingSimulationContext().tracked()`.
	 * Call once per context; a context closed by hand earlier is simply closed again —
	 * `Context.close()` is idempotent.
	 */
	protected fun <T : Context<*, *>> T.tracked(): T = tracker.track(this)

	/** Override to start Koin with different modules. Default: `dispatcherAgentTestModule`. */
	protected open fun getTestModules(): List<Module> = listOf(dispatcherAgentTestModule)

	@BeforeEach
	fun setUpKoin() {
		startKoin {
			modules(getTestModules())
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
