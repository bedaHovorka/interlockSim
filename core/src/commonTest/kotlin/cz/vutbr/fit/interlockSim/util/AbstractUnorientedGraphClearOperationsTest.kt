package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Clear Operations tests for AbstractUnorientedGraph.
 * Tests clear method which uses reflection to call implementationContainer.clear.
 */
class AbstractUnorientedGraphClearOperationsTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `clear removes all edges from graph`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		graph.put("C", "D", 300)
		assertThat(graph.size()).isEqualTo(3)
		graph.clear()
		assertThat(graph.size())
			.withMessage("size should be 0 after clear")
			.isZero()
		assertThat(graph.isEmpty())
			.withMessage("graph should be empty after clear")
			.isTrue()
	}

	@Test
	fun `clear on empty graph is safe`() {
		graph.clear()
		assertThat(graph.size()).isZero()
		assertThat(graph.isEmpty()).isTrue()
	}

	@Test
	fun `clear removes all node associations`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		graph.clear()
		assertThat(graph.get("A", "B"))
			.withMessage("edge A-B should not exist after clear")
			.isEqualTo(null)
		assertThat(graph.get("B", "C"))
			.withMessage("edge B-C should not exist after clear")
			.isEqualTo(null)
	}

	@Test
	fun `graph is usable after clear`() {
		graph.put("A", "B", 100)
		graph.clear()
		graph.put("C", "D", 300)
		assertThat(graph.size())
			.withMessage("graph should be usable after clear")
			.isEqualTo(1)
		assertThat(graph.get("C", "D"))
			.withMessage("new edges should be added after clear")
			.isEqualTo(300)
	}

	@Test
	fun `clear called multiple times is safe`() {
		graph.put("A", "B", 100)
		graph.clear()
		graph.clear()
		graph.clear()
		assertThat(graph.size()).isZero()
		assertThat(graph.isEmpty()).isTrue()
	}
}
