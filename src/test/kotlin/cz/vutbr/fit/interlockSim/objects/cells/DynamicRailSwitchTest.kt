/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for DynamicRailSwitch wrapper class
 *
 * Verifies:
 * - Dynamic state management (configuration, lock state)
 * - Stable identity (equals/hashCode based on static object)
 * - Safety constraints (SI-5: cannot change when locked)
 */
class DynamicRailSwitchTest {
	private lateinit var staticSwitch1: RailSwitch
	private lateinit var staticSwitch2: RailSwitch
	private lateinit var dynamicSwitch1: DynamicRailSwitch
	private lateinit var dynamicSwitch2: DynamicRailSwitch

	@BeforeEach
	fun setUp() {
		// Create static switches (these represent editing-time configuration)
		staticSwitch1 = RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_LEFT_FALSE)
		staticSwitch2 = RailSwitch(Cell.SpatialType.VERTICAL, Type.SIMPLE_RIGHT_TRUE)

		// Create dynamic wrappers
		dynamicSwitch1 = DynamicRailSwitch(staticSwitch1)
		dynamicSwitch2 = DynamicRailSwitch(staticSwitch2)
	}

	@Test
	fun `initial configuration is MAIN`() {
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.MAIN)
		assertThat(dynamicSwitch1.isNormal()).isTrue()
		assertThat(dynamicSwitch1.isReverse()).isFalse()
	}

	@Test
	fun `initial lock state is unlocked`() {
		assertThat(dynamicSwitch1.locked).isFalse()
	}

	@Test
	fun `can change configuration when unlocked`() {
		// Initially MAIN
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.MAIN)

		// Change to BRANCH
		dynamicSwitch1.changeConf()
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.BRANCH)
		assertThat(dynamicSwitch1.isReverse()).isTrue()

		// Change back to MAIN
		dynamicSwitch1.changeConf()
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.MAIN)
		assertThat(dynamicSwitch1.isNormal()).isTrue()
	}

	@Test
	fun `cannot change configuration when locked`() {
		// Lock the switch
		dynamicSwitch1.lock()
		assertThat(dynamicSwitch1.locked).isTrue()

		// Attempt to change configuration
		assertFailure { dynamicSwitch1.changeConf() }
			.isInstanceOf(IllegalStateException::class)
			.message()
			.isNotNull()
			.contains("Cannot change switch configuration while locked", "SI-5")
	}

	@Test
	fun `can lock and unlock switch`() {
		// Initially unlocked
		assertThat(dynamicSwitch1.locked).isFalse()

		// Lock
		dynamicSwitch1.lock()
		assertThat(dynamicSwitch1.locked).isTrue()

		// Unlock
		dynamicSwitch1.unlock()
		assertThat(dynamicSwitch1.locked).isFalse()
	}

	@Test
	fun `can change configuration after unlocking`() {
		// Lock and try to change
		dynamicSwitch1.lock()
		assertFailure { dynamicSwitch1.changeConf() }
			.isInstanceOf(IllegalStateException::class.java)

		// Unlock and change
		dynamicSwitch1.unlock()
		dynamicSwitch1.changeConf()

		// Verify change succeeded
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.BRANCH)
	}

	@Test
	fun `configuration state is independent for different dynamic wrappers`() {
		// Set different configurations
		dynamicSwitch1.changeConf() // BRANCH
		// dynamicSwitch2 stays MAIN

		// Verify independence
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.BRANCH)
		assertThat(dynamicSwitch2.conf).isEqualTo(Conf.MAIN)
	}

	@Test
	fun `lock state is independent for different dynamic wrappers`() {
		// Lock one switch
		dynamicSwitch1.lock()

		// Verify independence
		assertThat(dynamicSwitch1.locked).isTrue()
		assertThat(dynamicSwitch2.locked).isFalse()
	}

	@Test
	fun `equals is based on static object identity`() {
		// Same static object -> equal
		val anotherWrapper = DynamicRailSwitch(staticSwitch1)
		assertThat(dynamicSwitch1).isEqualTo(anotherWrapper)

		// Different static object -> not equal
		assertThat(dynamicSwitch1).isNotEqualTo(dynamicSwitch2)
	}

	@Test
	fun `hashCode is stable based on static object`() {
		// Hash code should be same for same static object
		val anotherWrapper = DynamicRailSwitch(staticSwitch1)
		assertThat(dynamicSwitch1.hashCode()).isEqualTo(anotherWrapper.hashCode())

		// Hash code should differ for different static objects
		assertThat(dynamicSwitch1.hashCode()).isNotEqualTo(dynamicSwitch2.hashCode())
	}

	@Test
	fun `hashCode is stable across configuration changes`() {
		// Record initial hash
		val initialHash = dynamicSwitch1.hashCode()

		// Change configuration multiple times
		dynamicSwitch1.changeConf()
		assertThat(dynamicSwitch1.hashCode()).isEqualTo(initialHash)

		dynamicSwitch1.changeConf()
		assertThat(dynamicSwitch1.hashCode()).isEqualTo(initialHash)
	}

	@Test
	fun `hashCode is stable across lock state changes`() {
		// Record initial hash
		val initialHash = dynamicSwitch1.hashCode()

		// Change lock state
		dynamicSwitch1.lock()
		assertThat(dynamicSwitch1.hashCode()).isEqualTo(initialHash)

		dynamicSwitch1.unlock()
		assertThat(dynamicSwitch1.hashCode()).isEqualTo(initialHash)
	}

	@Test
	fun `equals is stable across state changes`() {
		val anotherWrapper = DynamicRailSwitch(staticSwitch1)

		// Initially equal
		assertThat(dynamicSwitch1).isEqualTo(anotherWrapper)

		// Change one wrapper's configuration
		dynamicSwitch1.changeConf()

		// Still equal (equality based on static object, not configuration)
		assertThat(dynamicSwitch1).isEqualTo(anotherWrapper)

		// Lock one wrapper
		dynamicSwitch1.lock()

		// Still equal (equality based on static object, not lock state)
		assertThat(dynamicSwitch1).isEqualTo(anotherWrapper)
	}

	@Test
	fun `can use in hash-based collections`() {
		val set = mutableSetOf<DynamicRailSwitch>()

		// Add first wrapper
		set.add(dynamicSwitch1)
		assertThat(set).hasSize(1)

		// Add wrapper for same static object -> should not increase size
		val anotherWrapper1 = DynamicRailSwitch(staticSwitch1)
		set.add(anotherWrapper1)
		assertThat(set).hasSize(1)

		// Add wrapper for different static object -> should increase size
		set.add(dynamicSwitch2)
		assertThat(set).hasSize(2)
	}

	@Test
	fun `toString includes configuration and lock state`() {
		dynamicSwitch1.changeConf()
		dynamicSwitch1.lock()
		val str = dynamicSwitch1.toString()

		assertThat(str).contains("Dynamic")
		assertThat(str).contains("BRANCH")
		assertThat(str).contains("locked=true")
	}

	@Test
	fun `static object is accessible`() {
		assertThat(dynamicSwitch1.staticRef).isSameAs(staticSwitch1)
		assertThat(dynamicSwitch2.staticRef).isSameAs(staticSwitch2)
	}

	@Test
	fun `conf property reflects current configuration`() {
		// Access via property
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.MAIN)

		// Change via method
		dynamicSwitch1.changeConf()

		// Verify via property
		assertThat(dynamicSwitch1.conf).isEqualTo(Conf.BRANCH)
	}

	@Test
	fun `locked property reflects lock state`() {
		// Access via property
		assertThat(dynamicSwitch1.locked).isFalse()

		// Change via method
		dynamicSwitch1.lock()

		// Verify via property
		assertThat(dynamicSwitch1.locked).isTrue()
	}

	@Test
	fun `static properties are delegated correctly`() {
		// Verify delegation works - properties accessible directly
		assertThat(dynamicSwitch1.type).isEqualTo(staticSwitch1.type)
		assertThat(dynamicSwitch1.getSpatialType()).isEqualTo(staticSwitch1.getSpatialType())
		assertThat(dynamicSwitch1.name).isEqualTo(staticSwitch1.getName())

		// Verify for second switch with different properties
		assertThat(dynamicSwitch2.type).isEqualTo(staticSwitch2.type)
		assertThat(dynamicSwitch2.getSpatialType()).isEqualTo(staticSwitch2.getSpatialType())
	}

	// Tests for getActiveSegments() method (visual switch direction indicator)

	@Test
	fun `getActiveSegments returns correct segments for MAIN configuration`() {
		// HORIZONTAL SIMPLE_LEFT_FALSE: MAIN direction is A-F (left-right)
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_LEFT_FALSE)
			)

		// Initial configuration is MAIN
		val segments = switch.getActiveSegments()

		// Should return the main line segments (A and F for horizontal)
		assertThat(segments).hasSize(2)
		assertThat(segments).containsAll(Cell.Segment.A, Cell.Segment.F)
	}

	@Test
	fun `getActiveSegments returns correct segments for BRANCH configuration`() {
		// HORIZONTAL SIMPLE_LEFT_FALSE: BRANCH direction is A-E (merging=A, branch=E)
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_LEFT_FALSE)
			)

		// Change to BRANCH
		switch.changeConf()
		val segments = switch.getActiveSegments()

		// Should return the branch segments (A and E for left-false)
		assertThat(segments).hasSize(2)
		assertThat(segments).containsAll(Cell.Segment.A, Cell.Segment.E)
	}

	@Test
	fun `getActiveSegments updates after changeConf`() {
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_LEFT_FALSE)
			)

		// Initially MAIN (A-F)
		val mainSegments = switch.getActiveSegments()
		assertThat(mainSegments).containsAll(Cell.Segment.A, Cell.Segment.F)

		// Change to BRANCH (A-E)
		switch.changeConf()
		val branchSegments = switch.getActiveSegments()
		assertThat(branchSegments).containsAll(Cell.Segment.A, Cell.Segment.E)

		// Change back to MAIN (A-F)
		switch.changeConf()
		val mainSegmentsAgain = switch.getActiveSegments()
		assertThat(mainSegmentsAgain).containsAll(Cell.Segment.A, Cell.Segment.F)
	}

	@Test
	fun `getActiveSegments works for VERTICAL spatial type`() {
		// VERTICAL SIMPLE_RIGHT_TRUE: MAIN direction is C-H (top-bottom)
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.VERTICAL, Type.SIMPLE_RIGHT_TRUE)
			)

		// MAIN configuration
		val mainSegments = switch.getActiveSegments()
		assertThat(mainSegments).hasSize(2)
		assertThat(mainSegments).containsAll(Cell.Segment.C, Cell.Segment.H)

		// BRANCH configuration (merging=H, branch=E for SIMPLE_RIGHT_TRUE + VERTICAL)
		switch.changeConf()
		val branchSegments = switch.getActiveSegments()
		assertThat(branchSegments).hasSize(2)
		assertThat(branchSegments).containsAll(Cell.Segment.H, Cell.Segment.E)
	}

	@Test
	fun `getActiveSegments works for SIMPLE_RIGHT_FALSE type`() {
		// HORIZONTAL SIMPLE_RIGHT_FALSE:
		// - MAIN configuration: straight route between A (left) and F (right)
		// - BRANCH configuration: diverging route between A (common/merging) and G (right-bottom branch)
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_RIGHT_FALSE)
			)

		// MAIN configuration: path A-F
		val mainSegments = switch.getActiveSegments()
		assertThat(mainSegments).containsAll(Cell.Segment.A, Cell.Segment.F)

		// BRANCH configuration: path A-G (A remains the common/merging segment, G is the branch)
		switch.changeConf()
		val branchSegments = switch.getActiveSegments()
		assertThat(branchSegments).containsAll(Cell.Segment.A, Cell.Segment.G)
	}

	@Test
	fun `getActiveSegments works for SIMPLE_LEFT_TRUE type`() {
		// HORIZONTAL SIMPLE_LEFT_TRUE: branch=D (left-bottom)
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_LEFT_TRUE)
			)

		// MAIN configuration (A-F for horizontal)
		val mainSegments = switch.getActiveSegments()
		assertThat(mainSegments).containsAll(Cell.Segment.A, Cell.Segment.F)

		// BRANCH configuration (F-D for left-true)
		switch.changeConf()
		val branchSegments = switch.getActiveSegments()
		assertThat(branchSegments).containsAll(Cell.Segment.F, Cell.Segment.D)
	}

	@Test
	fun `getActiveSegments works for SIMPLE_RIGHT_TRUE type`() {
		// HORIZONTAL SIMPLE_RIGHT_TRUE: branch=B (left-top)
		val switch =
			DynamicRailSwitch(
				RailSwitch(Cell.SpatialType.HORIZONTAL, Type.SIMPLE_RIGHT_TRUE)
			)

		// MAIN configuration (A-F for horizontal)
		val mainSegments = switch.getActiveSegments()
		assertThat(mainSegments).containsAll(Cell.Segment.A, Cell.Segment.F)

		// BRANCH configuration (F-B for right-true)
		switch.changeConf()
		val branchSegments = switch.getActiveSegments()
		assertThat(branchSegments).containsAll(Cell.Segment.F, Cell.Segment.B)
	}

	@Test
	fun `getActiveSegments returns exactly 2 segments`() {
		// All switch configurations should return exactly 2 segments
		val switchTypes =
			listOf(
				Type.SIMPLE_LEFT_FALSE,
				Type.SIMPLE_LEFT_TRUE,
				Type.SIMPLE_RIGHT_FALSE,
				Type.SIMPLE_RIGHT_TRUE
			)

		for (type in switchTypes) {
			val switch =
				DynamicRailSwitch(
					RailSwitch(Cell.SpatialType.HORIZONTAL, type)
				)

			// Test MAIN configuration
			assertThat(switch.getActiveSegments()).hasSize(2)

			// Test BRANCH configuration
			switch.changeConf()
			assertThat(switch.getActiveSegments()).hasSize(2)
		}
	}

	@Nested
	@DisplayName("Equals contract tests")
	inner class EqualsContract {
		@Test
		fun `equals with null returns false`() {
			@Suppress("KotlinConstantConditions") // Testing null comparison explicitly
			assertThat(dynamicSwitch1 == null).isFalse()
		}

		@Test
		fun `equals with self returns true`() {
			assertThat(dynamicSwitch1).isEqualTo(dynamicSwitch1)
			assertThat(dynamicSwitch1.equals(dynamicSwitch1)).isTrue()
		}

		@Test
		fun `equals with wrong type returns false`() {
			assertThat(dynamicSwitch1.equals("string")).isFalse()
			assertThat(dynamicSwitch1.equals(42)).isFalse()
			assertThat(dynamicSwitch1.equals(listOf("A", "B"))).isFalse()
		}

		@Test
		fun `equals is reflexive`() {
			assertThat(dynamicSwitch1).isEqualTo(dynamicSwitch1)
		}

		@Test
		fun `equals is symmetric`() {
			val another = DynamicRailSwitch(staticSwitch1)
			assertThat(dynamicSwitch1).isEqualTo(another)
			assertThat(another).isEqualTo(dynamicSwitch1)
		}

		@Test
		fun `equals is transitive`() {
			val x = DynamicRailSwitch(staticSwitch1)
			val y = DynamicRailSwitch(staticSwitch1)
			val z = DynamicRailSwitch(staticSwitch1)

			assertThat(x).isEqualTo(y)
			assertThat(y).isEqualTo(z)
			assertThat(x).isEqualTo(z) // Transitivity
		}

		@Test
		fun `equals with static RailSwitch returns true when same reference`() {
			assertThat(dynamicSwitch1.equals(staticSwitch1)).isTrue()
		}

		@Test
		fun `equals with different static RailSwitch returns false`() {
			assertThat(dynamicSwitch1.equals(staticSwitch2)).isFalse()
		}
	}
}
