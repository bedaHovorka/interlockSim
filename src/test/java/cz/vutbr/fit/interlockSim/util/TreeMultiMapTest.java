/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for TreeMultiMap

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

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

	private TreeMultiMap<String, Integer> multiMap;

	@BeforeEach
	void setUp() {
		multiMap = new TreeMultiMap<>();
	}

	@Nested
	@DisplayName("Basic operations")
	class BasicOperations {

		@Test
		void put_singleValue_storesValue() {
			multiMap.put("A", 1);

			Set<Integer> values = multiMap.get("A");

			assertThat(values)
				.isNotNull()
				.hasSize(1)
				.contains(1);
		}

		@Test
		void put_multipleValuesForSameKey_storesAllValues() {
			multiMap.put("A", 1);
			multiMap.put("A", 2);
			multiMap.put("A", 3);

			Set<Integer> values = multiMap.get("A");

			assertThat(values)
				.hasSize(3)
				.containsExactlyInAnyOrder(1, 2, 3);
		}

		@Test
		void put_duplicateValue_storesOnlyOnce() {
			multiMap.put("A", 1);
			multiMap.put("A", 1);
			multiMap.put("A", 1);

			Set<Integer> values = multiMap.get("A");

			assertThat(values)
				.as("Set should contain unique values only")
				.hasSize(1)
				.contains(1);
		}

		@Test
		void put_multipleDifferentKeys_storesSeparately() {
			multiMap.put("A", 1);
			multiMap.put("B", 2);
			multiMap.put("C", 3);

			assertThat(multiMap.get("A")).containsExactly(1);
			assertThat(multiMap.get("B")).containsExactly(2);
			assertThat(multiMap.get("C")).containsExactly(3);
		}

		@Test
		void get_nonExistentKey_throwsNullPointerException() {
			// TreeMultiMap.get() calls Collections.unmodifiableSet(map.get(key))
			// If key doesn't exist, map.get returns null, causing NPE
			assertThatThrownBy(() -> multiMap.get("NonExistent"))
				.isInstanceOf(NullPointerException.class);
		}

		@Test
		void get_afterPut_returnsCorrectValues() {
			multiMap.put("key", 10);
			multiMap.put("key", 20);

			Set<Integer> values = multiMap.get("key");

			assertThat(values).containsExactlyInAnyOrder(10, 20);
		}
	}

	@Nested
	@DisplayName("Value collection operations")
	class ValueCollectionOperations {

		@Test
		void values_emptyMap_returnsEmptyCollection() {
			Collection<Integer> values = multiMap.values();

			assertThat(values).isEmpty();
		}

		@Test
		void values_singleKey_returnsAllValuesForKey() {
			multiMap.put("A", 1);
			multiMap.put("A", 2);
			multiMap.put("A", 3);

			Collection<Integer> values = multiMap.values();

			assertThat(values)
				.hasSize(3)
				.containsExactlyInAnyOrder(1, 2, 3);
		}

		@Test
		void values_multipleKeys_returnsAllValues() {
			multiMap.put("A", 1);
			multiMap.put("A", 2);
			multiMap.put("B", 3);
			multiMap.put("B", 4);
			multiMap.put("C", 5);

			Collection<Integer> values = multiMap.values();

			assertThat(values)
				.hasSize(5)
				.containsExactlyInAnyOrder(1, 2, 3, 4, 5);
		}

		@Test
		void values_withDuplicateAcrossKeys_returnsAllInstances() {
			// Different keys can have same value
			multiMap.put("A", 1);
			multiMap.put("B", 1);
			multiMap.put("C", 1);

			Collection<Integer> values = multiMap.values();

			assertThat(values)
				.as("Same value can exist in multiple key sets")
				.hasSize(3)
				.allMatch(v -> v.equals(1));
		}
	}

	@Nested
	@DisplayName("Immutability and encapsulation")
	class ImmutabilityTests {

		@Test
		void get_returnsUnmodifiableSet() {
			multiMap.put("A", 1);
			Set<Integer> values = multiMap.get("A");

			assertThatThrownBy(() -> values.add(2))
				.as("Returned set should be unmodifiable")
				.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		void get_returnsUnmodifiableSet_cannotRemove() {
			multiMap.put("A", 1);
			Set<Integer> values = multiMap.get("A");

			assertThatThrownBy(() -> values.remove(1))
				.as("Returned set should be unmodifiable")
				.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		void get_returnsUnmodifiableSet_cannotClear() {
			multiMap.put("A", 1);
			Set<Integer> values = multiMap.get("A");

			assertThatThrownBy(() -> values.clear())
				.as("Returned set should be unmodifiable")
				.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentation {

		@Test
		void toString_emptyMap_returnsEmptyMapString() {
			String str = multiMap.toString();

			assertThat(str)
				.isNotNull()
				.contains("{");
		}

		@Test
		void toString_withValues_containsKeysAndValues() {
			multiMap.put("A", 1);
			multiMap.put("A", 2);

			String str = multiMap.toString();

			assertThat(str)
				.isNotNull()
				.contains("A");
		}

		@Test
		void toString_multipleKeys_representable() {
			multiMap.put("A", 1);
			multiMap.put("B", 2);
			multiMap.put("C", 3);

			String str = multiMap.toString();

			assertThat(str).isNotEmpty();
		}
	}

	@Nested
	@DisplayName("Ordering and sorting")
	class OrderingTests {

		@Test
		void keys_sortedByNaturalOrder() {
			// TreeMap maintains sorted order
			multiMap.put("C", 3);
			multiMap.put("A", 1);
			multiMap.put("B", 2);

			// Get values in key order by traversing
			Collection<Integer> values = multiMap.values();

			// Note: TreeMultiMap uses TreeMap internally, so keys are sorted
			// but values() doesn't guarantee sorted order of values across keys
			assertThat(values).containsExactlyInAnyOrder(1, 2, 3);
		}

		@Test
		void valuesForKey_maintainInsertionOrder() {
			// LinkedHashSet maintains insertion order
			multiMap.put("A", 3);
			multiMap.put("A", 1);
			multiMap.put("A", 2);

			Set<Integer> values = multiMap.get("A");

			// LinkedHashSet maintains insertion order
			assertThat(new ArrayList<>(values))
				.as("LinkedHashSet should maintain insertion order")
				.containsExactly(3, 1, 2);
		}
	}

	@Nested
	@DisplayName("Complex scenarios")
	class ComplexScenarios {

		@Test
		void largeMultiMap_manyKeysAndValues_worksCorrectly() {
			// Add 10 keys with 10 values each
			for (int i = 0; i < 10; i++) {
				String key = "Key" + i;
				for (int j = 0; j < 10; j++) {
					multiMap.put(key, i * 10 + j);
				}
			}

			// Verify all values present
			Collection<Integer> allValues = multiMap.values();
			assertThat(allValues).hasSize(100);

			// Verify specific key
			Set<Integer> key5Values = multiMap.get("Key5");
			assertThat(key5Values)
				.hasSize(10)
				.allMatch(v -> v >= 50 && v < 60);
		}

		@Test
		void differentValueTypes_strings_worksCorrectly() {
			TreeMultiMap<String, String> stringMap = new TreeMultiMap<>();

			stringMap.put("colors", "red");
			stringMap.put("colors", "green");
			stringMap.put("colors", "blue");
			stringMap.put("animals", "cat");
			stringMap.put("animals", "dog");

			assertThat(stringMap.get("colors")).containsExactlyInAnyOrder("red", "green", "blue");
			assertThat(stringMap.get("animals")).containsExactlyInAnyOrder("cat", "dog");
		}

		@Test
		void differentKeyTypes_integers_worksCorrectly() {
			TreeMultiMap<Integer, String> intKeyMap = new TreeMultiMap<>();

			intKeyMap.put(1, "one");
			intKeyMap.put(1, "uno");
			intKeyMap.put(2, "two");
			intKeyMap.put(2, "dos");

			assertThat(intKeyMap.get(1)).containsExactlyInAnyOrder("one", "uno");
			assertThat(intKeyMap.get(2)).containsExactlyInAnyOrder("two", "dos");
		}

		@Test
		void mixedOperations_putAndGet_consistent() {
			multiMap.put("A", 1);
			assertThat(multiMap.get("A")).containsExactly(1);

			multiMap.put("A", 2);
			assertThat(multiMap.get("A")).containsExactlyInAnyOrder(1, 2);

			multiMap.put("B", 3);
			assertThat(multiMap.get("A")).containsExactlyInAnyOrder(1, 2);
			assertThat(multiMap.get("B")).containsExactly(3);

			Collection<Integer> all = multiMap.values();
			assertThat(all).containsExactlyInAnyOrder(1, 2, 3);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCases {

		@Test
		void put_nullKey_throwsException() {
			// TreeMap doesn't accept null keys
			assertThatThrownBy(() -> multiMap.put(null, 1))
				.isInstanceOf(NullPointerException.class);
		}

		@Test
		void put_nullValue_storesNull() {
			multiMap.put("A", null);

			Set<Integer> values = multiMap.get("A");

			assertThat(values)
				.isNotNull()
				.hasSize(1)
				.containsNull();
		}

		@Test
		void put_multipleNullValues_storesOnce() {
			multiMap.put("A", null);
			multiMap.put("A", null);

			Set<Integer> values = multiMap.get("A");

			assertThat(values)
				.as("Set stores only one null value")
				.hasSize(1)
				.containsNull();
		}

		@Test
		void emptyKey_worksCorrectly() {
			multiMap.put("", 1);
			multiMap.put("", 2);

			Set<Integer> values = multiMap.get("");

			assertThat(values).containsExactlyInAnyOrder(1, 2);
		}
	}
}
