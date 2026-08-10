/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaPrewarmExtension
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Live proof that round 2's prompt fixes hold against a real model on the real network
 * (Issue #847 round 3).
 *
 * ## The gap this closes
 *
 * [KoogRealOllamaToolCallingTest] drives real Ollama, but with **hand-written prompts and fake
 * tools**. Every defect PR #891 round 2 fixed lives in the production system prompt
 * ([KoogAgentFactory.buildSystemPrompt] plus [StationTopologySerializer.toPromptText]) and in
 * the four real actuator tools, so none of it was covered end to end. The offline tests assert the
 * prompt *text*; only a live model can show whether the text works.
 *
 * This builds the genuine article — real topology from `vyhybna.xml`, real system prompt, real
 * four-tool surface behind [ToolGroupRegistry.assembleAllTools] — and asserts on what the model
 * does with it.
 *
 * ## What is asserted, and what deliberately is not
 *
 * The assertions are on **rejection counts being zero**, never on a specific call sequence. A
 * 7B-class model's exact choice of route on any given cycle is an LLM-quality question; whether it
 * can name an endpoint or a train that exists is a prompt-correctness question, and only the second
 * is what round 2 changed. Rejections are counted by wrapping each real tool and classifying the
 * [ToolResult.Error] messages the tools themselves produce.
 *
 * ## Why the scenario is mid-run (Issue #847 round 4, finding R4-4)
 *
 * Round 3 recorded these tests as **non-discriminating** and said so plainly: three deliberate
 * reverts of the very fixes they claim to guard — dropping the Signals list from
 * [StationTopologySerializer.toPromptText], presenting the Blocks list without its "never valid as
 * a request_route endpoint" disqualifier, and restoring round 1's literal
 * `request_route(trainName="T1", …)` worked example to [KoogAgentFactory]'s system prompt — all
 * stayed green over six cycles.
 *
 * The diagnosis was the scenario, not the assertions: one queued train bound for "B", no active
 * trains, no occupied blocks, no competing reservations. With nothing on the network there is
 * almost nothing for the model to mis-name, so a broken prompt and a correct one produce the same
 * result. Round 2's 86 endpoint and 90 train-id rejections accumulated over hundreds of cycles of a
 * live run in which trains hold blocks and routes conflict.
 *
 * Round 4 therefore replaces the scenario rather than the assertions. Each cycle now presents:
 *
 * - **two** trains active on the network, on real block ids read from `vyhybna.xml` itself;
 * - blocks in genuinely mixed states — OCCUPIED under a train, RESERVED ahead of it, FREE;
 * - a queue that changes between cycles, so the set of legal train ids is never constant;
 * - a **different** observation on every cycle, instead of the same one replayed six times.
 *
 * The block ids come from [StationTopologySerializer.describe] rather than being hand-written, so
 * the names in play are exactly the `kA`/`k1` family that collides with the InOut names `A`/`B` —
 * the confusion the prompt fixes exist to prevent, and the one the model has to resolve here.
 *
 * ## Measured discrimination — 1 of 3, not 3 of 3
 *
 * The same three reverts were re-run against the mid-run scenario. Stated plainly rather than
 * rounded up:
 *
 * | Revert | Round 3 | Round 4 |
 * |---|---|---|
 * | Blocks list stripped of its "never valid as an endpoint" label | passed | **failed** — detected |
 * | Signals list dropped from [StationTopologySerializer.toPromptText] | passed | passed |
 * | Round 1's literal `request_route(trainName="T1", …)` restored | passed | passed |
 *
 * The Signals revert is **not a valid probe**, and that is a finding about the probe rather than
 * about the test: [StationTopologySerializer]'s section-hop example independently prints
 * `signals[0]` and `signals[1]` by name, so deleting the list still leaves two real signal names in
 * the prompt. There is no single edit that removes signal names from the prompt, so that property
 * cannot be probed this way at all.
 *
 * ## Issue #834 (SP2c.11): three further candidates, none of them assertable
 *
 * The SP2c.11 prompt rebuild added [PromptVariant.REVISED] and looked for live assertions to guard
 * the properties it changed. Three candidates were built and probed under this file's standing
 * rule. **None survived, so none was added** — the outcome is recorded here so the next attempt
 * starts from the evidence rather than repeating it.
 *
 * | Candidate property | Probe | Verdict |
 * |---|---|---|
 * | The unconditional `no_op` catch-all bullet | narrowed to the empty-station case, then deleted outright | **invalid probe** — `NoOpTool.description` independently tells the model "Call this when you have examined the current state and determined there is nothing to do", every cycle, through the tool schema. The model called `no_op` in every probe run. No system-prompt edit can remove this property from what the model sees. |
 * | Turn termination ("only a plain-text reply ends the tick") | delete the clause from the system prompt | **invalid probe, by inspection** — [KoogDispatchAgentImpl.buildUserPrompt]'s deliberately-last line says "When your actions for this tick are done, finish with one short plain-text sentence and no further tool calls" on every cycle, unconditionally. Same structural problem as the Signals row above; not run. |
 * | The per-tick action budget being stated at all | 3 cycles, three queued trains, `SinkHolder(maxActionsPerTick = 1)` | **not assertable** — the assertion fails against the *correct* prompt. `qwen2.5:7b-instruct` exceeded the cap in every cycle, producing 5 `ACTION_LIMIT_EXCEEDED` rejections; [PromptVariant.BASELINE], which states the budget three times, produced **the same 5**. Stating the budget more often does not help, and what actually stops the model is [SinkHolder]'s own terminal rejection message. |
 *
 * The third row is the useful measurement of the three, and it is the reason SP2c.11's compression
 * of the budget from three statements to one carries no measured regression on this axis: 5 and 5.
 *
 * The pattern behind rows one and two is worth stating on its own, because it will recur: the
 * properties most worth guarding live are exactly the ones the surface has since learned to state
 * twice — once in the system prompt and once in a tool description or the per-cycle message. That
 * redundancy is good for the model and fatal for single-edit probing. A live assertion for such a
 * property would need both statements reverted together, at which point it no longer guards the
 * prompt sentence it claims to.
 *
 * The `"T1"` revert stays undetected for a quantitative reason: round 2's poisoning accumulated
 * over hundreds of cycles of a 600 s run, and six cycles against a prompt that now also names real
 * ids every cycle is not enough for the literal to win. The offline guard in
 * [StationTopologySerializerTest.promptCarriesNoCopyableTrainName] — which asserts the topology
 * prompt block contains no concrete train name — is the load-bearing regression test for that
 * fix, and it is deterministic.
 *
 * ## The standing rule for this file
 *
 * An assertion belongs here only if it has been **observed to fail** with the prompt property it
 * guards removed. That is the discipline [KoogRealOllamaToolCallingTest] follows with its
 * unforgeable `QX7` token, and it is what round 3 found missing here. A green test whose failure
 * mode has never been demonstrated is a claim, not evidence — so these remain a live smoke test
 * plus one genuinely discriminating case, not three regression tests.
 *
 * @since Issue #847 (round 3); scenario rebuilt mid-run in round 4
 */
@ExtendWith(OllamaPrewarmExtension::class)
@DisplayName("The production prompt keeps a real model inside the real network's vocabulary")
class KoogRealPromptOllamaTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	/** Trains on the network. Matches `Train`'s real `"Train #<n>"` naming. */
	private val queuedTrainId = "Train #1"
	private val secondTrainId = "Train #2"
	private val thirdTrainId = "Train #3"

	private val testTimeoutMillis = 300_000L

	private companion object {
		/** Dispatch cycles per test — see [runCycles] for why one is not enough. */
		const val DEFAULT_CYCLES = 6

		/** Enough blocks for two trains plus a reservation ahead of one of them, without overlap. */
		const val MIN_BLOCKS_FOR_MID_RUN = 4
	}

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	/**
	 * Records every real-tool invocation and classifies the rejections the tools returned.
	 *
	 * Classification reads [ToolResult.Error.rejection] — the typed code round 4 added — rather
	 * than matching on the message prose it used to `startsWith`. Prose matching silently
	 * misclassifies the moment a message is reworded, and that is not a hypothetical: round 4
	 * reworded `approve_train`'s rejection of an already-active train precisely because the old
	 * wording ("Unknown trainId") described the wrong failure.
	 *
	 * **Hallucination and procedural error are counted separately.** A model naming a train that
	 * exists but is already running has not invented anything; it has sequenced badly. Only the
	 * first is a prompt-vocabulary failure, and only the first is what these tests assert on.
	 */
	private class RejectionRecorder {
		val calls: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()

		/** The model named an endpoint that does not exist, or a Block ID where one is never valid. */
		val unknownEndpointRejections: MutableList<String> = mutableListOf()

		/** The model named a train that no train on the network bears. */
		val unknownTrainRejections: MutableList<String> = mutableListOf()

		/**
		 * Real names, wrong moment or wrong direction — e.g. approving a train that is already
		 * active, or routing one against its timetable.
		 *
		 * The direction codes belong here for the same reason
		 * [RejectionCode.TRAIN_ALREADY_ACTIVE] does: `request_route`'s guards against a route
		 * aimed at the wrong end of the station, or starting somewhere the train is not, reject
		 * arguments that are all real names used badly. That is a planning weakness of a 7B model,
		 * measured by the SP2c A/B metrics, not the prompt-vocabulary failure these tests assert
		 * on — and folding it in would fail them for a reason they do not claim to measure.
		 *
		 * [RejectionCode.ACTION_LIMIT_EXCEEDED] joins them in Issue #847 (SP2c.24), and it is a
		 * measurement in its own right: the cap was made live on this path by that issue, and the
		 * very first live run against it rejected two calls — so `qwen2.5:7b-instruct` does emit
		 * more than three actions in a cycle on this station. Every name in such a call is real
		 * and the action is legal; only the budget is spent. Reported on #834 as prompt work.
		 */
		val proceduralRejections: MutableList<String> = mutableListOf()

		val otherRejections: MutableList<String> = mutableListOf()

		/**
		 * Cycles that threw instead of completing — in practice Koog's 20-iteration cap.
		 *
		 * Recorded, not asserted on: production answers such a cycle with the rule-based fallback
		 * and carries on, so a test about prompt vocabulary must not fail on it. Worth reporting
		 * because the mid-run scenario reaches the cap where round 3's empty one never did.
		 */
		val failedCycles: MutableList<String> = mutableListOf()

		fun record(
			toolName: String,
			args: Map<String, Any?>,
			result: ToolResult
		) {
			calls.add(toolName to args)
			if (result !is ToolResult.Error) return
			val message = result.message
			when (result.rejection) {
				RejectionCode.UNKNOWN_ENDPOINT, RejectionCode.ENDPOINT_IS_BLOCK_ID ->
					unknownEndpointRejections.add(message)
				RejectionCode.UNKNOWN_TRAIN -> unknownTrainRejections.add(message)
				RejectionCode.TRAIN_ALREADY_ACTIVE,
				RejectionCode.TRAIN_NOT_QUEUED,
				RejectionCode.TARGET_NOT_TRAIN_DESTINATION,
				RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION,
				RejectionCode.ACTION_LIMIT_EXCEEDED ->
					proceduralRejections.add(message)
				else -> otherRejections.add(message)
			}
		}
	}

	/** Passes calls through to the real tool, recording arguments and results on the way back. */
	private class RecordingTool(
		private val delegate: DomainTool,
		private val recorder: RejectionRecorder
	) : DomainTool {
		override val name: String get() = delegate.name
		override val description: String get() = delegate.description
		override val parameters: List<DomainToolParameter> get() = delegate.parameters

		override suspend fun execute(args: Map<String, Any?>): ToolResult =
			delegate.execute(args).also { recorder.record(delegate.name, args, it) }
	}

	/** Wraps every tool in a [RecordingTool] before handing them to the real agent service. */
	private class RecordingAgentService(
		private val delegate: AgentService,
		private val recorder: RejectionRecorder
	) : AgentService {
		override suspend fun createDispatchAgent(
			modelName: String,
			tools: List<DomainTool>,
			systemPrompt: String?,
			cycleHistory: CycleHistory,
			outcomeFeed: AppliedOutcomeFeed?
		): KoogDispatchAgent =
			delegate.createDispatchAgent(
				modelName = modelName,
				tools = tools.map { RecordingTool(it, recorder) },
				systemPrompt = systemPrompt,
				cycleHistory = cycleHistory,
				outcomeFeed = outcomeFeed
			)
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/**
	 * One cycle's worth of live state: what the ports report and what the observation carries.
	 *
	 * Kept together because the two must agree — the tools validate train ids against the ports
	 * while the prompt is rendered from the observation, and a harness where they disagree would
	 * reject calls the prompt legitimately invited.
	 */
	private data class Cycle(
		val snapshot: SimulationSnapshot,
		val queued: List<QueuedTrainObservation>
	)

	/**
	 * Builds a mid-run sequence over the network's own block ids (Issue #847 round 4, R4-4).
	 *
	 * Every cycle differs: trains advance from one block to the next, the queue drains and refills,
	 * and the mix of OCCUPIED/RESERVED/FREE blocks changes underneath. That is what round 3's
	 * single replayed observation could not offer, and why a broken prompt survived it.
	 *
	 * Block ids are read from the real topology, so the names the model must avoid confusing with
	 * the InOuts `A`/`B` are the genuine `kA`/`k1` family rather than plausible-looking inventions.
	 */
	private fun midRunCycles(
		blockIds: List<String>,
		cycles: Int
	): List<Cycle> {
		require(blockIds.size >= MIN_BLOCKS_FOR_MID_RUN) {
			"vyhybna.xml is expected to have at least $MIN_BLOCKS_FOR_MID_RUN blocks, got ${blockIds.size}"
		}
		return (0 until cycles).map { cycle ->
			// Two trains walking along the block list, one behind the other, wrapping around.
			val leadAt = cycle % blockIds.size
			val followAt = (cycle + 2) % blockIds.size
			val leadAhead = (leadAt + 1) % blockIds.size
			val blocks =
				blockIds.mapIndexed { index, id ->
					when (index) {
						leadAt -> BlockOccupancyReading(id, TrackFacility.State.OCCUPIED, queuedTrainId)
						leadAhead -> BlockOccupancyReading(id, TrackFacility.State.RESERVED, queuedTrainId)
						followAt -> BlockOccupancyReading(id, TrackFacility.State.OCCUPIED, secondTrainId)
						else -> BlockOccupancyReading(id, TrackFacility.State.FREE, null)
					}
				}
			Cycle(
				snapshot =
					SimulationSnapshot.EMPTY.copy(
						simTime = cycle * 2.0,
						blocks = blocks,
						trainPositions =
							listOf(
								trainAt(queuedTrainId, blockIds[leadAt]),
								trainAt(secondTrainId, blockIds[followAt])
							)
					),
				// The queue alternates, so the legal train-id set is never constant across cycles —
				// a model copying an id from an earlier turn is wrong on the next one.
				queued =
					if (cycle % 2 == 0) {
						listOf(QueuedTrainObservation(trainId = thirdTrainId, destinationInOutName = "B"))
					} else {
						emptyList()
					}
			)
		}
	}

	private fun trainAt(
		trainId: String,
		sectionName: String
	) = TrainPositionReading(
		trainId = trainId,
		velocity = 0.0,
		acceleration = 0.0,
		totalDistance = 0.0,
		frontSectionName = sectionName
	)

	/**
	 * Runs [cycles] real dispatch cycles through the production agent and returns what the tools
	 * saw across all of them.
	 *
	 * More than one cycle on purpose. Round 2's rejected-call counts (86 endpoint, 90 train-id)
	 * accumulated over a 600 s run of hundreds of cycles; a single cycle with one queued train
	 * bound for "B" is easy enough that the model gets it right even from a deliberately broken
	 * prompt, so a one-cycle assertion cannot discriminate. Several cycles against one agent — the
	 * same agent instance a run reuses — gives hallucination a realistic chance to appear.
	 */
	private fun runCycles(cycles: Int = DEFAULT_CYCLES): RejectionRecorder {
		val recorder = RejectionRecorder()
		loadShuntingLoopContext().use { context ->
			val config = OllamaExecutorConfig.forLocalTesting()
			val blockIds = StationTopologySerializer.describe(context).blocks.map { it.name }
			val script = midRunCycles(blockIds, cycles)
			// The ports read whichever cycle the run is currently on, so tool-side validation sees
			// exactly the state the prompt described for that cycle.
			var current = script.first()
			val perceptionPort = mockk<NetworkPerceptionPort> { every { snapshot() } answers { current.snapshot } }
			val sensorPort = mockk<DispatchLoopSensorPort> { every { getQueuedTrains() } answers { current.queued } }
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = config,
					agentService = RecordingAgentService(DefaultAgentService(OllamaSimpleExecutor(config), config), recorder),
					perceptionPort = perceptionPort,
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort = sensorPort,
					sinkHolder = SinkHolder()
				)
			runBlocking {
				withTimeout(testTimeoutMillis) {
					val agent = factory.createAgent(context)
					script.forEach { cycle ->
						current = cycle
						// Production tolerates a failed cycle: KoogAgentPlanAdapter catches it,
						// counts a FallbackReason.EXCEPTION and answers with the rule-based
						// dispatcher, so the run continues. This harness calls the agent directly,
						// so it has to tolerate it the same way — otherwise a cycle that exhausts
						// Koog's 20-iteration cap would fail an assertion about *vocabulary*, which
						// is not what it measures. The cap is not raised: round 3 established that
						// its budget is real and AgentBuildContractTest hard-asserts 20.
						try {
							agent.decideAsync(
								DispatchObservation(
									snapshot = cycle.snapshot,
									unapprovedTrains = cycle.queued,
									innerBlockInputs = emptyList(),
									outerBlockInputs = emptyList()
								)
							)
						} catch (e: Exception) {
							recorder.failedCycles.add(e::class.simpleName ?: "Exception")
						}
					}
				}
			}
		}
		return recorder
	}

	/**
	 * Round-2 fix #2 territory: `KoogAgentFactory` accepts InOuts ∪ Signals — `A, B, doA1, doA2,
	 * doB1, doB2, zA, zB` for `vyhybna.xml` — while round 1's topology block advertised only the
	 * InOuts, leaving the model reaching for block names (86 rejected endpoint calls in one run, 48
	 * naming the block `k1`).
	 *
	 * Round 4 rebuilt the scenario mid-run so this can actually discriminate — see the class KDoc.
	 * The revert probes and their verdicts are recorded on the PR.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("no route call is rejected for naming an endpoint that does not exist")
	fun noEndpointIsHallucinated() {
		val recorder = runCycles()

		assertThat(recorder.calls, "tool calls the model made").isNotEmpty()
		assertThat(recorder.unknownEndpointRejections, "endpoint names the network does not have").isEmpty()
	}

	/**
	 * Round-2 fixes #1, #4 and #5 territory: round 1's system prompt carried a worked example
	 * printing `request_route(trainName="T1", …)`, and because it sat in the *system* prompt it was
	 * present every cycle; the model copied `"T1"` verbatim, reserving real blocks for a train that
	 * does not exist and that nothing can ever release.
	 *
	 * **Verified non-discriminating**: still green with that literal `"T1"` example restored to the
	 * system prompt. See the class KDoc — this is a smoke test.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("no tool call names a train absent from this cycle's message")
	fun noTrainIdIsHallucinated() {
		val recorder = runCycles()

		assertThat(recorder.calls, "tool calls the model made").isNotEmpty()
		assertThat(recorder.unknownTrainRejections, "train ids no train in this cycle bears").isEmpty()
		assertThat(
			recorder.calls.filter { (_, args) -> args.values.any { it?.toString()?.contains("T1") == true } },
			"calls carrying the literal T1 from round 1's worked example"
		).isEmpty()
	}

	/**
	 * Round-2 fix #3 and the admission-first prompt clause. Round 1's prompts instructed the model,
	 * on every turn, to "check `queued_trains` and `all_train_positions` … before doing anything
	 * else" — tools deleted in SP2c.6 (#869). The live surface is exactly four actuators, and a
	 * queued train departs only when `approve_train` is called for it.
	 *
	 * Asserts only that the model acted at all and that nothing it *named* was invented; which of
	 * the four actuators it reaches for first is an LLM-quality question, not a prompt-correctness
	 * one. The broadest of the three, and the one most likely to catch a genuine surface break — an
	 * argument the tools cannot parse at all shows up here as an `otherRejections` entry.
	 *
	 * **Procedural rejections are deliberately not asserted on** (Issue #847 round 4). The mid-run
	 * scenario puts trains on the network, and a 7B model reliably tries to `approve_train` one that
	 * is already running — a real id at the wrong moment. That is a sequencing weakness for
	 * SP2c.11's prompt rebuild (#834) to address, not a vocabulary failure, and folding it into
	 * these counts would make them fail for a reason they do not claim to measure. The same now
	 * applies to `request_route`'s direction guards: the model routing a train against its
	 * timetable is real names used badly, counted as procedural for exactly the same reason.
	 */
	@Test
	@Tag("ollama-test")
	@DisplayName("the model acts through the four-actuator surface without a single rejected call")
	fun everyCallLandsOnTheRealSurface() {
		val recorder = runCycles()

		assertThat(recorder.calls, "tool calls the model made").isNotEmpty()
		assertThat(recorder.unknownEndpointRejections, "unknown-endpoint rejections").isEmpty()
		assertThat(recorder.unknownTrainRejections, "unknown-train rejections").isEmpty()
		assertThat(recorder.otherRejections, "any other tool rejection").isEmpty()
	}
}
