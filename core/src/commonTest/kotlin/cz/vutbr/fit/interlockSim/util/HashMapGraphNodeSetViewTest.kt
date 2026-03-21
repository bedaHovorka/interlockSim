package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for HashMapGraph nodeSet() immutable view.
 */
class HashMapGraphNodeSetViewTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
		graph.put("A", "B", 100)
		graph.put("B", "C", 150)
	}

	@Test
	fun nodeSet_isReadOnlySet() {
		val nodes = graph.nodeSet()
		assertThat(nodes).isNotNull()
		assertThat(nodes).isInstanceOf(Set::class)
	}

	@Test
	fun nodeSet_containsAllNodesInGraph() {
		val nodes = graph.nodeSet()
		assertThat(nodes).isNotNull()
		assertThat(nodes).isInstanceOf(Set::class)
		assertThat(nodes.size).isEqualTo(3)
	}

	@Test
	fun nodeSet_iteratorIsReadOnly() {
		val nodes = graph.nodeSet()
		val iterator = nodes.iterator()
		assertThat(iterator.hasNext()).isEqualTo(true)
		iterator.next()
		assertThat(iterator).isInstanceOf(Iterator::class)
	}
}
