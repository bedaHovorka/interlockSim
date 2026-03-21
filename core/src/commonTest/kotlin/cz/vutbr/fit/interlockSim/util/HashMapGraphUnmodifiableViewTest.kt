/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Unit tests for HashMapGraph unmodifiable collection views

	Hovorka Bedrich <xhovor07@stud.fit.vutbr.cz>
	Test implementation: 2026 (Issue #59 - Unmodifiable collection views)
*/

package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests verifying that HashMapGraph returns immutable collection views.
 *
 * These tests ensure:
 * 1. Returned collections are read-only (Kotlin immutable types)
 * 2. Collections are instances of immutable/read-only types
 * 3. Multiple calls return equivalent views
 *
 * Nested classes extracted to:
 * - [HashMapGraphNodeSetViewTest]
 * - [HashMapGraphValuesViewTest]
 * - [HashMapGraphEntrySetViewTest]
 */
class HashMapGraphUnmodifiableViewTest {
	private lateinit var graph: HashMapGraph<String, Int, String>

	@BeforeTest
	fun setUp() {
		graph = HashMapGraph()
		graph.put("A", "B", 100)
		graph.put("B", "C", 150)
	}

	@Test
	fun `nodeSet is not null`() {
		assertThat(graph.nodeSet()).isNotNull()
	}

	@Test
	fun `values is not null`() {
		assertThat(graph.values()).isNotNull()
	}

	@Test
	fun `entrySet is not null`() {
		assertThat(graph.entrySet()).isNotNull()
	}
}
