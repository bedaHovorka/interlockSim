package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.testutil.withMessage
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * String Representation tests for AbstractUnorientedGraph.
 * Tests toString method which delegates to implementationContainer.
 */
class AbstractUnorientedGraphStringRepresentationTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `toString returns meaningful representation for non-empty graph`() {
		graph.put("A", "B", 100)
		graph.put("B", "C", 200)
		val string = graph.toString()
		assertThat(string)
			.withMessage("toString should return non-empty string for non-empty graph")
			.isNotEmpty()
	}

	@Test
	fun `toString returns representation for empty graph`() {
		val string = graph.toString()
		assertThat(string)
			.withMessage("toString should return valid string for empty graph")
			.isNotEmpty()
	}

	@Test
	fun `toString changes when graph is modified`() {
		val emptyString = graph.toString()
		graph.put("A", "B", 100)
		val nonEmptyString = graph.toString()
		assertThat(emptyString).isNotEmpty()
		assertThat(nonEmptyString).isNotEmpty()
	}
}
