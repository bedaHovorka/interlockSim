/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.RouteScope
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmittedActionSink
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.loadNetworkContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.CandidateTarget
import cz.vutbr.fit.interlockSim.sim.SeparatorKind
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Acceptance criterion 1 of Issue #1378 on the real Praha station: a `request_route` from `S9` to
 * `S15` for a train standing at `S9` is refused, and the reply names the train's exit `N-Vys-1`.
 *
 * The live stall: Train #2 arrived from `S_60_14` and stood at `S9`, the west end of platform track
 * `k6`, bound for `N-Vys-1`. The model asked five times in a row for `S9 -> S15` — `S15` is the signal
 * at the other end of the same platform track, behind the train — and the engine granted a one-block
 * route onto `k6`, the block the train stood on.
 *
 * The station's names, signals and InOuts come from the real `praha-hlavni-nadrazi-station.xml`, and
 * the candidates one section ahead of `S9` from the real interlocking query
 * (`ReservationTargetQuery.findReservationTargetCandidates`) — the list the shell puts on the block
 * input the tool reads. Only the train's live state (perception and block input) is hand-built.
 */
@DisplayName("request_route on Praha: S9 -> S15 for a train standing at S9 is refused (Issue #1378)")
class RequestRoutePrahaBehindTrainTest : DispatcherKoinTestBase() {
	private companion object {
		const val TRAIN = "Train #2"
		const val AT_SIGNAL = "S9"
		const val BEHIND = "S15"
		const val EXIT = "N-Vys-1"
		const val HOP_SECTION = "hop-section"
	}

	private class PrahaFacts(
		val endpointNames: Set<String>,
		val inOutNames: Set<String>,
		val candidatesAheadOfS9: List<CandidateTarget>,
		val signalWithSignalAhead: String,
		val candidatesAheadOfIt: List<CandidateTarget>
	)

	private fun prahaFacts(): PrahaFacts =
		loadNetworkContext(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML).use { context ->
			val semaphores = context.cellsOfType<DynamicRailSemaphore>()
			val inOuts =
				context
					.getInOuts()
					.map { it.name }
					.filter { it.isNotBlank() }
					.toSet()
			val query = context.getRoutingServices().getReservationTargetQuery()

			fun candidatesAhead(signal: String): List<CandidateTarget> =
				query
					.findReservationTargetCandidates(semaphores.single { it.name == signal } as OrientedPathSeparator, TRAIN)
					.map { candidate ->
						when (val separator = candidate.separator) {
							is DynamicInOut -> CandidateTarget(separator.name, SeparatorKind.IN_OUT, candidate.available)
							is DynamicRailSemaphore ->
								CandidateTarget(separator.name, SeparatorKind.SEMAPHORE, candidate.available)
							else -> error("Unexpected candidate separator $separator")
						}
					}
			val (hopStart, aheadOfHopStart) =
				semaphores
					.map { it.name }
					.sorted()
					.asSequence()
					.map { it to candidatesAhead(it) }
					.first { (_, ahead) -> ahead.any { it.kind == SeparatorKind.SEMAPHORE } }
			PrahaFacts(
				endpointNames = inOuts + semaphores.map { it.name }.filter { it.isNotBlank() },
				inOutNames = inOuts,
				candidatesAheadOfS9 = candidatesAhead(AT_SIGNAL),
				signalWithSignalAhead = hopStart,
				candidatesAheadOfIt = aheadOfHopStart
			)
		}

	private fun perceptionPort(
		atSignal: String = AT_SIGNAL,
		section: String = "k6"
	): NetworkPerceptionPort =
		mockk<NetworkPerceptionPort>(relaxed = true).also { port ->
			every { port.snapshot() } returns
				SimulationSnapshot.EMPTY.copy(
					trainPerceptions =
						listOf(
							TrainPerceptionReading(
								trainId = TRAIN,
								signalAheadName = atSignal,
								signalAheadAspect = null,
								distanceToSignalAheadMetres = 0.0,
								currentSpeedLimitMps = 24.0,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = section,
								destinationInOutName = EXIT,
								scheduledArrivalTime = 0.0,
								isDwelling = true
							)
						),
					trainPositions =
						listOf(
							TrainPositionReading(
								trainId = TRAIN,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = section
							)
						)
				)
		}

	private fun sensorPort(
		candidates: List<CandidateTarget>,
		atSignal: String = AT_SIGNAL,
		section: String = "k6"
	): DispatchLoopSensorPort =
		mockk<DispatchLoopSensorPort>(relaxed = true).also { port ->
			every { port.getQueuedTrains() } returns emptyList()
			every { port.snapshot() } returns
				DispatchLoopSnapshot(
					queuedTrains = emptyList(),
					innerBlockInputs =
						listOf(
							BlockInputObservation(
								blockId = section,
								towardSemaphoreName = atSignal,
								state = TrackFacility.State.OCCUPIED,
								ownerTrainId = TRAIN,
								isApproachingThisInput = true,
								pathSetUpTowardThisInput = false,
								pathAlreadyExtendedBeyond = false,
								candidateTargets = candidates,
								destinationName = EXIT
							)
						),
					outerBlockInputs = emptyList()
				)
		}

	@Test
	@DisplayName("S9 -> S15 is TARGET_NOT_NEXT_SECTION and names N-Vys-1; the exit N-Vys-1 is granted")
	fun behindTheTrainIsRefusedAndTheExitIsNamed() {
		val facts = prahaFacts()
		val aheadNames = facts.candidatesAheadOfS9.map { it.name }
		// Preconditions from the real station: the exit is one section ahead of S9, S15 is not.
		assertThat(aheadNames, "candidates one section ahead of S9").contains(EXIT)
		assertThat(aheadNames, "candidates one section ahead of S9").doesNotContain(BEHIND)
		assertThat(facts.endpointNames, "S15 is a real Praha signal").contains(BEHIND)
		// Issue #1390: S9's one Signal target, S8, was reachable only by turning back through a
		// crossover, a leg no train can drive; the exit is now the only target one section ahead of S9.
		assertThat(facts.candidatesAheadOfS9.map { it.kind }, "kinds ahead of S9").containsOnly(SeparatorKind.IN_OUT)

		val emitted = mutableListOf<DispatchAction>()
		val tool =
			RequestRouteTool(
				SinkHolder(EmittedActionSink { emitted += it }),
				facts.endpointNames,
				perceptionPort(),
				sensorPort(facts.candidatesAheadOfS9),
				inOutNames = facts.inOutNames
			)

		val refused =
			runBlocking {
				tool.execute(mapOf("trainName" to TRAIN, "fromEndpointName" to AT_SIGNAL, "toEndpointName" to BEHIND))
			}
		assertThat(refused).isInstanceOf(ToolResult.Error::class)
		val error = refused as ToolResult.Error
		assertThat(error.rejection, "rejection code").isEqualTo(RejectionCode.TARGET_NOT_NEXT_SECTION)
		assertThat(error.message, "reply").contains(EXIT)
		assertThat(error.message, "reply").contains("NEXT SECTION is from '$AT_SIGNAL' to '$EXIT'")
		assertThat(emitted, "emitted after the refusal").isEmpty()

		// Sanity check only: an InOut target is not subject to the next-section guard at all.
		val granted =
			runBlocking {
				tool.execute(mapOf("trainName" to TRAIN, "fromEndpointName" to AT_SIGNAL, "toEndpointName" to EXIT))
			}
		assertThat(granted).isInstanceOf(ToolResult.Success::class)
		assertThat(emitted).containsExactly(DispatchAction.RequestRoute(TRAIN, AT_SIGNAL, EXIT, RouteScope.EndToEnd))
	}

	@Test
	@DisplayName("a real Signal one section ahead of a Praha signal passes the guard (Issue #1390 moved this from S9)")
	fun aSignalAheadPassesTheGuard() {
		val facts = prahaFacts()
		// The rule refuses what is not ahead, not every Signal target. Since Issue #1390 no Signal is
		// one section ahead of S9, so the hop starts at the first Praha signal (by name) whose real
		// candidate list holds a Signal.
		val start = facts.signalWithSignalAhead
		val signalAhead = facts.candidatesAheadOfIt.first { it.kind == SeparatorKind.SEMAPHORE }.name
		val emitted = mutableListOf<DispatchAction>()
		val tool =
			RequestRouteTool(
				SinkHolder(EmittedActionSink { emitted += it }),
				facts.endpointNames,
				perceptionPort(start, HOP_SECTION),
				sensorPort(facts.candidatesAheadOfIt, start, HOP_SECTION),
				inOutNames = facts.inOutNames
			)

		val signalHop =
			runBlocking {
				tool.execute(mapOf("trainName" to TRAIN, "fromEndpointName" to start, "toEndpointName" to signalAhead))
			}

		assertThat(signalHop, "$start -> $signalAhead").isInstanceOf(ToolResult.Success::class)
		assertThat(emitted).containsExactly(DispatchAction.RequestRoute(TRAIN, start, signalAhead, RouteScope.Section))
	}
}
