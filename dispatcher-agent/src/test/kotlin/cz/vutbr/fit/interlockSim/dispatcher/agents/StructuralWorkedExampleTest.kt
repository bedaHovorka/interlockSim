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
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.EntryJourneyPicker.JourneyTarget
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.loadNetworkContext
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.Station
import cz.vutbr.fit.interlockSim.sim.separatorName
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.cellsOfType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.security.MessageDigest
import java.util.stream.Stream

/**
 * Issue #1301: the [PromptVariant.REVISED_STRUCTURAL] prompt's worked examples are one real journey
 * taken from the station's structure — the train enters at an InOut, its home signal reserves to the
 * next signal, and that signal reserves on to the train's own exit — instead of the alphabetical
 * guess [WorkedExampleSource.ALPHABETICAL] renders.
 *
 * Each hop of the journey must be a target the interlocking itself offers
 * ([cz.vutbr.fit.interlockSim.context.navigation.ReservationTargetQuery.findReservationTargetCandidates],
 * the NEXT SECTION source) and must reserve with `Success`, in journey order, under one train id on
 * a fresh station.
 */
@DisplayName("Issue #1301 — worked prompt examples taken from the station's structure")
class StructuralWorkedExampleTest : DispatcherKoinTestBase() {
	companion object {
		private const val TRAIN_ID = "Train #1301"
		private const val OTHER_TRAIN_ID = "Train #1302"

		/**
		 * SHA-256 of the whole assembled REVISED_STRUCTURAL system prompt on vyhybna (default budget
		 * 3), so a campaign jar is tied to a known prompt.
		 */
		private const val VYHYBNA_REVISED_STRUCTURAL_SYSTEM_PROMPT_SHA256 =
			"06b4e16383c07b6fd01f78e51b7f60afb8d303c614c11f4889e61fd472e6eb45"

		/** Same budget as `StationPromptGeneralisationTest.PRAHA_SYSTEM_PROMPT_TOKEN_BUDGET`. */
		private const val PRAHA_SYSTEM_PROMPT_TOKEN_BUDGET = 6_144

		/** The network each LLM example runs on (`ExampleRegistry`'s `llmShuntingLoopExample` calls). */
		private val NETWORK_BY_EXAMPLE: Map<String, String>
			get() =
				mapOf(
					ExampleNames.SHUNTING_LOOP_AI to NetworkResources.VYHYBNA_XML,
					ExampleNames.CERVENY_UJEZD_AI to NetworkResources.CERVENY_UJEZD_XML,
					ExampleNames.PRAHA_HLAVNI_NADRAZI_AI to NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML
				)

		@JvmStatic
		fun journeys(): Stream<Arguments> =
			Stream.of(
				Arguments.of(
					ExampleNames.SHUNTING_LOOP_AI,
					EntryJourney("A", SignalId("zA"), SignalId("doB1"), "B")
				),
				Arguments.of(
					ExampleNames.CERVENY_UJEZD_AI,
					EntryJourney("east_exit_1", SignalId("homeE1"), SignalId("w33"), "west_entry_2")
				),
				// S_4_20 is a generated name: the home signal of N-Bypass carries no name in the XML.
				Arguments.of(
					ExampleNames.PRAHA_HLAVNI_NADRAZI_AI,
					EntryJourney("N-Bypass", SignalId("S_4_20"), SignalId("S18"), "S-Bypass")
				)
			)

		@JvmStatic
		fun examples(): Stream<String> = NETWORK_BY_EXAMPLE.keys.stream()

		private fun sha256(text: String): String =
			MessageDigest
				.getInstance("SHA-256")
				.digest(text.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }
	}

	private fun separatorNamed(
		context: DefaultSimulationContext,
		name: String
	): DynamicPathSeparator =
		context.getInOuts().firstOrNull { it.name == name }
			?: context.getRailWayNetGrid().cellsOfType<DynamicRailSemaphore>().first { it.name == name }

	private fun renderSystemPrompt(
		xml: String,
		variant: PromptVariant
	): String =
		loadNetworkContext(xml).use { context ->
			Station.from(context).applyBlockNames()
			// A correctly-wired REVISED_STRUCTURAL factory receives the journey the Koin binding
			// computes before the run, on this building thread (the dispatcherAgentModule
			// KoogAgentFactory binding): the unprepared fallback no longer computes it, so prepare
			// it here exactly like the wiring does.
			val prepared =
				if (KoogAgentFactory.workedExampleSourceOf(variant).needsEntryJourney) {
					PreparedEntryJourney.of(context)
				} else {
					null
				}
			val agentService = KoogAgentFactoryTest.CapturingAgentService()
			val factory =
				KoogAgentFactory(
					toolRegistry = ToolGroupRegistry(),
					ollamaConfig = OllamaExecutorConfig(ollamaEndpoint = "http://127.0.0.1:1"),
					agentService = agentService,
					perceptionPort =
						mockk<NetworkPerceptionPort> { every { snapshot() } returns SimulationSnapshot.EMPTY },
					commandQueue = ActuatorCommandQueue(),
					dispatchLoopSensorPort =
						mockk<DispatchLoopSensorPort> { every { getQueuedTrains() } returns emptyList() },
					sinkHolder = SinkHolder(),
					promptVariant = variant,
					preparedEntryJourney = prepared
				)
			runBlocking { factory.createAgent(context) }
			requireNotNull(agentService.capturedSystemPrompt)
		}

	@Nested
	@DisplayName("On the three LLM stations")
	inner class OnRealStations {
		@ParameterizedTest(name = "{0}")
		@MethodSource("cz.vutbr.fit.interlockSim.dispatcher.agents.StructuralWorkedExampleTest#journeys")
		@DisplayName("describe() picks the journey the pick rule names, and STRUCTURAL renders it")
		fun structuralPathIsTaken(
			example: String,
			expected: EntryJourney
		) {
			val topology =
				loadNetworkContext(NETWORK_BY_EXAMPLE.getValue(example)).use {
					StationTopologySerializer.describe(it).copy(entryJourney = EntryJourneyPicker.journeyOf(it))
				}

			assertThat(topology.entryJourney, "$example journey").isEqualTo(expected)
			// Every hop endpoint is a listed endpoint name (S_4_20 is a generated one).
			val endpointNames = topology.inOuts + topology.signals.map { it.name }
			for (hop in expected.hops) {
				assertThat(endpointNames, "$example endpoint names").contains(hop.from)
				assertThat(endpointNames, "$example endpoint names").contains(hop.to)
			}
			val text = StationTopologySerializer.toPromptText(topology, WorkedExampleSource.STRUCTURAL)
			val next = requireNotNull(expected.nextSignal).name
			assertThat(text).contains(
				"to start a train that entered at \"${expected.entry}\" and is bound for \"${expected.destination}\", " +
					"call request_route(trainName=<exact train id from this cycle's message>, " +
					"fromEndpointName=\"${expected.homeSignal.name}\", toEndpointName=\"$next\")"
			)
			assertThat(text).contains("fromEndpointName=\"$next\", toEndpointName=\"${expected.destination}\")")
			assertThat(text).contains(
				"EXAMPLE (next section — the same train continues; one section holds fewer blocks): request_route("
			)
			assertThat(text).contains("\"${expected.destination}\" is that train's own destination")
			// Never a request from an InOut origin, never the alphabetical guess.
			for (inOut in topology.inOuts) {
				assertThat(text).doesNotContain("fromEndpointName=\"$inOut\"")
			}
			val alphabetical = StationTopologySerializer.toPromptText(topology.copy(entryJourney = null))
			assertThat(alphabetical).doesNotContain(expected.homeSignal.name + "\", toEndpointName")
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("cz.vutbr.fit.interlockSim.dispatcher.agents.StructuralWorkedExampleTest#journeys")
		@DisplayName("every hop's target is a candidate the interlocking offers from that hop's origin, and the hops chain")
		fun hopsAreOfferedCandidatesAndChain(
			example: String,
			expected: EntryJourney
		) {
			loadNetworkContext(NETWORK_BY_EXAMPLE.getValue(example)).use { context ->
				val query = context.getRoutingServices().getReservationTargetQuery()
				val hops = expected.hops

				assertThat(hops.map { it.from to it.to }, "$example hops").isEqualTo(
					listOf(
						expected.homeSignal.name to requireNotNull(expected.nextSignal).name,
						expected.nextSignal.name to requireNotNull(expected.destination)
					)
				)

				fun offered(from: String) =
					query.findReservationTargetCandidates(separatorNamed(context, from) as OrientedPathSeparator, null)
				for (hop in hops) {
					assertThat(offered(hop.from).map { separatorName(it.separator) }, "$example candidates from ${hop.from}")
						.contains(hop.to)
				}
				assertThat(hops[1].from, "hop 2 starts where hop 1 ends").isEqualTo(hops[0].to)
				// The pick rule, checked independently: hop 1 ends at the FIRST signal offered from the
				// home signal, hop 2 at the FIRST InOut offered from that signal (query order).
				assertThat(
					offered(hops[0].from).first { it.separator is DynamicRailSemaphore }.separator.let(::separatorName),
					"$example first signal candidate"
				).isEqualTo(hops[0].to)
				assertThat(
					offered(hops[1].from).first { it.separator is DynamicInOut }.separator.let(::separatorName),
					"$example first InOut candidate"
				).isEqualTo(hops[1].to)
				// The home signal, checked independently from the station derivation.
				val entryBlock = Station.from(context).entryExitBlocks.single { b -> b.inOuts.any { it.name == expected.entry } }
				assertThat(entryBlock.signals.single().name, "$example home signal").isEqualTo(expected.homeSignal.name)
			}
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("cz.vutbr.fit.interlockSim.dispatcher.agents.StructuralWorkedExampleTest#journeys")
		@DisplayName("reservePath succeeds for hop 1 and then hop 2 under one train id on a fresh station")
		fun hopsReserveInJourneyOrder(
			example: String,
			expected: EntryJourney
		) {
			loadNetworkContext(NETWORK_BY_EXAMPLE.getValue(example)).use { context ->
				val service = context.getRoutingServices().getPathReservationService()
				// Per hop: the blocks the hop's Success reports (for the overlap check) and the blocks the hop
				// newly added to the train (for the "adds blocks" check). A delta alone cannot show overlap: a
				// block reserved by hop 1 and reused by hop 2 is not in hop 2's delta.
				val hopResults =
					expected.hops.mapIndexed { index, hop ->
						val before = service.getReservedBlocks(TRAIN_ID).toSet()
						val result =
							service.reservePath(TRAIN_ID, separatorNamed(context, hop.from), separatorNamed(context, hop.to))
						assertThat(result, "$example hop ${index + 1} ${hop.from} -> ${hop.to}")
							.isInstanceOf<PathReservationService.ReservationResult.Success>()
						val reported = (result as PathReservationService.ReservationResult.Success).reservedBlocks.toSet()
						reported to (service.getReservedBlocks(TRAIN_ID).toSet() - before)
					}
				assertThat(hopResults.all { it.second.isNotEmpty() }, "$example every hop adds blocks to the train").isTrue()
				assertThat(hopResults[0].first.intersect(hopResults[1].first), "$example hop 2 blocks disjoint from hop 1")
					.isEmpty()
			}
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("cz.vutbr.fit.interlockSim.dispatcher.agents.StructuralWorkedExampleTest#journeys")
		@DisplayName("the journey is static structure: another train's reservation of hop 1 does not change it")
		fun journeyIsIndependentOfReservationState(
			example: String,
			expected: EntryJourney
		) {
			loadNetworkContext(NETWORK_BY_EXAMPLE.getValue(example)).use { context ->
				val hop = expected.hops.first()
				val result =
					context
						.getRoutingServices()
						.getPathReservationService()
						.reservePath(OTHER_TRAIN_ID, separatorNamed(context, hop.from), separatorNamed(context, hop.to))
				assertThat(result, "$example hop 1 reserved by another train")
					.isInstanceOf<PathReservationService.ReservationResult.Success>()

				assertThat(EntryJourneyPicker.journeyOf(context), "$example journey with hop 1 reserved").isEqualTo(expected)
			}
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("cz.vutbr.fit.interlockSim.dispatcher.agents.StructuralWorkedExampleTest#examples")
		@DisplayName("the assembled REVISED_STRUCTURAL prompt keeps REVISED's instructions and changes only the examples")
		fun onlyTheExamplesDiffer(example: String) {
			val xml = NETWORK_BY_EXAMPLE.getValue(example)
			val revised = renderSystemPrompt(xml, PromptVariant.REVISED)
			val structural = renderSystemPrompt(xml, PromptVariant.REVISED_STRUCTURAL)
			val marker = "Routes (path context only"
			// The first worked example's header — appendEntryRouteExample's opening, the fixed literal
			// both variants' assembly guarantees on a signalled station — is the stable boundary after
			// the Routes region.
			val firstExampleHeader = "EXAMPLE (entry route). Never invent a train name"

			assertThat(structural.substringBefore(marker)).isEqualTo(revised.substringBefore(marker))
			// The Routes region itself is variant-independent too, byte for byte: from the Routes
			// marker through to the first worked example's header, both prompts render the same route
			// list (and, when the budget caps it, the same closing note).
			assertThat(structural).contains(firstExampleHeader)
			assertThat(revised).contains(firstExampleHeader)
			assertThat(structural.substringAfter(marker).substringBefore(firstExampleHeader))
				.isEqualTo(revised.substringAfter(marker).substringBefore(firstExampleHeader))
			assertThat(structural).contains("is that train's own destination")
			assertThat(revised).doesNotContain("is that train's own destination")
			println(
				"PromptTokenEstimate $example: REVISED=${PromptTokenEstimate.of(revised)} " +
					"REVISED_STRUCTURAL=${PromptTokenEstimate.of(structural)}"
			)
		}

		@Test
		@DisplayName("the Praha REVISED_STRUCTURAL system prompt stays within its token budget")
		fun prahaStructuralPromptFitsTheTokenBudget() {
			val prompt =
				renderSystemPrompt(
					NETWORK_BY_EXAMPLE.getValue(ExampleNames.PRAHA_HLAVNI_NADRAZI_AI),
					PromptVariant.REVISED_STRUCTURAL
				)

			assertThat(PromptTokenEstimate.of(prompt)).isLessThanOrEqualTo(PRAHA_SYSTEM_PROMPT_TOKEN_BUDGET)
		}

		@Test
		@DisplayName("vyhybna's assembled REVISED_STRUCTURAL system prompt is pinned by SHA-256")
		fun vyhybnaStructuralPromptIsPinned() {
			val prompt =
				renderSystemPrompt(
					NETWORK_BY_EXAMPLE.getValue(ExampleNames.SHUNTING_LOOP_AI),
					PromptVariant.REVISED_STRUCTURAL
				)

			assertThat(sha256(prompt)).isEqualTo(VYHYBNA_REVISED_STRUCTURAL_SYSTEM_PROMPT_SHA256)
		}
	}

	@Nested
	@DisplayName("The pick rule")
	inner class PickRule {
		private fun signal(name: String) = JourneyTarget(name, isSignal = true)

		private fun exit(name: String) = JourneyTarget(name, isSignal = false)

		@Test
		@DisplayName("an InOut without a home signal is skipped for the next one, in name order")
		fun inOutWithoutHomeSignalIsSkipped() {
			val journey =
				EntryJourneyPicker.pickEntryJourney(
					homeSignalByInOut = mapOf("B" to "zB", "A" to null),
					targetsOf = mapOf("zB" to listOf(signal("doA1")), "doA1" to listOf(exit("A")))::getValue
				)

			assertThat(journey).isEqualTo(EntryJourney("B", SignalId("zB"), SignalId("doA1"), "A"))
		}

		@Test
		@DisplayName("an unnamed InOut is skipped")
		fun unnamedInOutIsSkipped() {
			val journey =
				EntryJourneyPicker.pickEntryJourney(
					homeSignalByInOut = mapOf(" " to "x", "B" to "zB"),
					targetsOf = mapOf("zB" to listOf(signal("s")), "s" to listOf(exit("A")))::getValue
				)

			assertThat(journey).isNotNull().transform { it.entry }.isEqualTo("B")
		}

		@Test
		@DisplayName("an InOut whose home signal offers no signal is skipped when another InOut offers one")
		fun homeSignalWithoutSignalCandidateIsSkipped() {
			val journey =
				EntryJourneyPicker.pickEntryJourney(
					homeSignalByInOut = mapOf("A" to "zA", "B" to "zB"),
					targetsOf =
						mapOf(
							"zA" to listOf(exit("B")),
							"zB" to listOf(exit("A"), signal("s1"), signal("s2")),
							"s1" to listOf(signal("x"), exit("A"))
						)::getValue
				)

			// The first SEMAPHORE candidate, then the first IN_OUT candidate from it.
			assertThat(journey).isEqualTo(EntryJourney("B", SignalId("zB"), SignalId("s1"), "A"))
		}

		@Test
		@DisplayName("a one-signal station renders the single hop home signal -> exit")
		fun oneSignalStationIsASingleHop() {
			val journey =
				EntryJourneyPicker.pickEntryJourney(
					homeSignalByInOut = mapOf("A" to "zA", "B" to null),
					targetsOf = mapOf("zA" to listOf(exit("B")))::getValue
				)

			assertThat(journey).isEqualTo(EntryJourney("A", SignalId("zA"), nextSignal = null, destination = "B"))
			assertThat(requireNotNull(journey).hops.map { it.from to it.to }).isEqualTo(listOf("zA" to "B"))
		}

		@Test
		@DisplayName("a next signal that offers no exit leaves hop 1 alone")
		fun missingSecondHopLeavesTheFirst() {
			val journey =
				EntryJourneyPicker.pickEntryJourney(
					homeSignalByInOut = mapOf("A" to "zA"),
					targetsOf = mapOf("zA" to listOf(signal("s")), "s" to emptyList())::getValue
				)

			assertThat(journey).isEqualTo(EntryJourney("A", SignalId("zA"), SignalId("s"), destination = null))
			assertThat(requireNotNull(journey).hops.map { it.from to it.to }).isEqualTo(listOf("zA" to "s"))
		}

		@Test
		@DisplayName("no qualifying InOut yields no journey — never an alphabetical fallback")
		fun noQualifyingInOutYieldsNull() {
			val journey =
				EntryJourneyPicker.pickEntryJourney(
					homeSignalByInOut = mapOf("A" to null, "B" to "zB"),
					targetsOf = mapOf("zB" to emptyList<JourneyTarget>())::getValue
				)

			assertThat(journey).isNull()
		}

		@Test
		@DisplayName("a journey must have a second endpoint")
		fun journeyNeedsASecondEndpoint() {
			assertThrows<IllegalArgumentException> { EntryJourney("A", SignalId("zA"), null, null) }
		}
	}

	@Nested
	@DisplayName("STRUCTURAL rendering of rare shapes")
	inner class RareShapes {
		private fun topology(
			signals: List<String>,
			journey: EntryJourney?
		) = StationTopology(
			inOuts = listOf("A", "B"),
			signals = signals.map { SignalId(it) },
			switches = emptyList(),
			blocks = listOf(BlockId("kA")),
			routes = emptyList(),
			entryJourney = journey
		)

		@Test
		@DisplayName("no journey on a signalled station renders only the rule sentence, no guessed names")
		fun noJourneyRendersOnlyTheRule() {
			val text = StationTopologySerializer.toPromptText(topology(listOf("s1", "s2"), null), WorkedExampleSource.STRUCTURAL)

			assertThat(text).contains("A route reserves ONE section at a time, never the whole station")
			assertThat(text).doesNotContain("request_route(trainName=")
			assertThat(text).contains("Do NOT pass \"kA\"")
		}

		@Test
		@DisplayName("a single-hop journey renders home signal -> exit with the train's destination")
		fun singleHopJourney() {
			val text =
				StationTopologySerializer.toPromptText(
					topology(listOf("zA"), EntryJourney("A", SignalId("zA"), null, "B")),
					WorkedExampleSource.STRUCTURAL
				)

			assertThat(text).contains("entered at \"A\" and is bound for \"B\"")
			assertThat(text).contains("fromEndpointName=\"zA\", toEndpointName=\"B\")")
			assertThat(text).doesNotContain("EXAMPLE (next section")
		}

		@Test
		@DisplayName("hop 1 without a known exit names no destination")
		fun firstHopOnly() {
			val text =
				StationTopologySerializer.toPromptText(
					topology(listOf("zA", "s"), EntryJourney("A", SignalId("zA"), SignalId("s"), null)),
					WorkedExampleSource.STRUCTURAL
				)

			assertThat(text).contains("to start a train that entered at \"A\", call request_route(")
			assertThat(text).contains("fromEndpointName=\"zA\", toEndpointName=\"s\")")
			assertThat(text).doesNotContain("bound for")
			assertThat(text).doesNotContain("EXAMPLE (next section")
		}

		@Test
		@DisplayName("a station without signals keeps the whole-network example")
		fun noSignalsKeepsTheFullSpanExample() {
			val text = StationTopologySerializer.toPromptText(topology(emptyList(), null), WorkedExampleSource.STRUCTURAL)

			assertThat(text).contains("EXAMPLE (whole-network route")
		}
	}

	@Test
	@DisplayName("each prompt variant maps to its worked-example source")
	fun variantMapsToExampleSource() {
		assertThat(KoogAgentFactory.workedExampleSourceOf(PromptVariant.BASELINE)).isEqualTo(WorkedExampleSource.ALPHABETICAL)
		assertThat(KoogAgentFactory.workedExampleSourceOf(PromptVariant.REVISED)).isEqualTo(WorkedExampleSource.ALPHABETICAL)
		assertThat(KoogAgentFactory.workedExampleSourceOf(PromptVariant.REVISED_STRUCTURAL))
			.isEqualTo(WorkedExampleSource.STRUCTURAL)
		assertThat(KoogAgentFactory.buildSystemPrompt(3, PromptVariant.REVISED_STRUCTURAL, 2))
			.isEqualTo(KoogAgentFactory.buildSystemPrompt(3, PromptVariant.REVISED, 2))
	}
}
