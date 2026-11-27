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
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.Station
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.util.Array2DMap
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.xml.GeneratedElementName
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Issue #1473 (Goal 9B SP4.7): every switch and every track section that a command or a snapshot
 * names has a name that is non-blank, unique per network and equal across two loads. This class
 * runs in `commonTest`, so its pinned values also hold on `:core:linuxX64Test` (parity).
 *
 * - A switch keeps its XML name; an unnamed one gets [GeneratedElementName.switchAt] at load.
 * - A track section has a unique grid id, [BlockOccupancyReading.sectionId], and keeps its
 *   display and resolution name [BlockOccupancyReading.blockId] (`BlockIdentity.stableBlockId`,
 *   unchanged). The #1467 spec makes the `blockId` uniqueness an assertion, not a repair: a
 *   collision fails here.
 *
 * Names are read as the snapshot reads them: through [DefaultNetworkPerceptionPort] after a
 * [ShuntingLoop] was built, so `Station.applyBlockNames` has run.
 */
class StableElementNamesTest : CommonKoinTestBase() {
	private class Names(
		val switchNames: List<String>,
		val sectionIds: List<String>,
		val blockIds: List<String>,
		val stationBlockIds: Map<String, String>
	)

	private fun load(
		xml: String,
		buildShuntingLoop: Boolean
	): Names {
		val ctx: DefaultSimulationContext = CommonTestFixtures.parseSimulationContext(xml, get()).tracked()
		ctx.getInOuts() // initialize dynamic wrappers
		if (buildShuntingLoop) ShuntingLoop(ctx, END_TIME, seed = SEED, maxTrains = 1)
		val readings = DefaultNetworkPerceptionPort(ctx) { emptyList() }.allBlockOccupancies()
		val grid: RailwayNetGrid<Cell> = ctx.getRailWayNetGrid()
		val switchNames =
			grid
				.filter { it.value is DynamicRailSwitch }
				.sortedWith(compareBy(Array2DMap.POINT_COMPARATOR) { it.key })
				.map { (it.value as DynamicRailSwitch).name }
		val stationBlockIds =
			if (buildShuntingLoop) {
				val sectionIdOf = readings.associate { it.blockId to it.sectionId }
				Station.from(ctx).allBlocks.associate { it.id to sectionIdOf.getValue(it.name) }
			} else {
				emptyMap()
			}
		return Names(switchNames, readings.map { it.sectionId }, readings.map { it.blockId }, stationBlockIds)
	}

	private fun assertUniqueNonBlank(
		names: List<String>,
		what: String
	) {
		assertThat(names.all { it.isNotBlank() }, name = "every $what has a name: $names").isTrue()
		assertThat(names.toSet().size, name = "$what names are unique: $names").isEqualTo(names.size)
	}

	private fun assertStableNetwork(
		xml: String,
		switchCount: Int,
		sectionCount: Int
	) {
		val first = load(xml, buildShuntingLoop = true)
		val second = load(xml, buildShuntingLoop = true)

		assertThat(first.switchNames, name = "switches").hasSize(switchCount)
		assertThat(first.sectionIds, name = "track sections").hasSize(sectionCount)
		assertUniqueNonBlank(first.switchNames, "switch")
		assertUniqueNonBlank(first.sectionIds, "track section id")
		assertUniqueNonBlank(first.blockIds, "track section blockId")
		assertThat(first.blockIds, name = "blockIds").doesNotContain(UNKNOWN_BLOCK_ID)
		assertThat(second.switchNames, name = "switch names of a second load").isEqualTo(first.switchNames)
		assertThat(second.sectionIds.sorted(), name = "section ids of a second load").isEqualTo(first.sectionIds.sorted())
		assertThat(second.blockIds.sorted(), name = "blockIds of a second load").isEqualTo(first.blockIds.sorted())
		for ((stationId, sectionId) in first.stationBlockIds) {
			assertThat(sectionId, name = "sectionId of station block $stationId").isEqualTo(stationId)
		}
	}

	@Test
	fun everyCervenyUjezdSwitchAndTrackSectionHasAUniqueStableName() {
		assertStableNetwork(NetworkResources.CERVENY_UJEZD_XML, CU_SWITCHES, CU_SECTIONS)
	}

	@Test
	fun everyPrahaSwitchAndTrackSectionHasAUniqueStableName() {
		assertStableNetwork(NetworkResources.PRAHA_HLAVNI_NADRAZI_STATION_XML, PRAHA_SWITCHES, PRAHA_SECTIONS)
	}

	@Test
	fun cervenyUjezdSectionIdsArePinnedOnEveryPlatform() {
		assertThat(load(NetworkResources.CERVENY_UJEZD_XML, buildShuntingLoop = true).sectionIds.sorted())
			.containsExactly(*CU_SECTION_IDS.toTypedArray())
	}

	@Test
	fun theSixUnnamedPrahaFixtureSwitchesAreNamedByGridPositionOnEveryPlatform() {
		// praha-hlavni-nadrazi.xml (the core-test fixture, not the Station variant) leaves 6 of its
		// 50 switches unnamed; it is not a ShuntingLoop station, so only the load is checked.
		val first = load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, buildShuntingLoop = false)
		val second = load(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML, buildShuntingLoop = false)

		assertUniqueNonBlank(first.switchNames, "switch")
		assertUniqueNonBlank(first.sectionIds, "track section id")
		assertThat(first.switchNames.filter { it.startsWith(SWITCH_PREFIX) })
			.containsExactly("SW_14_8", "SW_14_12", "SW_44_12", "SW_14_16", "SW_44_16", "SW_44_18")
		assertThat(second.switchNames).isEqualTo(first.switchNames)
		assertThat(second.sectionIds.sorted()).isEqualTo(first.sectionIds.sorted())
	}

	@Test
	fun anUnnamedSwitchOfAContextBuiltInCodeIsCommandedByItsGeneratedName() {
		// A context built in code keeps the switch name blank (no XML load); the actuator port
		// resolves it through the same GeneratedElementName.switchAt form.
		val ctx = unnamedSwitchJunction().use { DefaultSimulationContext.fromEditingContext(it, get()) }.tracked()
		ctx.getInOuts()
		val port = DefaultNetworkActuatorPort(env = ctx)
		val switch = ctx.getRailWayNetGrid()[JUNCTION_SWITCH_AT] as DynamicRailSwitch
		val target = if (switch.conf == RailSwitch.Conf.MAIN) RailSwitch.Conf.BRANCH else RailSwitch.Conf.MAIN

		assertThat(switch.name, name = "static name of the code-built switch").isEqualTo("")
		assertThat(port.setSwitchPosition(GeneratedElementName.switchAt(JUNCTION_SWITCH_AT), target)).isTrue()
		assertThat(switch.conf).isEqualTo(target)
	}

	/** The `TestTopologies.yJunctionWithSwitch` layout with an unnamed switch. */
	private fun unnamedSwitchJunction(): DefaultEditingContext {
		val context = DefaultEditingContext(60, 50)
		val entry = InOut("J_IN", false, Cell.SpatialType.HORIZONTAL)
		val exitMain = InOut("J_MAIN", true, Cell.SpatialType.HORIZONTAL)
		val exitBranch = InOut("J_BRANCH", true, Cell.SpatialType.HORIZONTAL)
		val switch: NodeCell = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		context.putCell(Point(5, 30), entry)
		context.putCell(JUNCTION_SWITCH_AT, switch)
		context.putCell(Point(55, 30), exitMain)
		context.putCell(Point(50, 40), exitBranch)
		context.joinCells(Point(5, 30), JUNCTION_SWITCH_AT, SimpleTrackBlock(entry, switch, 250.0, 80.0))
		context.joinCells(JUNCTION_SWITCH_AT, Point(55, 30), SimpleTrackBlock(switch, exitMain, 250.0, 100.0))
		context.joinCells(JUNCTION_SWITCH_AT, Point(50, 40), SimpleTrackBlock(switch, exitBranch, 180.0, 90.0))
		return context
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val SEED: Long = 1473L
		const val UNKNOWN_BLOCK_ID = "unknown"
		const val SWITCH_PREFIX = "SW_"
		val JUNCTION_SWITCH_AT = Point(30, 30)

		const val CU_SWITCHES = 12
		const val CU_SECTIONS = 32
		const val PRAHA_SWITCHES = 50
		const val PRAHA_SECTIONS = 109

		/** Every Červený Újezd section id, sorted as strings: the JVM values, held on every platform. */
		val CU_SECTION_IDS: List<String> =
			listOf(
				"(11,30)-(31,30)",
				"(11,30)-(9,31)",
				"(11,31)-(31,31)",
				"(11,32)-(31,32)",
				"(11,33)-(31,33)",
				"(31,30)-(33,31)",
				"(31,31)-(33,31)",
				"(31,32)-(33,32)",
				"(33,31)-(34,31)",
				"(33,32)-(31,33)",
				"(33,32)-(34,32)",
				"(34,31)-(35,31)",
				"(34,31)-(35,32)",
				"(34,32)-(35,32)",
				"(35,31)-(34,32)",
				"(35,31)-(36,31)",
				"(35,32)-(36,32)",
				"(36,31)-(37,31)",
				"(36,32)-(37,32)",
				"(5,31)-(6,31)",
				"(5,32)-(6,32)",
				"(6,31)-(7,31)",
				"(6,32)-(7,32)",
				"(7,31)-(8,31)",
				"(7,31)-(8,32)",
				"(7,32)-(8,32)",
				"(8,31)-(7,32)",
				"(8,31)-(9,31)",
				"(8,32)-(9,32)",
				"(9,31)-(11,31)",
				"(9,32)-(11,32)",
				"(9,32)-(11,33)"
			)
	}
}
