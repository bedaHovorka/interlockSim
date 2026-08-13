/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import ai.koog.agents.core.agent.AIAgent
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeChannel
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Extends the no-menu constraint (C9, #825) from the test-only renderers to the three **live**
 * prompt surfaces the DISPATCHER agent actually sends to the LLM (Issue #893, phase beta, task B3).
 *
 * ## Why this exists
 *
 * `ObservationRendererTest.NoMenuConstraint` (now delegating to [NoMenuAssertions]) only ever
 * exercised [CompactTextRenderer]/[DeltaRenderer]/[SchematicRenderer] — three renderers with no
 * production caller on the live LLM path (that path is
 * [KoogAgentFactory]/[KoogDispatchAgentImpl]/[StationTopologySerializer], an entirely different
 * code family). A numbered procedure or a stray "option"/"select" could have lived in the real
 * system prompt indefinitely without ever failing a test. This class closes that gap by applying
 * the identical [NoMenuAssertions] checks to:
 *
 * 1. [SystemPromptSurface] — the full system prompt [KoogAgentFactory.createAgent] assembles
 *    (`buildSystemPrompt` + [StationTopologySerializer.toPromptText]), captured through the
 *    same `CapturingAgentService` seam [KoogAgentFactoryTest] uses.
 * 2. [TopologySurface] — [StationTopologySerializer.toPromptText] alone.
 * 3. [CycleMessageSurface] — a representative [KoogDispatchAgentImpl.buildUserPrompt] rendering
 *    carrying queued *and* active trains, a NEXT SECTION fact (task B1), a drained
 *    "OUTCOMES OF YOUR PREVIOUS ACTIONS" block (task B0), and non-empty cycle history.
 *
 * ## Run order (binding, per the task brief)
 *
 * This class was written and run **before** [KoogAgentFactory.buildSystemPrompt] was rewritten
 * (task B2). [TopologySurface] and [CycleMessageSurface] were already green against the
 * pre-existing renderers — [StationTopologySerializer.toPromptText] is untouched by #893 entirely
 * (#834's own acceptance criterion), and [KoogDispatchAgentImpl.buildUserPrompt]'s task-B0/B1 output
 * was already dash-bulleted with no menu verbs. [SystemPromptSurface] passed against the *old*
 * prompt too — the old prompt happened not to trip the regex/verb checks — so it served as this
 * task's regression lock for the B2 rewrite rather than a RED signal in the classic sense: any
 * future edit to `buildSystemPrompt` that reintroduces a numbered list or "optional"/"select"
 * now fails here immediately.
 *
 * @since Issue #825 (SP2c.2 — original constraint); extended Issue #893 (phase beta, task B3)
 */
@DisplayName("Live prompt surfaces contain no menu artifacts (Issue #893, phase beta, task B3)")
class LivePromptNoMenuTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

	/** Mirrors [KoogAgentFactoryTest.fakePerceptionPort]: reports [activeTrainIds] as active. */
	private fun fakePerceptionPort(activeTrainIds: List<String> = listOf("T1")): NetworkPerceptionPort =
		mockk<NetworkPerceptionPort> {
			every { snapshot() } returns
				SimulationSnapshot.EMPTY.copy(
					trainPositions =
						activeTrainIds.map {
							TrainPositionReading(
								trainId = it,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						}
				)
		}

	/** Mirrors [KoogAgentFactoryTest.fakeSensorPort]: reports [queuedTrainIds] as queued. */
	private fun fakeSensorPort(queuedTrainIds: List<String> = listOf("T1")): DispatchLoopSensorPort =
		mockk<DispatchLoopSensorPort> {
			every { getQueuedTrains() } returns
				queuedTrainIds.map { QueuedTrainObservation(trainId = it, destinationInOutName = "B") }
		}

	// ── Surface 1: the full system prompt KoogAgentFactory assembles ───────────────────────

	@Nested
	@DisplayName("Surface 1 — KoogAgentFactory-assembled system prompt (buildSystemPrompt + topology)")
	inner class SystemPromptSurface {
		/**
		 * Parameterised over [PromptVariant] since Issue #834 (SP2c.11): C9 binds the prompt the
		 * model is actually sent, so it binds every selectable variant of it, not merely whichever
		 * one happens to be the default. A revision that reintroduced a numbered procedure would
		 * otherwise reach a real sweep run untested.
		 */
		@ParameterizedTest
		@EnumSource(PromptVariant::class)
		@DisplayName("no numbered-option lines, no 'option'/'choose one'/'select' token")
		fun systemPromptHasNoMenuArtifacts(variant: PromptVariant) {
			loadShuntingLoopContext().use { context ->
				val agentService = KoogAgentFactoryTest.CapturingAgentService()
				val factory =
					KoogAgentFactory(
						toolRegistry = ToolGroupRegistry(),
						ollamaConfig = OllamaExecutorConfig.forLocalTesting(),
						agentService = agentService,
						perceptionPort = fakePerceptionPort(),
						commandQueue = ActuatorCommandQueue(),
						dispatchLoopSensorPort = fakeSensorPort(),
						sinkHolder = SinkHolder(),
						promptVariant = variant
					)

				runBlocking { factory.createAgent(context) }

				val systemPrompt = requireNotNull(agentService.capturedSystemPrompt)
				NoMenuAssertions.assertNoMenuArtifacts(systemPrompt)
			}
		}
	}

	// ── Surface 2: the topology text alone ──────────────────────────────────────────────────

	@Nested
	@DisplayName("Surface 2 — StationTopologySerializer.toPromptText alone")
	inner class TopologySurface {
		@Test
		@DisplayName("no numbered-option lines, no 'option'/'choose one'/'select' token")
		fun topologyTextHasNoMenuArtifacts() {
			loadShuntingLoopContext().use { context ->
				val topologyText = StationTopologySerializer.serialize(context)
				NoMenuAssertions.assertNoMenuArtifacts(topologyText)
			}
		}
	}

	// ── Surface 3: a representative rendered cycle message ─────────────────────────────────

	@Nested
	@DisplayName("Surface 3 — KoogDispatchAgentImpl.buildUserPrompt (queued+active, NEXT SECTION, OUTCOMES, history)")
	inner class CycleMessageSurface {
		private fun blockInput(
			towardSemaphoreName: String,
			toSeparatorName: String?,
			ownerTrainId: String?
		): BlockInputObservation =
			BlockInputObservation(
				blockId = "blk",
				towardSemaphoreName = towardSemaphoreName,
				toSeparatorName = toSeparatorName,
				state = TrackFacility.State.OCCUPIED,
				ownerTrainId = ownerTrainId,
				isApproachingThisInput = true,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false
			)

		/**
		 * Builds the representative cycle message: one active train with a NEXT SECTION fact (B1),
		 * one queued train (approve-only, B1), a drained OUTCOMES block (B0), and non-empty
		 * history — every live-message ingredient B0/B1 added, in one rendering.
		 */
		private fun capturedRepresentativePrompt(): String {
			val cycleHistory = CycleHistory(capacity = 3)
			cycleHistory.record(
				simTime = 100.0,
				outcome = TickOutcome.LLM_ACTIONS,
				actions = listOf(DispatchAction.RequestRoute("Train #1", "doA1", "doA2"))
			)

			val outcomeFeed = AppliedOutcomeChannel()
			outcomeFeed.publish(
				AppliedOutcome.Blocked(
					trainId = "Train #2",
					fromEndpointName = "doA1",
					toEndpointName = "A",
					attemptedPaths = 2,
					id = CommandId(1L),
					tickIndex = 1L
				)
			)

			val aiAgent = mockk<AIAgent<String, String>>()
			val prompts = mutableListOf<String>()
			coEvery { aiAgent.run(any(), null) } answers {
				prompts.add(firstArg())
				"done"
			}
			val agent = KoogDispatchAgentImpl(aiAgent, cycleHistory = cycleHistory, outcomeFeed = outcomeFeed)

			val observation =
				DispatchObservation(
					snapshot =
						SimulationSnapshot.EMPTY.copy(
							trainPositions =
								listOf(
									TrainPositionReading(
										trainId = "Train #1",
										velocity = 5.0,
										acceleration = 0.0,
										totalDistance = 0.0,
										frontSectionName = null
									)
								),
							trainPerceptions =
								listOf(
									TrainPerceptionReading(
										trainId = "Train #1",
										signalAheadName = null,
										signalAheadAspect = null,
										distanceToSignalAheadMetres = 0.0,
										currentSpeedLimitMps = 0.0,
										velocity = 5.0,
										acceleration = 0.0,
										totalDistance = 0.0,
										frontSectionName = null,
										destinationInOutName = "A",
										scheduledArrivalTime = 0.0,
										isDwelling = false
									)
								)
						),
					unapprovedTrains = listOf(QueuedTrainObservation(trainId = "Train #3", destinationInOutName = "B")),
					innerBlockInputs =
						listOf(blockInput(towardSemaphoreName = "doB1", toSeparatorName = "doB2", ownerTrainId = "Train #1")),
					outerBlockInputs = emptyList()
				)

			runBlocking { agent.decideAsync(observation) }
			return prompts.single()
		}

		@Test
		@DisplayName("no numbered-option lines, no 'option'/'choose one'/'select' token")
		fun cycleMessageHasNoMenuArtifacts() {
			NoMenuAssertions.assertNoMenuArtifacts(capturedRepresentativePrompt())
		}

		@Test
		@DisplayName("fixture sanity: the representative prompt actually carries every claimed ingredient")
		fun fixtureCarriesEveryClaimedIngredient() {
			val prompt = capturedRepresentativePrompt()
			assertThat(prompt).contains("NEXT SECTION") // task B1 fact
			assertThat(prompt).contains("OUTCOMES OF YOUR PREVIOUS ACTIONS") // task B0 block, drained
			assertThat(prompt).contains("Train #3") // queued train rendered
			assertThat(prompt).contains("YOUR LAST") // non-empty cycle history
		}

		// ── Issue #834 (SP2c.11, task 8): the deduped same-tick same-target rendering ──────────

		/**
		 * Two active trains whose owned inputs both resolve to the same
		 * [BlockInputObservation.toSeparatorName] -- the scenario
		 * [cz.vutbr.fit.interlockSim.dispatcher.agents.NextHopResolver.resolveAll] dedups. Checked
		 * against the same no-menu surface as [capturedRepresentativePrompt] plus the additional
		 * constraint that the loser's line must not misstate the railway as occupied/blocked.
		 */
		private fun capturedDedupedPrompt(): String {
			val aiAgent = mockk<AIAgent<String, String>>()
			val prompts = mutableListOf<String>()
			coEvery { aiAgent.run(any(), null) } answers {
				prompts.add(firstArg())
				"done"
			}
			val agent = KoogDispatchAgentImpl(aiAgent)

			val observation =
				DispatchObservation(
					snapshot =
						SimulationSnapshot.EMPTY.copy(
							trainPositions =
								listOf(
									TrainPositionReading(
										trainId = "Train #1",
										velocity = 5.0,
										acceleration = 0.0,
										totalDistance = 0.0,
										frontSectionName = null
									),
									TrainPositionReading(
										trainId = "Train #2",
										velocity = 5.0,
										acceleration = 0.0,
										totalDistance = 0.0,
										frontSectionName = null
									)
								)
						),
					unapprovedTrains = emptyList(),
					innerBlockInputs =
						listOf(
							blockInput(towardSemaphoreName = "doB1", toSeparatorName = "sharedSep", ownerTrainId = "Train #1"),
							blockInput(towardSemaphoreName = "doC1", toSeparatorName = "sharedSep", ownerTrainId = "Train #2")
						),
					outerBlockInputs = emptyList()
				)

			runBlocking { agent.decideAsync(observation) }
			return prompts.single()
		}

		@Test
		@DisplayName("deduped cycle message: no numbered-option lines, no 'option'/'choose one'/'select' token")
		fun dedupedCycleMessageHasNoMenuArtifacts() {
			NoMenuAssertions.assertNoMenuArtifacts(capturedDedupedPrompt())
		}

		@Test
		@DisplayName("deduped cycle message: the losing train's line never claims the track is occupied or blocked")
		fun dedupedCycleMessageLoserLineNeverImpliesTrackIsBlockedOrOccupied() {
			val prompt = capturedDedupedPrompt()
			val loserLine = prompt.lineSequence().first { it.contains("Train #2") }

			assertThat(loserLine.lowercase()).doesNotContain("blocked")
			assertThat(loserLine.lowercase()).doesNotContain("occupied")
		}
	}
}
