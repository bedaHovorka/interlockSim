/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for HashMapGraph

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HashMapGraph}.
 *
 * Coverage:
 * - Basic put/get operations
 * - Node and edge removal
 * - Graph traversal
 * - Extended graph operations with additional information
 * - Error handling and edge cases
 */
class HashMapGraphTest {

	private HashMapGraph<String, Integer, String> graph;

	@BeforeEach
	void setUp() {
		graph = new HashMapGraph<>();
	}

	@Nested
	@DisplayName("Basic operations")
	class BasicOperations {

		@Test
		void put_twoNodes_storesEdge() {
			graph.put("A", "B", 100);

			assertThat(graph.get("A", "B")).isEqualTo(100);
		}

		@Test
		void put_unorientedEdge_accessibleFromBothDirections() {
			graph.put("A", "B", 100);

			assertThat(graph.get("A", "B")).isEqualTo(100);
			assertThat(graph.get("B", "A")).isEqualTo(100);
		}

		@Test
		void put_multipleEdges_allStored() {
			graph.put("A", "B", 100);
			graph.put("B", "C", 150);
			graph.put("C", "D", 200);

			assertThat(graph.get("A", "B")).isEqualTo(100);
			assertThat(graph.get("B", "C")).isEqualTo(150);
			assertThat(graph.get("C", "D")).isEqualTo(200);
		}

		@Test
		void put_replacingEdge_updatesValue() {
			graph.put("A", "B", 100);
			graph.put("A", "B", 200);

			assertThat(graph.get("A", "B")).isEqualTo(200);
		}

		@Test
		void get_nonExistentEdge_returnsNull() {
			assertThat(graph.get("A", "B")).isNull();
		}

		@Test
		void size_emptyGraph_returnsZero() {
			assertThat(graph.size()).isZero();
		}

		@Test
		void size_afterAddingEdges_returnsCorrectCount() {
			graph.put("A", "B", 100);
			graph.put("B", "C", 150);
			graph.put("C", "D", 200);

			assertThat(graph.size()).isEqualTo(3);
		}
	}

	@Nested
	@DisplayName("Extended operations with additional information")
	class ExtendedOperations {

		@Test
		void putWithAddInfo_storesEdgeWithMetadata() {
			graph.put("A", "infoA", "B", "infoB", 100);

			assertThat(graph.get("A", "B")).isEqualTo(100);
		}

		@Test
		void putIfNotExists_newEdge_addsEdge() {
			graph.putIfNotExists("A", "infoA", "B", "infoB", 100);

			assertThat(graph.get("A", "B")).isEqualTo(100);
		}

		@Test
		void putIfNotExists_existingEdge_doesNotReplace() {
			graph.put("A", "infoA", "B", "infoB", 100);
			graph.putIfNotExists("A", "infoA", "B", "infoB", 200);

			assertThat(graph.get("A", "B"))
				.as("Should keep original value")
				.isEqualTo(100);
		}

		@Test
		void assignedEdges_nodeWithMultipleEdges_returnsAllEdges() {
			graph.put("A", "info1", "B", "info2", 100);
			graph.put("A", "info3", "C", "info4", 150);
			graph.put("A", "info5", "D", "info6", 200);

			Map<String, Integer> edges = graph.assignedEdges("A");

			assertThat(edges)
				.hasSize(3)
				.containsEntry("info1", 100)
				.containsEntry("info3", 150)
				.containsEntry("info5", 200);
		}

		@Test
		void extensionalObject_existingEdge_returnsAddInfo() {
			graph.put("A", "infoA", "B", "infoB", 100);

			String addInfo = graph.extensionalObject("A", 100);

			assertThat(addInfo).isEqualTo("infoA");
		}
	}

	@Nested
	@DisplayName("Node operations")
	class NodeOperations {

		@Test
		void nodeSet_emptyGraph_returnsEmptySet() {
			Set<String> nodes = graph.nodeSet();

			assertThat(nodes).isEmpty();
		}

		@Test
		void nodeSet_withEdges_returnsAllNodes() {
			graph.put("A", "B", 100);
			graph.put("B", "C", 150);

			Set<String> nodes = graph.nodeSet();

			assertThat(nodes)
				.hasSize(3)
				.containsExactlyInAnyOrder("A", "B", "C");
		}

		@Test
		void nodeSet_duplicateNodes_returnsUniqueSet() {
			graph.put("A", "B", 100);
			graph.put("A", "C", 150);
			graph.put("B", "C", 200);

			Set<String> nodes = graph.nodeSet();

			assertThat(nodes)
				.hasSize(3)
				.containsExactlyInAnyOrder("A", "B", "C");
		}

		@Test
		void contains_existingEdge_returnsTrue() {
			graph.put("A", "B", 100);

			assertThat(graph.contains("A", "B")).isTrue();
			assertThat(graph.contains("B", "A")).isTrue();
		}

		@Test
		void contains_nonExistentEdge_returnsFalse() {
			assertThat(graph.contains("A", "B")).isFalse();
		}
	}

	@Nested
	@DisplayName("Removal operations")
	class RemovalOperations {

		@Test
		void remove_existingEdge_removesAndReturnsValue() {
			graph.put("A", "B", 100);

			Integer removed = graph.remove("A", "B");

			assertThat(removed).isEqualTo(100);
			assertThat(graph.get("A", "B")).isNull();
		}

		@Test
		void remove_nonExistentEdge_returnsNull() {
			Integer removed = graph.remove("A", "B");

			assertThat(removed).isNull();
		}

		@Test
		void removeAll_nodeWithMultipleEdges_removesAllConnectedEdges() {
			graph.put("A", "B", 100);
			graph.put("A", "C", 150);
			graph.put("A", "D", 200);
			graph.put("B", "C", 250);

			Collection<Integer> removed = graph.removeAll("A");

			assertThat(removed)
				.hasSize(3)
				.containsExactlyInAnyOrder(100, 150, 200);
			assertThat(graph.get("A", "B")).isNull();
			assertThat(graph.get("A", "C")).isNull();
			assertThat(graph.get("A", "D")).isNull();
			assertThat(graph.get("B", "C"))
				.as("Edge not connected to A should remain")
				.isEqualTo(250);
		}

		@Test
		void removeByValue_existingValue_removesEdgeAndReturnsNodes() {
			graph.put("A", "B", 100);
			graph.put("C", "D", 200);

			Collection<String> nodes = graph.remove(100);

			assertThat(nodes)
				.hasSize(2)
				.containsExactlyInAnyOrder("A", "B");
			assertThat(graph.get("A", "B")).isNull();
			assertThat(graph.get("C", "D"))
				.as("Other edges should remain")
				.isEqualTo(200);
		}

		@Test
		void removeByValue_duplicateValues_removesAllMatchingEdges() {
			graph.put("A", "B", 100);
			graph.put("C", "D", 100);
			graph.put("E", "F", 200);

			Collection<String> nodes = graph.remove(100);

			assertThat(nodes)
				.hasSize(4)
				.containsExactlyInAnyOrder("A", "B", "C", "D");
			assertThat(graph.get("E", "F"))
				.as("Edge with different value should remain")
				.isEqualTo(200);
		}
	}

	@Nested
	@DisplayName("Graph traversal and queries")
	class TraversalOperations {

		@Test
		void get_nodeInGraph_returnsAllConnectedEdges() {
			graph.put("A", "B", 100);
			graph.put("A", "C", 150);
			graph.put("B", "C", 200);

			Collection<Integer> edges = graph.get("A");

			assertThat(edges)
				.hasSize(2)
				.containsExactlyInAnyOrder(100, 150);
		}

		@Test
		void get_isolatedNode_returnsEmptyCollection() {
			graph.put("A", "B", 100);

			Collection<Integer> edges = graph.get("C");

			assertThat(edges).isEmpty();
		}

		@Test
		void values_returnsAllEdgeValues() {
			graph.put("A", "B", 100);
			graph.put("B", "C", 150);
			graph.put("C", "D", 200);

			Collection<Integer> values = graph.values();

			assertThat(values)
				.hasSize(3)
				.containsExactlyInAnyOrder(100, 150, 200);
		}

		@Test
		void entrySet_returnsAllEntries() {
			graph.put("A", "B", 100);
			graph.put("B", "C", 150);

			Set<Map.Entry<Doubleton<String, String>, Integer>> entries = graph.entrySet();

			assertThat(entries).hasSize(2);
		}
	}

	@Nested
	@DisplayName("Edge cases and complex scenarios")
	class EdgeCases {

		@Test
		void complexGraph_starTopology_allOperationsWork() {
			// Create a star topology: A connected to B, C, D, E
			graph.put("A", "B", 1);
			graph.put("A", "C", 2);
			graph.put("A", "D", 3);
			graph.put("A", "E", 4);

			assertThat(graph.get("A")).hasSize(4);
			assertThat(graph.nodeSet()).containsExactlyInAnyOrder("A", "B", "C", "D", "E");
			assertThat(graph.size()).isEqualTo(4);
		}

		@Test
		void complexGraph_linearChain_traversable() {
			// Create linear chain: A-B-C-D-E
			graph.put("A", "B", 1);
			graph.put("B", "C", 2);
			graph.put("C", "D", 3);
			graph.put("D", "E", 4);

			assertThat(graph.get("B")).containsExactlyInAnyOrder(1, 2);
			assertThat(graph.get("C")).containsExactlyInAnyOrder(2, 3);
		}

		@Test
		void complexGraph_cycle_detectableThroughContains() {
			// Create cycle: A-B-C-A
			graph.put("A", "B", 1);
			graph.put("B", "C", 2);
			graph.put("C", "A", 3);

			assertThat(graph.contains("A", "B")).isTrue();
			assertThat(graph.contains("B", "C")).isTrue();
			assertThat(graph.contains("C", "A")).isTrue();
			assertThat(graph.size()).isEqualTo(3);
		}
	}
}
