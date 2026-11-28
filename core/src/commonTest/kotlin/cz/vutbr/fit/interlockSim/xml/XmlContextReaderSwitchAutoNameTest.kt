/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.message
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Issue #1473 (Goal 9B SP4.7): the XML loader names every unnamed `RailSwitch` after its grid
 * position ([GeneratedElementName.switchAt]), so a command or a snapshot can name it.
 *
 * Pinned here: the `SW_<x>_<y>` format, that explicit names win, the six unnamed switches of the
 * Praha fixture, that saving does not write a generated name back, and that a clash with an
 * explicit name fails the load with a message naming both positions.
 */
class XmlContextReaderSwitchAutoNameTest {
	private val reader = XmlContextReader()
	private val writer = XmlContextWriter()

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	@Test
	fun unnamedSwitchGetsItsGridPositionAsName() {
		// LEGACY_NETWORK_NO_NAMES_XML: one unnamed switch at grid (20,10).
		val name = reader.parse(NetworkResources.LEGACY_NETWORK_NO_NAMES_XML).use { switchAt(it, Point(20, 10)).getName() }

		assertThat(name).isEqualTo("SW_20_10")
	}

	@Test
	fun theSixUnnamedPrahaFixtureSwitchesGetGeneratedNamesAndTheOthersKeepTheirs() {
		val explicit = explicitSwitchNames(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)
		val loaded = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { switchNamesByPosition(it) }

		assertThat(loaded.size, name = "switches in the fixture").isEqualTo(PRAHA_SWITCH_COUNT)
		for ((point, name) in explicit) {
			assertThat(loaded[point], name = "switch at $point").isEqualTo(name)
		}
		val generated = loaded.filterKeys { it !in explicit }
		assertThat(generated.values.sorted(), name = "auto-named switches").containsExactly(*PRAHA_GENERATED.toTypedArray())
	}

	@Test
	fun generatedSwitchNameIsStableAcrossTwoLoads() {
		val first = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { switchNamesByPosition(it) }
		val second = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { switchNamesByPosition(it) }

		assertThat(second).isEqualTo(first)
	}

	@Test
	fun savingDoesNotWriteAGeneratedSwitchNameBack() {
		val xml = reader.parse(NetworkResources.SWITCH_BASIC_XML).use { writer.generate(it) }
		val position = reader.parse(NetworkResources.SWITCH_BASIC_XML).use { onlySwitchPosition(it) }
		val generated = GeneratedElementName.switchAt(position)

		assertThat(xml.contains(generated), name = "saved XML contains the generated name $generated").isFalse()
		val reloaded = reader.parse(xml).use { switchAt(it, position).getName() }
		assertThat(reloaded).isEqualTo(generated)
	}

	@Test
	fun prahaFixtureRoundTripKeepsEverySwitchNameAndWritesOnlyTheXmlNames() {
		val before = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { switchNamesByPosition(it) }
		val saved = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { writer.generate(it) }
		val after = reader.parse(saved).use { switchNamesByPosition(it) }

		assertThat(after).isEqualTo(before)
		assertThat(explicitSwitchNames(saved)).isEqualTo(explicitSwitchNames(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML))
	}

	@Test
	fun generatedSwitchNameClashingWithAnExplicitNameFailsTheLoadNamingTheCause() {
		// The semaphore at (15,10) is given the name the unnamed switch at (20,10) would generate.
		val xml =
			NetworkResources.LEGACY_NETWORK_NO_NAMES_XML.replace(
				LEGACY_SEMAPHORE_AT_15_10,
				LEGACY_SEMAPHORE_AT_15_10.replace("/>", " name=\"SW_20_10\"/>")
			)
		check(xml != NetworkResources.LEGACY_NETWORK_NO_NAMES_XML) { "fixture text changed: $LEGACY_SEMAPHORE_AT_15_10" }

		assertFailure { reader.parse(xml) }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains("SW_20_10", "RailSwitch at (20,10)", "(15,10)")
	}

	@Test
	fun generatedSwitchNameClashingWithAnotherSwitchNameFailsTheLoadNamingTheCause() {
		// Issue #1473 review: the switch-vs-switch shape, the one the thread pins -- the switch at
		// (5,10) is explicitly named SW_20_10, the generated name of the unnamed switch at (20,10).
		val xml =
			NetworkResources.LEGACY_NETWORK_NO_NAMES_XML.replace(
				LEGACY_SWITCH_AT_20_10,
				"$LEGACY_SWITCH_AT_20_10\n  $LEGACY_SWITCH_AT_5_10"
			)
		check(xml != NetworkResources.LEGACY_NETWORK_NO_NAMES_XML) { "fixture text changed: $LEGACY_SWITCH_AT_20_10" }

		assertFailure { reader.parse(xml) }
			.isInstanceOf<IllegalArgumentException>()
			.message()
			.isNotNull()
			.contains("SW_20_10", "RailSwitch at (20,10)", "(5,10)")
	}

	private fun switchAt(
		ctx: DefaultEditingContext,
		point: Point
	): RailSwitch {
		val cell = ctx.getRailWayNetGrid()[point]
		assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
		return cell as RailSwitch
	}

	private fun onlySwitchPosition(ctx: DefaultEditingContext): Point = switchNamesByPosition(ctx).keys.single()

	private fun switchNamesByPosition(ctx: DefaultEditingContext): Map<Point, String> {
		// The editing grid also holds TrackBlockPart cells despite its NodeCell type argument.
		val grid: RailwayNetGrid<*> = ctx.getRailWayNetGrid()
		return grid
			.mapNotNull { (position, cell) -> (cell as? RailSwitch)?.let { position to it.getName() } }
			.toMap()
	}

	private companion object {
		const val PRAHA_SWITCH_COUNT = 50

		/** The generated names of the six switches `praha-hlavni-nadrazi.xml` leaves unnamed. */
		val PRAHA_GENERATED: List<String> =
			listOf("SW_14_12", "SW_14_16", "SW_14_8", "SW_44_12", "SW_44_16", "SW_44_18")

		/** The unnamed semaphore element of `legacy-network-no-names.xml`. */
		const val LEGACY_SEMAPHORE_AT_15_10 =
			"<RailSemaphore X=\"15\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" />"

		/** The unnamed switch element of `legacy-network-no-names.xml`, at (20,10). */
		const val LEGACY_SWITCH_AT_20_10 =
			"<RailSwitch X=\"20\" Y=\"10\" SpatialType=\"HORIZONTAL\" Type=\"SIMPLE_LEFT_TRUE\" />"

		/** The switch named the generated form, which the switch-clash variant inserts at (5,10). */
		const val LEGACY_SWITCH_AT_5_10 =
			"<RailSwitch X=\"5\" Y=\"10\" SpatialType=\"HORIZONTAL\" Type=\"SIMPLE_LEFT_TRUE\" name=\"SW_20_10\"/>"

		private val SWITCH_TAG = Regex("<RailSwitch\\b[^>]*>")
		private val X_ATTR = Regex("\\bX=\"(\\d+)\"")
		private val Y_ATTR = Regex("\\bY=\"(\\d+)\"")
		private val NAME_ATTR = Regex("\\bname=\"([^\"]+)\"")

		/** The `name` attributes written in [xml] for `RailSwitch` elements, by grid position. */
		fun explicitSwitchNames(xml: String): Map<Point, String> =
			SWITCH_TAG
				.findAll(xml)
				.mapNotNull { tag ->
					val name = NAME_ATTR.find(tag.value)?.groupValues?.get(1) ?: return@mapNotNull null
					val x = checkNotNull(X_ATTR.find(tag.value)).groupValues[1].toInt()
					val y = checkNotNull(Y_ATTR.find(tag.value)).groupValues[1].toInt()
					Point(x, y) to name
				}.toMap()
	}
}
