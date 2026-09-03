/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Fixture Library
 *
 * Registry of contexts a test opened, closed together in teardown
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.Context

/**
 * Registry of the [Context]s one test opened, closed together by the Koin test bases in their
 * teardown (Issue #1038).
 *
 * Every base class (`KoinTestBase` in `:desktop-ui` and `:core` jvmTest, [CommonKoinTestBase] in
 * commonMain) holds one instance and exposes it through a `tracked()` extension; this class is the
 * single implementation behind all three, so the contract is tested once
 * (`ContextTrackerTest` in `:core` commonTest, JVM and linuxX64).
 *
 * Contract of [closeAll]:
 * - every registered context is closed, in reverse registration order (last opened, first closed —
 *   the same discipline nested `use { }` blocks give);
 * - a failing `close()` does not skip the remaining contexts;
 * - the registry is empty afterwards, so a repeated call is a no-op;
 * - the first failure is rethrown after all contexts were visited, so a broken teardown is
 *   reported instead of swallowed.
 *
 * Not thread-safe: register and close from the test thread, or from a thread the test joins
 * before teardown (`SwingUtilities.invokeAndWait` qualifies).
 *
 * @since 2026-09-03 (Issue #1038)
 */
class ContextTracker {
	private val contexts = mutableListOf<Context<*, *>>()

	/** Number of contexts registered and not yet closed by [closeAll]. */
	val size: Int
		get() = contexts.size

	/** Registers [context] for [closeAll] and returns it, so registration reads fluently at the creation site. */
	fun <T : Context<*, *>> track(context: T): T = context.also { contexts.add(it) }

	/**
	 * Closes every registered context in reverse registration order and empties the registry.
	 *
	 * @throws Throwable the first exception a `close()` raised, after every context was visited
	 */
	fun closeAll() {
		val firstFailure =
			contexts
				.asReversed()
				.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
				.firstOrNull()
		contexts.clear()
		firstFailure?.let { throw it }
	}
}
