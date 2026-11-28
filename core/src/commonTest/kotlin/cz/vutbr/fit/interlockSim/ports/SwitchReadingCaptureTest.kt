/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.message
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName
import org.koin.core.component.get
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

	@Test
	fun aGeneratedSwitchNameCollisionFailsThePerceptionPortConstruction() {
		// Copilot thread PRRT_kwDOQyJ9Cc68uzeu, end-to-end: the thread's scenario -- a blank switch
		// at (30,30) whose generated name is already the explicit name of the switch at (10,30).
		// The perception port's switch cache is the shared scan, so the scan's list-construction
		// rejection propagates to the port's construction: no snapshot can silently read conf,
		// lock or owner of the wrong twin.
		val ctx =
			collisionLane()
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()

		assertFailure { DefaultNetworkPerceptionPort(ctx, activeTrains = { emptyList() }) }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains(COLLISION_NAME, "RailSwitch at ${label(BLANK_AT)}", label(EXPLICIT_AT), FIX_HINT)
	}

	/**
	 * The collision lane the Copilot thread named (PRRT_kwDOQyJ9Cc68uzeu): a straight
	 * `entry → switch → switch → exit` row whose switch at [EXPLICIT_AT] is explicitly named
	 * [COLLISION_NAME] and whose switch at [BLANK_AT] is blank, so the scan generates
	 * [COLLISION_NAME] for it from the grid position. A code-built context, where the XML load's
	 * `requireGeneratedNamesUnique` never runs.
	 */
	private fun collisionLane(): DefaultEditingContext {
		val row = BLANK_AT.y
		val laneNodes: List<Pair<Point, NodeCell>> =
			listOf(Point(EXPLICIT_AT.x - LANE_MARGIN, row) to InOut("J_IN", false, Cell.SpatialType.HORIZONTAL)) +
				listOf(
					EXPLICIT_AT to RailSwitch(COLLISION_NAME, Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE),
					BLANK_AT to RailSwitch("", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
				) +
				listOf(Point(BLANK_AT.x + LANE_MARGIN, row) to InOut("J_OUT", true, Cell.SpatialType.HORIZONTAL))
		val context = DefaultEditingContext(60, 50)
		for ((at, cell) in laneNodes) context.putCell(at, cell)
		for (index in 0 until laneNodes.size - 1) {
			val (fromAt, fromCell) = laneNodes[index]
			val (toAt, toCell) = laneNodes[index + 1]
			context.joinCells(fromAt, toAt, SimpleTrackBlock(fromCell, toCell, 250.0, 90.0))
		}
		return context
	}

	private companion object {
		const val TRAIN = "T1468"

		/** The gap between a lane's InOut and its nearest switch, in columns. */
		const val LANE_MARGIN = 4

		/** The thread's blank switch, whose generated position form is the collision name. */
		val BLANK_AT = Point(30, 30)

		/** The explicit holder of [BLANK_AT]'s generated form, reached by the scan first. */
		val EXPLICIT_AT = Point(10, 30)

		/** The generated form of [BLANK_AT], held explicitly by the switch at [EXPLICIT_AT]. */
		val COLLISION_NAME = GeneratedElementName.switchAt(BLANK_AT)

		/** The rejection's tail, shared with the actuator's construction diagnostic (Issue #1473). */
		const val FIX_HINT = "explicit name in code or move it to another position"

		/** The position label of the generated-name diagnostics, `(<x>,<y>)`. */
		private fun label(at: Point): String = "(${at.x},${at.y})"
	}
}
