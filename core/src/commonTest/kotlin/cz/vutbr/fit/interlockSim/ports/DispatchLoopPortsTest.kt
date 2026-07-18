/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for DispatchLoopSensorPort / DispatchLoopActuatorPort interfaces (SP4.1, Issue #563).
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for the SP4.1 dispatch-loop port interfaces and their default implementations.
 *
 * Tests:
 * - [DefaultDispatchLoopSensorPort] correctly delegates to the observation provider.
 * - [DispatchLoopSensorPort] returns empty lists when the observation is empty.
 * - Interface contract: [DispatchLoopActuatorPort.approveTrain] rejects blank ids.
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class DispatchLoopPortsTest {
	// ── DefaultDispatchLoopSensorPort ─────────────────────────────────────────

	@Test
	fun `getQueuedTrains returns list from the observation provider`() {
		val expected = listOf(QueuedTrainObservation("Train #1", "inout_A"))
		val port = DefaultDispatchLoopSensorPort { makeObservation(queuedTrains = expected) }

		assertThat(port.getQueuedTrains()).isSameAs(expected)
	}

	@Test
	fun `getInnerBlockInputs returns list from the observation provider`() {
		val expected = listOf(makeBlockInput("k1", "zA"))
		val port = DefaultDispatchLoopSensorPort { makeObservation(innerBlockInputs = expected) }

		assertThat(port.getInnerBlockInputs()).isSameAs(expected)
	}

	@Test
	fun `getOuterBlockInputs returns list from the observation provider`() {
		val expected = listOf(makeBlockInput("kA", "doA1"))
		val port = DefaultDispatchLoopSensorPort { makeObservation(outerBlockInputs = expected) }

		assertThat(port.getOuterBlockInputs()).isSameAs(expected)
	}

	@Test
	fun `sensor port returns empty lists for fresh empty observation`() {
		val emptyObs = ShuntingLoop.TickObservation(emptyList(), emptyList(), emptyList())
		val port = DefaultDispatchLoopSensorPort { emptyObs }

		assertThat(port.getQueuedTrains()).isEmpty()
		assertThat(port.getInnerBlockInputs()).isEmpty()
		assertThat(port.getOuterBlockInputs()).isEmpty()
	}

	@Test
	fun `per-field accessor reads the latest observation on each call`() {
		var callCount = 0
		val obs1 = makeObservation(queuedTrains = listOf(QueuedTrainObservation("T1", "A")))
		val obs2 = makeObservation(queuedTrains = listOf(QueuedTrainObservation("T2", "B")))
		val port =
			DefaultDispatchLoopSensorPort {
				callCount++
				if (callCount == 1) obs1 else obs2
			}

		assertThat(port.getQueuedTrains()).isEqualTo(listOf(QueuedTrainObservation("T1", "A")))
		assertThat(port.getQueuedTrains()).isEqualTo(listOf(QueuedTrainObservation("T2", "B")))
	}

	// ── snapshot() atomicity contract (Critical #1 / #3) ──────────────────────

	@Test
	fun `snapshot returns all three fields from a single provider invocation`() {
		var calls = 0
		val queued = listOf(QueuedTrainObservation("T1", "A"))
		val inner = listOf(makeBlockInput("k1", "zA"))
		val outer = listOf(makeBlockInput("kA", "doA1"))
		val port =
			DefaultDispatchLoopSensorPort {
				calls++
				makeObservation(queuedTrains = queued, innerBlockInputs = inner, outerBlockInputs = outer)
			}

		val s = port.snapshot()

		assertThat(s.queuedTrains).isEqualTo(queued)
		assertThat(s.innerBlockInputs).isEqualTo(inner)
		assertThat(s.outerBlockInputs).isEqualTo(outer)
		// All three fields came from ONE provider call — the snapshot is atomic.
		assertThat(calls).isEqualTo(1)
	}

	@Test
	fun `per-field accessors can observe different ticks when provider swaps between calls`() {
		// Documents the tearing limitation that motivates snapshot(): two separate per-field
		// accessor calls see fields from different ticks when the sim thread republishes between
		// them. This is intentional per-field-only atomicity; callers needing cross-field
		// consistency must use snapshot().
		var tick = 0
		val tick0 = makeObservation(queuedTrains = listOf(QueuedTrainObservation("T0", "A")))
		val tick1 = makeObservation(innerBlockInputs = listOf(makeBlockInput("k1", "zA")))
		val port =
			DefaultDispatchLoopSensorPort {
				val o = if (tick == 0) tick0 else tick1
				tick++
				o
			}

		// First accessor sees tick 0's queue; second sees tick 1's inner inputs — different ticks.
		assertThat(port.getQueuedTrains()).isEqualTo(listOf(QueuedTrainObservation("T0", "A")))
		assertThat(port.getInnerBlockInputs()).isEqualTo(listOf(makeBlockInput("k1", "zA")))
	}

	@Test
	fun `snapshot stays internally consistent when provider swaps between calls`() {
		// Same swapping provider as above, but a single snapshot() call reads one tick atomically:
		// the returned bundle's fields all belong to the SAME tick (here tick 0), so the
		// innerBlockInputs that were absent in tick0 read back empty alongside tick0's queue.
		var tick = 0
		val tick0 = makeObservation(queuedTrains = listOf(QueuedTrainObservation("T0", "A")))
		val tick1 = makeObservation(innerBlockInputs = listOf(makeBlockInput("k1", "zA")))
		val port =
			DefaultDispatchLoopSensorPort {
				val o = if (tick == 0) tick0 else tick1
				tick++
				o
			}

		val s = port.snapshot()

		assertThat(s.queuedTrains).isEqualTo(listOf(QueuedTrainObservation("T0", "A")))
		// tick0 had no innerBlockInputs — atomicity means we see tick0's emptiness, not tick1's data.
		assertThat(s.innerBlockInputs).isEmpty()
	}

	@Test
	fun `DispatchLoopSnapshot EMPTY has all empty lists`() {
		val empty = DispatchLoopSnapshot.EMPTY

		assertThat(empty.queuedTrains).isEmpty()
		assertThat(empty.innerBlockInputs).isEmpty()
		assertThat(empty.outerBlockInputs).isEmpty()
	}

	// ── DispatchLoopActuatorPort contract ─────────────────────────────────────

	@Test
	fun `approveTrain contract rejects blank trainId`() {
		// Test the interface contract using a minimal stub implementation.
		val stub =
			object : DispatchLoopActuatorPort {
				override fun approveTrain(trainId: String): Boolean {
					require(trainId.isNotBlank()) { "trainId must not be blank" }
					return true
				}
			}

		assertFailsWith<IllegalArgumentException> { stub.approveTrain("") }
		assertFailsWith<IllegalArgumentException> { stub.approveTrain("   ") }
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private fun makeObservation(
		queuedTrains: List<QueuedTrainObservation> = emptyList(),
		innerBlockInputs: List<BlockInputObservation> = emptyList(),
		outerBlockInputs: List<BlockInputObservation> = emptyList()
	): ShuntingLoop.TickObservation = ShuntingLoop.TickObservation(queuedTrains, innerBlockInputs, outerBlockInputs)

	private fun makeBlockInput(
		blockId: String,
		semaphoreName: String
	): BlockInputObservation =
		BlockInputObservation(
			blockId = blockId,
			towardSemaphoreName = semaphoreName,
			toSeparatorName = null,
			state = TrackFacility.State.FREE,
			ownerTrainId = null,
			isApproachingThisInput = false,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false
		)
}
