package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Size Operations tests for AbstractUnorientedGraph.
 * Tests size and isEmpty methods.
 */
class AbstractUnorientedGraphSizeOperationsTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `size returns zero for empty graph`() {
		assertThat(graph.size())
			.withMessage("empty graph should have size 0")
			.isZero()
	}

	@Test
	fun `size returns correct count after adding edges`() {
		graph.put("A", "B", 100)
		assertThat(graph.size()).isEqualTo(1)
		graph.put("B", "C", 200)
		assertThat(graph.size()).isEqualTo(2)
		graph.put("C", "D", 300)
		assertThat(graph.size()).isEqualTo(3)
	}

	@Test
	fun `size decreases after removing edges`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		graph.put("C", "D", 300)
		assertThat(graph.size()).isEqualTo(3)
		graph.remove("B", "C")
		assertThat(graph.size())
			.withMessage("size should decrease after edge removal")
			.isEqualTo(2)
	}

	@Test
	fun `isEmpty returns true for empty graph`() {
		assertThat(graph.isEmpty())
			.withMessage("newly created graph should be empty")
			.isTrue()
	}

	@Test
	fun `isEmpty returns false for non-empty graph`() {
		graph.put("A", "B", 100)
		assertThat(graph.isEmpty())
			.withMessage("graph with edges should not be empty")
			.isFalse()
	}

	@Test
	fun `isEmpty returns true after clearing graph`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		assertThat(graph.isEmpty()).isFalse()
		graph.clear()
		assertThat(graph.isEmpty())
			.withMessage("graph should be empty after clear")
			.isTrue()
	}

	@Test
	fun `isEmpty consistent with size being zero`() {
		assertThat(graph.isEmpty()).isEqualTo(graph.size() == 0)
		graph.put("A", "B", 100)
		assertThat(graph.isEmpty()).isEqualTo(graph.size() == 0)
		graph.clear()
		assertThat(graph.isEmpty()).isEqualTo(graph.size() == 0)
	}
}
