/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for AbstractUnorientedGraph base class
 * Coverage improvement test - 2026
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isTrue
import assertk.assertions.isZero
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Comprehensive unit tests for AbstractUnorientedGraph abstract base class.
 *
 * Testing Strategy:
 * Since AbstractUnorientedGraph is abstract, tests are conducted through
 * HashMapGraph, a concrete implementation that extends AbstractUnorientedGraph.
 *
 * Nested classes extracted to separate files:
 * - [AbstractUnorientedGraphEdgeExistenceTest]
 * - [AbstractUnorientedGraphStringRepresentationTest]
 * - [AbstractUnorientedGraphSizeOperationsTest]
 * - [AbstractUnorientedGraphClearOperationsTest]
 * - [AbstractUnorientedGraphValuesCollectionTest]
 * - [AbstractUnorientedGraphEdgeCasesTest]
 */
class AbstractUnorientedGraphTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
	}

	@Test
	fun `containsEdge returns true for existing edge`() {
		graph.put("A", "B", 100)
		assertThat(graph.containsEdge(100)).isTrue()
	}

	@Test
	fun `size returns zero for empty graph`() {
		assertThat(graph.size()).isZero()
	}

	@Test
	fun `isEmpty returns true for empty graph`() {
		assertThat(graph.isEmpty()).isTrue()
	}
}
