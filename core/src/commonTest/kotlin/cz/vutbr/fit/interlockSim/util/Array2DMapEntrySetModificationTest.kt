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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Test suite for Array2DMap EntrySet modification operations.
 * Tests compliance with Map.entrySet() contract for modifiable sets.
 *
 * Issue #58: Make EntrySet modifiable for full Map contract compliance
 */
class Array2DMapEntrySetModificationTest {
	private lateinit var map: Array2DMap<String>

	@BeforeEach
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
		// Given: EntrySet with entries
		val entrySet = map.entries
		val iterator = entrySet.iterator()

		// When: Remove first entry via iterator
		val firstEntry = iterator.next()
		val key = firstEntry.key
		iterator.remove()

		// Then: Entry removed from map
		assertThat(map.containsKey(key)).isFalse()
		assertThat(map.size).isEqualTo(4)
		assertThat(entrySet).hasSize(4)
	}

	@Test
	fun testIteratorRemoveWithoutNext() {
		// Given: Iterator not advanced
		val iterator = map.entries.iterator()

		// Then: remove() throws IllegalStateException
		assertThrows<IllegalStateException> {
			iterator.remove()
		}
	}

	@Test
	fun testIteratorRemoveCalledTwice() {
		// Given: Iterator with one entry consumed
		val iterator = map.entries.iterator()
		iterator.next()
		iterator.remove()

		// Then: Second remove() throws IllegalStateException
		assertThrows<IllegalStateException> {
			iterator.remove()
		}
	}

	@Test
	fun testSetRemoveSingleEntry() {
		// Given: EntrySet with known entry
		val entrySet = map.entries
		val entryToRemove = entrySet.first { it.key == Point(1, 0) }

		// When: Remove entry via Set.remove()
		val removed = entrySet.remove(entryToRemove)

		// Then: Entry removed successfully
		assertThat(removed).isTrue()
		assertThat(map.containsKey(Point(1, 0))).isFalse()
		assertThat(map.size).isEqualTo(4)
		assertThat(entrySet).hasSize(4)
	}

	@Test
	fun testSetRemoveNonExistentEntry() {
		// Given: EntrySet and fake entry
		val entrySet = map.entries
		val fakeEntry = object : MutableMap.MutableEntry<Point, String> {
			override val key = Point(99, 99)
			override val value = "Fake"
			override fun setValue(newValue: String) = "Fake"
		}

		// When: Try to remove non-existent entry
		val removed = entrySet.remove(fakeEntry)

		// Then: Returns false, map unchanged
		assertThat(removed).isFalse()
		assertThat(map.size).isEqualTo(5)
	}

	@Test
	fun testSetRemoveAll() {
		// Given: EntrySet with entries
		val entrySet = map.entries
		val entriesToRemove = entrySet.filter { it.key.x == 0 }.toSet()

		// When: Remove multiple entries via removeAll()
		val modified = entrySet.removeAll(entriesToRemove)

		// Then: Entries removed successfully
		assertThat(modified).isTrue()
		assertThat(map.containsKey(Point(0, 0))).isFalse()
		assertThat(map.containsKey(Point(0, 1))).isFalse()
		assertThat(map.containsKey(Point(1, 0))).isTrue()
		assertThat(map.containsKey(Point(1, 1))).isTrue()
		assertThat(map.size).isEqualTo(3)
	}

	@Test
	fun testSetRemoveAllWithEmptyCollection() {
		// Given: EntrySet with entries
		val entrySet = map.entries
		val initialSize = map.size

		// When: removeAll with empty collection
		val modified = entrySet.removeAll(emptySet())

		// Then: No changes
		assertThat(modified).isFalse()
		assertThat(map.size).isEqualTo(initialSize)
	}

	@Test
	fun testSetRetainAll() {
		// Given: EntrySet with entries
		val entrySet = map.entries
		val entriesToKeep = entrySet.filter { it.key.x == 1 }.toSet()

		// When: Retain specific entries via retainAll()
		val modified = entrySet.retainAll(entriesToKeep)

		// Then: Only retained entries remain
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
		// Given: EntrySet with entries
		val entrySet = map.entries

		// When: retainAll with empty collection
		val modified = entrySet.retainAll(emptySet())

		// Then: All entries removed
		assertThat(modified).isTrue()
		assertThat(map).isEmpty()
		assertThat(entrySet).isEmpty()
	}

	@Test
	fun testSetClear() {
		// Given: EntrySet with entries
		val entrySet = map.entries
		assertThat(entrySet).hasSize(5)

		// When: Clear the entry set
		entrySet.clear()

		// Then: All entries removed from map
		assertThat(map).isEmpty()
		assertThat(entrySet).isEmpty()
	}

	@Test
	fun testSetModificationsReflectInMap() {
		// Given: EntrySet and initial map state
		val entrySet = map.entries
		val initialKeys = map.keys.toSet()

		// When: Modify entry set
		val firstEntry = entrySet.first()
		entrySet.remove(firstEntry)

		// Then: Map reflects the change
		assertThat(map.keys).hasSize(initialKeys.size - 1)
		assertThat(map.containsKey(firstEntry.key)).isFalse()
	}

	@Test
	fun testIteratorRemoveAll() {
		// Given: EntrySet with entries
		val entrySet = map.entries

		// When: Remove all entries via iterator
		val iterator = entrySet.iterator()
		while (iterator.hasNext()) {
			iterator.next()
			iterator.remove()
		}

		// Then: Map is empty
		assertThat(map).isEmpty()
		assertThat(entrySet).isEmpty()
	}

	@Test
	fun testMixedIteratorAndSetOperations() {
		// Given: EntrySet with entries
		val entrySet = map.entries

		// When: Remove via iterator
		val iterator = entrySet.iterator()
		val firstEntry = iterator.next()
		iterator.remove()

		// And: Remove via Set.remove()
		val secondEntry = entrySet.first()
		entrySet.remove(secondEntry)

		// Then: Both removals reflected in map
		assertThat(map.containsKey(firstEntry.key)).isFalse()
		assertThat(map.containsKey(secondEntry.key)).isFalse()
		assertThat(map.size).isEqualTo(3)
	}

	@Test
	fun testEntrySetSizeConsistency() {
		// Given: Map and entry set
		val entrySet = map.entries

		// Then: Size should always match
		assertThat(entrySet.size).isEqualTo(map.size)

		// When: Modify via entry set
		entrySet.remove(entrySet.first())

		// Then: Size still matches
		assertThat(entrySet.size).isEqualTo(map.size)

		// When: Clear via entry set
		entrySet.clear()

		// Then: Both empty
		assertThat(entrySet.size).isEqualTo(0)
		assertThat(map.size).isEqualTo(0)
	}
}
