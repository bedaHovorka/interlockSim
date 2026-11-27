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
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.message
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
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
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
		val ctx = TestTopologies.yJunctionWithSwitchSimulation(switchName = "").tracked()
		ctx.getInOuts()
		val port = DefaultNetworkActuatorPort(env = ctx)
		val switch = ctx.getRailWayNetGrid()[TestTopologies.Y_JUNCTION_SWITCH_AT] as DynamicRailSwitch
		val target = if (switch.conf == RailSwitch.Conf.MAIN) RailSwitch.Conf.BRANCH else RailSwitch.Conf.MAIN

		assertThat(switch.name, name = "static name of the code-built switch").isEqualTo("")
		val generatedName = GeneratedElementName.switchAt(TestTopologies.Y_JUNCTION_SWITCH_AT)
		assertThat(port.setSwitchPosition(generatedName, target)).isTrue()
		assertThat(switch.conf).isEqualTo(target)
	}

	@Test
	fun aGeneratedSwitchNameCollidingWithAnEarlierExplicitNameFailsAtConstruction() {
		// Copilot review (PRRT_kwDOQyJ9Cc68puGU): the fallback would name the blank switch at
		// (20,10) SW_20_10, and that name is already the explicit name of the switch at (5,10) --
		// the one the x-outer scan reaches first. The XML load rejects the match in
		// requireGeneratedNamesUnique, so a context built in code must fail the same way, at
		// construction time, instead of giving one name to two switches.
		val ctx =
			laneJunction(EXPLICIT_AT to COLLISION_NAME, COLLISION_AT to null)
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()

		assertFailure { DefaultNetworkActuatorPort(env = ctx) }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains(COLLISION_NAME, "RailSwitch at ${label(COLLISION_AT)}", label(EXPLICIT_AT))
	}

	@Test
	fun aGeneratedSwitchNameCollidingWithALaterExplicitNameAlsoFailsAtConstruction() {
		// The mirror scan order: the blank switch at (5,10) claims the generated SW_5_10 first and
		// the switch explicitly named SW_5_10 at (20,10) reaches the cache second. Last-wins would
		// silently re-point the shared name at the explicit switch, so the mirror must fail too and
		// the outcome must not depend on the order.
		val ctx =
			laneJunction(EXPLICIT_AT to null, COLLISION_AT to MIRROR_NAME)
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()

		assertFailure { DefaultNetworkActuatorPort(env = ctx) }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains(MIRROR_NAME, "RailSwitch at ${label(EXPLICIT_AT)}", label(COLLISION_AT))
	}

	@Test
	fun twoSwitchesWithTheSameExplicitNameKeepTheLastWinsOrder() {
		// Issue #1473 review: the rejection is for the FALLBACK side only. Two explicitly
		// same-named switches are the documented last-wins contract of the name index and must
		// keep loading, addressed by the switch the scan met last.
		val ctx =
			laneJunction(EXPLICIT_AT to TWIN_NAME, COLLISION_AT to TWIN_NAME)
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()
		val port = DefaultNetworkActuatorPort(env = ctx)
		val firstScanned = ctx.getRailWayNetGrid()[EXPLICIT_AT] as DynamicRailSwitch
		val lastScanned = ctx.getRailWayNetGrid()[COLLISION_AT] as DynamicRailSwitch
		val firstSaved = firstScanned.conf
		val target = if (lastScanned.conf == RailSwitch.Conf.MAIN) RailSwitch.Conf.BRANCH else RailSwitch.Conf.MAIN

		assertThat(port.setSwitchPosition(TWIN_NAME, target)).isTrue()
		assertThat(lastScanned.conf, name = "the last-scanned twin answers the name").isEqualTo(target)
		assertThat(firstScanned.conf, name = "the first-scanned twin keeps its conf").isEqualTo(firstSaved)
	}

	@Test
	fun anExplicitSwitchNamedItsOwnPositionFormDoesNotClashWithTheBlankSwitch() {
		// Issue #1473 review: an explicit name equal to the switch's OWN position form is not a
		// collision, and the fallback names of other, blank switches stand next to it unchanged.
		val ctx =
			laneJunction(OWN_POSITION_AT to OWN_POSITION_NAME, BLANK_ELSEWHERE_AT to null)
				.use { DefaultSimulationContext.fromEditingContext(it, get()) }
				.tracked()
		ctx.getInOuts()
		val port = DefaultNetworkActuatorPort(env = ctx)
		val explicit = ctx.getRailWayNetGrid()[OWN_POSITION_AT] as DynamicRailSwitch
		val blank = ctx.getRailWayNetGrid()[BLANK_ELSEWHERE_AT] as DynamicRailSwitch
		val blankSaved = blank.conf
		val target = if (explicit.conf == RailSwitch.Conf.MAIN) RailSwitch.Conf.BRANCH else RailSwitch.Conf.MAIN

		assertThat(port.setSwitchPosition(OWN_POSITION_NAME, target)).isTrue()
		assertThat(explicit.conf, name = "the name stays with the explicitly named switch").isEqualTo(target)
		assertThat(blank.conf, name = "the blank switch keeps its conf").isEqualTo(blankSaved)
	}

	/**
	 * A straight `entry → switch(es) → exit` lane on one shared row whose switches carry the
	 * [placements] names at their grid positions, `null` for blank (the fallback case under
	 * test). The lane keeps every join on the row with y-junction-style simple wiring, and
	 * [LANE_MARGIN] leaves room between two joins for the intermediate track cells.
	 */
	private fun laneJunction(vararg placements: Pair<Point, String?>): DefaultEditingContext {
		val row = placements.first().first.y
		check(placements.all { (at, _) -> at.y == row }) { "a lane needs a single row" }
		val switchNodes: List<Pair<Point, NodeCell>> =
			placements.map { (at, name) ->
				at to RailSwitch(name ?: "", Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			}
		val laneNodes: List<Pair<Point, NodeCell>> =
			listOf(Point(switchNodes.first().first.x - LANE_MARGIN, row) to InOut("J_IN", false, Cell.SpatialType.HORIZONTAL)) +
				switchNodes +
				listOf(Point(switchNodes.last().first.x + LANE_MARGIN, row) to InOut("J_OUT", true, Cell.SpatialType.HORIZONTAL))
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
		const val END_TIME: Long = 600L
		const val SEED: Long = 1473L
		const val UNKNOWN_BLOCK_ID = "unknown"
		const val SWITCH_PREFIX = "SW_"

		/** The gap between a lane's InOut and its nearest switch, in columns. */
		const val LANE_MARGIN = 4

		val COLLISION_AT = Point(20, 10)
		val EXPLICIT_AT = Point(5, 10)

		/** The name both parties of the first collision pin hold: the form of [COLLISION_AT]. */
		val COLLISION_NAME = GeneratedElementName.switchAt(COLLISION_AT)

		/** The form of [EXPLICIT_AT]: the name the mirror's blank switch at [EXPLICIT_AT] claims. */
		val MIRROR_NAME = GeneratedElementName.switchAt(EXPLICIT_AT)

		/** A plain non-positional name, so the duplicate-explicit pin does not meet the fallback. */
		const val TWIN_NAME = "Twin"

		val OWN_POSITION_AT = Point(30, 30)

		/** What a switch named its own position form is called: the form of [OWN_POSITION_AT]. */
		val OWN_POSITION_NAME = GeneratedElementName.switchAt(OWN_POSITION_AT)
		val BLANK_ELSEWHERE_AT = Point(44, 30)

		/** The position label of the generated-name diagnostics, `(<x>,<y>)`. */
		private fun label(at: Point): String = "(${at.x},${at.y})"

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
