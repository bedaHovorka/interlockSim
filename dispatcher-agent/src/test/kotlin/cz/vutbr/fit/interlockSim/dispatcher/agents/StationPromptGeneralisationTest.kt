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

import ai.koog.agents.core.agent.AIAgent
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.loadNetworkContext
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.Station
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.security.MessageDigest
import java.util.stream.Stream

/**
 * Goal 1B acceptance B6 (Issue #1155, SP8): the dispatcher prompt and the four tool descriptions,
 * rendered for a station other than vyhybna, carry no vyhybna literal and state that station's own
 * capacity; the Praha prompt fits its token budget; and the frozen BASELINE text is unchanged.
 *
 * Every prompt here is assembled by the real [KoogAgentFactory.createAgent] (the
 * [KoogAgentFactoryTest.CapturingAgentService] seam), after [Station.applyBlockNames] — the naming
 * step every Station-based loop performs before the agent is built, so the Blocks list is the one a
 * real run renders. Ollama is never contacted: the warm-up targets a closed port and is non-fatal.
 */
@DisplayName("Issue #1155 — station-generic dispatcher prompts and tool descriptions (B6)")
class StationPromptGeneralisationTest : DispatcherKoinTestBase() {
	companion object {
		/** The vyhybna literals B6 forbids in any prompt or tool description rendered for another station. */
		private val VYHYBNA_LITERALS = listOf("vyhybna", "ShuntingLoop", "\"kA\"")

		/** Červený Újezd's topology capacity: 4 station tracks (SP3, docs/goal9b-demands). */
		private const val CERVENY_UJEZD_CAPACITY = 4

		/** Praha hlavní nádraží's topology capacity: 8 platform tracks and the bypass track (SP2). */
		private const val PRAHA_CAPACITY = 9

		/**
		 * Budget for the whole Praha system prompt (instructions + STATION TOPOLOGY), in
		 * [PromptTokenEstimate] tokens: three eighths of the 16,384-token context window, so the
		 * tool schemas, the cycle message with its history and outcome blocks, and the reply keep
		 * the other five eighths. Unbounded, the Praha prompt was 31,291 real Qwen2.5 tokens.
		 */
		private const val PRAHA_SYSTEM_PROMPT_TOKEN_BUDGET = 6_144

		/**
		 * SHA-256 of `buildSystemPrompt(maxActions, BASELINE)` for maxActions 1..5 on vyhybna's cap 2,
		 * taken from the unchanged code before Issue #1155 (commit cfec5139).
		 */
		private val BASELINE_SHA256_BY_MAX_ACTIONS =
			mapOf(
				1 to "52a80040e1f99b0992f8441c15252a7f7c3b125c5ab6dba05e025baf48d70160",
				2 to "36e3f0f7a039a7636fc224390be633c51b964a77a6f61d2ef82a37731cc18e1c",
				3 to "639e6416237f01b8a541c325087e5eefc99fbc9eba4fe396028b3e4387826c3a",
				4 to "72098ce3391ee70c53a12db0e22b480f4847578391165ae0ed0c18770cb90b4c",
				5 to "0fbd726a36ba5dbf3afdc6d789e79a93846b56c265a983c98ffcb9f639d10793"
			)

		/** vyhybna's capacity, the value BASELINE was frozen with. */
		private const val VYHYBNA_CAPACITY = 2

		/**
		 * SHA-256 of the whole assembled REVISED system prompt on vyhybna (default budget 3), taken
		 * before Issue #1155: generalising the prompt must not change what the Goal 10 runs see.
		 */
		private const val VYHYBNA_REVISED_SYSTEM_PROMPT_SHA256 =
			"8a442cca7790c1c01ba9b29a43f2cfe1a4a3638382f7b95d7337b5f101f6c942"

		@JvmStatic
		fun stations(): Stream<Arguments> =
			Stream.of(
				Arguments.of("Červený Újezd", NetworkResources.CERVENY_UJEZD_XML, CERVENY_UJEZD_CAPACITY),
				Arguments.of("Praha hlavní nádraží", NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, PRAHA_CAPACITY)
			)

		private fun sha256(text: String): String =
			MessageDigest
				.getInstance("SHA-256")
				.digest(text.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }
	}

	private class Rendered(
		val systemPrompt: String,
		val toolText: String,
		val capturedCapacity: Int?,
		val topologyCapacity: Int
	)

	private fun render(
		xml: String,
		declaredCapacity: StationCapacity? = null
	): Rendered =
		loadNetworkContext(xml).use { context ->
			val station = Station.from(context)
			station.applyBlockNames()
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
					promptVariant = PromptVariant.REVISED,
					stationCapacityProvider = { declaredCapacity }
				)
			runBlocking { factory.createAgent(context) }
			val toolText =
				requireNotNull(agentService.capturedTools).joinToString("\n") { tool ->
					tool.name + ": " + tool.description + "\n" +
						tool.parameters.joinToString("\n") { "  " + it.name + ": " + it.description }
				}
			Rendered(
				systemPrompt = requireNotNull(agentService.capturedSystemPrompt),
				toolText = toolText,
				capturedCapacity = agentService.capturedStationCapacity,
				topologyCapacity = station.topologyCapacity
			)
		}

	@ParameterizedTest(name = "{0}")
	@MethodSource("stations")
	@DisplayName("the REVISED system prompt and every tool description hold no vyhybna literal")
	fun noVyhybnaLiteral(
		station: String,
		xml: String,
		capacity: Int
	) {
		val rendered = render(xml)

		for (literal in VYHYBNA_LITERALS) {
			assertThat(rendered.systemPrompt, "$station system prompt").doesNotContain(literal)
			assertThat(rendered.toolText, "$station tool descriptions").doesNotContain(literal)
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("stations")
	@DisplayName("the capacity stated in the system prompt and handed to the cycle message is the topology value")
	fun capacityIsTheTopologyValue(
		station: String,
		xml: String,
		capacity: Int
	) {
		val rendered = render(xml)

		assertThat(rendered.topologyCapacity, "$station topology capacity").isEqualTo(capacity)
		assertThat(rendered.systemPrompt).contains("fewer than $capacity trains are currently active")
		assertThat(rendered.systemPrompt).contains("up to $capacity total active")
		assertThat(rendered.capturedCapacity, "capacity handed to the agent").isEqualTo(capacity)
	}

	@Test
	@DisplayName("a cap declared by the running loop (scenario override) wins over the topology value")
	fun declaredLoopCapacityWins() {
		val rendered = render(NetworkResources.CERVENY_UJEZD_XML, StationCapacity(3))

		assertThat(rendered.systemPrompt).contains("fewer than 3 trains are currently active")
		assertThat(rendered.capturedCapacity).isEqualTo(3)
	}

	@Test
	@DisplayName("a network without a station track falls back to the default cap instead of stating 0")
	fun noStationTrackFallsBackToTheDefault() {
		val rendered = render(NetworkResources.LINEAR_TRACK_XML)

		assertThat(rendered.topologyCapacity).isEqualTo(0)
		assertThat(rendered.capturedCapacity).isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
	}

	@Test
	@DisplayName("the request_route warnings name one of the station's own blocks, the same one the topology names")
	fun blockIdExampleComesFromTheStation() {
		val rendered = render(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)

		assertThat(rendered.systemPrompt).contains("(e.g. \"kN-Bypass\" vs InOut \"N-Bypass\")")
		assertThat(rendered.toolText).contains("such as \"kN-Bypass\"")
	}

	@Test
	@DisplayName("the rendered Praha system prompt stays within its token budget, routes capped with a note")
	fun prahaPromptFitsTheTokenBudget() {
		val prompt = render(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).systemPrompt

		assertThat(PromptTokenEstimate.of(prompt)).isLessThanOrEqualTo(PRAHA_SYSTEM_PROMPT_TOKEN_BUDGET)
		assertThat(prompt).contains("more routes not shown, to keep this prompt within its token budget")

		// StationTopologySerializer.appendRoutesWithinBudget documents one-route-per-pair-first: every
		// ordered InOut pair gets its first route before any pair gets its second. The rank of each
		// shown line within its own pair (0 = a pair's first route, 1 = its second, ...) must therefore
		// be non-decreasing — the original per-pair adjacency, or any drop of the round-robin, repeats
		// one pair's later routes first and fails this pin. The >= 2 guard keeps it from passing
		// vacuously on a section that shows a single pair.
		val routePairs = boundedRoutePairs(prompt)
		val rankByPair = mutableMapOf<String, Int>()
		val rankWithinPair =
			routePairs.map { pair ->
				val rank = rankByPair.getOrDefault(pair, 0)
				rankByPair[pair] = rank + 1
				rank
			}
		assertThat(routePairs.distinct().size, "distinct ordered InOut pairs among the shown routes")
			.isGreaterThanOrEqualTo(2)
		assertThat(rankWithinPair, "route rank within its ordered pair, in shown order")
			.isEqualTo(rankWithinPair.sorted())
	}

	/**
	 * The ordered InOut pairs (`from->to`) of the bounded "Routes" portion of the STATION TOPOLOGY
	 * section: the lines between the `Routes (...)` header and the section's closing note, in shown
	 * order. Route lines are rendered as `  from->to: <block ids>` (unquoted, [StationTopologySerializer]);
	 * the closing note is the only later line indented by two spaces before the worked example.
	 */
	private fun boundedRoutePairs(prompt: String): List<String> =
		prompt
			.lineSequence()
			.dropWhile { !it.startsWith("Routes (path context only") }
			.drop(1)
			.takeWhile { !it.startsWith("  (") }
			.filter { "->" in it }
			.map { it.trim().substringBefore(": ") }
			.toList()

	@Test
	@DisplayName("PromptTokenEstimate counts one piece per word, per digit and per punctuation mark, as documented")
	fun promptTokenEstimateFollowsItsDocumentedCountingRules() {
		// Id-shaped (the PromptTokenEstimate KDoc's own example): "SW" is one letter run, then every
		// digit, hyphen and underscore counts on its own — "SW" "1" "0" "-" "S" "_" "8" "_" "8" — which
		// is why the estimator is piece-aware instead of a characters-per-token ratio.
		assertThat(PromptTokenEstimate.of("SW10-S_8_8")).isEqualTo(9)

		// Prose: one piece per word plus one per punctuation mark, whitespace merged into the word after
		// it — "Reserve" "ONE" "section" "at" "a" "time" "," "never" "the" "whole" "station" ".".
		assertThat(PromptTokenEstimate.of("Reserve ONE section at a time, never the whole station."))
			.isEqualTo(12)
	}

	@Test
	@DisplayName("vyhybna's assembled REVISED system prompt is byte-identical to before the generalisation")
	fun vyhybnaRevisedPromptUnchanged() {
		val rendered = render(NetworkResources.VYHYBNA_XML)

		assertThat(sha256(rendered.systemPrompt)).isEqualTo(VYHYBNA_REVISED_SYSTEM_PROMPT_SHA256)
		assertThat(rendered.toolText).contains("such as \"kA\"")
	}

	@Test
	@DisplayName("the frozen BASELINE text is byte-identical to before, for every action budget 1..5")
	fun baselineIsByteIdentical() {
		for ((maxActions, expected) in BASELINE_SHA256_BY_MAX_ACTIONS) {
			val baseline = KoogAgentFactory.buildSystemPrompt(maxActions, PromptVariant.BASELINE, VYHYBNA_CAPACITY)
			assertThat(sha256(baseline), "BASELINE maxActions=$maxActions").isEqualTo(expected)
		}
	}

	@Test
	@DisplayName("the cycle message states the station's capacity, not vyhybna's 2")
	fun cycleMessageStatesTheStationCapacity() {
		val aiAgent = mockk<AIAgent<String, String>>()
		coEvery { aiAgent.run(any(), null) } returns "done"
		val agent = KoogDispatchAgentImpl(aiAgent, stationCapacity = CERVENY_UJEZD_CAPACITY)
		val observation =
			DispatchObservation(
				snapshot = SimulationSnapshot.EMPTY,
				unapprovedTrains = emptyList(),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)

		runBlocking { agent.decideAsync(observation) }

		coVerify { aiAgent.run(match { it.contains("Active (approved) trains right now: 0 / 4") }, null) }
	}

	@Test
	@DisplayName("confusableBlockExample prefers the k + InOut block, skips endpoint names, falls back to any block")
	fun confusableBlockExampleRule() {
		val vyhybna =
			StationTopologySerializer.confusableBlockExample(
				inOuts = listOf("B", "A"),
				blocks = listOf("doA1-vA", "kB", "kA", "vA-zA"),
				endpointNames = setOf("A", "B", "doA1", "zA")
			)
		val collision =
			StationTopologySerializer.confusableBlockExample(
				inOuts = listOf("west"),
				blocks = listOf("e30", "k1"),
				endpointNames = setOf("west", "e30")
			)
		val none =
			StationTopologySerializer.confusableBlockExample(
				inOuts = listOf("A"),
				blocks = listOf("A"),
				endpointNames = setOf("A")
			)

		assertThat(vyhybna).isNotNull().transform { it.block to it.inOut }.isEqualTo("kA" to "A")
		assertThat(collision).isNotNull().transform { listOf(it.block, it.inOut) }.containsExactly("k1", null)
		assertThat(none).isNull()
	}
}
