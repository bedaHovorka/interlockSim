package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Edge Existence Checking tests for AbstractUnorientedGraph.
 * Tests containsEdge method which delegates to values.contains.
 */
class AbstractUnorientedGraphEdgeExistenceTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `containsEdge returns true for existing edge`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		assertThat(graph.containsEdge(100))
			.withMessage("graph should contain edge value 100")
			.isTrue()
		assertThat(graph.containsEdge(200))
			.withMessage("graph should contain edge value 200")
			.isTrue()
	}

	@Test
	fun `containsEdge returns false for non-existent edge`() {
		graph.put("A", "B", 100)
		assertThat(graph.containsEdge(999))
			.withMessage("graph should not contain edge value 999")
			.isFalse()
	}

	@Test
	fun `containsEdge returns false on empty graph`() {
		assertThat(graph.containsEdge(100))
			.withMessage("empty graph should not contain any edge")
			.isFalse()
	}

	@Test
	fun `containsEdge finds edge after multiple additions`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		graph.put("C", "D", 300)
		graph.put("D", "E", 400)
		assertThat(graph.containsEdge(100)).isTrue()
		assertThat(graph.containsEdge(200)).isTrue()
		assertThat(graph.containsEdge(300)).isTrue()
		assertThat(graph.containsEdge(400)).isTrue()
	}

	@Test
	fun `containsEdge returns false after edge removal`() {
		graph.put("A", "B", 100)
		assertThat(graph.containsEdge(100)).isTrue()
		graph.remove("A", "B")
		assertThat(graph.containsEdge(100))
			.withMessage("removed edge should not be found")
			.isFalse()
	}

	@Test
	fun `containsEdge handles duplicate edge values correctly`() {
		graph.put("A", "B", 100)
		graph.put("C", "D", 100)
		assertThat(graph.containsEdge(100))
			.withMessage("duplicate edge values should be found")
			.isTrue()
	}
}
