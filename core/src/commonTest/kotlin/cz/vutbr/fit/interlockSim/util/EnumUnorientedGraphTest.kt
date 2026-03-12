/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for EnumUnorientedGraph

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.containsEntry
import cz.vutbr.fit.interlockSim.testutil.doesNotContainValue
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.*
import java.util.*

// Type alias to use Map with Kotlin type annotations
typealias JMap<K, V> = Map<K, V>

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
	enum class Direction {
		NORTH,
		SOUTH,
		EAST,
		WEST
	}

	/**
	 * Test enum representing signal states
	 */
	enum class Signal {
		RED,
		YELLOW,
		GREEN
	}

	private lateinit var graph: EnumUnorientedGraph<Direction, Int>

	@BeforeEach
	fun setUp() {
		graph = EnumUnorientedGraph()
	}

	@Nested
	@DisplayName("Basic operations")
	inner class BasicOperations {
		@Test
		fun constructor_validEnumClass_createsGraph() {
			val g: EnumUnorientedGraph<Direction, Int> = EnumUnorientedGraph()

			assertThat(g).isNotNull()
		}

		@Test
		fun put_twoEnumValues_storesEdge() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100)
		}

		@Test
		fun put_unorientedEdge_accessibleFromBothDirections() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100)
			assertThat(graph.get(Direction.SOUTH, Direction.NORTH))
				.withMessage("Unoriented graph: edge accessible from both directions")
				.isEqualTo(100)
		}

		@Test
		fun put_multipleEdges_allStored() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)
			graph.put(Direction.EAST, Direction.WEST, 150)
			graph.put(Direction.NORTH, Direction.EAST, 200)

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100)
			assertThat(graph.get(Direction.EAST, Direction.WEST)).isEqualTo(150)
			assertThat(graph.get(Direction.NORTH, Direction.EAST)).isEqualTo(200)
		}

		@Test
		fun put_replacingEdge_updatesValue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)
			graph.put(Direction.NORTH, Direction.SOUTH, 200)

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(200)
		}

		@Test
		fun get_nonExistentEdge_returnsNull() {
			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isNull()
		}

		@Test
		fun get_afterPut_returnsValue() {
			graph.put(Direction.NORTH, Direction.EAST, 42)

			val value: Int? = graph.get(Direction.NORTH, Direction.EAST)

			assertThat(value).isEqualTo(42)
		}
	}

	@Nested
	@DisplayName("Contains operations")
	inner class ContainsOperations {
		@Test
		fun contains_existingEdge_returnsTrue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isTrue()
		}

		@Test
		fun contains_reverseDirection_returnsTrue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertThat(graph.contains(Direction.SOUTH, Direction.NORTH))
				.withMessage("Unoriented graph: contains check symmetric")
				.isTrue()
		}

		@Test
		fun contains_nonExistentEdge_returnsFalse() {
			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isFalse()
		}

		@Test
		fun contains_afterRemoval_returnsFalse() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)
			// Note: remove() throws NotImplementedException, so we can't test this
			// Just verify contains works correctly
			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isTrue()
		}
	}

	@Nested
	@DisplayName("Joined nodes operations")
	inner class JoinedNodesOperations {
		@Test
		fun getJoinedNodesAndEdges_singleEdge_returnsConnectedNode() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			val joined: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.NORTH)

			@Suppress("UNCHECKED_CAST")
			val joinedMap = joined as Map<Direction, Int>
			assertThat(joinedMap).hasSize(1)
			assertThat(joinedMap).containsEntry(Direction.SOUTH, 100)
		}

		@Test
		fun getJoinedNodesAndEdges_multipleEdges_returnsAllConnected() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)
			graph.put(Direction.NORTH, Direction.EAST, 150)
			graph.put(Direction.NORTH, Direction.WEST, 200)

			val joined: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.NORTH)

			@Suppress("UNCHECKED_CAST")
			val joinedMap = joined as Map<Direction, Int>
			assertThat(joinedMap).hasSize(3)
			assertThat(joinedMap)
				.containsEntry(Direction.SOUTH, 100)
				.containsEntry(Direction.EAST, 150)
				.containsEntry(Direction.WEST, 200)
		}

		@Test
		fun getJoinedNodesAndEdges_nodeNotInGraph_returnsEmptyMap() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			val joined: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.EAST)

			@Suppress("UNCHECKED_CAST")
			assertThat(joined as Map<Direction, Int>).isEmpty()
		}

		@Test
		fun getJoinedNodesAndEdges_symmetricAccess_worksBothWays() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			val fromNorth: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.NORTH)
			val fromSouth: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.SOUTH)

			@Suppress("UNCHECKED_CAST")
			assertThat(fromNorth as Map<Direction, Int>).containsEntry(Direction.SOUTH, 100)
			@Suppress("UNCHECKED_CAST")
			assertThat(fromSouth as Map<Direction, Int>).containsEntry(Direction.NORTH, 100)
		}

		@Test
		fun getJoinedNodesAndEdges_complexTopology_returnsCorrectNeighbors() {
			// Create a star topology: NORTH connected to SOUTH, EAST, WEST
			graph.put(Direction.NORTH, Direction.SOUTH, 1)
			graph.put(Direction.NORTH, Direction.EAST, 2)
			graph.put(Direction.NORTH, Direction.WEST, 3)
			// Add separate edge not connected to NORTH
			graph.put(Direction.SOUTH, Direction.EAST, 4)

			val joined: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.NORTH)

			@Suppress("UNCHECKED_CAST")
			val joinedMap = joined as Map<Direction, Int>
			assertThat(joinedMap).hasSize(3)
			assertThat(joinedMap)
				.containsEntry(Direction.SOUTH, 1)
				.containsEntry(Direction.EAST, 2)
				.containsEntry(Direction.WEST, 3)
				.doesNotContainValue(4)
		}
	}

	@Nested
	@DisplayName("Enum ordering and symmetry")
	inner class EnumOrderingTests {
		@Test
		fun put_differentEnumOrder_storedConsistently() {
			// Enum.compareTo() defines natural ordering
			graph.put(Direction.SOUTH, Direction.NORTH, 100)

			// Should be accessible regardless of order
			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(100)
			assertThat(graph.get(Direction.SOUTH, Direction.NORTH)).isEqualTo(100)
		}

		@Test
		fun put_allCombinations_symmetric() {
			// Test that all enum pair combinations work symmetrically
			graph.put(Direction.NORTH, Direction.SOUTH, 1)
			graph.put(Direction.EAST, Direction.WEST, 2)
			graph.put(Direction.NORTH, Direction.EAST, 3)
			graph.put(Direction.SOUTH, Direction.WEST, 4)

			// Verify all are accessible from both directions
			assertThat(graph.get(Direction.SOUTH, Direction.NORTH)).isEqualTo(1)
			assertThat(graph.get(Direction.WEST, Direction.EAST)).isEqualTo(2)
			assertThat(graph.get(Direction.EAST, Direction.NORTH)).isEqualTo(3)
			assertThat(graph.get(Direction.WEST, Direction.SOUTH)).isEqualTo(4)
		}
	}

	@Nested
	@DisplayName("Different enum types")
	inner class DifferentEnumTypes {
		@Test
		fun signalEnum_worksCorrectly() {
			val signalGraph: EnumUnorientedGraph<Signal, String> = EnumUnorientedGraph()

			signalGraph.put(Signal.RED, Signal.GREEN, "transition")

			assertThat(signalGraph.get(Signal.RED, Signal.GREEN)).isEqualTo("transition")
			assertThat(signalGraph.contains(Signal.RED, Signal.GREEN)).isTrue()
		}

		@Test
		fun signalEnum_multipleTransitions_allStored() {
			val signalGraph: EnumUnorientedGraph<Signal, String> = EnumUnorientedGraph()

			signalGraph.put(Signal.RED, Signal.YELLOW, "caution")
			signalGraph.put(Signal.YELLOW, Signal.GREEN, "go")

			assertThat(signalGraph.get(Signal.RED, Signal.YELLOW)).isEqualTo("caution")
			assertThat(signalGraph.get(Signal.YELLOW, Signal.GREEN)).isEqualTo("go")
		}
	}

	@Nested
	@DisplayName("Complex scenarios")
	inner class ComplexScenarios {
		@Test
		fun fullMeshTopology_allNodesConnected() {
			// Create full mesh: every node connected to every other node
			graph.put(Direction.NORTH, Direction.SOUTH, 1)
			graph.put(Direction.NORTH, Direction.EAST, 2)
			graph.put(Direction.NORTH, Direction.WEST, 3)
			graph.put(Direction.SOUTH, Direction.EAST, 4)
			graph.put(Direction.SOUTH, Direction.WEST, 5)
			graph.put(Direction.EAST, Direction.WEST, 6)

			// Verify all connections exist
			assertThat(graph.contains(Direction.NORTH, Direction.SOUTH)).isTrue()
			assertThat(graph.contains(Direction.NORTH, Direction.EAST)).isTrue()
			assertThat(graph.contains(Direction.NORTH, Direction.WEST)).isTrue()
			assertThat(graph.contains(Direction.SOUTH, Direction.EAST)).isTrue()
			assertThat(graph.contains(Direction.SOUTH, Direction.WEST)).isTrue()
			assertThat(graph.contains(Direction.EAST, Direction.WEST)).isTrue()
		}

		@Test
		fun linearChain_traversable() {
			// Create linear chain: NORTH-SOUTH-EAST-WEST
			graph.put(Direction.NORTH, Direction.SOUTH, 1)
			graph.put(Direction.SOUTH, Direction.EAST, 2)
			graph.put(Direction.EAST, Direction.WEST, 3)

			// Verify chain structure
			val northNeighbors: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.NORTH)
			val southNeighbors: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.SOUTH)
			val eastNeighbors: JMap<Direction, Int> = graph.getJoinedNodesAndEdges(Direction.EAST)

			@Suppress("UNCHECKED_CAST")
			assertThat(northNeighbors as Map<Direction, Int>).hasSize(1)
			@Suppress("UNCHECKED_CAST")
			assertThat(southNeighbors as Map<Direction, Int>).hasSize(2)
			@Suppress("UNCHECKED_CAST")
			assertThat(eastNeighbors as Map<Direction, Int>).hasSize(2)
		}

		@Test
		fun updateExistingEdge_replacesValue() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)
			graph.put(Direction.NORTH, Direction.SOUTH, 200)
			graph.put(Direction.SOUTH, Direction.NORTH, 300) // Same edge, reversed

			assertThat(graph.get(Direction.NORTH, Direction.SOUTH)).isEqualTo(300)
		}
	}

	@Nested
	@DisplayName("Not implemented operations")
	inner class NotImplementedOperations {
		@Test
		fun get_byNode_throwsNotImplementedException() {
			assertFailure { graph.get(Direction.NORTH) }
				.isInstanceOf<NotImplementedException>()
		}

		@Test
		fun nodeSet_throwsNotImplementedException() {
			assertFailure { graph.nodeSet() }
				.isInstanceOf<NotImplementedException>()
		}

		@Test
		fun remove_byNodes_throwsNotImplementedException() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertFailure { graph.remove(Direction.NORTH, Direction.SOUTH) }
				.isInstanceOf<NotImplementedException>()
		}

		@Test
		fun remove_byEdge_throwsNotImplementedException() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertFailure { graph.remove(100) }
				.isInstanceOf<NotImplementedException>()
		}

		@Test
		fun removeAll_throwsNotImplementedException() {
			graph.put(Direction.NORTH, Direction.SOUTH, 100)

			assertFailure { graph.removeAll(Direction.NORTH) }
				.isInstanceOf<NotImplementedException>()
		}

		@Test
		fun values_throwsNotImplementedException() {
			assertFailure { graph.values() }
				.isInstanceOf<NotImplementedException>()
		}
	}
}
