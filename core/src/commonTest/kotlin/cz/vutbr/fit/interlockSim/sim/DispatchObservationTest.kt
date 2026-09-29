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
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import kotlin.test.Test

/**
 * Unit tests for [DispatchObservation.from].
 *
 * The factory builds the stub [SimulationSnapshot] that a caller converting from a push-based
 * observation source (for example `:dispatcher-agent`'s `DispatcherObservation`, a type `:core`
 * cannot see) hands to [Dispatcher.decide]: only [SimulationSnapshot.trainPositions] is
 * populated; every other snapshot field is empty.
 *
 * @since Issue #971
 */
class DispatchObservationTest {
	private val position1 = TrainPositionReading("Train #1", 10.0, 2.0, 300.0, "k1")
	private val position2 = TrainPositionReading("Train #2", 0.0, 0.0, 0.0, null)

	@Test
	fun `from builds a stub snapshot with only trainPositions and carries the rest through`() {
		val queued = QueuedTrainObservation("Train #3", "B")
		val innerInput =
			BlockInputObservation(
				blockId = "kA",
				towardSemaphoreName = "doA1",
				state = TrackFacility.State.FREE,
				ownerTrainId = null,
				isApproachingThisInput = false,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false
			)
		val outerInput = innerInput.copy(blockId = "kZ", towardSemaphoreName = "zA")

		val observation =
			DispatchObservation.from(
				simTime = 42.0,
				trainPositions = listOf(position1, position2),
				unapprovedTrains = listOf(queued),
				innerBlockInputs = listOf(innerInput),
				outerBlockInputs = listOf(outerInput)
			)

		// Data-class equality pins every field at once, including the empty stub facets.
		assertThat(observation).isEqualTo(
			DispatchObservation(
				snapshot =
					SimulationSnapshot(
						simTime = 42.0,
						semaphores = emptyList(),
						blocks = emptyList(),
						trainPositions = listOf(position1, position2),
						timetables = emptyList(),
						trainPerceptions = emptyList()
					),
				unapprovedTrains = listOf(queued),
				innerBlockInputs = listOf(innerInput),
				outerBlockInputs = listOf(outerInput)
			)
		)
		assertThat(observation.approvedTrainCount).isEqualTo(2)
	}
}
