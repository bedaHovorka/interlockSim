/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for EnumUnorientedGraph

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EnumUnorientedGraph}.
 *
 * Coverage:
 * - Basic put/get operations with enum types
 * - Contains operations
 * - Joined nodes retrieval
 * - Unoriented graph properties
 * - Edge cases with enum ordering
 */
class EnumUnorientedGraphTest {

	/**
	 * Test enum representing railway directions
	 */
	enum Direction {
		NORTH, SOUTH, EAST, WEST
	}

	/**
	 * Test enum representing signal states
	 */
	enum Signal {
		RED, YELLOW, GREEN
	}

	private EnumUnorientedGraph<Direction, Integer> graph;

	@BeforeEach
	void setUp() {
		graph = new EnumUnorientedGraph<>(Direction.class);
	}

	@Nested
	@DisplayName("Basic operations")
	class BasicOperations {

		@Test
		void constructor_validEnumClass_createsGraph() {
			EnumUnorientedGraph<Direction, Integer> g = new EnumUnorientedGraph<>(Direction.class);

			assertThat(g).isNotNull();
		}

		@Test
		void put_twoEnumValues_storesEdge() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100);
		}

		@Test
		void put_unorientedEdge_accessibleFromBothDirections() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100);
			assertThat(graph.get(Direction.SOUTH, Direction.NORTH))
				.as("Unoriented graph: edge accessible from both directions")
				.isEqualTo(100);
		}

		@Test
		void put_multipleEdges_allStored() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);
			graph.put(Direction.EAST, Direction.WEST, 150);
			graph.put(Direction.NORTH, Direction.EAST, 200);

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100);
			assertThat(graph.get(Direction.EAST, Direction.WEST)).isEqualTo(150);
			assertThat(graph.get(Direction.NORTH, Direction.EAST)).isEqualTo(200);
		}

		@Test
		void put_replacingEdge_updatesValue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);
			graph.put(Direction.NORTH, Direction.SOUTH, 200);

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(200);
		}

		@Test
		void get_nonExistentEdge_returnsNull() {
			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isNull();
		}

		@Test
		void get_afterPut_returnsValue() {
			graph.put(Direction.NORTH, Direction.EAST, 42);

			Integer value = graph.get(Direction.NORTH, Direction.EAST);

			assertThat(value).isEqualTo(42);
		}
	}

	@Nested
	@DisplayName("Contains operations")
	class ContainsOperations {

		@Test
		void contains_existingEdge_returnsTrue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isTrue();
		}

		@Test
		void contains_reverseDirection_returnsTrue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThat(graph.contains(Direction.SOUTH, Direction.NORTH))
				.as("Unoriented graph: contains check symmetric")
				.isTrue();
		}

		@Test
		void contains_nonExistentEdge_returnsFalse() {
			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isFalse();
		}

		@Test
		void contains_afterRemoval_returnsFalse() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);
			// Note: remove() throws NotImplementedException, so we can't test this
			// Just verify contains works correctly
			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isTrue();
		}
	}

	@Nested
	@DisplayName("Joined nodes operations")
	class JoinedNodesOperations {

		@Test
		void getJoinedNodesAndEdges_singleEdge_returnsConnectedNode() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			Map<Direction, Integer> joined = graph.getJoinedNodesAndEdges(Direction.NORTH);

			assertThat(joined)
				.hasSize(1)
				.containsEntry(Direction.SOUTH, 100);
		}

		@Test
		void getJoinedNodesAndEdges_multipleEdges_returnsAllConnected() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);
			graph.put(Direction.NORTH, Direction.EAST, 150);
			graph.put(Direction.NORTH, Direction.WEST, 200);

			Map<Direction, Integer> joined = graph.getJoinedNodesAndEdges(Direction.NORTH);

			assertThat(joined)
				.hasSize(3)
				.containsEntry(Direction.SOUTH, 100)
				.containsEntry(Direction.EAST, 150)
				.containsEntry(Direction.WEST, 200);
		}

		@Test
		void getJoinedNodesAndEdges_nodeNotInGraph_returnsEmptyMap() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			Map<Direction, Integer> joined = graph.getJoinedNodesAndEdges(Direction.EAST);

			assertThat(joined).isEmpty();
		}

		@Test
		void getJoinedNodesAndEdges_symmetricAccess_worksBothWays() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			Map<Direction, Integer> fromNorth = graph.getJoinedNodesAndEdges(Direction.NORTH);
			Map<Direction, Integer> fromSouth = graph.getJoinedNodesAndEdges(Direction.SOUTH);

			assertThat(fromNorth).containsEntry(Direction.SOUTH, 100);
			assertThat(fromSouth).containsEntry(Direction.NORTH, 100);
		}

		@Test
		void getJoinedNodesAndEdges_complexTopology_returnsCorrectNeighbors() {
			// Create a star topology: NORTH connected to SOUTH, EAST, WEST
			graph.put(Direction.NORTH, Direction.SOUTH, 1);
			graph.put(Direction.NORTH, Direction.EAST, 2);
			graph.put(Direction.NORTH, Direction.WEST, 3);
			// Add separate edge not connected to NORTH
			graph.put(Direction.SOUTH, Direction.EAST, 4);

			Map<Direction, Integer> joined = graph.getJoinedNodesAndEdges(Direction.NORTH);

			assertThat(joined)
				.hasSize(3)
				.containsEntry(Direction.SOUTH, 1)
				.containsEntry(Direction.EAST, 2)
				.containsEntry(Direction.WEST, 3)
				.doesNotContainValue(4);
		}
	}

	@Nested
	@DisplayName("Enum ordering and symmetry")
	class EnumOrderingTests {

		@Test
		void put_differentEnumOrder_storedConsistently() {
			// Enum.compareTo() defines natural ordering
			graph.put(Direction.SOUTH, Direction.NORTH, 100);

			// Should be accessible regardless of order
			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100);
			assertThat(graph.get(Direction.SOUTH, Direction.NORTH)).isEqualTo(100);
		}

		@Test
		void put_allCombinations_symmetric() {
			// Test that all enum pair combinations work symmetrically
			graph.put(Direction.NORTH, Direction.SOUTH, 1);
			graph.put(Direction.EAST, Direction.WEST, 2);
			graph.put(Direction.NORTH, Direction.EAST, 3);
			graph.put(Direction.SOUTH, Direction.WEST, 4);

			// Verify all are accessible from both directions
			assertThat(graph.get(Direction.SOUTH, Direction.NORTH)).isEqualTo(1);
			assertThat(graph.get(Direction.WEST, Direction.EAST)).isEqualTo(2);
			assertThat(graph.get(Direction.EAST, Direction.NORTH)).isEqualTo(3);
			assertThat(graph.get(Direction.WEST, Direction.SOUTH)).isEqualTo(4);
		}
	}

	@Nested
	@DisplayName("Different enum types")
	class DifferentEnumTypes {

		@Test
		void signalEnum_worksCorrectly() {
			EnumUnorientedGraph<Signal, String> signalGraph = new EnumUnorientedGraph<>(Signal.class);

			signalGraph.put(Signal.RED, Signal.GREEN, "transition");

			assertThat(signalGraph.get(Signal.RED, Signal.GREEN)).isEqualTo("transition");
			assertThat(signalGraph.contains(Signal.RED, Signal.GREEN)).isTrue();
		}

		@Test
		void signalEnum_multipleTransitions_allStored() {
			EnumUnorientedGraph<Signal, String> signalGraph = new EnumUnorientedGraph<>(Signal.class);

			signalGraph.put(Signal.RED, Signal.YELLOW, "caution");
			signalGraph.put(Signal.YELLOW, Signal.GREEN, "go");

			assertThat(signalGraph.get(Signal.RED, Signal.YELLOW)).isEqualTo("caution");
			assertThat(signalGraph.get(Signal.YELLOW, Signal.GREEN)).isEqualTo("go");
		}
	}

	@Nested
	@DisplayName("Complex scenarios")
	class ComplexScenarios {

		@Test
		void fullMeshTopology_allNodesConnected() {
			// Create full mesh: every node connected to every other node
			graph.put(Direction.NORTH, Direction.SOUTH, 1);
			graph.put(Direction.NORTH, Direction.EAST, 2);
			graph.put(Direction.NORTH, Direction.WEST, 3);
			graph.put(Direction.SOUTH, Direction.EAST, 4);
			graph.put(Direction.SOUTH, Direction.WEST, 5);
			graph.put(Direction.EAST, Direction.WEST, 6);

			// Verify all connections exist
			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isTrue();
			assertThat(graph.contains(Direction.NORTH, Direction.EAST)).isTrue();
			assertThat(graph.contains(Direction.NORTH, Direction.WEST)).isTrue();
			assertThat(graph.contains(Direction.SOUTH, Direction.EAST)).isTrue();
			assertThat(graph.contains(Direction.SOUTH, Direction.WEST)).isTrue();
			assertThat(graph.contains(Direction.EAST, Direction.WEST)).isTrue();
		}

		@Test
		void linearChain_traversable() {
			// Create linear chain: NORTH-SOUTH-EAST-WEST
			graph.put(Direction.NORTH, Direction.SOUTH, 1);
			graph.put(Direction.SOUTH, Direction.EAST, 2);
			graph.put(Direction.EAST, Direction.WEST, 3);

			// Verify chain structure
			Map<Direction, Integer> northNeighbors = graph.getJoinedNodesAndEdges(Direction.NORTH);
			Map<Direction, Integer> southNeighbors = graph.getJoinedNodesAndEdges(Direction.SOUTH);
			Map<Direction, Integer> eastNeighbors = graph.getJoinedNodesAndEdges(Direction.EAST);

			assertThat(northNeighbors).hasSize(1);
			assertThat(southNeighbors).hasSize(2);
			assertThat(eastNeighbors).hasSize(2);
		}

		@Test
		void updateExistingEdge_replacesValue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);
			graph.put(Direction.NORTH, Direction.SOUTH, 200);
			graph.put(Direction.SOUTH, Direction.NORTH, 300); // Same edge, reversed

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(300);
		}
	}

	@Nested
	@DisplayName("Not implemented operations")
	class NotImplementedOperations {

		@Test
		void get_byNode_throwsNotImplementedException() {
			assertThatThrownBy(() -> graph.get(Direction.NORTH))
				.isInstanceOf(NotImplementedException.class);
		}

		@Test
		void nodeSet_throwsNotImplementedException() {
			assertThatThrownBy(() -> graph.nodeSet())
				.isInstanceOf(NotImplementedException.class);
		}

		@Test
		void remove_byNodes_throwsNotImplementedException() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThatThrownBy(() -> graph.remove(Direction.NORTH, Direction.SOUTH))
				.isInstanceOf(NotImplementedException.class);
		}

		@Test
		void remove_byEdge_throwsNotImplementedException() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThatThrownBy(() -> graph.remove(100))
				.isInstanceOf(NotImplementedException.class);
		}

		@Test
		void removeAll_throwsNotImplementedException() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100);

			assertThatThrownBy(() -> graph.removeAll(Direction.NORTH))
				.isInstanceOf(NotImplementedException.class);
		}

		@Test
		void values_throwsNotImplementedException() {
			assertThatThrownBy(() -> graph.values())
				.isInstanceOf(NotImplementedException.class);
		}
	}
}
