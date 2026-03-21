package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Edge Cases and Reflection Behavior tests for AbstractUnorientedGraph.
 * Tests boundary conditions and special scenarios.
 */
class AbstractUnorientedGraphEdgeCasesTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `operations on empty graph are safe`() {
		assertThat(graph.isEmpty()).isTrue()
		assertThat(graph.size()).isZero()
		assertThat(graph.containsEdge(100)).isFalse()
		assertThat(graph.toString()).isNotEmpty()
		assertThat(graph.values()).isEmpty()
		graph.clear()
		assertThat(graph.isEmpty()).isTrue()
	}

	@Test
	fun `single edge graph operations work correctly`() {
		graph.put("A", "B", 100)
		assertThat(graph.isEmpty()).isFalse()
		assertThat(graph.size()).isEqualTo(1)
		assertThat(graph.containsEdge(100)).isTrue()
		assertThat(graph.values().size).isEqualTo(1)
		graph.clear()
		assertThat(graph.isEmpty()).isTrue()
	}

	@Test
	fun `size uses reflection successfully`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		assertThat(graph.size())
			.withMessage("size should use reflection to get underlying map size")
			.isEqualTo(2)
	}

	@Test
	fun `clear uses reflection successfully`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		graph.put("C", "D", 300)
		graph.clear()
		assertThat(graph.size())
			.withMessage("clear should use reflection to clear underlying map")
			.isZero()
	}

	@Test
	fun `containsEdge uses values method correctly`() {
		graph.put("A", "B", 100)
		val viaContainsEdge = graph.containsEdge(100)
		val viaValues = graph.values().contains(100)
		assertThat(viaContainsEdge)
			.withMessage("containsEdge should delegate to values contains")
			.isEqualTo(viaValues)
	}

	@Test
	fun `toString delegates to implementationContainer`() {
		graph.put("A", "B", 100)
		val string = graph.toString()
		assertThat(string)
			.withMessage("toString should delegate to implementation container")
			.isNotEmpty()
	}

	@Test
	fun `graph handles large number of edges`() {
		for (i in 0 until 100) {
			graph.put("Node$i", "Node${i + 1}", i)
		}
		assertThat(graph.size()).isEqualTo(100)
		assertThat(graph.isEmpty()).isFalse()
		graph.clear()
		assertThat(graph.size()).isZero()
		assertThat(graph.isEmpty()).isTrue()
	}
}
