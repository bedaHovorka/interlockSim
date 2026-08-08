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
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
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
 * Typed-rejection tests for the four actuator tools (Issue #847 round 4).
 *
 * ## Why a code and not just a message
 *
 * Rounds 2 and 3 headline "86 rejected endpoint calls", "90 rejected train-id calls", "0 rejected"
 * — every one of those numbers was produced by **grepping the log after the fact**. #847's sweep is
 * unattended and writes one JSON per run; it cannot grep, and `DispatcherRunSnapshot.rejectionsByCode`
 * — the field #846's aggregator renders its "Failure Modes" table from — was structurally guaranteed
 * to be empty, because the only `ActionOutcome` ever constructed in production passes
 * `rejection = null` and `ActionValidator` (the component that produces [RejectionCode]s) is only
 * ever invoked from the test-only `DispatchTickLoop`.
 *
 * The external review of this PR put the same point the other way round: prompt instructions the
 * model can ignore are worth less than a validation layer it cannot talk around — and a validation
 * layer whose effect is never measured is an assertion, not evidence.
 *
 * The tools already reject these calls and already explain why in prose. Attaching the existing
 * [RejectionCode] to the same rejection makes the rate countable without parsing English.
 *
 * ## `ENDPOINT_IS_BLOCK_ID` specifically
 *
 * This is the `kA` vs `A` confusion that motivated the whole anti-hallucination work: `vyhybna.xml`
 * names blocks `k1`/`kA`/`kB` and InOuts `A`/`B`, and round 2 measured 48 rejected calls naming
 * block `k1` in one run. Lumping it under `UNKNOWN_ENDPOINT` would hide the one failure mode the
 * prompt changes are aimed at, so it gets its own code — which is exactly what the existing enum
 * already provides.
 */
@DisplayName("Actuator tools attach a typed RejectionCode to every rejection")
class ToolRejectionCodeTest {
	private val endpoints = setOf("A", "B", "doA1", "doB1")

	/** The InOut subset of [endpoints] — `doA1`/`doB1` are signals, not entry/exit points. */
	private val inOuts = setOf("A", "B")

	private fun perceptionPort(
		activeTrains: List<String> = emptyList(),
		blockIds: List<String> = listOf("k1", "kA", "kB"),
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
						blockIds.map {
							BlockOccupancyReading(blockId = it, state = TrackFacility.State.FREE, trainId = null)
						}
				)
		}

	private fun sensorPort(
		queued: List<String>,
		destination: String = "B"
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

	@Test
	@DisplayName("a blank argument is BLANK_ARGUMENT")
	fun blankArgumentIsCoded() {
		val result =
			runBlocking {
				requestRouteTool().execute(mapOf("trainName" to "", "fromEndpointName" to "A", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.BLANK_ARGUMENT)
	}

	@Test
	@DisplayName("an invented endpoint name is UNKNOWN_ENDPOINT")
	fun unknownEndpointIsCoded() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "Z9", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_ENDPOINT)
	}

	/**
	 * The `kA` vs `A` trap, measured 48 times in one round-2 run. It must be countable separately
	 * from a wholly invented name, because the two say different things about the prompt.
	 */
	@Test
	@DisplayName("a Block ID passed as an endpoint is ENDPOINT_IS_BLOCK_ID, not UNKNOWN_ENDPOINT")
	fun blockIdAsEndpointIsCodedDistinctly() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "kA", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.ENDPOINT_IS_BLOCK_ID)
	}

	@Test
	@DisplayName("an unresolvable train name on request_route is UNKNOWN_TRAIN")
	fun unknownTrainOnRequestRouteIsCoded() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Ghost", "fromEndpointName" to "A", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_TRAIN)
	}

	/**
	 * The backwards-route defect, observed live on `exampleGui shuntingLoopAI 333`.
	 *
	 * Train #1's timetable is `B → A`; the model asked for `A → B`, and nothing on the live tool
	 * path checked it. `ActionValidator` has exactly this rule
	 * ([RejectionCode.TARGET_NOT_TRAIN_DESTINATION]) but is only ever reached from
	 * `DispatchTickLoop`, which is never constructed in production — so the request went straight
	 * to `reservePath`, which reserved all seven blocks from the wrong end. The train, standing at
	 * B, then could not find a free path because **it** owned every block, and only moved once the
	 * `OrphanReservationSweeper` cancelled its own reservation 60 simulated seconds later.
	 */
	@Test
	@DisplayName("a route to the wrong InOut is TARGET_NOT_TRAIN_DESTINATION")
	fun backwardsRouteIsRejected() {
		// Origin deliberately a Signal, not "A": the live call was both errors at once
		// (from=A, to=B for a train bound for A) and the origin rule fires first, so isolating
		// the destination rule needs a from-endpoint that is not the destination.
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doB1", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code")
			.isEqualTo(RejectionCode.TARGET_NOT_TRAIN_DESTINATION)
	}

	/**
	 * The bare ordinal is the form the model actually used for Train #1 in the live run
	 * (`trainName: "1"`), and [RequestRouteTool.resolveTrainId] accepts it. The destination check
	 * must run against the RESOLVED id, or the very call that exposed the defect still slips past.
	 */
	@Test
	@DisplayName("a backwards route named by bare ordinal is rejected too")
	fun backwardsRouteByBareOrdinalIsRejected() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "1", "fromEndpointName" to "doB1", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code")
			.isEqualTo(RejectionCode.TARGET_NOT_TRAIN_DESTINATION)
	}

	/**
	 * The second shape of the same mistake, and the one the model produced live once the
	 * end-to-end form was rejected: `from=A, to=doA1` for a train running `B → A`. The target is a
	 * Signal, so the destination check does not apply — but a train still cannot depart from the
	 * place it is trying to reach.
	 */
	@Test
	@DisplayName("departing FROM the train's destination is ORIGIN_NOT_AT_TRAIN_POSITION")
	fun departingFromDestinationIsRejected() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "A", "toEndpointName" to "doA1"))
			}

		assertThat(errorOf(result).rejection, "rejection code")
			.isEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
	}

	/**
	 * The case the queued-only first attempt missed. `approve_train` then `request_route` is the
	 * intended order, so by the time the route is requested the train has already left the queue —
	 * which is exactly what happened in the live run: `from=A, to=doA1` for a train bound for A was
	 * GRANTED because the destination lookup found nothing. The destination must therefore also be
	 * read from the (off-thread-safe) snapshot's active-train list.
	 */
	@Test
	@DisplayName("an already-approved train is checked too, via the snapshot")
	fun activeTrainDirectionIsChecked() {
		val result =
			runBlocking {
				requestRouteTool(active = listOf("Train #1"), activeDestination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "A", "toEndpointName" to "doA1"))
			}

		assertThat(errorOf(result).rejection, "rejection code")
			.isEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
	}

	/**
	 * The remaining half of the deadlock, seen once the direction was corrected: a correctly
	 * *directed* route can still be reserved in the wrong *place*. `doA1 → A` was granted for a
	 * train standing at kB; it holds blocks, releases nobody, and the orphan sweeper reclaims it
	 * 60 simulated seconds later.
	 */
	@Test
	@DisplayName("a route that does not start at the signal a stopped train waits at is rejected")
	fun originAwayFromStoppedTrainIsRejected() {
		val result =
			runBlocking {
				requestRouteTool(
					active = listOf("Train #1"),
					activeDestination = "A",
					activeSignalAhead = "doB1",
					activeVelocity = 0.0
				).execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doA1", "toEndpointName" to "A"))
			}

		val error = errorOf(result)
		assertThat(error.rejection, "rejection code").isEqualTo(RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION)
		// Issue #893 iteration 3: this train is active, so its NEXT SECTION line (rendered by
		// KoogDispatchAgentImpl.renderActiveTrainLine) names exactly one legal from/to pair --
		// pointing the model at it beats the bare "do not retry" directive from iteration 2.
		// queuedOriginError below stays on the older wording: a queued train never gets a NEXT
		// SECTION line at all (renderQueuedTrainLine is approve-only), so there is nothing to
		// point at yet.
		assertThat(error.message, "message points the model at this train's NEXT SECTION line")
			.contains(
				"Do not send another origin of your own. Use exactly the from/to pair printed on " +
					"this train's NEXT SECTION line, or make no route request for this train this tick."
			)
		assertThat(error.message, "legacy do-not-retry wording is gone")
			.doesNotContain("Do not retry with the same origin.")
	}

	@Test
	@DisplayName("a route starting at the signal the stopped train waits at is accepted")
	fun originAtStoppedTrainIsAccepted() {
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
	 * A moving train's signal ahead changes under the dispatcher's feet, so the origin rule must
	 * not apply to it — otherwise a legitimate look-ahead reservation is rejected for having been
	 * computed one tick ago. Mirrors `ActionValidator` gating its equivalent rule on `HELD`.
	 */
	@Test
	@DisplayName("a moving train's origin is not pinned to its signal ahead")
	fun movingTrainOriginIsNotChecked() {
		val result =
			runBlocking {
				requestRouteTool(
					active = listOf("Train #1"),
					activeDestination = "A",
					activeSignalAhead = "doB1",
					activeVelocity = 12.0
				).execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "doA1", "toEndpointName" to "A"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}

	/**
	 * A Signal target is a section hop, not a claim about where the train finishes — the tool's own
	 * description recommends it over an InOut-to-InOut route. Only InOut targets are checked, so
	 * this must still pass.
	 */
	@Test
	@DisplayName("a section route to a Signal is not checked against the destination")
	fun sectionRouteToSignalIsAllowed() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "B", "toEndpointName" to "doB1"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}

	@Test
	@DisplayName("a route to the train's declared destination is accepted")
	fun correctlyDirectedRouteIsAccepted() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"), destination = "A")
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "B", "toEndpointName" to "A"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
	}

	@Test
	@DisplayName("an unknown trainId on approve_train is UNKNOWN_TRAIN")
	fun unknownTrainOnApproveIsCoded() {
		val tool = ApproveTrainTool(SinkHolder(), sensorPort(listOf("Train #1")))

		val result = runBlocking { tool.execute(mapOf("trainId" to "Ghost")) }

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_TRAIN)
	}

	@Test
	@DisplayName("an unknown trainId on cancel_route is UNKNOWN_TRAIN")
	fun unknownTrainOnCancelIsCoded() {
		val tool = CancelRouteTool(SinkHolder(), perceptionPort(activeTrains = listOf("Train #1")))

		val result = runBlocking { tool.execute(mapOf("trainId" to "Ghost")) }

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_TRAIN)
	}

	@Test
	@DisplayName("a blank trainId on approve_train is BLANK_ARGUMENT")
	fun blankTrainIdOnApproveIsCoded() {
		val tool = ApproveTrainTool(SinkHolder(), sensorPort(listOf("Train #1")))

		val result = runBlocking { tool.execute(mapOf("trainId" to " ")) }

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.BLANK_ARGUMENT)
	}

	/**
	 * A successful call must carry no code — otherwise the rate this exists to measure would count
	 * every call rather than every rejection.
	 */
	@Test
	@DisplayName("a successful call carries no rejection code")
	fun successCarriesNoCode() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "A", "toEndpointName" to "B"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
		assertThat((result as? ToolResult.Error)?.rejection, "rejection code on success").isNull()
	}

	/**
	 * Issue #893 iteration 2: the per-cycle action cap rejection must tell the model to stop calling
	 * actuator tools this tick rather than retrying — every retry after the cap is refused is a
	 * wasted call in an already-exhausted 20-iteration budget.
	 */
	@Test
	@DisplayName("the action-limit rejection tells the model to stop calling tools and end its turn")
	fun actionLimitRejectionTellsModelToStop() {
		val sinkHolder = SinkHolder(maxActionsPerTick = 1)
		val tool = ApproveTrainTool(sinkHolder, sensorPort(listOf("Train #1", "Train #2")))
		runBlocking { tool.execute(mapOf("trainId" to "Train #1")) }

		val result = runBlocking { tool.execute(mapOf("trainId" to "Train #2")) }

		val error = errorOf(result)
		assertThat(error.rejection, "rejection code").isEqualTo(RejectionCode.ACTION_LIMIT_EXCEEDED)
		assertThat(error.message, "message").contains(
			"Action budget for this cycle is exhausted. Do not call any more actuator tools. End your turn now."
		)
	}
}
