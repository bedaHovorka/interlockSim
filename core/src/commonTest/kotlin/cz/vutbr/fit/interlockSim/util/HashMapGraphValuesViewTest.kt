package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for HashMapGraph values() immutable view.
 */
class HashMapGraphValuesViewTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
		graph.put("A", "B", 100)
		graph.put("B", "C", 150)
	}

	@Test
	fun values_isReadOnlyCollection() {
		val values = graph.values()
		assertThat(values).isNotNull()
		assertThat(values).isInstanceOf(Collection::class)
	}

	@Test
	fun values_containsAllEdgeValues() {
		val values = graph.values()
		assertThat(values).isNotNull()
		assertThat(values).isInstanceOf(Collection::class)
		assertThat(values.size).isEqualTo(2)
	}

	@Test
	fun values_iteratorIsReadOnly() {
		val values = graph.values()
		val iterator = values.iterator()
		assertThat(iterator.hasNext()).isEqualTo(true)
		iterator.next()
		assertThat(iterator).isInstanceOf(Iterator::class)
	}
}
