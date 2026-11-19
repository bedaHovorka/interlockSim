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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetCandidate
import cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetQuery
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.di.dispatcherAgentModule
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.loadNetworkContext
import cz.vutbr.fit.interlockSim.dispatcher.testutil.loadShuntingLoopContext
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1301 review (traffic-simulation-expert MUST 1): the reservation target query the
 * structural journey is picked from mutates navigation state confined to the simulation thread,
 * while [KoogAgentFactory.createAgent] runs lazily on the dispatcher thread in the first `plan()`,
 * with the simulation already running. So BASELINE and REVISED make no query call at all, and
 * REVISED_STRUCTURAL computes its journey before the run ([PreparedEntryJourney], prepared by the
 * `KoogAgentFactory` binding the example setup resolves) and makes no query call in `plan()`.
 */
@DisplayName("Issue #1301 — the structural journey is computed before the run, never in plan()")
class StructuralJourneyThreadingTest : DispatcherKoinTestBase() {
	/** The run config the Koin binding reads; set before the factory is resolved. */
	private var runConfig = DispatcherRunConfig()

	override fun getTestModules(): List<Module> =
		listOf(
			dispatcherAgentModule,
			commonCoreTestModule,
			module { single<DispatcherRunConfig> { runConfig } }
		)

	private val vyhybnaJourney = EntryJourney("A", SignalId("zA"), SignalId("doB1"), "B")

	/** Counts every candidate query made through it. */
	private class CountingQuery(
		private val real: ReservationTargetQuery
	) : ReservationTargetQuery {
		val calls = AtomicInteger()

		override fun findReservationTargetCandidates(
			start: OrientedPathSeparator,
			ownerTrainId: String?
		): List<ReservationTargetCandidate> {
			calls.incrementAndGet()
			return real.findReservationTargetCandidates(start, ownerTrainId)
		}
	}

	/** A spy of [context] whose reservation target query counts its calls. */
	private fun countingContext(context: DefaultSimulationContext): Pair<DefaultSimulationContext, CountingQuery> {
		val realRouting = context.getRoutingServices()
		val query = CountingQuery(realRouting.getReservationTargetQuery())
		val routing =
			object : RoutingServices by realRouting {
				override fun getReservationTargetQuery(): ReservationTargetQuery = query
			}
		val spy = spyk(context) { every { getRoutingServices() } returns routing }
		return spy to query
	}

	private fun factory(
		agentService: AgentService,
		variant: PromptVariant,
		sinkHolder: SinkHolder,
		prepared: PreparedEntryJourney? = null
	) = KoogAgentFactory(
		toolRegistry = ToolGroupRegistry(),
		ollamaConfig = OllamaExecutorConfig(ollamaEndpoint = "http://127.0.0.1:1"),
		agentService = agentService,
		perceptionPort = mockk<NetworkPerceptionPort> { every { snapshot() } returns SimulationSnapshot.EMPTY },
		commandQueue = ActuatorCommandQueue(),
		dispatchLoopSensorPort = mockk<DispatchLoopSensorPort> { every { getQueuedTrains() } returns emptyList() },
		sinkHolder = sinkHolder,
		promptVariant = variant,
		stationCapacityProvider = { StationCapacity(2) },
		preparedEntryJourney = prepared
	)

	private val emptyObservation =
		DispatchObservation(
			snapshot = SimulationSnapshot.EMPTY,
			unapprovedTrains = emptyList(),
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	@ParameterizedTest
	@EnumSource(value = PromptVariant::class, names = ["BASELINE", "REVISED"])
	@DisplayName("BASELINE and REVISED agent construction makes no reservation target query")
	fun alphabeticalVariantsMakeNoQuery(variant: PromptVariant) {
		loadNetworkContext(NetworkResources.VYHYBNA_XML).use { context ->
			val (spy, query) = countingContext(context)
			val agentService = KoogAgentFactoryTest.CapturingAgentService()

			runBlocking { factory(agentService, variant, SinkHolder()).createAgent(spy) }

			assertThat(query.calls.get(), "query calls in createAgent").isEqualTo(0)
		}
	}

	@Test
	@DisplayName("a prepared journey is rendered and plan() makes no reservation target query")
	fun planMakesNoQueryWithAPreparedJourney() {
		loadNetworkContext(NetworkResources.VYHYBNA_XML).use { context ->
			// Before the run, on this (the building) thread.
			val prepared = PreparedEntryJourney.of(context)
			assertThat(prepared.journey).isEqualTo(vyhybnaJourney)
			val (spy, query) = countingContext(context)
			val agentService = KoogAgentFactoryTest.CapturingAgentService()
			val sinkHolder = SinkHolder()
			val adapter =
				KoogAgentPlanAdapter(
					agentFactory = factory(agentService, PromptVariant.REVISED_STRUCTURAL, sinkHolder, prepared),
					context = spy,
					fallbackDispatcher = Dispatcher { emptyList() },
					commandQueue = ActuatorCommandQueue(),
					sinkHolder = sinkHolder
				)

			runBlocking { adapter.plan(emptyObservation) }

			assertThat(query.calls.get(), "query calls in plan()").isEqualTo(0)
			assertThat(agentService.capturedSystemPrompt)
				.isNotNull()
				.contains("fromEndpointName=\"zA\", toEndpointName=\"doB1\")")
		}
	}

	@Test
	@DisplayName("the Koin KoogAgentFactory binding prepares the journey when the run is REVISED_STRUCTURAL")
	fun koinBindingPreparesTheJourney() {
		runConfig = DispatcherRunConfig(promptVariant = PromptVariant.REVISED_STRUCTURAL)
		loadShuntingLoopContext().use { context ->
			val factory = context.scope.get<KoogAgentFactory>()

			assertThat(factory.preparedEntryJourney).isNotNull().transform { it.journey }.isEqualTo(vyhybnaJourney)
		}
	}

	@Test
	@DisplayName("the Koin KoogAgentFactory binding prepares nothing (no query) for REVISED")
	fun koinBindingPreparesNothingForRevised() {
		runConfig = DispatcherRunConfig(promptVariant = PromptVariant.REVISED)
		loadShuntingLoopContext().use { context ->
			assertThat(context.scope.get<KoogAgentFactory>().preparedEntryJourney).isNull()
		}
	}

	@Test
	@DisplayName("an unprepared REVISED_STRUCTURAL factory (outside a run) still computes the journey")
	fun unpreparedFactoryComputesTheJourney() {
		loadNetworkContext(NetworkResources.VYHYBNA_XML).use { context ->
			val (spy, query) = countingContext(context)
			val agentService = KoogAgentFactoryTest.CapturingAgentService()

			runBlocking { factory(agentService, PromptVariant.REVISED_STRUCTURAL, SinkHolder()).createAgent(spy) }

			assertThat(query.calls.get()).isGreaterThan(0)
			assertThat(agentService.capturedSystemPrompt)
				.isNotNull()
				.contains("fromEndpointName=\"doB1\", toEndpointName=\"B\")")
		}
	}
}
