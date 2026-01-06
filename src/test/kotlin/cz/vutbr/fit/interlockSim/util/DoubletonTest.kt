/*
    Brno University of Technology
    Faculty of Information Technology

    BSc Thesis       2006/2007
    Railway Interlocking Simulator

    Unit tests for Doubleton

    Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
    Test implementation: 2025 (Phase 1)
*/

package cz.vutbr.fit.interlockSim.util

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.util.*

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
		fun constructor_twoDistinctElements_createsDoubleton() {
			val doubleton: Doubleton<String, Int> = Doubleton("A", "B")

			assertThat(doubleton).isNotNull()
			assertThat(doubleton.size).isEqualTo(2)
		}

		@Test
		fun constructor_withAdditionalInfo_storesValues() {
			val doubleton: Doubleton<String, Int> = Doubleton("A", "B", 100, 200)

			assertThat(doubleton).isNotNull()
			assertThat(doubleton.getValue("A")).isEqualTo(100)
			assertThat(doubleton.getValue("B")).isEqualTo(200)
		}

		@Test
		fun constructor_sameObject_throwsException() {
			val same = "A"

			assertThatThrownBy { Doubleton<String, Int>(same, same) }
				.isInstanceOf(IllegalArgumentException::class.java)
				.hasMessageContaining("equal")
		}

		@Test
		fun constructor_equalObjects_throwsException() {
			val a = "A" // Create new string to potentially have different reference
			val b = "A"

			assertThatThrownBy { Doubleton<String, Int>(a, b) }
				.isInstanceOf(IllegalArgumentException::class.java)
				.hasMessageContaining("equal")
		}

		@Test
		fun constructor_nullElements_allowed() {
			val doubleton: Doubleton<String?, Int> = Doubleton(null, "B")

			assertThat(doubleton).isNotNull()
			assertThat(doubleton.size).isEqualTo(2)
		}
	}

	@Nested
	@DisplayName("Set operations")
	class SetOperations {
		private lateinit var doubleton: Doubleton<String, Int>

		@BeforeEach
		fun setUp() {
			doubleton = Doubleton("A", "B")
		}

		@Test
		fun size_alwaysReturnsTwo() {
			assertThat(doubleton.size).isEqualTo(2)
		}

		@Test
		fun isEmpty_alwaysReturnsFalse() {
			assertThat(doubleton.isEmpty()).isFalse()
		}

		@Test
		fun contains_firstElement_returnsTrue() {
			assertThat(doubleton.contains("A")).isTrue()
		}

		@Test
		fun contains_secondElement_returnsTrue() {
			assertThat(doubleton.contains("B")).isTrue()
		}

		@Test
		fun contains_nonExistentElement_returnsFalse() {
			assertThat(doubleton.contains("C")).isFalse()
		}

		@Test
		fun contains_nullElement_worksCorrectly() {
			val withNull: Doubleton<String?, Int> = Doubleton(null, "B")

			assertThat(withNull.contains(null)).isTrue()
			assertThat(withNull.contains("B")).isTrue()
			assertThat(withNull.contains("A")).isFalse()
		}

		@Test
		fun iterator_iteratesOverBothElements() {
			val it = doubleton.iterator()

			assertThat(it.hasNext()).isTrue()
			val first = it.next()
			assertThat(it.hasNext()).isTrue()
			val second = it.next()
			assertThat(it.hasNext()).isFalse()

			assertThat(setOf(first, second))
				.`as`("Iterator should return both elements in some order")
				.containsExactlyInAnyOrder("A", "B")
		}

		@Test
		fun iterator_afterExhaustion_throwsException() {
			val it = doubleton.iterator()
			it.next()
			it.next()

			assertThatThrownBy { it.next() }
				.isInstanceOf(NoSuchElementException::class.java)
		}

		@Test
		fun iterator_remove_throwsException() {
			val it = doubleton.iterator()
			it.next()

			assertThatThrownBy { it.remove() }
				.isInstanceOf(UnsupportedOperationException::class.java)
		}
	}

	@Nested
	@DisplayName("Value operations")
	class ValueOperations {
		private lateinit var doubleton: Doubleton<String, Int>

		@BeforeEach
		fun setUp() {
			doubleton = Doubleton("A", "B", 100, 200)
		}

		@Test
		fun getValue_firstElement_returnsFirstValue() {
			assertThat(doubleton.getValue("A")).isEqualTo(100)
		}

		@Test
		fun getValue_secondElement_returnsSecondValue() {
			assertThat(doubleton.getValue("B")).isEqualTo(200)
		}

		@Test
		fun getValue_nonExistentElement_throwsException() {
			assertThatThrownBy { doubleton.getValue("C") }
				.isInstanceOf(IllegalArgumentException::class.java)
				.hasMessageContaining("not contain")
		}

		@Test
		fun setValues_updatesValues() {
			doubleton.setValues(300, 400)

			assertThat(doubleton.getValue("A")).isEqualTo(300)
			assertThat(doubleton.getValue("B")).isEqualTo(400)
		}

		@Test
		fun setValues_nullValues_allowed() {
			val nullableDoubleton: Doubleton<String, Int?> = Doubleton("A", "B")
			nullableDoubleton.setValues(null, null)

			assertThat(nullableDoubleton.getValue("A")).isNull()
			assertThat(nullableDoubleton.getValue("B")).isNull()
		}

		@Test
		fun getValue_nullKey_worksCorrectly() {
			val withNull: Doubleton<String?, Int> = Doubleton(null, "B", 100, 200)

			assertThat(withNull.getValue(null)).isEqualTo(100)
			assertThat(withNull.getValue("B")).isEqualTo(200)
		}
	}

	@Nested
	@DisplayName("Hash code and equality")
	class HashCodeAndEquality {
		@Test
		fun hashCode_sameElements_sameHashCode() {
			val d1: Doubleton<String, Int> = Doubleton("A", "B")
			val d2: Doubleton<String, Int> = Doubleton("A", "B")

			assertThat(d1.hashCode()).isEqualTo(d2.hashCode())
		}

		@Test
		fun hashCode_reversedElements_sameHashCode() {
			// Unordered pair property: {A, B} should equal {B, A}
			val d1: Doubleton<String, Int> = Doubleton("A", "B")
			val d2: Doubleton<String, Int> = Doubleton("B", "A")

			assertThat(d1.hashCode())
				.`as`("Hash code should be commutative")
				.isEqualTo(d2.hashCode())
		}

		@Test
		fun equals_sameElements_equal() {
			val d1: Doubleton<String, Int> = Doubleton("A", "B")
			val d2: Doubleton<String, Int> = Doubleton("A", "B")

			// Using AbstractSet's equals implementation
			assertThat(d1).isEqualTo(d2)
		}

		@Test
		fun equals_reversedElements_equal() {
			val d1: Doubleton<String, Int> = Doubleton("A", "B")
			val d2: Doubleton<String, Int> = Doubleton("B", "A")

			assertThat(d1)
				.`as`("Doubleton should be order-independent")
				.isEqualTo(d2)
		}

		@Test
		fun equals_nullElements_handledCorrectly() {
			val d1: Doubleton<String?, Int> = Doubleton(null, "B")
			val d2: Doubleton<String?, Int> = Doubleton(null, "B")

			assertThat(d1).isEqualTo(d2)
		}
	}

	@Nested
	@DisplayName("Integration and complex scenarios")
	class ComplexScenarios {
		@Test
		fun asMapKey_worksInHashMap() {
			val map: MutableMap<Doubleton<String, Int>, String> = HashMap()
			val key1: Doubleton<String, Int> = Doubleton("A", "B")
			val key2: Doubleton<String, Int> = Doubleton("B", "A") // Reversed

			map[key1] = "value"

			// Both keys should access the same value due to commutative hashCode/equals
			assertThat(map[key1]).isEqualTo("value")
			assertThat(map[key2])
				.`as`("Reversed key should access same entry")
				.isEqualTo("value")
		}

		@Test
		fun withIntegerTypes_worksCorrectly() {
			val doubleton: Doubleton<Int, String> = Doubleton(1, 2, "one", "two")

			assertThat(doubleton.contains(1)).isTrue()
			assertThat(doubleton.contains(2)).isTrue()
			assertThat(doubleton.getValue(1)).isEqualTo("one")
			assertThat(doubleton.getValue(2)).isEqualTo("two")
		}

		@Test
		fun conversionToList_containsBothElements() {
			val doubleton: Doubleton<String, Int> = Doubleton("A", "B")

			val list = ArrayList(doubleton)

			assertThat(list)
				.hasSize(2)
				.containsExactlyInAnyOrder("A", "B")
		}

		@Test
		fun withComplexObjects_worksCorrectly() {
			val list1 = listOf("a", "b")
			val list2 = listOf("c", "d")

			val doubleton: Doubleton<List<String>, Int> = Doubleton(list1, list2)

			assertThat(doubleton.contains(list1)).isTrue()
			assertThat(doubleton.contains(list2)).isTrue()
			assertThat(doubleton.size).isEqualTo(2)
		}
	}
}
