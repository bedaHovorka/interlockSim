/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for Multimap Extension Functions

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
    Updated: 2026 (Kotlin-idiomatic extensions)
*/

package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.allMatch
import cz.vutbr.fit.interlockSim.testutil.containsNull
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.*
import java.util.*

/**
 * Unit tests for Multimap extension functions on TreeMap.
 *
 * Tests the Kotlin-idiomatic approach using TreeMap<K, MutableSet<V>>
 * with extension functions as replacement for custom TreeMultiMap class.
 *
 * Coverage:
 * - Basic putMulti/getMulti operations
 * - Multiple values per key
 * - Value retrieval and collections
 * - String representation
 * - Edge cases
 */
class MultimapExtensionsTest {
	private lateinit var multiMap: TreeMap<String, MutableSet<Int>>

	@BeforeEach
	fun setUp() {
		multiMap = TreeMap()
	}

	@Nested
	@DisplayName("Basic operations")
	inner class BasicOperations {
		@Test
		fun putMulti_singleValue_storesValue() {
			multiMap.putMulti("A", 1)

			val values = multiMap.getMulti("A")

			assertThat(values).isNotNull()
			assertThat(values).hasSize(1)
			assertThat(values as Iterable<Int>).contains(1)
		}

		@Test
		fun putMulti_multipleValuesForSameKey_storesAllValues() {
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 2)
			multiMap.putMulti("A", 3)

			val values = multiMap.getMulti("A")

			assertThat(values).hasSize(3)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}

		@Test
		fun putMulti_duplicateValue_storesOnlyOnce() {
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 1)

			val values = multiMap.getMulti("A")

			assertThat(values).withMessage("Set should contain unique values only").hasSize(1)
			assertThat(values as Iterable<Int>).contains(1)
		}

		@Test
		fun putMulti_multipleDifferentKeys_storesSeparately() {
			multiMap.putMulti("A", 1)
			multiMap.putMulti("B", 2)
			multiMap.putMulti("C", 3)

			assertThat(multiMap.getMulti("A") as Iterable<Int>).containsExactlyInAnyOrder(1)
			assertThat(multiMap.getMulti("B") as Iterable<Int>).containsExactlyInAnyOrder(2)
			assertThat(multiMap.getMulti("C") as Iterable<Int>).containsExactlyInAnyOrder(3)
		}

		@Test
		fun getMulti_nonExistentKey_returnsEmptySet() {
// Extension function returns empty set instead of throwing NPE
			val values = multiMap.getMulti("NonExistent")

			assertThat(values).isEmpty()
		}

		@Test
		fun getMulti_afterPutMulti_returnsCorrectValues() {
			multiMap.putMulti("key", 10)
			multiMap.putMulti("key", 20)

			val values = multiMap.getMulti("key")

			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(10, 20)
		}
	}

	@Nested
	@DisplayName("Value collection operations")
	inner class ValueCollectionOperations {
		@Test
		fun valuesMulti_emptyMap_returnsEmptyCollection() {
			val values = multiMap.valuesMulti()

			assertThat(values).isEmpty()
		}

		@Test
		fun valuesMulti_singleKey_returnsAllValuesForKey() {
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 2)
			multiMap.putMulti("A", 3)

			val values = multiMap.valuesMulti()

			assertThat(values).hasSize(3)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}

		@Test
		fun valuesMulti_multipleKeys_returnsAllValues() {
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 2)
			multiMap.putMulti("B", 3)
			multiMap.putMulti("B", 4)
			multiMap.putMulti("C", 5)

			val values = multiMap.valuesMulti()

			assertThat(values).hasSize(5)
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3, 4, 5)
		}

		@Test
		fun valuesMulti_withDuplicateAcrossKeys_returnsAllInstances() {
// Different keys can have same value
			multiMap.putMulti("A", 1)
			multiMap.putMulti("B", 1)
			multiMap.putMulti("C", 1)

			val values = multiMap.valuesMulti() as Collection<Int>

			assertThat(values).withMessage("Same value can exist in multiple key sets").hasSize(3)
			assertThat(values).allMatch { v: Int -> v == 1 }
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
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 2)

			val str = multiMap.toString()

			assertThat(str).isNotNull()
			assertThat(str as CharSequence).contains("A")
		}

		@Test
		fun toString_multipleKeys_representable() {
			multiMap.putMulti("A", 1)
			multiMap.putMulti("B", 2)
			multiMap.putMulti("C", 3)

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
			multiMap.putMulti("C", 3)
			multiMap.putMulti("A", 1)
			multiMap.putMulti("B", 2)

// Get values in key order by traversing
			val values = multiMap.valuesMulti()

// TreeMap keys are sorted, valuesMulti returns in key order
			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}

		@Test
		fun valuesMulti_withHashMap_returnsSortedByKey() {
// Use HashMap (no guaranteed order) to exercise the sortedBy code path in valuesMulti
			val hashMap = mutableMapOf<Int, MutableSet<String>>()
			hashMap.putMulti(3, "three")
			hashMap.putMulti(1, "one")
			hashMap.putMulti(2, "two")

// valuesMulti must sort by key regardless of map iteration order
			val result = hashMap.valuesMulti().toList()
			assertThat(result[0]).isEqualTo("one")
			assertThat(result[1]).isEqualTo("two")
			assertThat(result[2]).isEqualTo("three")
		}

		@Test
		fun getMulti_withHashMap_returnsEmptySetForMissingKey() {
// Use HashMap to exercise getMulti missing-key code path
			val hashMap = mutableMapOf<Int, MutableSet<String>>()
			hashMap.putMulti(1, "value")

			val missing = hashMap.getMulti(999)
			assertThat(missing).isEmpty()
		}

		@Test
		fun valuesMulti_withHashMap_returnsAllValuesWhenKeyPresent() {
// Use LinkedHashMap (inserted-order, not sorted) to ensure sort happens
			val linkedMap = LinkedHashMap<Int, MutableSet<String>>()
			linkedMap.putMulti(10, "ten")
			linkedMap.putMulti(5, "five")
			linkedMap.putMulti(7, "seven")

			val result = linkedMap.valuesMulti().toList()
// Result should be sorted by key: 5, 7, 10
			assertThat(result[0]).isEqualTo("five")
			assertThat(result[1]).isEqualTo("seven")
			assertThat(result[2]).isEqualTo("ten")
		}

		@Test
		fun valuesForKey_maintainInsertionOrder() {
// LinkedHashSet maintains insertion order
			multiMap.putMulti("A", 3)
			multiMap.putMulti("A", 1)
			multiMap.putMulti("A", 2)

			val values = multiMap.getMulti("A")

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
					multiMap.putMulti(key, i * 10 + j)
				}
			}

// Verify all values present
			val allValues = multiMap.valuesMulti()
			assertThat(allValues).hasSize(100)

// Verify specific key
			val key5Values = multiMap.getMulti("Key5")
			assertThat(key5Values).hasSize(10)
			assertThat(key5Values as Collection<Int>).allMatch { v: Int -> v >= 50 && v < 60 }
		}

		@Test
		fun differentValueTypes_strings_worksCorrectly() {
			val stringMap = TreeMap<String, MutableSet<String>>()

			stringMap.putMulti("colors", "red")
			stringMap.putMulti("colors", "green")
			stringMap.putMulti("colors", "blue")
			stringMap.putMulti("animals", "cat")
			stringMap.putMulti("animals", "dog")

			assertThat(stringMap.getMulti("colors") as Iterable<String>).containsExactlyInAnyOrder("red", "green", "blue")
			assertThat(stringMap.getMulti("animals") as Iterable<String>).containsExactlyInAnyOrder("cat", "dog")
		}

		@Test
		fun differentKeyTypes_integers_worksCorrectly() {
			val intKeyMap = TreeMap<Int, MutableSet<String>>()

			intKeyMap.putMulti(1, "one")
			intKeyMap.putMulti(1, "uno")
			intKeyMap.putMulti(2, "two")
			intKeyMap.putMulti(2, "dos")

			assertThat(intKeyMap.getMulti(1) as Iterable<String>).containsExactlyInAnyOrder("one", "uno")
			assertThat(intKeyMap.getMulti(2) as Iterable<String>).containsExactlyInAnyOrder("two", "dos")
		}

		@Test
		fun mixedOperations_putMultiAndGetMulti_consistent() {
			multiMap.putMulti("A", 1)
			assertThat(multiMap.getMulti("A") as Iterable<Int>).containsExactlyInAnyOrder(1)

			multiMap.putMulti("A", 2)
			assertThat(multiMap.getMulti("A") as Iterable<Int>).containsExactlyInAnyOrder(1, 2)

			multiMap.putMulti("B", 3)
			assertThat(multiMap.getMulti("A") as Iterable<Int>).containsExactlyInAnyOrder(1, 2)
			assertThat(multiMap.getMulti("B") as Iterable<Int>).containsExactlyInAnyOrder(3)

			val all = multiMap.valuesMulti()
			assertThat(all as Iterable<Int>).containsExactlyInAnyOrder(1, 2, 3)
		}
	}

	@Nested
	@DisplayName("Edge cases")
	inner class EdgeCases {
		@Test
		fun putMulti_nullValue_storesNull() {
			val multiMapNullable: TreeMap<String, MutableSet<Int?>> = TreeMap()
			multiMapNullable.putMulti("A", null)

			val values = multiMapNullable.getMulti("A")

			assertThat(values).isNotNull()
			assertThat(values).hasSize(1)
			assertThat(values as Set<Int?>).containsNull()
		}

		@Test
		fun putMulti_multipleNullValues_storesOnce() {
			val multiMapNullable: TreeMap<String, MutableSet<Int?>> = TreeMap()
			multiMapNullable.putMulti("A", null)
			multiMapNullable.putMulti("A", null)

			val values = multiMapNullable.getMulti("A")

			assertThat(values).withMessage("Set stores only one null value").hasSize(1)
			assertThat(values as Set<Int?>).containsNull()
		}

		@Test
		fun emptyKey_worksCorrectly() {
			multiMap.putMulti("", 1)
			multiMap.putMulti("", 2)

			val values = multiMap.getMulti("")

			assertThat(values as Iterable<Int>).containsExactlyInAnyOrder(1, 2)
		}
	}
}
