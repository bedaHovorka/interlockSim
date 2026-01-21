/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for ArrayPath - ArrayDeque-backed Path implementation
 * Coverage improvement test - 2026
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import assertk.fail
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockNodeCell
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive unit tests for ArrayPath class.
 *
 * ArrayPath is a Path implementation backed by Kotlin's ArrayDeque, providing:
 * - Efficient O(1) operations at both ends
 * - Multiplatform compatibility (JVM, JS, Native)
 * - Memory-efficient resizable circular buffer
 *
 * Test Coverage:
 * - MutableCollection contract operations (add, remove, contains, clear, etc.)
 * - Deque-specific operations (addFirst, addLast, removeFirst)
 * - Typed accessors with runtime validation (getFirst, getLast)
 * - Iteration in both directions (iterator, descendingIterator)
 * - Collection operations (containsAll, removeAll, retainAll)
 * - Edge cases (empty path, single element, type mismatches)
 *
 * This test addresses the coverage gap: ArrayPath has no dedicated tests,
 * only indirectly tested through AbstractPathTest.
 *
 * @since 2026-01 (Coverage improvement initiative)
 * @see ArrayPath
 * @see AbstractPath
 */
@DisplayName("ArrayPath - ArrayDeque-backed Path Implementation")
class ArrayPathTest : KoinTestBase() {
	private lateinit var mockContext: MockSimulationContext
	private lateinit var path: ArrayPath
	private lateinit var end1: MockNodeCell
	private lateinit var end2: MockNodeCell
	private lateinit var end3: MockNodeCell
	private lateinit var semaphore1: RailSemaphore
	private lateinit var semaphore2: RailSemaphore
	private lateinit var track1: SimpleTrackBlock

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
		path = ArrayPath(mockContext)
		end1 = MockNodeCell("End1")
		end2 = MockNodeCell("End2")
		end3 = MockNodeCell("End3")
		semaphore1 = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)
		semaphore2 = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
		track1 = SimpleTrackBlock(end1, end2, 100.0, 80.0)
	}

	/**
	 * Nested test group: Basic MutableCollection Contract
	 * Tests fundamental collection operations delegated to ArrayDeque
	 */
	@Nested
	@DisplayName("MutableCollection Contract Operations")
	inner class MutableCollectionOperations {
		@Test
		fun `add() adds element and returns true`() {
			// Act
			val result = path.add(end1)

			// Assert
			assertThat(result)
				.withMessage("add() should return true")
				.isTrue()
			assertThat(path)
				.withMessage("path should contain added element")
				.contains(end1)
			assertThat(path.size)
				.withMessage("size should be 1 after adding one element")
				.isEqualTo(1)
		}

		@Test
		fun `size reflects number of elements`() {
			// Initially empty
			assertThat(path.size).isEqualTo(0)

			// Add elements
			path.add(end1)
			assertThat(path.size).isEqualTo(1)

			path.add(track1)
			assertThat(path.size).isEqualTo(2)

			path.add(semaphore1)
			assertThat(path.size).isEqualTo(3)
		}

		@Test
		fun `isEmpty() returns true for empty path`() {
			assertThat(path.isEmpty())
				.withMessage("newly created path should be empty")
				.isTrue()
		}

		@Test
		fun `isEmpty() returns false after adding elements`() {
			path.add(end1)

			assertThat(path.isEmpty())
				.withMessage("path with elements should not be empty")
				.isFalse()
		}

		@Test
		fun `contains() finds existing element`() {
			// Arrange
			path.add(end1)
			path.add(track1)

			// Assert
			assertThat(path.contains(end1))
				.withMessage("path should contain end1")
				.isTrue()
			assertThat(path.contains(track1))
				.withMessage("path should contain track1")
				.isTrue()
		}

		@Test
		fun `contains() returns false for non-existent element`() {
			// Arrange
			path.add(end1)

			// Assert
			assertThat(path.contains(end2))
				.withMessage("path should not contain end2")
				.isFalse()
		}

		@Test
		fun `clear() removes all elements`() {
			// Arrange - add multiple elements
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)
			assertThat(path.size).isEqualTo(3)

			// Act
			path.clear()

			// Assert
			assertThat(path.size)
				.withMessage("size should be 0 after clear")
				.isEqualTo(0)
			assertThat(path.isEmpty())
				.withMessage("path should be empty after clear")
				.isTrue()
		}

		@Test
		fun `remove() removes existing element and returns true`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)

			// Act
			val result = path.remove(track1)

			// Assert
			assertThat(result)
				.withMessage("remove() should return true for existing element")
				.isTrue()
			assertThat(path.size)
				.withMessage("size should decrease after removal")
				.isEqualTo(2)
			assertThat(path)
				.withMessage("path should not contain removed element")
				.doesNotContain(track1)
		}

		@Test
		fun `remove() returns false for non-existent element`() {
			// Arrange
			path.add(end1)

			// Act
			val result = path.remove(end2)

			// Assert
			assertThat(result)
				.withMessage("remove() should return false for non-existent element")
				.isFalse()
			assertThat(path.size)
				.withMessage("size should not change")
				.isEqualTo(1)
		}
	}

	/**
	 * Nested test group: Collection Operations
	 * Tests bulk collection operations (containsAll, addAll, removeAll, retainAll)
	 */
	@Nested
	@DisplayName("Collection Operations")
	inner class CollectionOperations {
		@Test
		fun `containsAll() returns true when all elements present`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)

			// Act & Assert
			assertThat(path.containsAll(listOf(end1, track1)))
				.withMessage("path should contain all specified elements")
				.isTrue()
		}

		@Test
		fun `containsAll() returns false when some elements missing`() {
			// Arrange
			path.add(end1)

			// Act & Assert
			assertThat(path.containsAll(listOf(end1, end2)))
				.withMessage("path should not contain all elements if some are missing")
				.isFalse()
		}

		@Test
		fun `addAll() adds multiple elements`() {
			// Act
			val result = path.addAll(listOf(end1, track1, semaphore1))

			// Assert
			assertThat(result)
				.withMessage("addAll() should return true")
				.isTrue()
			assertThat(path.size)
				.withMessage("size should reflect all added elements")
				.isEqualTo(3)
			assertThat(path.containsAll(listOf(end1, track1, semaphore1)))
				.withMessage("path should contain all added elements")
				.isTrue()
		}

		@Test
		fun `removeAll() removes specified elements`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)
			path.add(end2)

			// Act
			val result = path.removeAll(listOf(track1, end2))

			// Assert
			assertThat(result)
				.withMessage("removeAll() should return true when elements removed")
				.isTrue()
			assertThat(path.size)
				.withMessage("size should decrease by number of removed elements")
				.isEqualTo(2)
			assertThat(path)
				.withMessage("path should contain only remaining elements")
				.containsAll(end1, semaphore1)
		}

		@Test
		fun `retainAll() keeps only specified elements`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)
			path.add(end2)

			// Act
			val result = path.retainAll(listOf(end1, end2))

			// Assert
			assertThat(result)
				.withMessage("retainAll() should return true when elements removed")
				.isTrue()
			assertThat(path.size)
				.withMessage("size should reflect retained elements only")
				.isEqualTo(2)
			assertThat(path)
				.withMessage("path should contain only retained elements")
				.containsAll(end1, end2)
			assertThat(path)
				.withMessage("path should not contain track1")
				.doesNotContain(track1)
			assertThat(path)
				.withMessage("path should not contain semaphore1")
				.doesNotContain(semaphore1)
		}
	}

	/**
	 * Nested test group: Deque Operations
	 * Tests ArrayPath-specific deque operations at both ends
	 */
	@Nested
	@DisplayName("Deque Operations (addFirst, addLast, removeFirst)")
	inner class DequeOperations {
		@Test
		fun `addFirst() adds element at beginning`() {
			// Arrange
			path.add(track1)
			path.add(semaphore1)

			// Act
			path.addFirst(end1)

			// Assert
			assertThat(path.size).isEqualTo(3)
			// Verify order using iterator
			val iterator = path.iterator()
			assertThat(iterator.next())
				.withMessage("first element should be the one added with addFirst()")
				.isEqualTo(end1)
		}

		@Test
		fun `addLast() appends element at end`() {
			// Arrange
			path.add(end1)
			path.add(track1)

			// Act
			path.addLast(semaphore1)

			// Assert
			assertThat(path.size).isEqualTo(3)
			// Verify order by converting to list
			val elements = path.toList()
			assertThat(elements.last())
				.withMessage("last element should be the one added with addLast()")
				.isEqualTo(semaphore1)
		}

		@Test
		fun `removeFirst() removes and returns first element`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)

			// Act
			val removed = path.removeFirst()

			// Assert
			assertThat(removed)
				.withMessage("removeFirst() should return the first element")
				.isEqualTo(end1)
			assertThat(path.size)
				.withMessage("size should decrease after removeFirst()")
				.isEqualTo(2)
			assertThat(path)
				.withMessage("removed element should not be in path")
				.doesNotContain(end1)
		}

		@Test
		fun `addFirst() on empty path works correctly`() {
			// Act
			path.addFirst(end1)

			// Assert
			assertThat(path.size).isEqualTo(1)
			assertThat(path.iterator().next()).isEqualTo(end1)
		}

		@Test
		fun `addLast() on empty path works correctly`() {
			// Act
			path.addLast(end1)

			// Assert
			assertThat(path.size).isEqualTo(1)
			assertThat(path.iterator().next()).isEqualTo(end1)
		}
	}

	/**
	 * Nested test group: Typed Accessors
	 * Tests getFirst() and getLast() with runtime type validation
	 */
	@Nested
	@DisplayName("Typed Accessors (getFirst, getLast)")
	inner class TypedAccessors {
		@Test
		fun `getFirst() returns PathSeparator when first element has correct type`() {
			// Arrange - MockNodeCell implements PathSeparator
			path.add(end1)
			path.add(track1)

			// Act
			val first = path.getFirst()

			// Assert
			assertThat(first)
				.withMessage("getFirst() should return PathSeparator instance")
				.isInstanceOf(PathSeparator::class)
			assertThat(first)
				.withMessage("getFirst() should return the actual first element")
				.isEqualTo(end1)
		}

		@Test
		fun `getLast() returns OrientedPathSeparator when last element has correct type`() {
			// Arrange - Use InOut which implements OrientedPathSeparator
			val inOut = InOut("Test", false, Cell.SpatialType.HORIZONTAL)
			path.add(end1)
			path.add(track1)
			path.add(inOut)

			// Act
			val last = path.getLast()

			// Assert
			assertThat(last)
				.withMessage("getLast() should return OrientedPathSeparator instance")
				.isInstanceOf(OrientedPathSeparator::class)
			assertThat(last)
				.withMessage("getLast() should return the actual last element")
				.isEqualTo(inOut)
		}

		@Test
		fun `getFirst() throws when first element is not PathSeparator`() {
			// Arrange - SimpleTrackBlock is NOT a PathSeparator
			path.add(track1)

			// Act & Assert
			try {
				path.getFirst()
				fail("getFirst() should throw when element is not PathSeparator")
			} catch (e: IllegalStateException) {
				// Expected - Util.assertInstanceOf throws IllegalStateException
			}
		}

		@Test
		fun `getLast() throws when last element is not OrientedPathSeparator`() {
			// Arrange - MockNodeCell is PathSeparator but not OrientedPathSeparator
			// (NodeCell implements PathSeparator, OrientedNodeCell implements OrientedPathSeparator)
			path.add(track1)
			path.add(end1) // MockNodeCell extends NodeCell, implements PathSeparator only

			// Act & Assert
			try {
				path.getLast()
				fail("getLast() should throw when element is not OrientedPathSeparator")
			} catch (e: IllegalStateException) {
				// Expected - Util.assertInstanceOf throws IllegalStateException
			}
		}
	}

	/**
	 * Nested test group: Iteration
	 * Tests forward and reverse iteration over path elements
	 */
	@Nested
	@DisplayName("Iteration (iterator, descendingIterator)")
	inner class IterationOperations {
		@Test
		fun `iterator() iterates elements in insertion order`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)

			// Act
			val elements = mutableListOf<PathElement>()
			for (element in path) {
				elements.add(element)
			}

			// Assert
			assertThat(elements)
				.withMessage("iterator should return elements in insertion order")
				.containsExactly(end1, track1, semaphore1)
		}

		@Test
		fun `iterator() on empty path has no elements`() {
			// Act
			val hasElements = path.iterator().hasNext()

			// Assert
			assertThat(hasElements)
				.withMessage("empty path iterator should have no elements")
				.isFalse()
		}

		@Test
		fun `iterator() supports removal during iteration`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)

			// Act - remove track1 during iteration
			val iterator = path.iterator()
			while (iterator.hasNext()) {
				val element = iterator.next()
				if (element == track1) {
					iterator.remove()
				}
			}

			// Assert
			assertThat(path.size)
				.withMessage("size should decrease after removal")
				.isEqualTo(2)
			assertThat(path)
				.withMessage("removed element should not be in path")
				.doesNotContain(track1)
		}

		@Test
		fun `descendingIterator() iterates elements in reverse order`() {
			// Arrange
			path.add(end1)
			path.add(track1)
			path.add(semaphore1)

			// Act
			val elements = mutableListOf<PathElement>()
			val descIterator = path.descendingIterator()
			while (descIterator.hasNext()) {
				elements.add(descIterator.next())
			}

			// Assert
			assertThat(elements)
				.withMessage("descendingIterator should return elements in reverse order")
				.containsExactly(semaphore1, track1, end1)
		}

		@Test
		fun `descendingIterator() on single element returns that element`() {
			// Arrange
			path.add(end1)

			// Act
			val descIterator = path.descendingIterator()
			val element = descIterator.next()

			// Assert
			assertThat(element)
				.withMessage("descending iterator on single-element path returns that element")
				.isEqualTo(end1)
			assertThat(descIterator.hasNext())
				.withMessage("no more elements after single element")
				.isFalse()
		}
	}

	/**
	 * Nested test group: Edge Cases
	 * Tests boundary conditions and special scenarios
	 */
	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCases {
		@Test
		fun `operations on empty path behave correctly`() {
			// isEmpty
			assertThat(path.isEmpty()).isTrue()

			// size
			assertThat(path.size).isEqualTo(0)

			// contains
			assertThat(path.contains(end1)).isFalse()

			// remove
			assertThat(path.remove(end1)).isFalse()

			// iterator
			assertThat(path.iterator().hasNext()).isFalse()
		}

		@Test
		fun `single element path operations work correctly`() {
			// Arrange
			path.add(end1)

			// Assert various operations
			assertThat(path.size).isEqualTo(1)
			assertThat(path.isEmpty()).isFalse()
			assertThat(path.contains(end1)).isTrue()

			// Iterator
			val iterator = path.iterator()
			assertThat(iterator.hasNext()).isTrue()
			assertThat(iterator.next()).isEqualTo(end1)
			assertThat(iterator.hasNext()).isFalse()

			// Descending iterator
			val descIterator = path.descendingIterator()
			assertThat(descIterator.hasNext()).isTrue()
			assertThat(descIterator.next()).isEqualTo(end1)
			assertThat(descIterator.hasNext()).isFalse()
		}

		@Test
		fun `clear() on empty path is safe`() {
			// Act
			path.clear()

			// Assert
			assertThat(path.isEmpty())
				.withMessage("path should remain empty")
				.isTrue()
		}

		@Test
		fun `toString() returns meaningful representation`() {
			// Arrange
			path.add(end1)
			path.add(track1)

			// Act
			val string = path.toString()

			// Assert
			assertThat(string)
				.withMessage("toString() should delegate to ArrayDeque")
				.isNotEmpty()
		}

		@Test
		fun `path with duplicate elements maintains count correctly`() {
			// Arrange - add same element twice (if allowed by domain logic)
			path.add(end1)
			path.add(track1)
			path.add(end1) // duplicate

			// Assert
			assertThat(path.size)
				.withMessage("size should count duplicates")
				.isEqualTo(3)
			assertThat(path.contains(end1))
				.withMessage("contains should find duplicate element")
				.isTrue()
		}

		@Test
		fun `removeAll() with empty collection returns false`() {
			// Arrange
			path.add(end1)

			// Act
			val result = path.removeAll(emptyList())

			// Assert
			assertThat(result)
				.withMessage("removeAll with empty list should return false")
				.isFalse()
			assertThat(path.size)
				.withMessage("size should not change")
				.isEqualTo(1)
		}

		@Test
		fun `retainAll() with empty collection removes all elements`() {
			// Arrange
			path.add(end1)
			path.add(track1)

			// Act
			val result = path.retainAll(emptyList())

			// Assert
			assertThat(result)
				.withMessage("retainAll with empty list should return true")
				.isTrue()
			assertThat(path.size)
				.withMessage("all elements should be removed")
				.isEqualTo(0)
			assertThat(path.isEmpty())
				.withMessage("path should be empty")
				.isTrue()
		}
	}
}
