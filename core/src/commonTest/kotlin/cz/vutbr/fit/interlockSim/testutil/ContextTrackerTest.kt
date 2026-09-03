/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationContext
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Contract of [ContextTracker], the registry every Koin test base closes in its teardown
 * (Issue #1038): fluent registration, close-all in reverse order, the registry is empty
 * afterwards, and one failing `close()` neither skips the other contexts nor gets swallowed.
 *
 * Runs on both JVM and linuxX64. Uses the light [buildMinimalSimulation] fixture — the contract
 * is about closing Koin scopes, so no railway content is needed.
 */
class ContextTrackerTest : CommonKoinTestBase() {
	private val tracker = ContextTracker()

	@Test
	fun trackReturnsTheSameInstance() {
		val context = buildMinimalSimulation()

		val returned = tracker.track(context)

		assertThat(returned).isSameInstanceAs(context)
		tracker.closeAll()
	}

	@Test
	fun closeAllClosesEveryTrackedContext() {
		val a = tracker.track(buildMinimalSimulation())
		val b = tracker.track(buildMinimalSimulation())

		tracker.closeAll()

		assertThat(a.scope.closed, name = "first tracked context closed").isTrue()
		assertThat(b.scope.closed, name = "second tracked context closed").isTrue()
	}

	@Test
	fun closeAllClearsTheRegistry() {
		tracker.track(buildMinimalSimulation())
		tracker.track(buildMinimalSimulation())
		assertThat(tracker.size, name = "size before closeAll").isEqualTo(2)

		tracker.closeAll()

		assertThat(tracker.size, name = "size after closeAll").isEqualTo(0)
	}

	@Test
	fun closeAllClosesInReverseRegistrationOrder() {
		val log = mutableListOf<String>()
		tracker.track(RecordingCloseContext(buildMinimalSimulation(), "first", log))
		tracker.track(RecordingCloseContext(buildMinimalSimulation(), "second", log))

		tracker.closeAll()

		assertThat(log).containsExactly("second", "first")
	}

	@Test
	fun sameContextTrackedTwiceIsClosedWithoutError() {
		val context = buildMinimalSimulation()
		tracker.track(context)
		tracker.track(context)

		tracker.closeAll()

		assertThat(context.scope.closed).isTrue()
	}

	@Test
	fun closeAllContinuesPastAFailingCloseAndRethrowsIt() {
		val survivor = tracker.track(buildMinimalSimulation())
		// Tracked last, so it is closed first; the survivor must still be closed afterwards.
		tracker.track(FailingCloseContext(buildMinimalSimulation()))

		val failure = assertFailsWith<IllegalStateException> { tracker.closeAll() }

		assertThat(failure.message).isEqualTo(FailingCloseContext.MESSAGE)
		assertThat(survivor.scope.closed, name = "survivor closed").isTrue()
		assertThat(tracker.size, name = "registry cleared after failure").isEqualTo(0)
	}

	/** Records the order of `close()` calls, then closes the real context. */
	private class RecordingCloseContext(
		private val delegate: SimulationContext,
		private val name: String,
		private val log: MutableList<String>
	) : SimulationContext by delegate {
		override fun close() {
			log += name
			delegate.close()
		}
	}

	/** Closes the real context, then fails — so the test itself leaks nothing. */
	private class FailingCloseContext(
		private val delegate: SimulationContext
	) : SimulationContext by delegate {
		override fun close() {
			delegate.close()
			throw IllegalStateException(MESSAGE)
		}

		companion object {
			const val MESSAGE = "close failed on purpose"
		}
	}
}
