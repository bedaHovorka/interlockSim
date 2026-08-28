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

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.hasMessageContaining
import kotlin.test.BeforeTest
import kotlin.test.Test
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Unit tests for [Doubleton].
 *
 * Coverage:
 * - Construction and validation
 * - Set operations (contains, iterator, size)
 * - Value storage and retrieval
 * - Hash code and equality
 * - Error handling and edge cases
 */
class DoubletonTest {
	// === Construction and validation ===

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

		assertThatBlock { Doubleton<String, Int>(same, same) }
			.isFailure()
			.isInstanceOf(IllegalArgumentException::class)
			.hasMessageContaining("equal")
	}

	@Test
	fun constructor_equalObjects_throwsException() {
		val a = "A" // Create new string to potentially have different reference
		val b = "A"

		assertThatBlock { Doubleton<String, Int>(a, b) }
			.isFailure()
			.isInstanceOf(IllegalArgumentException::class)
			.hasMessageContaining("equal")
	}

	@Test
	fun constructor_nullElements_allowed() {
		val doubleton: Doubleton<String?, Int> = Doubleton(null, "B")

		assertThat(doubleton).isNotNull()
		assertThat(doubleton.size).isEqualTo(2)
	}

	// === Set operations ===

	private lateinit var setOpsDoubleton: Doubleton<String, Int>

	@BeforeTest
	fun setUpSetOps() {
		setOpsDoubleton = Doubleton("A", "B")
	}

	@Test
	fun size_alwaysReturnsTwo() {
		assertThat(setOpsDoubleton.size).isEqualTo(2)
	}

	@Test
	fun isEmpty_alwaysReturnsFalse() {
		assertThat(setOpsDoubleton.isEmpty()).isFalse()
	}

	@Test
	fun contains_firstElement_returnsTrue() {
		assertThat(setOpsDoubleton.contains("A")).isTrue()
	}

	@Test
	fun contains_secondElement_returnsTrue() {
		assertThat(setOpsDoubleton.contains("B")).isTrue()
	}

	@Test
	fun contains_nonExistentElement_returnsFalse() {
		assertThat(setOpsDoubleton.contains("C")).isFalse()
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
		val it = setOpsDoubleton.iterator()

		assertThat(it.hasNext()).isTrue()
		val first = it.next()
		assertThat(it.hasNext()).isTrue()
		val second = it.next()
		assertThat(it.hasNext()).isFalse()

		assertThat(setOf(first, second), "Iterator should return both elements in some order")
			.containsExactlyInAnyOrder("A", "B")
	}

	@Test
	fun iterator_afterExhaustion_throwsException() {
		val it = setOpsDoubleton.iterator()
		it.next()
		it.next()

		assertThatBlock { it.next() }
			.isFailure()
			.isInstanceOf(NoSuchElementException::class)
	}

	@Test
	fun iterator_remove_throwsException() {
		val it = setOpsDoubleton.iterator()
		it.next()

		assertThatBlock { it.remove() }
			.isFailure()
			.isInstanceOf(UnsupportedOperationException::class)
	}

	// === Value operations ===

	@Test
	fun getValue_firstElement_returnsFirstValue() {
		val doubleton = Doubleton("A", "B", 100, 200)
		assertThat(doubleton.getValue("A")).isEqualTo(100)
	}

	@Test
	fun getValue_secondElement_returnsSecondValue() {
		val doubleton = Doubleton("A", "B", 100, 200)
		assertThat(doubleton.getValue("B")).isEqualTo(200)
	}

	@Test
	fun getValue_nonExistentElement_throwsException() {
		val doubleton = Doubleton("A", "B", 100, 200)
		assertThatBlock { doubleton.getValue("C") }
			.isFailure()
			.isInstanceOf(IllegalArgumentException::class)
			.hasMessageContaining("not contain")
	}

	@Test
	fun setValues_updatesValues() {
		val doubleton = Doubleton("A", "B", 100, 200)
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

	// === Hash code and equality ===

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

		assertThat(d1.hashCode(), "Hash code should be commutative")
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

		assertThat(d1, "Doubleton should be order-independent")
			.isEqualTo(d2)
	}

	@Test
	fun equals_nullElements_handledCorrectly() {
		val d1: Doubleton<String?, Int> = Doubleton(null, "B")
		val d2: Doubleton<String?, Int> = Doubleton(null, "B")

		assertThat(d1).isEqualTo(d2)
	}

	// Additional equals() contract tests for SonarQube refactoring

	@Test
	fun `equals with null returns false`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		val other: Any? = null
		assertThat(d1 == other).isFalse()
	}

	@Test
	fun `equals with self returns true`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		assertThat(d1).isEqualTo(d1)
		assertThat(d1.equals(d1)).isTrue()
	}

	@Test
	fun `equals with wrong type returns false`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		assertThat(d1.equals("string")).isFalse()
		assertThat(d1.equals(42)).isFalse()
		assertThat(d1.equals(listOf("A", "B"))).isFalse()
	}

	@Test
	fun `equals with HashSet of same elements returns true`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		val set = hashSetOf("A", "B")

		// Doubleton delegates to AbstractSet.equals() - should equal any Set with same content
		assertThat(d1).isEqualTo(set)
		assertThat(set).isEqualTo(d1) // Symmetry
	}

	@Test
	fun `equals with other set of same elements returns true`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		val set = mutableSetOf("A", "B")

		assertThat(d1).isEqualTo(set)
		assertThat(set).isEqualTo(d1)
	}

	@Test
	fun `equals with different elements returns false`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		val d2: Doubleton<String, Int> = Doubleton("C", "D")

		assertThat(d1.equals(d2)).isFalse()
	}

	@Test
	fun `equals is reflexive`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		assertThat(d1).isEqualTo(d1)
	}

	@Test
	fun `equals is symmetric with other Doubleton`() {
		val d1: Doubleton<String, Int> = Doubleton("A", "B")
		val d2: Doubleton<String, Int> = Doubleton("A", "B")

		assertThat(d1).isEqualTo(d2)
		assertThat(d2).isEqualTo(d1)
	}

	@Test
	fun `equals is transitive`() {
		val x: Doubleton<String, Int> = Doubleton("A", "B")
		val y: Doubleton<String, Int> = Doubleton("A", "B")
		val z: Doubleton<String, Int> = Doubleton("A", "B")

		assertThat(x).isEqualTo(y)
		assertThat(y).isEqualTo(z)
		assertThat(x).isEqualTo(z)
	}

	// === Integration and complex scenarios ===

	@Test
	fun asMapKey_worksInHashMap() {
		val map: MutableMap<Doubleton<String, Int>, String> = mutableMapOf()
		val key1: Doubleton<String, Int> = Doubleton("A", "B")
		val key2: Doubleton<String, Int> = Doubleton("B", "A") // Reversed

		map[key1] = "value"

		// Both keys should access the same value due to commutative hashCode/equals
		assertThat(map[key1]).isEqualTo("value")
		assertThat(map[key2], "Reversed key should access same entry")
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

		val list = doubleton.toMutableList()

		assertThat(list).hasSize(2)
		assertThat(list as Iterable<String>).containsExactlyInAnyOrder("A", "B")
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
