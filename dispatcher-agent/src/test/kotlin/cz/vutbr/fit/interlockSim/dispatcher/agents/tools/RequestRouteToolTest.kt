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
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.RouteScope
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmittedActionSink
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.CandidateTarget
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.SeparatorKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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

	/**
	 * Issue #1155: on a station with more than two InOuts the queued train's entry point is not
	 * known to this tool (the queue observation carries only the destination), so "any InOut but the
	 * destination" no longer pins it. The #1276 Červený Újezd baseline stalled in all 6 of its early
	 * terminations this way: a queued train got a route from another InOut, and its own entry then
	 * failed on a non-contiguous path. Such a request is refused as premature instead.
	 */
	@Test
	@DisplayName("on a station with more than two InOuts a queued train gets no route, whatever the origin")
	fun queuedTrainGetsNoRouteWhenTheEntryIsAmbiguous() {
		val fourInOuts = setOf("east_1", "east_2", "west_1", "west_2")
		val tool =
			RequestRouteTool(
				SinkHolder(),
				fourInOuts + setOf("homeE1", "homeW1"),
				perceptionPort(emptyList()),
				sensorPort(listOf("Train #6"), destination = "west_1"),
				inOutNames = fourInOuts
			)

		for (origin in listOf("east_1", "homeE1")) {
			val error =
				errorOf(
					runBlocking {
						tool.execute(mapOf("trainName" to "Train #6", "fromEndpointName" to origin, "toEndpointName" to "homeW1"))
					}
				)
			assertThat(error.rejection, "rejection code for origin $origin").isEqualTo(RejectionCode.TRAIN_NOT_ADMITTED)
			assertThat(error.message, "message").contains("approve_train")
		}
	}

	/**
	 * The same call with the entry InOut as origin is the legitimate one and must pass.
	 *
	 * The target is a Signal rather than the far InOut because this test is about the *origin*
	 * rule: an InOut→InOut pair is refused outright as a full-span route (Issue #936), which would
	 * mask what is under test here.
	 */
	@Test
	@DisplayName("a queued train's route starting at its entry InOut is accepted")
	fun queuedTrainOriginAtEntryInOutIsAccepted() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "B", "toEndpointName" to "doB1"))
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

	/**
	 * The queued-origin guard disables itself without InOut names (D8): every origin would
	 * otherwise look like a Signal, so the same `doB1` origin that is refused above passes.
	 */
	@Test
	@DisplayName("the queued-origin guard is disabled when the tool has no InOut names")
	fun queuedOriginGuardDisabledWithoutInOutNames() {
		val tool =
			RequestRouteTool(
				SinkHolder(),
				endpoints,
				perceptionPort(),
				sensorPort(listOf("Train #1"), "A"),
				inOutNames = emptySet()
			)
		val result =
			runBlocking {
				tool.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doB1", "toEndpointName" to "A"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}

	/**
	 * The second self-disable case from the D8 table: without a `DispatchLoopSensorPort` the tool
	 * cannot tell who is queued, so the guard falls through and the `doB1` origin passes.
	 */
	@Test
	@DisplayName("the queued-origin guard is disabled when the tool has no sensor port")
	fun queuedOriginGuardDisabledWithoutSensorPort() {
		val tool =
			RequestRouteTool(
				SinkHolder(),
				endpoints,
				perceptionPort(),
				sensorPort = null,
				inOutNames = inOuts
			)
		val result =
			runBlocking {
				tool.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doB1", "toEndpointName" to "A"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}

	/**
	 * The next-section guard (Issue #1378): a Signal target must be one of the separators the
	 * interlocking lists one section ahead of `from` for this train — the list the NEXT SECTION line
	 * is rendered from. The Praha LLM arm asked for the signal at the far end of the train's own
	 * platform track (behind it) five times in a row; the engine granted a one-block "route" onto the
	 * block the train stood on, and the train never moved again.
	 *
	 * Train #1 here stands still at `doB1`, bound for `A`; its input facing `doB1` lists what lies one
	 * section ahead of it.
	 */
	@Nested
	@DisplayName("next-section guard for Signal targets (Issue #1378)")
	inner class NextSectionGuard {
		private val emitted = mutableListOf<DispatchAction>()

		private fun ownedInput(
			toward: String = "doB1",
			candidates: List<CandidateTarget>,
			extendedBeyond: Boolean = false
		) = BlockInputObservation(
			blockId = "k1",
			towardSemaphoreName = toward,
			state = TrackFacility.State.OCCUPIED,
			ownerTrainId = "Train #1",
			isApproachingThisInput = true,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = extendedBeyond,
			candidateTargets = candidates
		)

		private fun snapshotPort(inputs: List<BlockInputObservation>): DispatchLoopSensorPort =
			mockk<DispatchLoopSensorPort>(relaxed = true).also { port ->
				every { port.getQueuedTrains() } returns emptyList()
				every { port.snapshot() } returns
					DispatchLoopSnapshot(queuedTrains = emptyList(), innerBlockInputs = inputs, outerBlockInputs = emptyList())
			}

		private fun tool(
			inputs: List<BlockInputObservation>,
			velocity: Double = 0.0,
			withSensorPort: Boolean = true
		) = RequestRouteTool(
			SinkHolder(EmittedActionSink { emitted += it }),
			endpoints,
			perceptionPort(
				listOf("Train #1"),
				activeDestination = "A",
				activeSignalAhead = "doB1",
				activeVelocity = velocity
			),
			if (withSensorPort) snapshotPort(inputs) else null,
			inOutNames = inOuts
		)

		private fun request(
			tool: RequestRouteTool,
			from: String,
			to: String
		): ToolResult =
			runBlocking {
				tool.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to from, "toEndpointName" to to))
			}

		private fun inOut(
			name: String,
			available: Boolean = true
		) = CandidateTarget(name, SeparatorKind.IN_OUT, available)

		private fun signal(
			name: String,
			available: Boolean = true
		) = CandidateTarget(name, SeparatorKind.SEMAPHORE, available)

		@Test
		@DisplayName("a Signal target not listed one section ahead is refused, emits nothing, and names the pick")
		fun signalNotAheadIsRefused() {
			val error = errorOf(request(tool(listOf(ownedInput(candidates = listOf(inOut("A"))))), "doB1", "doA1"))

			assertThat(error.rejection, "rejection code").isEqualTo(RejectionCode.TARGET_NOT_NEXT_SECTION)
			assertThat(emitted, "emitted actions").isEmpty()
			assertThat(error.message, "message").contains("NEXT SECTION is from 'doB1' to 'A'")
			assertThat(error.message, "message").contains("toEndpointName='A'")
		}

		@Test
		@DisplayName("the guard reads the block inputs from exactly one atomic snapshot")
		fun readsOneSnapshot() {
			val port = snapshotPort(listOf(ownedInput(candidates = listOf(inOut("A")))))
			val tool =
				RequestRouteTool(
					SinkHolder(EmittedActionSink { emitted += it }),
					endpoints,
					perceptionPort(listOf("Train #1"), activeDestination = "A", activeSignalAhead = "doB1"),
					port,
					inOutNames = inOuts
				)

			errorOf(request(tool, "doB1", "doA1"))

			verify(exactly = 1) { port.snapshot() }
		}

		@Test
		@DisplayName("a listed but unavailable Signal target is emitted — the engine answers it")
		fun unavailableCandidateIsEmitted() {
			val inputs = listOf(ownedInput(candidates = listOf(inOut("A"), signal("doA1", false))))
			val result = request(tool(inputs), "doB1", "doA1")

			assertThat(result).isInstanceOf(ToolResult.Success::class)
			assertThat(emitted).containsExactly(DispatchAction.RequestRoute("Train #1", "doB1", "doA1", RouteScope.Section))
		}

		@Test
		@DisplayName("a look-ahead request from a signal the train owns no input toward is emitted")
		fun lookAheadWithoutOwnedInputIsEmitted() {
			// Moving, so the standing-at-signal origin rule does not apply to the doA1 origin.
			val inputs = listOf(ownedInput(candidates = listOf(inOut("A"))))
			val result = request(tool(inputs, velocity = 5.0), "doA1", "doB1")

			assertThat(result).isInstanceOf(ToolResult.Success::class)
			assertThat(emitted).containsExactly(DispatchAction.RequestRoute("Train #1", "doA1", "doB1", RouteScope.Section))
		}

		@Test
		@DisplayName("an input whose path is already extended beyond it (no candidates) leaves the guard inert")
		fun extendedBeyondIsEmitted() {
			val inputs = listOf(ownedInput(candidates = emptyList(), extendedBeyond = true))
			val result = request(tool(inputs), "doB1", "doA1")

			assertThat(result).isInstanceOf(ToolResult.Success::class)
			assertThat(emitted).containsExactly(DispatchAction.RequestRoute("Train #1", "doB1", "doA1", RouteScope.Section))
		}

		@Test
		@DisplayName("an InOut target (the destination) is not subject to the next-section guard")
		fun inOutDestinationIsUnaffected() {
			val result = request(tool(listOf(ownedInput(candidates = listOf(signal("doA1"))))), "doB1", "A")

			assertThat(result).isInstanceOf(ToolResult.Success::class)
			assertThat(emitted).containsExactly(DispatchAction.RequestRoute("Train #1", "doB1", "A", RouteScope.EndToEnd))
		}

		@Test
		@DisplayName("without a sensor port the guard is inert")
		fun noSensorPortIsInert() {
			val result = request(tool(emptyList(), withSensorPort = false), "doB1", "doA1")

			assertThat(result).isInstanceOf(ToolResult.Success::class)
			assertThat(emitted).containsExactly(DispatchAction.RequestRoute("Train #1", "doB1", "doA1", RouteScope.Section))
		}

		@Test
		@DisplayName("with nothing reservable ahead the refusal tells the model to make no route request")
		fun noPickMessage() {
			val error = errorOf(request(tool(listOf(ownedInput(candidates = listOf(inOut("A", false))))), "doB1", "doA1"))

			assertThat(error.rejection, "rejection code").isEqualTo(RejectionCode.TARGET_NOT_NEXT_SECTION)
			assertThat(emitted, "emitted actions").isEmpty()
			assertThat(error.message, "message").contains("No section ahead of 'doB1' can be reserved right now")
			assertThat(error.message, "message").contains("Make no route request for this train this tick.")
		}
	}
}
