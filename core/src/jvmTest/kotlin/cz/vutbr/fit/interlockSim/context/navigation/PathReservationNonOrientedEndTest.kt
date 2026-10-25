/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for Issue #1200, the END-side twin of Issue #1188: a reservation request whose
 * `target` is a [DynamicRailSwitch] used to succeed and store a `PathInfo` whose target is a switch.
 * A train holding such a route is stuck, because every extension FROM the switch is refused by the
 * Issue #1188 origin guard, so the malformed `PathInfo` persists and the train holds track it can
 * never leave.
 *
 * Fix (traffic-simulation-expert ruling on #1200): a route endpoint, origin or end, must be an
 * oriented separator (a semaphore or an InOut). A Step 0 guard
 * ([RouteCandidateScreen.rejectNonOrientedEnd]) refuses a non-oriented end with the typed
 * [PathReservationService.ReservationResult.GeometricallyImpossible] before candidate discovery,
 * so no block, switch, signal or PathInfo is touched.
 *
 * Topology (`vyhybna.xml`): `A - zA - vA - doA1/doA2 - doB1/doB2 - vB - zB - B`; `zA`, `doB1`
 * and `doB2` face A to B.
 */
@Tag("integration-test")
class PathReservationNonOrientedEndTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath to a switch end is refused and touches nothing`() {
		// Arrange - vA is a switch, i.e. a non-oriented separator; zA is a legal origin facing it
		val zA = findSemaphoreByName("zA")
		val switchVA = findSwitchByName("vA")
		val signalsBefore = signalStates()
		val switchesBefore = switchStates()
		// Registered after the arrangement, so it records only the refused call's events
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		// Act - must return a typed result, never a Success
		val result = service.reservePath("train1", zA, switchVA)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat((result as PathReservationService.ReservationResult.GeometricallyImpossible).reason)
			.startsWith("Route end 'vA' is not an oriented separator (it is a DynamicRailSwitch)")
		assertThat(service.getReservedBlocks("train1"), "reserved blocks").isEmpty()
		assertThat(registry.getBlocks("train1"), "registered blocks").isEmpty()
		assertThat(registry.getSwitches("train1"), "registered switches").isEmpty()
		assertThat(registry.getPathInfo("train1"), "stored PathInfo").isNull()
		assertThat(service.hasClearedSignals("train1"), "cleared-signal ledger").isFalse()
		assertThat(signalStates(), "signals").isEqualTo(signalsBefore)
		assertThat(switchStates(), "switch positions and locks").isEqualTo(switchesBefore)
		assertThat(listener.events, "block occupancy events of the refused call").isEmpty()
	}

	@Test
	fun `a route extension to a switch end is refused and leaves the held route unmerged`() {
		// Arrange - train1 holds zA to doB1; vB lies just past doB1 in the direction of travel
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")
		assertReservationSuccess(service.reservePath("train1", zA, doB1))
		val blocksBefore = registry.getBlocks("train1").toSet()
		val switchesHeldBefore = registry.getSwitches("train1").toSet()
		val pathInfoBefore = registry.getPathInfo("train1")
		assertThat(pathInfoBefore).isNotNull()
		val signalsBefore = signalStates()
		val switchesBefore = switchStates()
		// Registered after the held route was granted, so it records only the refused call's events
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		// Act
		val result = service.reservePath("train1", doB1, findSwitchByName("vB"))

		// Assert - the held route is exactly as it was, and its PathInfo was not merged
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()
		assertThat(registry.getBlocks("train1").toSet(), "registered blocks").isEqualTo(blocksBefore)
		assertThat(registry.getSwitches("train1").toSet(), "registered switches").isEqualTo(switchesHeldBefore)
		assertThat(registry.getPathInfo("train1"), "stored PathInfo").isSameInstanceAs(pathInfoBefore)
		assertThat(signalStates(), "signals").isEqualTo(signalsBefore)
		assertThat(switchStates(), "switch positions and locks").isEqualTo(switchesBefore)
		assertThat(listener.events, "block occupancy events of the refused call").isEmpty()
	}

	@Test
	fun `reservePath to an InOut end is still accepted`() {
		// An InOut is an oriented separator, so the END guard lets it through.
		val zA = findSemaphoreByName("zA")
		val inOutB = inOutNamed("B")

		val result = service.reservePath("train1", zA, inOutB)

		assertReservationSuccess(result)
		assertThat(registry.getPathInfo("train1")?.target, "stored PathInfo target").isEqualTo(inOutB)
	}

	@Test
	fun `reservePath to a semaphore end facing the train is still accepted`() {
		val zA = findSemaphoreByName("zA")
		val doB1 = findSemaphoreByName("doB1")

		val result = service.reservePath("train1", zA, doB1)

		assertReservationSuccess(result)
		assertThat(registry.getPathInfo("train1")?.target, "stored PathInfo target").isEqualTo(doB1)
	}

	@Test
	fun `a refused switch end leaves a later ordinary request of the same train unaffected`() {
		val zA = findSemaphoreByName("zA")
		assertThat(service.reservePath("train1", zA, findSwitchByName("vA")))
			.isInstanceOf<PathReservationService.ReservationResult.GeometricallyImpossible>()

		val result = service.reservePath("train1", zA, findSemaphoreByName("doB1"))

		assertReservationSuccess(result)
	}

	/** Every semaphore's aspect, by name. */
	private fun signalStates(): Map<String, Signal> =
		simulationContext.cellsOfType<DynamicRailSemaphore>().associate { it.name to it.signal }

	/** Every switch's position and lock, by name. */
	private fun switchStates(): Map<String, Pair<RailSwitch.Conf, Boolean>> =
		simulationContext.cellsOfType<DynamicRailSwitch>().associate { it.name to (it.conf to it.locked) }
}
