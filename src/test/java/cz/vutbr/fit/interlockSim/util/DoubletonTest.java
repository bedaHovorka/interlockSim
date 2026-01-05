/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for Doubleton

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link Doubleton}.
 *
 * Coverage:
 * - Construction and validation
 * - Set operations (contains, iterator, size)
 * - Value storage and retrieval
 * - Hash code and equality
 * - Error handling and edge cases
 */
class DoubletonTest {

	@Nested
	@DisplayName("Construction and validation")
	class Construction {

		@Test
		void constructor_twoDistinctElements_createsDoubleton() {
			Doubleton<String, Integer> doubleton = new Doubleton<>("A", "B");

			assertThat(doubleton).isNotNull();
			assertThat(doubleton.size()).isEqualTo(2);
		}

		@Test
		void constructor_withAdditionalInfo_storesValues() {
			Doubleton<String, Integer> doubleton = new Doubleton<>("A", "B", 100, 200);

			assertThat(doubleton).isNotNull();
			assertThat(doubleton.getValue("A")).isEqualTo(100);
			assertThat(doubleton.getValue("B")).isEqualTo(200);
		}

		@Test
		void constructor_sameObject_throwsException() {
			String same = "A";

			assertThatThrownBy(() -> new Doubleton<>(same, same))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("equal");
		}

		@Test
		void constructor_equalObjects_throwsException() {
			String a = new String("A");
			String b = new String("A");

			assertThatThrownBy(() -> new Doubleton<>(a, b))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("equal");
		}

		@Test
		void constructor_nullElements_allowed() {
			Doubleton<String, Integer> doubleton = new Doubleton<>(null, "B");

			assertThat(doubleton).isNotNull();
			assertThat(doubleton.size()).isEqualTo(2);
		}
	}

	@Nested
	@DisplayName("Set operations")
	class SetOperations {

		private Doubleton<String, Integer> doubleton;

		@BeforeEach
		void setUp() {
			doubleton = new Doubleton<>("A", "B");
		}

		@Test
		void size_alwaysReturnsTwo() {
			assertThat(doubleton.size()).isEqualTo(2);
		}

		@Test
		void isEmpty_alwaysReturnsFalse() {
			assertThat(doubleton.isEmpty()).isFalse();
		}

		@Test
		void contains_firstElement_returnsTrue() {
			assertThat(doubleton.contains("A")).isTrue();
		}

		@Test
		void contains_secondElement_returnsTrue() {
			assertThat(doubleton.contains("B")).isTrue();
		}

		@Test
		void contains_nonExistentElement_returnsFalse() {
			assertThat(doubleton.contains("C")).isFalse();
		}

		@Test
		void contains_nullElement_worksCorrectly() {
			Doubleton<String, Integer> withNull = new Doubleton<>(null, "B");

			assertThat(withNull.contains(null)).isTrue();
			assertThat(withNull.contains("B")).isTrue();
			assertThat(withNull.contains("A")).isFalse();
		}

		@Test
		void iterator_iteratesOverBothElements() {
			Iterator<String> it = doubleton.iterator();

			assertThat(it.hasNext()).isTrue();
			String first = it.next();
			assertThat(it.hasNext()).isTrue();
			String second = it.next();
			assertThat(it.hasNext()).isFalse();

			assertThat(Set.of(first, second))
				.as("Iterator should return both elements in some order")
				.containsExactlyInAnyOrder("A", "B");
		}

		@Test
		void iterator_afterExhaustion_throwsException() {
			Iterator<String> it = doubleton.iterator();
			it.next();
			it.next();

			assertThatThrownBy(() -> it.next())
				.isInstanceOf(NoSuchElementException.class);
		}

		@Test
		void iterator_remove_throwsException() {
			Iterator<String> it = doubleton.iterator();
			it.next();

			assertThatThrownBy(() -> it.remove())
				.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("Value operations")
	class ValueOperations {

		private Doubleton<String, Integer> doubleton;

		@BeforeEach
		void setUp() {
			doubleton = new Doubleton<>("A", "B", 100, 200);
		}

		@Test
		void getValue_firstElement_returnsFirstValue() {
			assertThat(doubleton.getValue("A")).isEqualTo(100);
		}

		@Test
		void getValue_secondElement_returnsSecondValue() {
			assertThat(doubleton.getValue("B")).isEqualTo(200);
		}

		@Test
		void getValue_nonExistentElement_throwsException() {
			assertThatThrownBy(() -> doubleton.getValue("C"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not contain");
		}

		@Test
		void setValues_updatesValues() {
			doubleton.setValues(300, 400);

			assertThat(doubleton.getValue("A")).isEqualTo(300);
			assertThat(doubleton.getValue("B")).isEqualTo(400);
		}

		@Test
		void setValues_nullValues_allowed() {
			doubleton.setValues(null, null);

			assertThat(doubleton.getValue("A")).isNull();
			assertThat(doubleton.getValue("B")).isNull();
		}

		@Test
		void getValue_nullKey_worksCorrectly() {
			Doubleton<String, Integer> withNull = new Doubleton<>(null, "B", 100, 200);

			assertThat(withNull.getValue(null)).isEqualTo(100);
			assertThat(withNull.getValue("B")).isEqualTo(200);
		}
	}

	@Nested
	@DisplayName("Hash code and equality")
	class HashCodeAndEquality {

		@Test
		void hashCode_sameElements_sameHashCode() {
			Doubleton<String, Integer> d1 = new Doubleton<>("A", "B");
			Doubleton<String, Integer> d2 = new Doubleton<>("A", "B");

			assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
		}

		@Test
		void hashCode_reversedElements_sameHashCode() {
			// Unordered pair property: {A, B} should equal {B, A}
			Doubleton<String, Integer> d1 = new Doubleton<>("A", "B");
			Doubleton<String, Integer> d2 = new Doubleton<>("B", "A");

			assertThat(d1.hashCode())
				.as("Hash code should be commutative")
				.isEqualTo(d2.hashCode());
		}

		@Test
		void hashCode_differentElements_mayDiffer() {
			Doubleton<String, Integer> d1 = new Doubleton<>("A", "B");
			Doubleton<String, Integer> d2 = new Doubleton<>("C", "D");

			// Not guaranteed to differ, but likely
			// Just verifying it doesn't crash and returns valid hash codes
			int h1 = d1.hashCode();
			int h2 = d2.hashCode();
			// Primitive ints are never null, so we just verify the method executes without error
			// The fact that we got here means hashCode() didn't throw an exception
		}

		@Test
		void equals_sameElements_equal() {
			Doubleton<String, Integer> d1 = new Doubleton<>("A", "B");
			Doubleton<String, Integer> d2 = new Doubleton<>("A", "B");

			// Using AbstractSet's equals implementation
			assertThat(d1).isEqualTo(d2);
		}

		@Test
		void equals_reversedElements_equal() {
			Doubleton<String, Integer> d1 = new Doubleton<>("A", "B");
			Doubleton<String, Integer> d2 = new Doubleton<>("B", "A");

			assertThat(d1)
				.as("Doubleton should be order-independent")
				.isEqualTo(d2);
		}

		@Test
		void equals_nullElements_handledCorrectly() {
			Doubleton<String, Integer> d1 = new Doubleton<>(null, "B");
			Doubleton<String, Integer> d2 = new Doubleton<>(null, "B");

			assertThat(d1).isEqualTo(d2);
		}
	}

	@Nested
	@DisplayName("Integration and complex scenarios")
	class ComplexScenarios {

		@Test
		void asMapKey_worksInHashMap() {
			Map<Doubleton<String, Integer>, String> map = new HashMap<>();
			Doubleton<String, Integer> key1 = new Doubleton<>("A", "B");
			Doubleton<String, Integer> key2 = new Doubleton<>("B", "A"); // Reversed

			map.put(key1, "value");

			// Both keys should access the same value due to commutative hashCode/equals
			assertThat(map.get(key1)).isEqualTo("value");
			assertThat(map.get(key2))
				.as("Reversed key should access same entry")
				.isEqualTo("value");
		}

		@Test
		void withIntegerTypes_worksCorrectly() {
			Doubleton<Integer, String> doubleton = new Doubleton<>(1, 2, "one", "two");

			assertThat(doubleton.contains(1)).isTrue();
			assertThat(doubleton.contains(2)).isTrue();
			assertThat(doubleton.getValue(1)).isEqualTo("one");
			assertThat(doubleton.getValue(2)).isEqualTo("two");
		}

		@Test
		void conversionToList_containsBothElements() {
			Doubleton<String, Integer> doubleton = new Doubleton<>("A", "B");

			List<String> list = new ArrayList<>(doubleton);

			assertThat(list)
				.hasSize(2)
				.containsExactlyInAnyOrder("A", "B");
		}

		@Test
		void withComplexObjects_worksCorrectly() {
			List<String> list1 = Arrays.asList("a", "b");
			List<String> list2 = Arrays.asList("c", "d");

			Doubleton<List<String>, Integer> doubleton = new Doubleton<>(list1, list2);

			assertThat(doubleton.contains(list1)).isTrue();
			assertThat(doubleton.contains(list2)).isTrue();
			assertThat(doubleton.size()).isEqualTo(2);
		}
	}
}
