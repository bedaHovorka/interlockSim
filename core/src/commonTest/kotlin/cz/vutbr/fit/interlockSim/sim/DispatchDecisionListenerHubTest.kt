/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [DispatchDecisionListenerHub] (SP2b.6 — Issue #561).
 *
 * Verifies:
 * 1. With no sink attached, [DispatchDecisionListenerHub.onDecisionApplied] is a no-op.
 * 2. After [DispatchDecisionListenerHub.setSink], decisions are forwarded to the sink.
 * 3. Replacing the sink swaps the forwarder.
 * 4. `setSink(null)` detaches (subsequent calls are no-ops).
 * 5. Concurrent setSink + onDecisionApplied do not corrupt state (last-writer-wins sink).
 *
 * @since Issue #561 (SP2b.6 — Goal 10)
 */
class DispatchDecisionListenerHubTest {
	@Test
	fun noSink_onDecisionApplied_isNoOp() {
		val hub = DispatchDecisionListenerHub()
		val decision = DispatchDecision.ApproveTrain("T1")

		// Must not throw.
		hub.onDecisionApplied(decision)
	}

	@Test
	fun setSink_forwardsDecisions() {
		val hub = DispatchDecisionListenerHub()
		val received = mutableListOf<DispatchDecision>()
		hub.setSink { received.add(it) }

		hub.onDecisionApplied(DispatchDecision.ApproveTrain("T1"))
		hub.onDecisionApplied(DispatchDecision.HoldTrain("T2", 5.0))

		assertThat(received.map { it::class.simpleName }).containsExactly("ApproveTrain", "HoldTrain")
	}

	@Test
	fun setSink_replacesPreviousSink() {
		val hub = DispatchDecisionListenerHub()
		val first = mutableListOf<DispatchDecision>()
		val second = mutableListOf<DispatchDecision>()
		hub.setSink { first.add(it) }
		hub.setSink { second.add(it) }

		hub.onDecisionApplied(DispatchDecision.ApproveTrain("T1"))

		assertThat(first).isEmpty()
		assertThat(second.map { it::class.simpleName }).containsExactly("ApproveTrain")
	}

	@Test
	fun setSinkNull_detachesForwarder() {
		val hub = DispatchDecisionListenerHub()
		val received = mutableListOf<DispatchDecision>()
		hub.setSink { received.add(it) }
		hub.setSink(null)

		hub.onDecisionApplied(DispatchDecision.ApproveTrain("T1"))

		assertThat(received).isEmpty()
	}

	@Test
	fun concurrentSetSinkAndOnDecisionApplied_doesNotCorrupt() {
		runBlocking {
			val hub = DispatchDecisionListenerHub()
			// Synchronized list (KMP-clean; java.util.concurrent is unavailable on native).
			val lock = SynchronizedObject()
			val received = mutableListOf<DispatchDecision>()

			withTimeout(5.seconds) {
				val jobs =
					(1..50).map { i ->
						launch(Dispatchers.Default) {
							if (i % 2 == 0) {
								hub.setSink { synchronized(lock) { received.add(it) } }
							} else {
								hub.onDecisionApplied(DispatchDecision.ApproveTrain("T$i"))
							}
						}
					}
				jobs.joinAll()
			}

			// No exception thrown and the hub is still usable afterward.
			hub.setSink { synchronized(lock) { received.add(it) } }
			hub.onDecisionApplied(DispatchDecision.ApproveTrain("final"))
			assertThat(received.any { it is DispatchDecision.ApproveTrain }).isTrue()
			assertThat(received.isEmpty()).isFalse()
		}
	}
}
