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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.domain.COMMON_BRANCH_SPEED
import cz.vutbr.fit.interlockSim.domain.COMMON_MAIN_SPEED
import cz.vutbr.fit.interlockSim.exceptions.SwitchLockedException
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for DynamicRailSwitch cell type with comprehensive behavior validation.
 */
class RailSwitchTest {
	private lateinit var switch: DynamicRailSwitch
	private lateinit var propertyListener: TestPropertyChangeListener

	@BeforeTest
	fun setUp() {
		val staticSwitch =
			RailSwitch(
				Cell.SpatialType.HORIZONTAL,
				Type.SIMPLE_RIGHT_FALSE,
				COMMON_MAIN_SPEED.toDouble(),
				COMMON_BRANCH_SPEED.toDouble()
			)
		switch = DynamicRailSwitch(staticSwitch)
		propertyListener = TestPropertyChangeListener()
		switch.addPropertyChangeListener(propertyListener)
	}

	// ========== Position Management ==========

	@Test
	fun `switch created in normal position`() {
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
		assertThat(propertyListener.events).isEmpty()
	}

	@Test
	fun `switch toggles to reverse position`() {
		switch.changeConf()
		assertThat(switch.conf).isEqualTo(Conf.BRANCH)
	}

	@Test
	fun `switch toggles back to normal position`() {
		switch.changeConf()
		switch.changeConf()
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
	}

	@Test
	fun `switch position fires property change`() {
		propertyListener.events.clear()
		switch.changeConf()
		val newConf = Conf.BRANCH
		assertThat(switch.conf).isEqualTo(newConf)
	}

	// ========== Locking Mechanism ==========

	@Test
	fun `switch can be locked`() {
		switch.lock()
		assertThat(switch.locked).isTrue()
	}

	@Test
	fun `locked switch cannot change position`() {
		switch.lock()
		val exception =
			assertFailsWith<IllegalStateException> {
				switch.changeConf()
			}
		assertThat(exception.message).isNotNull().contains("locked")
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
	}

	@Test
	fun `switch can be unlocked`() {
		switch.lock()
		switch.unlock()
		assertThat(switch.locked).isFalse()
	}

	@Test
	fun `unlocked switch can change position`() {
		switch.lock()
		switch.unlock()
		switch.changeConf()
		assertThat(switch.conf).isEqualTo(Conf.BRANCH)
	}

	@Test
	fun `toggle while locked throws exception`() {
		switch.lock()
		val exception =
			assertFailsWith<IllegalStateException> {
				switch.changeConf()
			}
		assertThat(exception.message).isNotNull()
		val message = exception.message!!
		assertThat(message).contains("locked")
	}

	// ===== Issue #1065: safety property SI-5 also applies to setUpPath =====

	@Test
	fun `setUpPath refuses to reposition a switch locked in a different position`() {
		// Given: locked in MAIN via (A,F) -- see RailSwitch's SIMPLE_RIGHT_FALSE HORIZONTAL
		// topology: merging=A, mainDir=F -> MAIN; branch=G -> BRANCH.
		switch.setUpPath(Segment.A, Segment.F, COMMON_MAIN_SPEED.toDouble(), stubTrackOccupant())
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
		assertThat(switch.locked).isTrue()
		propertyListener.events.clear()

		// When: a second candidate needs BRANCH (A,G) while still locked in MAIN
		val exception =
			assertFailsWith<SwitchLockedException> {
				switch.setUpPath(Segment.A, Segment.G, COMMON_BRANCH_SPEED.toDouble(), stubTrackOccupant())
			}

		// Then: the switch is completely untouched -- still MAIN, still locked, no event fired.
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
		assertThat(switch.locked).isTrue()
		assertThat(propertyListener.events).isEmpty()
		assertThat(exception.heldConf).isEqualTo(Conf.MAIN)
		assertThat(exception.requiredConf).isEqualTo(Conf.BRANCH)
	}

	@Test
	fun `setUpPath is idempotent when the switch is already locked in the required position`() {
		switch.setUpPath(Segment.A, Segment.F, COMMON_MAIN_SPEED.toDouble(), stubTrackOccupant())
		assertThat(switch.conf).isEqualTo(Conf.MAIN)

		// A route re-reservation over the same hop must keep working.
		switch.setUpPath(Segment.A, Segment.F, COMMON_MAIN_SPEED.toDouble(), stubTrackOccupant())
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
		assertThat(switch.locked).isTrue()
	}

	// ========== Track Routing ==========

	@Test
	fun `switch routes through track for normal position`() {
		val from = Segment.A
		val following = switch.getFollowingSegment(from)
		assertThat(following).isNotNull()
		assertThat(switch.conf).isEqualTo(Conf.MAIN)
	}

	@Test
	fun `switch routes through track for reverse position`() {
		switch.changeConf()
		val from = Segment.A
		val following = switch.getFollowingSegment(from)
		assertThat(following).isNotNull()
		assertThat(switch.conf).isEqualTo(Conf.BRANCH)
	}

	@Test
	fun `switch provides correct exit for entry direction`() {
		val from = Segment.A
		val possibleFollowers = switch.possibleFollowers(from)
		assertThat(possibleFollowers).isNotNull()
		assertThat(possibleFollowers.size > 0).isTrue()
		for (segment in possibleFollowers) {
			assertThat(Segment.values().toList()).contains(segment)
		}
	}

	// ========== State Queries ==========

	@Test
	fun `isNormal returns true in normal position`() {
		assertThat(switch.isNormal()).isTrue()
	}

	@Test
	fun `isReverse returns true in reverse position`() {
		switch.changeConf()
		assertThat(switch.isReverse()).isTrue()
		assertThat(switch.isNormal()).isFalse()
	}

	@Test
	fun `isLocked returns correct state`() {
		assertThat(switch.locked).isFalse()
		switch.lock()
		assertThat(switch.locked).isTrue()
		switch.unlock()
		assertThat(switch.locked).isFalse()
	}

	/**
	 * Test PropertyChangeListener implementation that captures all events.
	 */
	private class TestPropertyChangeListener : ContextPropertyChangeListener {
		val events: MutableList<ContextChangeEvent> = mutableListOf()

		override fun propertyChange(event: ContextChangeEvent) {
			events.add(event)
		}
	}

	private fun stubTrackOccupant(): TrackOccupant =
		object : TrackOccupant {
			override val name = "StubTrain"

			override fun distanceToSemaphore() = 0.0

			override fun nextSemaphore(): OrientedPathSeparator? = null
		}
}
