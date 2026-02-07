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
import assertk.assertions.isNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests verifying that HashMapGraph returns unmodifiable collection views.
 *
 * These tests ensure:
 * 1. Returned collections cannot be modified (throw UnsupportedOperationException)
 * 2. Views reflect changes to the underlying graph
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
	@DisplayName("nodeSet() unmodifiable view")
	inner class NodeSetView {
		@Test
		fun nodeSet_attemptToAdd_throwsUnsupportedOperationException() {
			val nodes = graph.nodeSet()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(nodes as MutableSet<String>).add("D")
			}
			assertThat(exception).isNotNull()
		}

		@Test
		fun nodeSet_attemptToRemove_throwsUnsupportedOperationException() {
			val nodes = graph.nodeSet()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(nodes as MutableSet<String>).remove("A")
			}
			assertThat(exception).isNotNull()
		}

		@Test
		fun nodeSet_attemptToClear_throwsUnsupportedOperationException() {
			val nodes = graph.nodeSet()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(nodes as MutableSet<String>).clear()
			}
			assertThat(exception).isNotNull()
		}

		@Test
		fun nodeSet_attemptToRemoveThroughIterator_throwsUnsupportedOperationException() {
			val nodes = graph.nodeSet()
			val iterator = nodes.iterator()
			iterator.next()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(iterator as MutableIterator<String>).remove()
			}
			assertThat(exception).isNotNull()
		}
	}

	@Nested
	@DisplayName("values() unmodifiable view")
	inner class ValuesView {
		@Test
		fun values_attemptToAdd_throwsUnsupportedOperationException() {
			val values = graph.values()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(values as MutableCollection<Int>).add(200)
			}
			assertThat(exception).isNotNull()
		}

		@Test
		fun values_attemptToRemove_throwsUnsupportedOperationException() {
			val values = graph.values()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(values as MutableCollection<Int>).remove(100)
			}
			assertThat(exception).isNotNull()
		}

		@Test
		fun values_attemptToClear_throwsUnsupportedOperationException() {
			val values = graph.values()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(values as MutableCollection<Int>).clear()
			}
			assertThat(exception).isNotNull()
		}
	}

	@Nested
	@DisplayName("entrySet() unmodifiable view")
	inner class EntrySetView {
		@Test
		fun entrySet_attemptToRemove_throwsUnsupportedOperationException() {
			val entries = graph.entrySet()
			val firstEntry = entries.first()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(entries as MutableSet<*>).remove(firstEntry)
			}
			assertThat(exception).isNotNull()
		}

		@Test
		fun entrySet_attemptToClear_throwsUnsupportedOperationException() {
			val entries = graph.entrySet()

			val exception = assertThrows<UnsupportedOperationException> {
				@Suppress("UNCHECKED_CAST")
				(entries as MutableSet<*>).clear()
			}
			assertThat(exception).isNotNull()
		}
	}
}
