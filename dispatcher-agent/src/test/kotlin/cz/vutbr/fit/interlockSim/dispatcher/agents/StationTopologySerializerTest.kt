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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.dispatcherAgentTestModule
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchId
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Unit tests for [StationTopologySerializer] (SP2b.8, Issue #695).
 *
 * Verifies that the static topology of `vyhybna.xml` is serialized into the smallest sufficient
 * form using SP3.2 compact IDs, and that the rendered prompt text only carries static structure.
 *
 * @since Issue #695 (SP2b.8 — Goal 10)
 */
@DisplayName("StationTopologySerializer — static topology into LLM context (SP2b.8, #695)")
class StationTopologySerializerTest {
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

	@Test
	@DisplayName("describe() extracts vyhybna InOuts, signals and switches with SP3.2 IDs")
	fun describeExtractsStaticElements() {
		loadShuntingLoopContext().use { context ->
			val topology = StationTopologySerializer.describe(context)

			assertThat(topology.inOuts).isEqualTo(listOf("A", "B"))

			assertThat(topology.signals).containsAll(
				SignalId("doA1"),
				SignalId("doA2"),
				SignalId("doB1"),
				SignalId("doB2"),
				SignalId("zA"),
				SignalId("zB")
			)

			assertThat(topology.switches.map { it.id }).containsAll(SwitchId("vA"), SwitchId("vB"))
			assertThat(topology.switches.first { it.id == SwitchId("vA") }.type).isEqualTo("SIMPLE_RIGHT_FALSE")
			assertThat(topology.switches.first { it.id == SwitchId("vB") }.type).isEqualTo("SIMPLE_LEFT_TRUE")

			assertThat(topology.blocks).isNotEmpty()
		}
	}

	@Test
	@DisplayName("describe() enumerates topologically valid routes between InOuts")
	fun describeEnumeratesRoutes() {
		loadShuntingLoopContext().use { context ->
			val topology = StationTopologySerializer.describe(context)

			assertThat(topology.routes).isNotEmpty()
			val directions = topology.routes.map { it.from to it.to }.toSet()
			assertThat(directions).contains("A" to "B")
			assertThat(directions).contains("B" to "A")
			topology.routes.forEach { route ->
				assertThat(route.blocks).isNotEmpty()
			}
		}
	}

	@Test
	@DisplayName("serialize() renders a compact ID-only prompt block for vyhybna")
	fun serializeRendersCompactPrompt() {
		loadShuntingLoopContext().use { context ->
			val prompt = StationTopologySerializer.serialize(context)

			assertThat(prompt).contains("STATION TOPOLOGY")
			assertThat(prompt).contains("InOuts (entry/exit): A, B")
			assertThat(prompt).contains("vA[SIMPLE_RIGHT_FALSE]")
			assertThat(prompt).contains("vB[SIMPLE_LEFT_TRUE]")
			assertThat(prompt).contains("doA1")
			assertThat(prompt).contains("A->B")
			assertThat(prompt).contains("NOT valid request_route arguments")
		}
	}

	@Test
	@DisplayName("toPromptText() renders 'none' for an empty topology (no context needed)")
	fun toPromptTextHandlesEmptyTopology() {
		val empty =
			StationTopology(
				inOuts = emptyList(),
				signals = emptyList(),
				switches = emptyList(),
				blocks = emptyList(),
				routes = emptyList()
			)

		val text = StationTopologySerializer.toPromptText(empty)

		assertThat(text).contains("InOuts (entry/exit): none")
		assertThat(text).contains("Signals: none")
		assertThat(text).contains("Switches: none")
		assertThat(text).contains(
			"Blocks (IDs for block_occupancy/all_block_occupancies only — NOT valid request_route arguments): none"
		)
		assertThat(text).contains("Routes (path context only; block IDs shown are NOT valid request_route arguments;")
		assertThat(text).contains(": none")
	}

	@Test
	@DisplayName("toPromptText() renders switch type, block list and routes (no context needed)")
	fun toPromptTextRendersPopulatedTopology() {
		val topology =
			StationTopology(
				inOuts = listOf("A", "B"),
				signals = listOf(SignalId("L1")),
				switches = listOf(SwitchDescriptor(SwitchId("V7"), "SIMPLE_LEFT_TRUE")),
				blocks = listOf(BlockId("U3"), BlockId("U4")),
				routes = listOf(RouteDescriptor("A", "B", listOf(BlockId("U3"), BlockId("U4"))))
			)

		val text = StationTopologySerializer.toPromptText(topology)

		assertThat(text).contains("Signals: L1")
		assertThat(text).contains("Switches: V7[SIMPLE_LEFT_TRUE]")
		assertThat(text).contains(
			"Blocks (IDs for block_occupancy/all_block_occupancies only — NOT valid request_route arguments): U3, U4"
		)
		assertThat(text).contains("A->B: U3, U4")
	}

	@Test
	@DisplayName("toPromptText() notes the per-pair route cap (no context needed)")
	fun toPromptTextNotesRouteCap() {
		val topology =
			StationTopology(
				inOuts = listOf("A", "B"),
				signals = emptyList(),
				switches = emptyList(),
				blocks = emptyList(),
				routes = listOf(RouteDescriptor("A", "B", listOf(BlockId("U1"))))
			)

		val text = StationTopologySerializer.toPromptText(topology)

		assertThat(text).contains("Routes (path context only; block IDs shown are NOT valid request_route arguments;")
		assertThat(text).contains("more may exist)")
		assertThat(text).contains("A->B: U1")
	}

	@Test
	@DisplayName("empty-topology text carries no dynamic-state keywords")
	fun promptOmitsDynamicState() {
		val empty =
			StationTopology(
				inOuts = emptyList(),
				signals = emptyList(),
				switches = emptyList(),
				blocks = emptyList(),
				routes = emptyList()
			)

		val text = StationTopologySerializer.toPromptText(empty).lowercase()

		// Static topology only: no occupancy/reservation/pending-request wording.
		assertThat(text.contains("occupied")).isEqualTo(false)
		assertThat(text.contains("reserved")).isEqualTo(false)
		assertThat(text.contains("pending")).isEqualTo(false)
	}

	@Test
	@DisplayName("distinct IDs are de-duplicated in describe() output")
	fun describeProducesDistinctIds() {
		loadShuntingLoopContext().use { context ->
			val topology = StationTopologySerializer.describe(context)

			assertThat(topology.signals.distinct()).isEqualTo(topology.signals)
			assertThat(topology.blocks.distinct()).isEqualTo(topology.blocks)
			assertThat(topology.switches.map { it.id }.distinct()).isEqualTo(topology.switches.map { it.id })
			assertThat(topology.inOuts.filter { it.isBlank() }).isEmpty()
		}
	}
}
