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
import org.junit.jupiter.api.BeforeEach
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
		assertThat(dynamicSwitch1.static).isSameAs(staticSwitch1)
		assertThat(dynamicSwitch2.static).isSameAs(staticSwitch2)
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
		assertThat(dynamicSwitch1.spatialType).isEqualTo(staticSwitch1.getSpatialType())
		assertThat(dynamicSwitch1.name).isEqualTo(staticSwitch1.getName())

		// Verify for second switch with different properties
		assertThat(dynamicSwitch2.type).isEqualTo(staticSwitch2.type)
		assertThat(dynamicSwitch2.spatialType).isEqualTo(staticSwitch2.getSpatialType())
	}
}
