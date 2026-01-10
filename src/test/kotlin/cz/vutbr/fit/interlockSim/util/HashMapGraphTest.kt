/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for HashMapGraph

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.*
import java.util.*

/**
 * Unit tests for HashMapGraph.
 *
 * Coverage:
 * - Basic put/get operations
 * - Node and edge removal
 * - Graph traversal
 * - Extended graph operations with additional information
 * - Error handling and edge cases
 */
class HashMapGraphTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeEach
	fun setUp() {
		graph = HashMapGraph()
	}

	@Nested
	@DisplayName("Basic operations")
	inner class BasicOperations {
		@Test
		fun put_twoNodes_storesEdge() {
			graph.put("A", "B", 100)

			assertThat(graph.get("A", "B")).isEqualTo(100)
		}

		@Test
		fun put_unorientedEdge_accessibleFromBothDirections() {
			graph.put("A", "B", 100)

			assertThat(graph.get("A", "B")).isEqualTo(100)
			assertThat(graph.get("B", "A")).isEqualTo(100)
		}

		@Test
		fun put_multipleEdges_allStored() {
			graph.put("A", "B", 100)
			graph.put("B", "C", 150)
			graph.put("C", "D", 200)

			assertThat(graph.get("A", "B")).isEqualTo(100)
			assertThat(graph.get("B", "C")).isEqualTo(150)
			assertThat(graph.get("C", "D")).isEqualTo(200)
		}

		@Test
		fun put_replacingEdge_updatesValue() {
			graph.put("A", "B", 100)
			graph.put("A", "B", 200)

			assertThat(graph.get("A", "B")).isEqualTo(200)
		}

		@Test
		fun get_nonExistentEdge_returnsNull() {
			assertThat(graph.get("A", "B")).isNull()
		}

		@Test
		fun size_emptyGraph_returnsZero() {
			assertThat(graph.size()).isZero()
		}

		@Test
		fun size_afterAddingEdges_returnsCorrectCount() {
			graph.put("A", "B", 100)
			graph.put("B", "C", 150)
			graph.put("C", "D", 200)

			assertThat(graph.size()).isEqualTo(3)
		}
	}

	@Nested
	@DisplayName("Extended operations with additional information")
	inner class ExtendedOperations {
		@Test
		fun putWithAddInfo_storesEdgeWithMetadata() {
			graph.put("A", "infoA", "B", "infoB", 100)

			assertThat(graph.get("A", "B")).isEqualTo(100)
		}

		@Test
		fun putIfNotExists_newEdge_addsEdge() {
			graph.putIfNotExists("A", "infoA", "B", "infoB", 100)

			assertThat(graph.get("A", "B")).isEqualTo(100)
		}

		@Test
		fun putIfNotExists_existingEdge_doesNotReplace() {
			graph.put("A", "infoA", "B", "infoB", 100)
			graph.putIfNotExists("A", "infoA", "B", "infoB", 200)

			assertThat(graph.get("A", "B")).withMessage("Should keep original value").isEqualTo(100)
		}

		@Test
		fun assignedEdges_nodeWithMultipleEdges_returnsAllEdges() {
			graph.put("A", "info1", "B", "info2", 100)
			graph.put("A", "info3", "C", "info4", 150)
			graph.put("A", "info5", "D", "info6", 200)

			val edges = graph.assignedEdges("A")

			assertThat(edges.size()).isEqualTo(3)
			assertThat(edges.get("info1")).isEqualTo(100)
			assertThat(edges.get("info3")).isEqualTo(150)
			assertThat(edges.get("info5")).isEqualTo(200)
		}

		@Test
		fun extensionalObject_existingEdge_returnsAddInfo() {
			graph.put("A", "infoA", "B", "infoB", 100)

			val addInfo = graph.extensionalObject("A", 100)

			assertThat(addInfo).isEqualTo("infoA")
		}
	}

	@Nested
	@DisplayName("Node operations")
	inner class NodeOperations {
		@Test
		fun nodeSet_emptyGraph_returnsEmptySet() {
			val nodes = graph.nodeSet()

			assertThat(nodes).isEmpty()
		}

		@Test
		fun nodeSet_withEdges_returnsAllNodes() {
			graph.put("A", "B", 100)
			graph.put("B", "C", 150)

			val nodes = graph.nodeSet()

			assertThat(nodes).hasSize(3)
			assertThat(nodes as Iterable<String>).containsExactlyInAnyOrder("A", "B", "C")
		}

		@Test
		fun nodeSet_duplicateNodes_returnsUniqueSet() {
			graph.put("A", "B", 100)
			graph.put("A", "C", 150)
			graph.put("B", "C", 200)

			val nodes = graph.nodeSet()

			assertThat(nodes).hasSize(3)
			assertThat(nodes as Iterable<String>).containsExactlyInAnyOrder("A", "B", "C")
		}

		@Test
		fun contains_existingEdge_returnsTrue() {
			graph.put("A", "B", 100)

			assertThat(graph.contains("A", "B")).isTrue()
			assertThat(graph.contains("B", "A")).isTrue()
		}

		@Test
		fun contains_nonExistentEdge_returnsFalse() {
			assertThat(graph.contains("A", "B")).isFalse()
		}
	}

	@Nested
	@DisplayName("Removal operations")
	inner class RemovalOperations {
		@Test
		fun remove_existingEdge_removesAndReturnsValue() {
			graph.put("A", "B", 100)

			val removed = graph.remove("A", "B")

			assertThat(removed).isEqualTo(100)
			assertThat(graph.get("A", "B")).isNull()
		}

		@Test
		fun remove_nonExistentEdge_returnsNull() {
			val removed = graph.remove("A", "B")

			assertThat(removed).isNull()
		}

		@Test
		fun removeAll_nodeWithMultipleEdges_removesAllConnectedEdges() {
			graph.put("A", "B", 100)
			graph.put("A", "C", 150)
			graph.put("A", "D", 200)
			graph.put("B", "C", 250)

			val removed = graph.removeAll("A") as Collection<Int>

			assertThat(removed).hasSize(3)
			assertThat(removed as Iterable<Int>).containsExactlyInAnyOrder(100, 150, 200)
			assertThat(graph.get("A", "B")).isNull()
			assertThat(graph.get("A", "C")).isNull()
			assertThat(graph.get("A", "D")).isNull()
			assertThat(graph.get("B", "C")).withMessage("Edge not connected to A should remain").isEqualTo(250)
		}

		@Test
		fun removeByValue_existingValue_removesEdgeAndReturnsNodes() {
			graph.put("A", "B", 100)
			graph.put("C", "D", 200)

			val nodes = graph.remove(100) as Collection<String>

			assertThat(nodes).hasSize(2)
			assertThat(nodes as Iterable<String>).containsExactlyInAnyOrder("A", "B")
			assertThat(graph.get("A", "B")).isNull()
			assertThat(graph.get("C", "D")).withMessage("Other edges should remain").isEqualTo(200)
		}

		@Test
		fun removeByValue_duplicateValues_removesAllMatchingEdges() {
			graph.put("A", "B", 100)
			graph.put("C", "D", 100)
			graph.put("E", "F", 200)

			val nodes = graph.remove(100) as Collection<String>

			assertThat(nodes).hasSize(4)
			assertThat(nodes as Iterable<String>).containsExactlyInAnyOrder("A", "B", "C", "D")
			assertThat(graph.get("E", "F")).withMessage("Edge with different value should remain").isEqualTo(200)
		}
	}

	@Nested
	@DisplayName("Graph traversal and queries")
	inner class TraversalOperations {
		@Test
		fun get_nodeInGraph_returnsAllConnectedEdges() {
			graph.put("A", "B", 100)
			graph.put("A", "C", 150)
			graph.put("B", "C", 200)

			val edges = graph.get("A") as Collection<Int>

			assertThat(edges).hasSize(2)
			assertThat(edges as Iterable<Int>).containsExactlyInAnyOrder(100, 150)
		}

		@Test
		fun get_isolatedNode_returnsEmptyCollection() {
			graph.put("A", "B", 100)

			val edges = graph.get("C")

			assertThat(edges).isEmpty()
		}

		@Test
		fun values_returnsAllEdgeValues() {
			graph.put("A", "B", 100)
			graph.put("B", "C", 150)
			graph.put("C", "D", 200)

			val values = graph.values() as Collection<Int>

			assertThat(values).hasSize(3)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(100, 150, 200)
		}

		@Test
		fun entrySet_returnsAllEntries() {
			graph.put("A", "B", 100)
			graph.put("B", "C", 150)

			val entries = graph.entrySet()

			assertThat(entries).hasSize(2)
		}
	}

	@Nested
	@DisplayName("Edge cases and complex scenarios")
	inner class EdgeCases {
		@Test
		fun complexGraph_starTopology_allOperationsWork() {
			// Create a star topology: A connected to B, C, D, E
			graph.put("A", "B", 1)
			graph.put("A", "C", 2)
			graph.put("A", "D", 3)
			graph.put("A", "E", 4)

			assertThat(graph.get("A") as Collection<Int>).hasSize(4)
			assertThat(graph.nodeSet() as Iterable<String>).containsExactlyInAnyOrder("A", "B", "C", "D", "E")
			assertThat(graph.size()).isEqualTo(4)
		}

		@Test
		fun complexGraph_linearChain_traversable() {
			// Create linear chain: A-B-C-D-E
			graph.put("A", "B", 1)
			graph.put("B", "C", 2)
			graph.put("C", "D", 3)
			graph.put("D", "E", 4)

			assertThat(graph.get("B") as Iterable<Int>).containsExactlyInAnyOrder(1, 2)
			assertThat(graph.get("C") as Iterable<Int>).containsExactlyInAnyOrder(2, 3)
		}

		@Test
		fun complexGraph_cycle_detectableThroughContains() {
			// Create cycle: A-B-C-A
			graph.put("A", "B", 1)
			graph.put("B", "C", 2)
			graph.put("C", "A", 3)

			assertThat(graph.contains("A", "B")).isTrue()
			assertThat(graph.contains("B", "C")).isTrue()
			assertThat(graph.contains("C", "A")).isTrue()
			assertThat(graph.size()).isEqualTo(3)
		}
	}
}
