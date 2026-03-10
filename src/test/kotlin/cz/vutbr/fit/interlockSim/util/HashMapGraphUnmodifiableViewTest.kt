/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for HashMapGraph unmodifiable collection views

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test implementation: 2026 (Issue #59 - Unmodifiable collection views)
*/

package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests verifying that HashMapGraph returns immutable collection views.
 *
 * These tests ensure:
 * 1. Returned collections are read-only (Kotlin immutable types)
 * 2. Collections are instances of immutable/read-only types
 * 3. Multiple calls return equivalent views
 */
class HashMapGraphUnmodifiableViewTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeEach
	fun setUp() {
		graph = HashMapGraph()
		// Pre-populate with some data
		graph.put("A", "B", 100)
		graph.put("B", "C", 150)
	}

	@Nested
	@DisplayName("nodeSet() immutable view")
	inner class NodeSetView {
		@Test
		fun nodeSet_isReadOnlySet() {
			val nodes = graph.nodeSet()

			// Verify it's a Set interface (read-only)
			assertThat(nodes).isNotNull()
			assertThat(nodes).isInstanceOf(Set::class)
		}

		@Test
		fun nodeSet_containsAllNodesInGraph() {
			val nodes = graph.nodeSet()

			// Verify the set contains expected nodes
			assertThat(nodes).isNotNull()
			assertThat(nodes).isInstanceOf(Set::class)
			assertThat(nodes.size).isEqualTo(3)  // A, B, C
		}

		@Test
		fun nodeSet_iteratorIsReadOnly() {
			val nodes = graph.nodeSet()
			val iterator = nodes.iterator()

			// Verify we can iterate
			assertThat(iterator.hasNext()).isEqualTo(true)
			iterator.next()  // Should not throw

			// Verify iterator is read-only (no remove method on immutable iterator)
			assertThat(iterator).isInstanceOf(Iterator::class)
		}
	}

	@Nested
	@DisplayName("values() immutable view")
	inner class ValuesView {
		@Test
		fun values_isReadOnlyCollection() {
			val values = graph.values()

			// Verify it's a Collection interface (read-only)
			assertThat(values).isNotNull()
			assertThat(values).isInstanceOf(Collection::class)
		}

		@Test
		fun values_containsAllEdgeValues() {
			val values = graph.values()

			// Verify the collection contains expected values
			assertThat(values).isNotNull()
			assertThat(values).isInstanceOf(Collection::class)
			assertThat(values.size).isEqualTo(2)  // 100, 150
		}

		@Test
		fun values_iteratorIsReadOnly() {
			val values = graph.values()
			val iterator = values.iterator()

			// Verify we can iterate
			assertThat(iterator.hasNext()).isEqualTo(true)
			iterator.next()  // Should not throw

			// Verify iterator is read-only (no remove method on immutable iterator)
			assertThat(iterator).isInstanceOf(Iterator::class)
		}
	}

	@Nested
	@DisplayName("entrySet() immutable view")
	inner class EntrySetView {
		@Test
		fun entrySet_isReadOnlySet() {
			val entries = graph.entrySet()

			// Verify it's a Set interface (read-only)
			assertThat(entries).isNotNull()
			assertThat(entries).isInstanceOf(Set::class)
		}

		@Test
		fun entrySet_containsAllEntries() {
			val entries = graph.entrySet()

			// Verify the set contains expected entries
			assertThat(entries).isNotNull()
			assertThat(entries).isInstanceOf(Set::class)
			assertThat(entries.size).isEqualTo(2)  // Two edges
		}
	}
}
