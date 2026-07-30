/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.observation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DispatcherObservation], [DispatcherObservation.EMPTY], [DispatcherObservation.digest],
 * and [DispatcherObservationSource] (SP2c.1, #824).
 *
 * Behavior that needs a live simulation environment (sorting under a real
 * [DispatcherObservationProjector], the switch grid walk, path reservations) is covered by
 * [DispatcherObservationProjectorTest] instead — this file only exercises the plain data-class
 * contract.
 */
@DisplayName("DispatcherObservation — EMPTY default, digest(), DispatcherObservationSource (#824)")
class DispatcherObservationTest {
	@Nested
	@DisplayName("EMPTY")
	inner class EmptyTests {
		@Test
		@DisplayName("is a safe default: every list empty, zero counters, tick zero")
		fun emptyIsSafeDefault() {
			val empty = DispatcherObservation.EMPTY

			assertThat(empty.tick).isZero()
			assertThat(empty.simTime).isZero()
			assertThat(empty.trains).isEmpty()
			assertThat(empty.blocks).isEmpty()
			assertThat(empty.switches).isEmpty()
			assertThat(empty.signals).isEmpty()
			assertThat(empty.reservations).isEmpty()
			assertThat(empty.queued).isEmpty()
			assertThat(empty.activeCount).isZero()
			assertThat(empty.appliedOutcomes).isEmpty()
		}

		@Test
		@DisplayName("capacity defaults to RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS")
		fun emptyCapacityMatchesStationCapacityConstant() {
			assertThat(DispatcherObservation.EMPTY.capacity).isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
		}
	}

	@Nested
	@DisplayName("digest()")
	inner class DigestTests {
		@Test
		@DisplayName("two structurally equal observations produce equal digests")
		fun equalObservationsProduceEqualDigests() {
			val a = sampleObservation(tick = 7L)
			val b = sampleObservation(tick = 7L)

			assertThat(a).isEqualTo(b)
			assertThat(a.digest()).isEqualTo(b.digest())
		}

		@Test
		@DisplayName("calling digest() twice on the same value is stable")
		fun digestIsStableAcrossRepeatedCalls() {
			val observation = sampleObservation(tick = 3L)

			assertThat(observation.digest()).isEqualTo(observation.digest())
		}

		@Test
		@DisplayName("a different tick produces a different digest")
		fun differentTickProducesDifferentDigest() {
			val a = sampleObservation(tick = 1L)
			val b = sampleObservation(tick = 2L)

			assertThat(a).isNotEqualTo(b)
			assertThat(a.digest()).isNotEqualTo(b.digest())
		}

		@Test
		@DisplayName("EMPTY has a well-formed, non-blank digest")
		fun emptyHasWellFormedDigest() {
			val digest = DispatcherObservation.EMPTY.digest()

			assertThat(digest).isEqualTo(digest) // stable
			assert(digest.isNotBlank()) { "digest() must not be blank" }
			assert(digest.length == 64) { "SHA-256 hex digest must be 64 characters, was ${digest.length}" }
		}
	}

	@Nested
	@DisplayName("DispatcherObservationSource")
	inner class SourceTests {
		@Test
		@DisplayName("is a SAM interface: a plain lambda satisfies it")
		fun isSamInterface() {
			val source = DispatcherObservationSource { DispatcherObservation.EMPTY }

			assertThat(source.latest()).isEqualTo(DispatcherObservation.EMPTY)
		}
	}

	private fun sampleObservation(tick: Long): DispatcherObservation =
		DispatcherObservation(
			tick = tick,
			simTime = 12.5,
			trains = emptyList(),
			blocks = emptyList(),
			switches = emptyList(),
			signals = emptyList(),
			reservations = emptyList(),
			queued = emptyList(),
			activeCount = 0,
			capacity = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
			appliedOutcomes = emptyList()
		)
}
