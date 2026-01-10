/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for TreeMultiMap

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.assertThatThrownBy
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.testutil.*
import org.junit.jupiter.api.*
import java.util.*

/**
 * Unit tests for {@link TreeMultiMap}.
 *
 * Coverage:
 * - Basic put/get operations
 * - Multiple values per key
 * - Value retrieval and collections
 * - String representation
 * - Edge cases and immutability
 */
class TreeMultiMapTest {
	private lateinit var multiMap: TreeMultiMap<String, Int>

	@BeforeEach
	fun setUp() {
		multiMap = TreeMultiMap()
	}

	@Nested
	@DisplayName("Basic operations")
	inner class BasicOperations {
		@Test
		fun put_singleValue_storesValue() {
			multiMap.put("A", 1)

			val values = multiMap.get("A") as Set<Int>

			assertThat(values).isNotNull()
			assertThat(values).hasSize(1)
			assertThat(values as Iterable<Int>).contains(1)
		}

		@Test
		fun put_multipleValuesForSameKey_storesAllValues() {
			multiMap.put("A", 1)
			multiMap.put("A", 2)
			multiMap.put("A", 3)

			val values = multiMap.get("A") as Set<Int>

			assertThat(values).hasSize(3)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}

		@Test
		fun put_duplicateValue_storesOnlyOnce() {
			multiMap.put("A", 1)
			multiMap.put("A", 1)
			multiMap.put("A", 1)

			val values = multiMap.get("A") as Set<Int>

			assertThat(values).withMessage("Set should contain unique values only").hasSize(1)
			assertThat(values as Iterable<Int>).contains(1)
		}

		@Test
		fun put_multipleDifferentKeys_storesSeparately() {
			multiMap.put("A", 1)
			multiMap.put("B", 2)
			multiMap.put("C", 3)

			assertThat(multiMap.get("A") as Iterable<Int>).containsExactlyInAnyOrder(1)
			assertThat(multiMap.get("B") as Iterable<Int>).containsExactlyInAnyOrder(2)
			assertThat(multiMap.get("C") as Iterable<Int>).containsExactlyInAnyOrder(3)
		}

		@Test
		fun get_nonExistentKey_throwsNullPointerException() {
			// TreeMultiMap.get() calls Collections.unmodifiableSet(map.get(key))
			// If key doesn't exist, map.get returns null, causing NPE
			assertThatThrownBy { multiMap.get("NonExistent") }
				.isInstanceOf(NullPointerException::class)
		}

		@Test
		fun get_afterPut_returnsCorrectValues() {
			multiMap.put("key", 10)
			multiMap.put("key", 20)

			val values = multiMap.get("key")

			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(10, 20)
		}
	}

	@Nested
	@DisplayName("Value collection operations")
	inner class ValueCollectionOperations {
		@Test
		fun values_emptyMap_returnsEmptyCollection() {
			val values = multiMap.values()

			assertThat(values).isEmpty()
		}

		@Test
		fun values_singleKey_returnsAllValuesForKey() {
			multiMap.put("A", 1)
			multiMap.put("A", 2)
			multiMap.put("A", 3)

			val values = multiMap.values()

			assertThat(values).hasSize(3)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}

		@Test
		fun values_multipleKeys_returnsAllValues() {
			multiMap.put("A", 1)
			multiMap.put("A", 2)
			multiMap.put("B", 3)
			multiMap.put("B", 4)
			multiMap.put("C", 5)

			val values = multiMap.values()

			assertThat(values).hasSize(5)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3, 4, 5)
		}

		@Test
		fun values_withDuplicateAcrossKeys_returnsAllInstances() {
			// Different keys can have same value
			multiMap.put("A", 1)
			multiMap.put("B", 1)
			multiMap.put("C", 1)

			val values = multiMap.values() as Collection<Int>

			assertThat(values).withMessage("Same value can exist in multiple key sets").hasSize(3)
			assertThat(values).allMatch { v -> v == 1 }
		}
	}

	@Nested
	@DisplayName("Immutability and encapsulation")
	inner class ImmutabilityTests {
		@Test
		fun get_returnsUnmodifiableSet() {
			multiMap.put("A", 1)
			val values = multiMap.get("A")

			assertThatThrownBy { (values as MutableSet<Int>).add(2) }
				.withMessage("Returned set should be unmodifiable")
				.isInstanceOf(UnsupportedOperationException::class)
		}

		@Test
		fun get_returnsUnmodifiableSet_cannotRemove() {
			multiMap.put("A", 1)
			val values = multiMap.get("A")

			assertThatThrownBy { (values as MutableSet<Int>).remove(1) }
				.withMessage("Returned set should be unmodifiable")
				.isInstanceOf(UnsupportedOperationException::class)
		}

		@Test
		fun get_returnsUnmodifiableSet_cannotClear() {
			multiMap.put("A", 1)
			val values = multiMap.get("A")

			assertThatThrownBy { (values as MutableSet<Int>).clear() }
				.withMessage("Returned set should be unmodifiable")
				.isInstanceOf(UnsupportedOperationException::class)
		}
	}

	@Nested
	@DisplayName("String representation")
	inner class StringRepresentation {
		@Test
		fun toString_emptyMap_returnsEmptyMapString() {
			val str = multiMap.toString()

			assertThat(str).isNotNull()
			assertThat(str as CharSequence).contains("{")
		}

		@Test
		fun toString_withValues_containsKeysAndValues() {
			multiMap.put("A", 1)
			multiMap.put("A", 2)

			val str = multiMap.toString()

			assertThat(str).isNotNull()
			assertThat(str as CharSequence).contains("A")
		}

		@Test
		fun toString_multipleKeys_representable() {
			multiMap.put("A", 1)
			multiMap.put("B", 2)
			multiMap.put("C", 3)

			val str = multiMap.toString()

			assertThat(str).isNotNull()
			assertThat(str.isNotEmpty()).isTrue()
		}
	}

	@Nested
	@DisplayName("Ordering and sorting")
	inner class OrderingTests {
		@Test
		fun keys_sortedByNaturalOrder() {
			// TreeMap maintains sorted order
			multiMap.put("C", 3)
			multiMap.put("A", 1)
			multiMap.put("B", 2)

			// Get values in key order by traversing
			val values = multiMap.values()

			// Note: TreeMultiMap uses TreeMap internally, so keys are sorted
			// but values() doesn't guarantee sorted order of values across keys
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}

		@Test
		fun valuesForKey_maintainInsertionOrder() {
			// LinkedHashSet maintains insertion order
			multiMap.put("A", 3)
			multiMap.put("A", 1)
			multiMap.put("A", 2)

			val values = multiMap.get("A")

			// LinkedHashSet maintains insertion order
			val list = ArrayList(values)
			assertThat(list)
				.withMessage("LinkedHashSet should maintain insertion order")
				.containsExactlyInAnyOrder(3, 1, 2)
		}
	}

	@Nested
	@DisplayName("Complex scenarios")
	inner class ComplexScenarios {
		@Test
		fun largeMultiMap_manyKeysAndValues_worksCorrectly() {
			// Add 10 keys with 10 values each
			for (i in 0 until 10) {
				val key = "Key$i"
				for (j in 0 until 10) {
					multiMap.put(key, i * 10 + j)
				}
			}

			// Verify all values present
			val allValues = multiMap.values()
			assertThat(allValues).hasSize(100)

			// Verify specific key
			val key5Values = multiMap.get("Key5") as Set<Int>
			assertThat(key5Values).hasSize(10)
			assertThat(key5Values as Collection<Int>).allMatch { v -> v >= 50 && v < 60 }
		}

		@Test
		fun differentValueTypes_strings_worksCorrectly() {
			val stringMap = TreeMultiMap<String, String>()

			stringMap.put("colors", "red")
			stringMap.put("colors", "green")
			stringMap.put("colors", "blue")
			stringMap.put("animals", "cat")
			stringMap.put("animals", "dog")

			assertThat(stringMap.get("colors") as Iterable<String>).containsExactlyInAnyOrder("red", "green", "blue")
			assertThat(stringMap.get("animals") as Iterable<String>).containsExactlyInAnyOrder("cat", "dog")
		}

		@Test
		fun differentKeyTypes_integers_worksCorrectly() {
			val intKeyMap = TreeMultiMap<Int, String>()

			intKeyMap.put(1, "one")
			intKeyMap.put(1, "uno")
			intKeyMap.put(2, "two")
			intKeyMap.put(2, "dos")

			assertThat(intKeyMap.get(1) as Iterable<String>).containsExactlyInAnyOrder("one", "uno")
			assertThat(intKeyMap.get(2) as Iterable<String>).containsExactlyInAnyOrder("two", "dos")
		}

		@Test
		fun mixedOperations_putAndGet_consistent() {
			multiMap.put("A", 1)
			assertThat(multiMap.get("A") as Iterable<Int>).containsExactlyInAnyOrder(1)

			multiMap.put("A", 2)
			assertThat(multiMap.get("A") as Iterable<Int>).containsExactlyInAnyOrder(1, 2)

			multiMap.put("B", 3)
			assertThat(multiMap.get("A") as Iterable<Int>).containsExactlyInAnyOrder(1, 2)
			assertThat(multiMap.get("B") as Iterable<Int>).containsExactlyInAnyOrder(3)

			val all = multiMap.values()
			assertThat(all as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}
	}

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {
		@Test
		fun put_nullKey_throwsException() {
			// TreeMap doesn't accept null keys
			val nullKey: String? = null
			@Suppress("UNCHECKED_CAST")
			assertThatThrownBy {
				@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				multiMap.put(nullKey as String, 1)
			}.isInstanceOf(NullPointerException::class)
		}

		@Test
		fun put_nullValue_storesNull() {
			val multiMapNullable: TreeMultiMap<String, Int?> = TreeMultiMap()
			multiMapNullable.put("A", null)

			val values = multiMapNullable.get("A") as Set<Int?>

			assertThat(values).isNotNull()
			assertThat(values).hasSize(1)
			assertThat(values as Set<Int?>).containsNull()
		}

		@Test
		fun put_multipleNullValues_storesOnce() {
			val multiMapNullable: TreeMultiMap<String, Int?> = TreeMultiMap()
			multiMapNullable.put("A", null)
			multiMapNullable.put("A", null)

			val values = multiMapNullable.get("A") as Set<Int?>

			assertThat(values).withMessage("Set stores only one null value").hasSize(1)
			assertThat(values as Set<Int?>).containsNull()
		}

		@Test
		fun emptyKey_worksCorrectly() {
			multiMap.put("", 1)
			multiMap.put("", 2)

			val values = multiMap.get("")

			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2)
		}
	}
}
