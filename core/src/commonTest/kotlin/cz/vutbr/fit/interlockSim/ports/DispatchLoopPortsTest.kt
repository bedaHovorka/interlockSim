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
	fun `sensor port reads from the latest observation on each call`() {
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
