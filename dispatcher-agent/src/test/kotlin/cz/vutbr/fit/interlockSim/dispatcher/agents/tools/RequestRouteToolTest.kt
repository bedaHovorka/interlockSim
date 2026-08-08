/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Origin-guard tests for [RequestRouteTool] (Issue #893, task A-R1).
 *
 * ## What this covers that the existing guards do not
 *
 * The tool already rejects a route that departs from the train's own destination, and one that
 * does not start at the signal a *stopped, active* train waits at. Neither says anything about a
 * train that has not been admitted yet: a **queued** train is physically nowhere in the network,
 * so the only place its route can legitimately start is its entry InOut. Asking for a route from
 * a Signal in the middle of the station reserves track the train cannot reach, holds it against
 * every other train, and releases nobody until the orphan sweeper reclaims it.
 *
 * This is the synchronous half of the contiguity invariant. The authoritative half lives in
 * `DefaultPathReservationService.reservePath`, which rejects a start that is not contiguous with
 * the requesting train's actual footprint; the tool cannot compute footprints (it holds no
 * `:core` query surface and runs off the simulation thread), so it enforces only what a stale
 * snapshot can prove.
 */
@DisplayName("RequestRouteTool — route origin guards (Issue #893 A-R1)")
class RequestRouteToolTest {
	private val endpoints = setOf("A", "B", "doA1", "doB1")

	/** The InOut subset of [endpoints] — `doA1`/`doB1` are signals, not entry/exit points. */
	private val inOuts = setOf("A", "B")

	private fun perceptionPort(
		activeTrains: List<String> = emptyList(),
		activeDestination: String = "",
		activeSignalAhead: String? = null,
		activeVelocity: Double = 0.0
	): NetworkPerceptionPort =
		mockk<NetworkPerceptionPort>(relaxed = true).also { port ->
			every { port.snapshot() } returns
				SimulationSnapshot.EMPTY.copy(
					trainPerceptions =
						activeTrains.map {
							TrainPerceptionReading(
								trainId = it,
								signalAheadName = activeSignalAhead,
								signalAheadAspect = null,
								distanceToSignalAheadMetres = 0.0,
								currentSpeedLimitMps = 24.0,
								velocity = activeVelocity,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null,
								destinationInOutName = activeDestination,
								scheduledArrivalTime = 0.0,
								isDwelling = activeVelocity == 0.0
							)
						},
					trainPositions =
						activeTrains.map {
							TrainPositionReading(
								trainId = it,
								velocity = activeVelocity,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						},
					blocks =
						listOf("k1", "kA", "kB").map {
							BlockOccupancyReading(blockId = it, state = TrackFacility.State.FREE, trainId = null)
						}
				)
		}

	private fun sensorPort(
		queued: List<String>,
		destination: String
	): DispatchLoopSensorPort =
		mockk<DispatchLoopSensorPort>(relaxed = true).also { port ->
			every { port.getQueuedTrains() } returns
				queued.map { QueuedTrainObservation(trainId = it, destinationInOutName = destination) }
		}

	private fun requestRouteTool(
		active: List<String> = emptyList(),
		queued: List<String> = emptyList(),
		destination: String = "B",
		activeDestination: String = "",
		activeSignalAhead: String? = null,
		activeVelocity: Double = 0.0
	) = RequestRouteTool(
		SinkHolder(),
		endpoints,
		perceptionPort(
			active,
			activeDestination = activeDestination,
			activeSignalAhead = activeSignalAhead,
			activeVelocity = activeVelocity
		),
		sensorPort(queued, destination),
		inOutNames = inOuts
	)

	private fun errorOf(result: ToolResult): ToolResult.Error {
		assertThat(result).isInstanceOf(ToolResult.Error::class)
		return result as ToolResult.Error
	}

	/**
	 * The malformation this task exists to stop, in the shape the tool can see on its own: the
	 * model asked for `doB1 → A` for a train still queued at B. The direction is right, the place
	 * is wrong — a queued train stands at its entry point and nowhere else.
	 */
	@Test
	@DisplayName("a queued train's route must start at its entry InOut, not at a Signal")
	fun queuedTrainOriginMustBeAnInOut() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doB1", "toEndpointName" to "A"))
			}

		val error = errorOf(result)
		assertThat(error.rejection, "rejection code").isEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
		assertThat(error.message, "message").contains("must start at its entry point")
		// The remaining InOut is the only place a train bound for A can have been queued.
		assertThat(error.message, "message names the legal origin").contains("B")
		// Issue #893 iteration 2: the message already names the right origin, so retrying with the
		// same (wrong) one wastes a call in an already-scarce tool-calling budget.
		assertThat(error.message, "message tells the model not to retry with the same origin")
			.contains("Do not retry with the same origin.")
	}

	/** The same call with the entry InOut as origin is the legitimate one and must pass. */
	@Test
	@DisplayName("a queued train's route starting at its entry InOut is accepted")
	fun queuedTrainOriginAtEntryInOutIsAccepted() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "B", "toEndpointName" to "A"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}

	/**
	 * An admitted train is inside the network and legitimately starts a section route at a
	 * Signal. The queued rule must not bleed onto it — the snapshot's active list is what tells
	 * the two apart.
	 */
	@Test
	@DisplayName("an active train may still start a route at a Signal")
	fun activeTrainMayStartAtSignal() {
		val result =
			runBlocking {
				requestRouteTool(
					active = listOf("Train #1"),
					activeDestination = "A",
					activeSignalAhead = "doB1",
					activeVelocity = 0.0
				).execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doB1", "toEndpointName" to "A"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}
}
