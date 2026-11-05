/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LatestObservationReader.fromPorts] (Issue #1343).
 *
 * Both ports are strict mocks answering only their `snapshot()`: a sim-thread capture request
 * ([NetworkPerceptionPort.captureSnapshot]) or a per-field accessor read would throw, and
 * [confirmVerified] proves none was made.
 */
@DisplayName("LatestObservationReader.fromPorts reads the published state without a capture (#1343)")
class LatestObservationReaderTest {
	private val snapshot = SimulationSnapshot.EMPTY.copy(simTime = 42.0)

	private val input =
		BlockInputObservation(
			blockId = "kB",
			towardSemaphoreName = "zB",
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = "Train #4",
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false
		)

	private val tick =
		DispatchLoopSnapshot(
			queuedTrains = listOf(QueuedTrainObservation("Train #5", "A")),
			innerBlockInputs = listOf(input.copy(blockId = "k1", towardSemaphoreName = "doA1")),
			outerBlockInputs = listOf(input)
		)

	private val perceptionPort = mockk<NetworkPerceptionPort>()
	private val sensorPort = mockk<DispatchLoopSensorPort>()

	private fun publish(
		snapshot: SimulationSnapshot,
		tick: DispatchLoopSnapshot
	) {
		every { perceptionPort.snapshot() } returns snapshot
		every { sensorPort.snapshot() } returns tick
	}

	@Test
	@DisplayName("builds the whole observation from one tick read and one snapshot read")
	fun buildsTheObservationFromOneReadOfEach() {
		publish(snapshot, tick)

		val observation = LatestObservationReader.fromPorts(perceptionPort, sensorPort).read()

		assertThat(observation).isEqualTo(
			DispatchObservation(
				snapshot = snapshot,
				unapprovedTrains = tick.queuedTrains,
				innerBlockInputs = tick.innerBlockInputs,
				outerBlockInputs = tick.outerBlockInputs
			)
		)
		assertThat(observation?.snapshot).isSameInstanceAs(snapshot)
	}

	@Test
	@DisplayName("reads the tick before the snapshot, so the snapshot is never older than the tick")
	fun readsTheTickBeforeTheSnapshot() {
		publish(snapshot, tick)

		LatestObservationReader.fromPorts(perceptionPort, sensorPort).read()

		verifyOrder {
			sensorPort.snapshot()
			perceptionPort.snapshot()
		}
	}

	@Test
	@DisplayName("requests no sim-thread capture and no per-field accessor (C3)")
	fun requestsNoCapture() {
		publish(snapshot, tick)

		LatestObservationReader.fromPorts(perceptionPort, sensorPort).read()

		verify(exactly = 1) { perceptionPort.snapshot() }
		verify(exactly = 1) { sensorPort.snapshot() }
		confirmVerified(perceptionPort, sensorPort)
	}

	@Test
	@DisplayName("returns null while the perception snapshot is still the pre-first-capture sentinel")
	fun nullOnEmptySnapshot() {
		publish(SimulationSnapshot.EMPTY, tick)

		assertThat(LatestObservationReader.fromPorts(perceptionPort, sensorPort).read()).isNull()
	}

	@Test
	@DisplayName("returns null while the dispatch-loop port has nothing published")
	fun nullOnEmptyTick() {
		publish(snapshot, DispatchLoopSnapshot.EMPTY)

		assertThat(LatestObservationReader.fromPorts(perceptionPort, sensorPort).read()).isNull()
	}
}
