/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName
import kotlin.test.Test

/**
 * Issue #1468 (Goal 9B SP4.2): [DefaultNetworkPerceptionPort.captureSnapshot] samples every switch
 * as a [SwitchReading] — stable name, `RailSwitch.Conf`, locked and owning train — and the reading
 * is a value: a later change of the live switch does not reach a snapshot already taken.
 *
 * Runs in `commonTest`, so it also holds on `:core:linuxX64Test`.
 */
class SwitchReadingCaptureTest : CommonKoinTestBase() {
	@Test
	fun aNamedSwitchIsSampledWithItsPositionLockAndOwner() {
		val ctx = TestTopologies.yJunctionWithSwitchSimulation().tracked()
		ctx.getInOuts()
		val port = DefaultNetworkPerceptionPort(ctx, activeTrains = { emptyList() })
		val switch = ctx.getRailWayNetGrid()[TestTopologies.Y_JUNCTION_SWITCH_AT] as DynamicRailSwitch
		val initialConf = switch.conf

		val before = port.captureSnapshot()
		assertThat(before.switches).containsExactly(
			SwitchReading(TestTopologies.Y_JUNCTION_SWITCH, initialConf, locked = false, ownerTrainId = null)
		)

		val other = if (initialConf == RailSwitch.Conf.MAIN) RailSwitch.Conf.BRANCH else RailSwitch.Conf.MAIN
		switch.changeConf()
		switch.lock()
		ctx.scope.get<PathReservationRegistry>().registerSwitches(TRAIN, listOf(switch))

		val after = port.captureSnapshot()
		assertThat(after.switches).containsExactly(
			SwitchReading(TestTopologies.Y_JUNCTION_SWITCH, other, locked = true, ownerTrainId = TRAIN)
		)
		assertThat(after.switchReading(TestTopologies.Y_JUNCTION_SWITCH)?.ownerTrainId).isEqualTo(TRAIN)
		assertThat(before.switches.single().locked, name = "the earlier snapshot is unchanged").isEqualTo(false)
	}

	@Test
	fun anUnnamedSwitchOfAContextBuiltInCodeIsSampledUnderItsGeneratedName() {
		val ctx = TestTopologies.yJunctionWithSwitchSimulation(switchName = "").tracked()
		ctx.getInOuts()
		val port = DefaultNetworkPerceptionPort(ctx, activeTrains = { emptyList() })

		val reading = port.captureSnapshot().switches.single()

		assertThat(reading.name).isEqualTo(GeneratedElementName.switchAt(TestTopologies.Y_JUNCTION_SWITCH_AT))
		assertThat(reading.ownerTrainId).isNull()
	}

	@Test
	fun theEmptySnapshotHasNoSwitches() {
		assertThat(SimulationSnapshot.EMPTY.switches).isEmpty()
		assertThat(SimulationSnapshot.EMPTY.switchReading(TestTopologies.Y_JUNCTION_SWITCH)).isNull()
	}

	private companion object {
		const val TRAIN = "T1468"
	}
}
