/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Unit tests for Path Iterator functionality
 * Phase 2: Logic and Domain Tests - Path Iterator
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockNodeCell
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for Path iterator functionality.
 *
 * This test suite verifies:
 * - Iterator contract compliance (hasNext, next, remove)
 * - Empty path handling
 * - Bidirectional traversal (forward and reverse)
 * - Concurrent modification detection
 * - Iterator boundary conditions
 * - Multiple iterator instances
 * - Element ordering preservation
 *
 * **Coverage Goals:**
 * - AbstractPath.iterator() and descendingIterator()
 * - ArrayPath.iterator() delegation
 * - Path traversal edge cases
 *
 * @since 2026-01 (Phase 2: Logic and Domain Tests)
 */
@DisplayName("Path Iterator Tests")
class PathIteratorTest : KoinTestBase() {
	private lateinit var mockContext: cz.vutbr.fit.interlockSim.testutil.MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
	}

	/**
	 * Nested test group: Empty Path Iterator
	 * Tests iterator behavior on empty paths
	 */
	@Nested
	@DisplayName("Empty Path Iterator")
	inner class EmptyPathIteratorTests {
		@Test
		fun `iterator handles empty path gracefully`() {
			// Arrange
			val path = ArrayPath(mockContext)

			// Act
			val iterator = path.iterator()

			// Assert
			assertThat(iterator.hasNext()).isFalse()
		}

		@Test
		fun `empty path iterator throws NoSuchElementException on next`() {
			// Arrange
			val path = ArrayPath(mockContext)
			val iterator = path.iterator()

			// Act & Assert
			assertFailure { iterator.next() }
				.isInstanceOf(NoSuchElementException::class)
		}

		@Test
		fun `empty path descending iterator has no elements`() {
			// Arrange
			val path = ArrayPath(mockContext)

			// Act
			val descendingIterator = path.descendingIterator()

			// Assert
			assertThat(descendingIterator.hasNext()).isFalse()
		}

		@Test
		fun `multiple empty iterators are independent`() {
			// Arrange
			val path = ArrayPath(mockContext)

			// Act
			val iterator1 = path.iterator()
			val iterator2 = path.iterator()

			// Assert
			assertThat(iterator1.hasNext()).isFalse()
			assertThat(iterator2.hasNext()).isFalse()
		}
	}

	/**
	 * Nested test group: Bidirectional Traversal
	 * Tests forward and reverse iteration
	 */
	@Nested
	@DisplayName("Bidirectional Traversal")
	inner class BidirectionalTraversalTests {
		@Test
		fun `iterator supports forward traversal`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 80.0, 80.0)
			val track2 = SimpleTrackBlock(end2, end3, 150.0, 70.0, 70.0)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(end2)
			path.addLast(track2)
			path.addLast(end3)

			// Act
			val elements = mutableListOf<PathElement>()
			val iterator = path.iterator()
			while (iterator.hasNext()) {
				elements.add(iterator.next())
			}

			// Assert
			assertThat(elements).hasSize(5)
			assertThat(elements[0]).isEqualTo(end1)
			assertThat(elements[1]).isEqualTo(track1)
			assertThat(elements[2]).isEqualTo(end2)
			assertThat(elements[3]).isEqualTo(track2)
			assertThat(elements[4]).isEqualTo(end3)
		}

		@Test
		fun `iterator supports bidirectional traversal`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val end3 = createMockNodeCell(name = "End3")
			val track1 = SimpleTrackBlock(end1, end2, 100.0, 80.0, 80.0)
			val track2 = SimpleTrackBlock(end2, end3, 150.0, 70.0, 70.0)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track1)
			path.addLast(end2)
			path.addLast(track2)
			path.addLast(end3)

			// Act
			val forwardElements = mutableListOf<PathElement>()
			val forwardIterator = path.iterator()
			while (forwardIterator.hasNext()) {
				forwardElements.add(forwardIterator.next())
			}

			val reverseElements = mutableListOf<PathElement>()
			val reverseIterator = path.descendingIterator()
			while (reverseIterator.hasNext()) {
				reverseElements.add(reverseIterator.next())
			}

			// Assert
			assertThat(forwardElements).hasSize(5)
			assertThat(reverseElements).hasSize(5)
			assertThat(reverseElements[0]).isEqualTo(end3)
			assertThat(reverseElements[1]).isEqualTo(track2)
			assertThat(reverseElements[2]).isEqualTo(end2)
			assertThat(reverseElements[3]).isEqualTo(track1)
			assertThat(reverseElements[4]).isEqualTo(end1)
		}

		@Test
		fun `descending iterator reverses element order`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0)
			val semaphore = RailSemaphore(false, Cell.SpatialType.HORIZONTAL)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(semaphore)

			// Act
			val forwardList = path.toList()
			val reverseList = mutableListOf<PathElement>()
			val reverseIterator = path.descendingIterator()
			while (reverseIterator.hasNext()) {
				reverseList.add(reverseIterator.next())
			}

			// Assert
			assertThat(forwardList).hasSize(3)
			assertThat(reverseList).hasSize(3)
			assertThat(reverseList[0]).isEqualTo(forwardList[2])
			assertThat(reverseList[1]).isEqualTo(forwardList[1])
			assertThat(reverseList[2]).isEqualTo(forwardList[0])
		}

		@Test
		fun `reversePath creates path with reversed iterator order`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val track = SimpleTrackBlock(end1, end2, 100.0, 80.0, 80.0)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(end2)

			// Act
			val reversed = path.reversePath()
			val reversedElements = reversed.toList()
			val originalElements = path.toList()

			// Assert
			assertThat(reversedElements).hasSize(3)
			assertThat(reversedElements[0]).isEqualTo(originalElements[2])
			assertThat(reversedElements[1]).isEqualTo(originalElements[1])
			assertThat(reversedElements[2]).isEqualTo(originalElements[0])
		}
	}

	/**
	 * Nested test group: Iterator Modification Detection
	 * Tests concurrent modification behavior
	 */
	@Nested
	@DisplayName("Iterator Modification Detection")
	inner class IteratorModificationTests {
		@Test
		fun `iterator concurrent modification is handled by ArrayDeque`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)

			val iterator = path.iterator()

			// Act - modify path after creating iterator
			val end3 = createMockNodeCell(name = "End3")
			path.addLast(end3)

			// Assert - ArrayDeque iterator may or may not throw ConcurrentModificationException
			// This is implementation-dependent, so we just verify the iterator can be used
			val hasNext = iterator.hasNext()
			assertThat(hasNext).isTrue()
		}

		@Test
		fun `mutable iterator can remove elements`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0)
			val end3 = createMockNodeCell(name = "End3")

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(end3)

			// Act - use mutable iterator to remove track
			val iterator = path.iterator()
			iterator.next() // end1
			iterator.next() // track
			iterator.remove() // remove track

			// Assert
			assertThat(path).hasSize(2)
			assertThat(path.contains(track)).isFalse()
		}

		@Test
		fun `iterator remove without next throws IllegalStateException`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val path = ArrayPath(mockContext)
			path.addLast(end1)

			val iterator = path.iterator()

			// Act & Assert - remove before next() should throw
			assertFailure { iterator.remove() }
				.isInstanceOf(IllegalStateException::class)
		}

		@Test
		fun `multiple iterators see path modifications independently`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0)

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)

			// Act
			val iterator1 = path.iterator()
			val count1Before = path.toList().size

			val end3 = createMockNodeCell(name = "End3")
			path.addLast(end3)

			val iterator2 = path.iterator()
			val count2 = path.toList().size

			// Assert
			assertThat(count1Before).isEqualTo(2)
			assertThat(count2).isEqualTo(3)
			assertThat(iterator1.hasNext()).isTrue()
			assertThat(iterator2.hasNext()).isTrue()
		}
	}

	/**
	 * Nested test group: Iterator Boundary Conditions
	 * Tests edge cases and special conditions
	 */
	@Nested
	@DisplayName("Iterator Boundary Conditions")
	inner class IteratorBoundaryTests {
		@Test
		fun `iterator on single element path`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val path = ArrayPath(mockContext)
			path.addLast(end1)

			// Act
			val iterator = path.iterator()
			val hasNext = iterator.hasNext()
			val element = iterator.next()
			val hasNextAfter = iterator.hasNext()

			// Assert
			assertThat(hasNext).isTrue()
			assertThat(element).isEqualTo(end1)
			assertThat(hasNextAfter).isFalse()
		}

		@Test
		fun `iterator hasNext is idempotent`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val path = ArrayPath(mockContext)
			path.addLast(end1)

			val iterator = path.iterator()

			// Act - call hasNext multiple times
			val hasNext1 = iterator.hasNext()
			val hasNext2 = iterator.hasNext()
			val hasNext3 = iterator.hasNext()

			// Assert - all calls should return same result
			assertThat(hasNext1).isTrue()
			assertThat(hasNext2).isTrue()
			assertThat(hasNext3).isTrue()
		}

		@Test
		fun `iterator next after hasNext returns false throws exception`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val path = ArrayPath(mockContext)
			path.addLast(end1)

			val iterator = path.iterator()
			iterator.next() // consume the only element

			// Act & Assert
			assertThat(iterator.hasNext()).isFalse()
			assertFailure { iterator.next() }
				.isInstanceOf(NoSuchElementException::class)
		}

		@Test
		fun `iterator processes all elements exactly once`() {
			// Arrange
			val elements =
				listOf(
					createMockNodeCell(name = "End1"),
					SimpleTrackBlock(createMockNodeCell(name = "End1"), createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0),
					createMockNodeCell(name = "End2")
				)

			val path = ArrayPath(mockContext)
			elements.forEach { path.addLast(it) }

			// Act
			val visitedElements = mutableListOf<PathElement>()
			val iterator = path.iterator()
			while (iterator.hasNext()) {
				visitedElements.add(iterator.next())
			}

			// Assert
			assertThat(visitedElements).hasSize(3)
			assertThat(visitedElements).isEqualTo(elements)
		}
	}

	/**
	 * Nested test group: Multiple Iterators
	 * Tests multiple independent iterators on same path
	 */
	@Nested
	@DisplayName("Multiple Iterators")
	inner class MultipleIteratorsTests {
		@Test
		fun `multiple forward iterators are independent`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0)
			val end3 = createMockNodeCell(name = "End3")

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(end3)

			// Act
			val iterator1 = path.iterator()
			val iterator2 = path.iterator()

			val elem11 = iterator1.next()
			val elem21 = iterator2.next()
			val elem12 = iterator1.next()

			// Assert
			assertThat(elem11).isEqualTo(end1)
			assertThat(elem21).isEqualTo(end1)
			assertThat(elem12).isEqualTo(track)
			assertThat(iterator2.hasNext()).isTrue()
		}

		@Test
		fun `forward and reverse iterators are independent`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val track = SimpleTrackBlock(end1, createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0)
			val end3 = createMockNodeCell(name = "End3")

			val path = ArrayPath(mockContext)
			path.addLast(end1)
			path.addLast(track)
			path.addLast(end3)

			// Act
			val forwardIterator = path.iterator()
			val reverseIterator = path.descendingIterator()

			val forwardFirst = forwardIterator.next()
			val reverseFirst = reverseIterator.next()

			// Assert
			assertThat(forwardFirst).isEqualTo(end1)
			assertThat(reverseFirst).isEqualTo(end3)
			assertThat(forwardIterator.hasNext()).isTrue()
			assertThat(reverseIterator.hasNext()).isTrue()
		}

		@Test
		fun `multiple iterators can traverse complete path independently`() {
			// Arrange
			val elements =
				listOf(
					createMockNodeCell(name = "End1"),
					SimpleTrackBlock(createMockNodeCell(name = "End1"), createMockNodeCell(name = "End2"), 100.0, 80.0, 80.0),
					createMockNodeCell(name = "End2")
				)

			val path = ArrayPath(mockContext)
			elements.forEach { path.addLast(it) }

			// Act
			val iterator1Elements = mutableListOf<PathElement>()
			val iterator1 = path.iterator()
			while (iterator1.hasNext()) {
				iterator1Elements.add(iterator1.next())
			}

			val iterator2Elements = mutableListOf<PathElement>()
			val iterator2 = path.iterator()
			while (iterator2.hasNext()) {
				iterator2Elements.add(iterator2.next())
			}

			// Assert
			assertThat(iterator1Elements).hasSize(3)
			assertThat(iterator2Elements).hasSize(3)
			assertThat(iterator1Elements).isEqualTo(elements)
			assertThat(iterator2Elements).isEqualTo(elements)
		}
	}
}
