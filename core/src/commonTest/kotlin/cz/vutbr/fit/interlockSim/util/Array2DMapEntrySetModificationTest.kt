/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Test suite for Array2DMap EntrySet modification operations.
 * Tests compliance with Map.entrySet() contract for modifiable sets.
 *
 * Issue #58: Make EntrySet modifiable for full Map contract compliance
 */
class Array2DMapEntrySetModificationTest {
	private lateinit var map: Array2DMap<String>

	@BeforeTest
	fun setUp() {
		map = Array2DMap()
		// Populate with test data
		map[Point(0, 0)] = "A"
		map[Point(1, 0)] = "B"
		map[Point(0, 1)] = "C"
		map[Point(1, 1)] = "D"
		map[Point(2, 2)] = "E"
	}

	@Test
	fun testIteratorRemove() {
		// Given
		val entrySet = map.entries
		val iterator = entrySet.iterator()
		val firstEntry = iterator.next()
		val key = firstEntry.key

		// When
		iterator.remove()

		// Then
		assertThat(map.containsKey(key)).isFalse()
		assertThat(map.size).isEqualTo(4)
		assertThat(entrySet).hasSize(4)
	}

	@Test
	fun testIteratorRemoveWithoutNext() {
		// Given
		val iterator = map.entries.iterator()

		// When / Then
		assertFailsWith<IllegalStateException> {
			iterator.remove()
		}
	}

	@Test
	fun testIteratorRemoveCalledTwice() {
		// Given
		val iterator = map.entries.iterator()
		iterator.next()
		iterator.remove()

		// When / Then
		assertFailsWith<IllegalStateException> {
			iterator.remove()
		}
	}

	@Test
	fun testSetRemoveSingleEntry() {
		// Given
		val entrySet = map.entries
		val entryToRemove = entrySet.first { it.key == Point(1, 0) }

		// When
		val removed = entrySet.remove(entryToRemove)

		// Then
		assertThat(removed).isTrue()
		assertThat(map.containsKey(Point(1, 0))).isFalse()
		assertThat(map.size).isEqualTo(4)
		assertThat(entrySet).hasSize(4)
	}

	@Test
	fun testSetRemoveNonExistentEntry() {
		// Given
		val entrySet = map.entries
		val fakeEntry =
			object : MutableMap.MutableEntry<Point, String> {
				override val key = Point(99, 99)
				override val value = "Fake"

				override fun setValue(newValue: String) = "Fake"
			}

		// When
		val removed = entrySet.remove(fakeEntry)

		// Then
		assertThat(removed).isFalse()
		assertThat(map.size).isEqualTo(5)
	}

	@Test
	fun testSetRemoveAll() {
		// Given
		val entrySet = map.entries
		val entriesToRemove = entrySet.filter { it.key.x == 0 }.toSet()

		// When
		val modified = entrySet.removeAll(entriesToRemove)

		// Then
		assertThat(modified).isTrue()
		assertThat(map.containsKey(Point(0, 0))).isFalse()
		assertThat(map.containsKey(Point(0, 1))).isFalse()
		assertThat(map.containsKey(Point(1, 0))).isTrue()
		assertThat(map.containsKey(Point(1, 1))).isTrue()
		assertThat(map.size).isEqualTo(3)
	}

	@Test
	fun testSetRemoveAllWithEmptyCollection() {
		// Given
		val entrySet = map.entries
		val initialSize = map.size

		// When
		val modified = entrySet.removeAll(emptySet())

		// Then
		assertThat(modified).isFalse()
		assertThat(map.size).isEqualTo(initialSize)
	}

	@Test
	fun testSetRetainAll() {
		// Given
		val entrySet = map.entries
		val entriesToKeep = entrySet.filter { it.key.x == 1 }.toSet()

		// When
		val modified = entrySet.retainAll(entriesToKeep)

		// Then
		assertThat(modified).isTrue()
		assertThat(map.containsKey(Point(0, 0))).isFalse()
		assertThat(map.containsKey(Point(0, 1))).isFalse()
		assertThat(map.containsKey(Point(1, 0))).isTrue()
		assertThat(map.containsKey(Point(1, 1))).isTrue()
		assertThat(map.containsKey(Point(2, 2))).isFalse()
		assertThat(map.size).isEqualTo(2)
	}

	@Test
	fun testSetRetainAllWithEmptyCollection() {
		// Given
		val entrySet = map.entries

		// When
		val modified = entrySet.retainAll(emptySet())

		// Then
		assertThat(modified).isTrue()
		assertThat(map).isEmpty()
		assertThat(entrySet).isEmpty()
	}

	@Test
	fun testSetClear() {
		// Given
		val entrySet = map.entries
		assertThat(entrySet).hasSize(5)

		// When
		entrySet.clear()

		// Then
		assertThat(map).isEmpty()
		assertThat(entrySet).isEmpty()
	}

	@Test
	fun testSetModificationsReflectInMap() {
		// Given
		val entrySet = map.entries
		val initialKeys = map.keys.toSet()
		val firstEntry = entrySet.first()

		// When
		entrySet.remove(firstEntry)

		// Then
		assertThat(map.keys).hasSize(initialKeys.size - 1)
		assertThat(map.containsKey(firstEntry.key)).isFalse()
	}

	@Test
	fun testIteratorRemoveAll() {
		// Given
		val entrySet = map.entries
		val iterator = entrySet.iterator()

		// When
		while (iterator.hasNext()) {
			iterator.next()
			iterator.remove()
		}

		// Then
		assertThat(map).isEmpty()
		assertThat(entrySet).isEmpty()
	}

	@Test
	fun testMixedIteratorAndSetOperations() {
		// Given
		val entrySet = map.entries
		val iterator = entrySet.iterator()
		val firstEntry = iterator.next()

		// When
		iterator.remove()
		val secondEntry = entrySet.first()
		entrySet.remove(secondEntry)

		// Then
		assertThat(map.containsKey(firstEntry.key)).isFalse()
		assertThat(map.containsKey(secondEntry.key)).isFalse()
		assertThat(map.size).isEqualTo(3)
	}

	@Test
	fun testEntrySetSizeConsistency() {
		// Given
		val entrySet = map.entries
		assertThat(entrySet.size).isEqualTo(map.size)

		// When / Then — remove one
		entrySet.remove(entrySet.first())
		assertThat(entrySet.size).isEqualTo(map.size)

		// When / Then — clear all
		entrySet.clear()
		assertThat(entrySet.size).isEqualTo(0)
		assertThat(map.size).isEqualTo(0)
	}
}
