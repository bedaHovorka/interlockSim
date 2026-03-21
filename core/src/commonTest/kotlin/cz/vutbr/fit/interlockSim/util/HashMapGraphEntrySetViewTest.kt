package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for HashMapGraph entrySet() immutable view.
 */
class HashMapGraphEntrySetViewTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
		graph.put("A", "B", 100)
		graph.put("B", "C", 150)
	}

	@Test
	fun entrySet_isReadOnlySet() {
		val entries = graph.entrySet()
		assertThat(entries).isNotNull()
		assertThat(entries).isInstanceOf(Set::class)
	}

	@Test
	fun entrySet_containsAllEntries() {
		val entries = graph.entrySet()
		assertThat(entries).isNotNull()
		assertThat(entries).isInstanceOf(Set::class)
		assertThat(entries.size).isEqualTo(2)
	}
}
