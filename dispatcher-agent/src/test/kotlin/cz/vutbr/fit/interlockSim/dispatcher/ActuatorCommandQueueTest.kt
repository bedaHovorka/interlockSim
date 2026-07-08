/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ActuatorCommandQueue].
 *
 * Covers basic offer/poll/drain semantics, capacity-based backpressure, and a
 * thread-safety stress test with concurrent producers and a single consumer.
 *
 * @since Issue #730 (SP0.8 — Goal 10)
 */
@DisplayName("ActuatorCommandQueue — thread-safe driver-to-sim command handoff")
class ActuatorCommandQueueTest {
	@Test
	@DisplayName("drain returns decisions in FIFO order")
	fun drainReturnsFifoOrder() {
		val queue = ActuatorCommandQueue()
		val decisions =
			listOf(
				DispatchDecision.ApproveTrain("T1"),
				DispatchDecision.ReservePath("T1", "zA"),
				DispatchDecision.NoAction
			)

		val posted = queue.postAll(decisions)

		assertThat(posted).isTrue()
		assertThat(queue.drain()).containsExactly(*decisions.toTypedArray())
		assertThat(queue.drain()).isEmpty()
	}

	@Test
	@DisplayName("postAll of empty list is a no-op")
	fun postAllEmptyIsNoOp() {
		val queue = ActuatorCommandQueue()

		val posted = queue.postAll(emptyList())

		assertThat(posted).isTrue()
		assertThat(queue.approximateSize()).isEqualTo(0)
		assertThat(queue.drain()).isEmpty()
	}

	@Test
	@DisplayName("drain is idempotent when queue is empty")
	fun drainEmptyQueueIsIdempotent() {
		val queue = ActuatorCommandQueue()

		assertThat(queue.drain()).isEmpty()
		assertThat(queue.drain()).isEmpty()
	}

	@Test
	@DisplayName("postAll accepts decisions up to capacity")
	fun postAllAcceptsUpToCapacity() {
		val queue = ActuatorCommandQueue(capacity = 2)

		val first =
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ApproveTrain("T2")
				)
			)

		assertThat(first).isTrue()
		assertThat(queue.approximateSize()).isEqualTo(2)
	}

	@Test
	@DisplayName("postAll rejects decisions that would exceed capacity")
	fun postAllRejectsWhenOverCapacity() {
		val queue = ActuatorCommandQueue(capacity = 2)

		val first =
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ApproveTrain("T2")
				)
			)
		val second =
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T3")
				)
			)

		assertThat(first).isTrue()
		assertThat(second).isFalse()
		assertThat(queue.approximateSize()).isEqualTo(2)
		assertThat(queue.drain()).hasSize(2)
	}

	@Test
	@DisplayName("postAll rejects atomically when batch exceeds capacity")
	fun postAllRejectsBatchAtomically() {
		val queue = ActuatorCommandQueue(capacity = 2)

		val posted =
			queue.postAll(
				listOf(
					DispatchDecision.ApproveTrain("T1"),
					DispatchDecision.ApproveTrain("T2"),
					DispatchDecision.ApproveTrain("T3")
				)
			)

		assertThat(posted).isFalse()
		assertThat(queue.approximateSize()).isEqualTo(0)
		assertThat(queue.drain()).isEmpty()
	}

	@Test
	@DisplayName("negative capacity disables backpressure")
	fun negativeCapacityIsUnlimited() {
		val queue = ActuatorCommandQueue(capacity = -1)

		val decisions = (1..10_000).map { DispatchDecision.ApproveTrain("T$it") }
		val posted = queue.postAll(decisions)

		assertThat(posted).isTrue()
		assertThat(queue.drain()).hasSize(10_000)
	}

	@Test
	@DisplayName("concurrent producers post all decisions without loss")
	fun concurrentProducersNoLoss() {
		val queue = ActuatorCommandQueue()
		val producerCount = 8
		val decisionsPerProducer = 1_000
		val executor = Executors.newFixedThreadPool(producerCount)
		val startLatch = CountDownLatch(1)
		val doneLatch = CountDownLatch(producerCount)

		repeat(producerCount) { producerIndex ->
			executor.submit {
				startLatch.await()
				repeat(decisionsPerProducer) { decisionIndex ->
					queue.postAll(
						listOf(
							DispatchDecision.ApproveTrain("P$producerIndex-D$decisionIndex")
						)
					)
				}
				doneLatch.countDown()
			}
		}

		startLatch.countDown()
		val finished = doneLatch.await(30, TimeUnit.SECONDS)

		assertThat(finished).isTrue()

		val drained = mutableListOf<DispatchDecision>()
		var batch = queue.drain()
		while (batch.isNotEmpty()) {
			drained.addAll(batch)
			batch = queue.drain()
		}

		assertThat(drained).hasSize(producerCount * decisionsPerProducer)
		assertThat(queue.approximateSize()).isEqualTo(0)

		executor.shutdown()
		executor.awaitTermination(5, TimeUnit.SECONDS)
	}

	@Test
	@DisplayName("concurrent producers and single consumer preserve total count")
	fun concurrentProducersAndConsumer() {
		val queue = ActuatorCommandQueue()
		val producerCount = 4
		val decisionsPerProducer = 500
		val executor = Executors.newFixedThreadPool(producerCount + 1)
		val startLatch = CountDownLatch(1)
		val producerDoneLatch = CountDownLatch(producerCount)
		val consumerDoneLatch = CountDownLatch(1)
		val consumedCount = AtomicInteger(0)

		repeat(producerCount) { producerIndex ->
			executor.submit {
				startLatch.await()
				repeat(decisionsPerProducer) { decisionIndex ->
					queue.postAll(
						listOf(
							DispatchDecision.ReservePath("P$producerIndex", "S$decisionIndex")
						)
					)
				}
				producerDoneLatch.countDown()
			}
		}

		executor.submit {
			startLatch.await()
			while (producerDoneLatch.count > 0 || queue.approximateSize() > 0) {
				consumedCount.addAndGet(queue.drain().size)
				Thread.yield()
			}
			consumerDoneLatch.countDown()
		}

		startLatch.countDown()
		val producersFinished = producerDoneLatch.await(30, TimeUnit.SECONDS)
		val consumerFinished = consumerDoneLatch.await(30, TimeUnit.SECONDS)

		assertThat(producersFinished).isTrue()
		assertThat(consumerFinished).isTrue()
		assertThat(consumedCount.get()).isEqualTo(producerCount * decisionsPerProducer)
		assertThat(queue.drain()).isEmpty()

		executor.shutdown()
		executor.awaitTermination(5, TimeUnit.SECONDS)
	}

	@Test
	@DisplayName("approximateSize tracks queue size")
	fun approximateSizeTracksQueue() {
		val queue = ActuatorCommandQueue()

		queue.postAll(listOf(DispatchDecision.NoAction, DispatchDecision.NoAction))
		assertThat(queue.approximateSize()).isEqualTo(2)

		queue.drain()
		assertThat(queue.approximateSize()).isEqualTo(0)
	}

	@Test
	@DisplayName("zero capacity is rejected")
	fun zeroCapacityRejected() {
		assertk
			.assertFailure { ActuatorCommandQueue(capacity = 0) }
			.isInstanceOf<IllegalArgumentException>()
	}
}
