/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for DispatchObservation.from (#971).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import kotlin.test.Test

/**
 * Unit tests for [DispatchObservation.from].
 *
 * The factory builds the stub [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot] that a
 * push-based observation source (which cannot depend on `:core`'s callers such as
 * `:dispatcher-agent`'s `DispatcherObservation`) hands to [Dispatcher.decide]: only
 * [DispatchObservation.snapshot.trainPositions] is populated; every other snapshot field is
 * empty.
 *
 * @since Issue #971 (STEP 3 PR-5 — perf/snapshot-lookups)
 */
class DispatchObservationTest {
	private val position1 = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
	private val position2 = TrainPositionReading("Train #2", 0.0, 0.0, 0.0, null)

	@Test
	fun `from populates snapshot simTime and trainPositions`() {
		val observation =
			DispatchObservation.from(
				simTime = 42.0,
				trainPositions = listOf(position1, position2),
				unapprovedTrains = emptyList()
			)

		assertThat(observation.snapshot.simTime).isEqualTo(42.0)
		assertThat(observation.snapshot.trainPositions).containsExactly(position1, position2)
	}

	@Test
	fun `from leaves semaphores, blocks and timetables empty`() {
		val observation =
			DispatchObservation.from(
				simTime = 0.0,
				trainPositions = listOf(position1),
				unapprovedTrains = emptyList()
			)

		assertThat(observation.snapshot.semaphores).isEmpty()
		assertThat(observation.snapshot.blocks).isEmpty()
		assertThat(observation.snapshot.timetables).isEmpty()
	}

	@Test
	fun `from sets approvedTrainCount from trainPositions size`() {
		val observation =
			DispatchObservation.from(
				simTime = 0.0,
				trainPositions = listOf(position1, position2),
				unapprovedTrains = emptyList()
			)

		assertThat(observation.approvedTrainCount).isEqualTo(2)
	}

	@Test
	fun `from carries unapprovedTrains and block inputs through unchanged`() {
		val queued = QueuedTrainObservation("Train #3", "B")
		val innerInput =
			BlockInputObservation(
				blockId = "kA",
				towardSemaphoreName = "doA1",
				state = cz.vutbr.fit.interlockSim.objects.core.TrackFacility.State.FREE,
				ownerTrainId = null,
				isApproachingThisInput = false,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false
			)
		val outerInput = innerInput.copy(blockId = "kZ", towardSemaphoreName = "zA")

		val observation =
			DispatchObservation.from(
				simTime = 0.0,
				trainPositions = emptyList(),
				unapprovedTrains = listOf(queued),
				innerBlockInputs = listOf(innerInput),
				outerBlockInputs = listOf(outerInput)
			)

		assertThat(observation.unapprovedTrains).containsExactly(queued)
		assertThat(observation.innerBlockInputs).containsExactly(innerInput)
		assertThat(observation.outerBlockInputs).containsExactly(outerInput)
	}

	@Test
	fun `from defaults block inputs to empty lists`() {
		val observation =
			DispatchObservation.from(
				simTime = 0.0,
				trainPositions = emptyList(),
				unapprovedTrains = emptyList()
			)

		assertThat(observation.innerBlockInputs).isEmpty()
		assertThat(observation.outerBlockInputs).isEmpty()
	}
}
