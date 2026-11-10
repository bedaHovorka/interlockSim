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
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LatestObservationReader.fromPorts] (Issues #1343, #1354).
 *
 * The sensor port is a strict mock answering only its `snapshot()`: a per-field accessor read
 * would throw, and [confirmVerified] proves none was made. The reader takes no perception port,
 * so it cannot request a sim-thread capture (C3).
 */
@DisplayName("LatestObservationReader.fromPorts reads one published tick-stamped record (#1343, #1354)")
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

	private val record =
		DispatchLoopSnapshot(
			queuedTrains = listOf(QueuedTrainObservation("Train #5", "A")),
			innerBlockInputs = listOf(input.copy(blockId = "k1", towardSemaphoreName = "doA1")),
			outerBlockInputs = listOf(input),
			tick = 7L,
			snapshot = snapshot
		)

	private val sensorPort = mockk<DispatchLoopSensorPort>()

	private fun publish(record: DispatchLoopSnapshot) {
		every { sensorPort.snapshot() } returns record
	}

	@Test
	@DisplayName("builds the whole observation from one record read")
	fun buildsTheObservationFromOneRecordRead() {
		publish(record)

		val observation = LatestObservationReader.fromPorts(sensorPort).read()

		assertThat(observation).isEqualTo(
			DispatchObservation(
				snapshot = snapshot,
				unapprovedTrains = record.queuedTrains,
				innerBlockInputs = record.innerBlockInputs,
				outerBlockInputs = record.outerBlockInputs
			)
		)
		assertThat(observation?.snapshot).isSameInstanceAs(snapshot)
	}

	@Test
	@DisplayName("reads the sensor port exactly once and no per-field accessor")
	fun readsTheSensorPortOnce() {
		publish(record)

		LatestObservationReader.fromPorts(sensorPort).read()

		verify(exactly = 1) { sensorPort.snapshot() }
		confirmVerified(sensorPort)
	}

	@Test
	@DisplayName("returns the snapshot and the queue of one tick-stamped record (#1354)")
	fun readsOneTickStampedRecord() {
		val snapshot5 = SimulationSnapshot.EMPTY.copy(simTime = 10.0)
		val record5 = record.copy(tick = 5L, snapshot = snapshot5)
		publish(record5)

		val observation = LatestObservationReader.fromPorts(sensorPort).read()

		assertThat(observation?.snapshot).isSameInstanceAs(snapshot5)
		assertThat(observation?.unapprovedTrains).isSameInstanceAs(record5.queuedTrains)
	}

	@Test
	@DisplayName("returns null while the record still carries the pre-first-capture sentinel")
	fun nullOnEmptySnapshot() {
		publish(record.copy(snapshot = SimulationSnapshot.EMPTY))

		assertThat(LatestObservationReader.fromPorts(sensorPort).read()).isNull()
	}

	@Test
	@DisplayName("returns null while the dispatch-loop port has nothing published")
	fun nullOnEmptyRecord() {
		publish(DispatchLoopSnapshot.EMPTY)

		assertThat(LatestObservationReader.fromPorts(sensorPort).read()).isNull()
	}
}
