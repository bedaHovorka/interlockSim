package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Values Collection tests for AbstractUnorientedGraph.
 * Tests values method used by containsEdge.
 */
class AbstractUnorientedGraphValuesCollectionTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `values returns empty collection for empty graph`() {
		val values = graph.values()
		assertThat(values)
			.withMessage("values should be empty for empty graph")
			.isEmpty()
	}

	@Test
	fun `values returns all edge values`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		graph.put("C", "D", 300)
		val values = graph.values()
		assertThat(values.size)
			.withMessage("values should contain all edge values")
			.isEqualTo(3)
		assertThat(values.contains(100)).isTrue()
		assertThat(values.contains(200)).isTrue()
		assertThat(values.contains(300)).isTrue()
	}

	@Test
	fun `values reflects graph changes`() {
		graph.put("A", "B", 100)
		val values1 = graph.values()
		assertThat(values1.size).isEqualTo(1)
		graph.put("B", "C", 200)
		val values2 = graph.values()
		assertThat(values2.size)
			.withMessage("values should reflect new edges")
			.isEqualTo(2)
	}
}
