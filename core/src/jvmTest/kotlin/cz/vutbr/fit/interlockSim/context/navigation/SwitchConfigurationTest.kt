package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.ContextTransformer
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.ContextChangeEvent
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Unit tests for switch configuration during path reservation.
 *
 * These tests verify that:
 * 1. Switches are configured to correct positions (MAIN/BRANCH) based on reserved paths
 * 2. PropertyChangeEvents are fired when switch configuration changes
 * 3. Switches are locked after configuration
 * 4. Multiple switches in a path are configured independently
 * 5. Switch configuration happens ONCE per reservation (no duplicates)
 *
 * ## Test Infrastructure
 *
 * Uses vyhybna.xml configuration which has:
 * - Switch vA at (15,8): connects k0 to k1/k2
 * - Switch vB at (26,8): connects k3 to k4/k5
 * - Semaphores: zA(14,8), doA1(16,8), doB1(25,8), zB(27,8), doA2(17,9), doB2(24,9)
 *
 * ## Issue #300 - Fix Switch Animation Regression
 *
 * These tests verify that switches animate to BRANCH configuration when trains
 * take branch routes, not just MAIN configuration. The root cause was duplicate
 * switch configuration (PathReservationService + AbstractPath), causing last-write-wins
 * conflicts.
 *
 * @since Issue #300
 */
class SwitchConfigurationTest : KoinTestBase() {
	private lateinit var context: SimulationContext
	private val processFactory by inject<cz.vutbr.fit.interlockSim.context.SimulationProcessFactory>()
	private val editingContextFactory by inject<JvmEditingContextFactory>()

	// Grid elements (from vyhybna.xml)
	private lateinit var vA: DynamicRailSwitch // at (15,8)
	private lateinit var vB: DynamicRailSwitch // at (26,8)
	private lateinit var zA: DynamicRailSemaphore // at (14,8)
	private lateinit var doA1: DynamicRailSemaphore // at (16,8) - main route from vA
	private lateinit var doA2: DynamicRailSemaphore // at (17,9) - branch route from vA
	private lateinit var doB1: DynamicRailSemaphore // at (25,8) - main route to vB
	private lateinit var doB2: DynamicRailSemaphore // at (24,9) - branch route to vB
	private lateinit var zB: DynamicRailSemaphore // at (27,8)

	@BeforeEach
	fun setUp() {
		val editingContext = TestFixtures.loadShuntingXml().use { editingContextFactory.createContext(it) } as EditingContext
		context = ContextTransformer.createSimulationContext(editingContext, processFactory)

		// Get elements from grid by coordinates (same as ShuntingLoop)
		vA = elementAt<DynamicRailSwitch>(context, 15, 8)
		vB = elementAt<DynamicRailSwitch>(context, 26, 8)
		zA = elementAt<DynamicRailSemaphore>(context, 14, 8)
		doA1 = elementAt<DynamicRailSemaphore>(context, 16, 8)
		doA2 = elementAt<DynamicRailSemaphore>(context, 17, 9)
		doB1 = elementAt<DynamicRailSemaphore>(context, 25, 8)
		doB2 = elementAt<DynamicRailSemaphore>(context, 24, 9)
		zB = elementAt<DynamicRailSemaphore>(context, 27, 8)
	}

	/**
	 * Get element from grid by coordinates (same pattern as ShuntingLoop).
	 */
	private inline fun <reified T : Cell> elementAt(
		context: SimulationContext,
		x: Int,
		y: Int
	): T {
		val point =
			cz.vutbr.fit.interlockSim.util
				.Point(x, y)
		val cell =
			context.getRailWayNetGrid()[point]
				?: throw IllegalArgumentException("No cell at ($x, $y)")
		return Util.assertInstanceOf(cell)
	}

	/**
	 * Test that switch is configured to MAIN when train takes main route.
	 *
	 * Main route: zA → vA(MAIN) → doA1
	 */
	@Test
	fun testSwitchConfiguredToMain() {
		// Get PathReservationService
		val pathService = context.getPathReservationService()

		// Reserve path: zA → vA → doA1 (main route)
		val result = pathService.reservePath("train1", zA, doA1)

		// Verify reservation succeeded
		assertThat(result).isNotNull()

		// Verify switch vA is configured to MAIN
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.MAIN)
	}

	/**
	 * Test that switch is configured to BRANCH when train takes branch route.
	 *
	 * Branch route: zA → vA(BRANCH) → doA2
	 */
	@Test
	fun testSwitchConfiguredToBranch() {
		// Get PathReservationService
		val pathService = context.getPathReservationService()

		// Reserve path: zA → vA → doA2 (branch route)
		val result = pathService.reservePath("train1", zA, doA2)

		// Verify reservation succeeded
		assertThat(result).isNotNull()

		// Verify switch vA is configured to BRANCH
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.BRANCH)
	}

	/**
	 * Test that PropertyChangeEvent is fired when switch configuration changes.
	 *
	 * This verifies that AnimationStateCapture can detect switch configuration changes.
	 */
	@Test
	fun testSwitchPropertyChangeEventFired() {
		// Add PropertyChangeListener to track conf changes
		var eventFired = false
		var oldValue: RailSwitch.Conf? = null
		var newValue: RailSwitch.Conf? = null

		vA.addPropertyChangeListener(
			ContextPropertyChangeListener { evt: ContextChangeEvent ->
				if (evt.propertyName == "conf") {
					eventFired = true
					oldValue = evt.oldValue as? RailSwitch.Conf
					newValue = evt.newValue as? RailSwitch.Conf
				}
			}
		)

		// Get PathReservationService
		val pathService = context.getPathReservationService()

		// Reserve path: zA → vA → doA2 (branch route)
		val result = pathService.reservePath("train1", zA, doA2)

		// Verify reservation succeeded
		assertThat(result).isNotNull()

		// Verify PropertyChangeEvent was fired
		assertThat(eventFired).isTrue()
		assertThat(newValue).isEqualTo(RailSwitch.Conf.BRANCH)
	}

	/**
	 * Test that switch is locked after configuration.
	 *
	 * Railway safety: switches must be locked after configuration to prevent
	 * unauthorized position changes while train is approaching.
	 */
	@Test
	fun testSwitchLockedAfterConfiguration() {
		// Get PathReservationService
		val pathService = context.getPathReservationService()

		// Reserve path: zA → vA → doA1
		val result = pathService.reservePath("train1", zA, doA1)

		// Verify reservation succeeded
		assertThat(result).isNotNull()

		// Verify switch vA is locked
		assertThat(vA.locked).isTrue()
	}

	/**
	 * Test that multiple switches in a path are configured independently.
	 *
	 * Path with 2 switches: zA → vA(BRANCH) → doA2 → ... → doB2 → vB(BRANCH) → zB
	 */
	@Test
	fun testMultipleSwitchesConfiguredIndependently() {
		// Get PathReservationService
		val pathService = context.getPathReservationService()

		// Reserve path: zA → vA → doA2 (switch vA to BRANCH)
		val result1 = pathService.reservePath("train1", zA, doA2)
		assertThat(result1).isNotNull()

		// Reserve path: doB2 → vB → zB (switch vB to BRANCH)
		val result2 = pathService.reservePath("train2", doB2, zB)
		assertThat(result2).isNotNull()

		// Verify switch vA is configured to BRANCH
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.BRANCH)

		// Verify switch vB is configured to BRANCH
		assertThat(vB.conf).isEqualTo(RailSwitch.Conf.BRANCH)
	}

	/**
	 * Test that switch configuration happens ONCE per reservation, not duplicated.
	 *
	 * This test verifies that the fix for Issue #300 (duplicate switch configuration)
	 * is effective. We count PropertyChangeEvents to ensure switches are configured
	 * exactly once per reservation.
	 */
	@Test
	fun testSwitchConfigurationNotDuplicated() {
		// Count PropertyChangeEvents for "conf" property
		var confChangeCount = 0

		vA.addPropertyChangeListener(
			ContextPropertyChangeListener { evt: ContextChangeEvent ->
				if (evt.propertyName == "conf") {
					confChangeCount++
				}
			}
		)

		// Get PathReservationService
		val pathService = context.getPathReservationService()

		// Reserve path: zA → vA → doA2 (branch route)
		val result = pathService.reservePath("train1", zA, doA2)

		// Verify reservation succeeded
		assertThat(result).isNotNull()

		// Verify switch conf changed AT MOST once (not duplicated)
		// Note: If switch was already in BRANCH configuration, count may be 0
		// (no change needed). If it was in MAIN, count should be 1.
		// The important thing is that count is NOT 2+ (duplicate configuration)
		assertThat(confChangeCount).isGreaterThanOrEqualTo(0)
		assertThat(confChangeCount).isLessThanOrEqualTo(1)
	}

	/**
	 * Test that a second train can reconfigure a switch after the first train completes.
	 *
	 * Simulates the real production flow: the tail of train1 leaves every reserved
	 * block (making it FREE), then the train-completion cleanup path
	 * pathService.unregister(trainId) removes registry ownership and unlocks switches.
	 * After that, train2 must be able to reserve a path requiring vA in the opposite
	 * configuration.
	 */
	@Test
	fun `second train can reconfigure switch after first train completes`() {
		// First train: zA -> vA(BRANCH) -> doA2
		val pathService = context.getPathReservationService()
		val result1 = pathService.reservePath("train1", zA, doA2)
		assertThat(result1).isInstanceOf(PathReservationService.ReservationResult.Success::class)
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.BRANCH)
		assertThat(vA.locked).isTrue()

		// Simulate train1's tail leaving each reserved block, transitioning blocks
		// from RESERVED to FREE. In production this is done by Train.Tail.
		val reservedBlocks = (result1 as PathReservationService.ReservationResult.Success).reservedBlocks
		reservedBlocks.forEach { block ->
			block.cancelPathSetup(zA)
		}

		// Production completion cleanup for train1: registry removal + switch unlock
		pathService.unregister("train1")
		assertThat(vA.locked).isFalse()

		// Second train: zA -> vA(MAIN) -> doA1 (opposite configuration)
		val result2 = pathService.reservePath("train2", zA, doA1)
		assertThat(result2).isInstanceOf(PathReservationService.ReservationResult.Success::class)
		assertThat(vA.conf).isEqualTo(RailSwitch.Conf.MAIN)
		assertThat(vA.locked).isTrue()
	}
}
