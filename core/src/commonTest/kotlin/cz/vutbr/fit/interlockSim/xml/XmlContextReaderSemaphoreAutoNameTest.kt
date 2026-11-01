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
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
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
 * Issue #1273 (Goal 1B SP3 demand D6): the XML loader names every unnamed `RailSemaphore`
 * after its grid position, so a dispatcher can address it by name.
 *
 * Pinned here: the generated name format, that it is stable across loads, that explicit names
 * are untouched, that all 37 Praha signals are addressable, that saving keeps the user's XML
 * clean (a generated name is not written back unless another node also carries it), and that a
 * clash with an explicit name fails the load with a message naming the cause. Switches stay
 * unnamed (issue #1279 names them by hand).
 */
class XmlContextReaderSemaphoreAutoNameTest {
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
	fun unnamedSemaphoreGetsItsGridPositionAsName() {
		// SEMAPHORE_BASIC_XML has one unnamed semaphore at grid (15,10).
		val name = reader.parse(NetworkResources.SEMAPHORE_BASIC_XML).use { semaphoreAt(it, Point(15, 10)).getName() }

		assertThat(name).isEqualTo("S_15_10")
	}

	@Test
	fun generatedNameIsStableAcrossTwoLoads() {
		val first = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { semaphoreNamesByPosition(it) }
		val second = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { semaphoreNamesByPosition(it) }

		assertThat(second).isEqualTo(first)
	}

	@Test
	fun allPrahaSemaphoresAreAddressableByUniqueNames() {
		reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { ctx ->
			val semaphoreNames = semaphoreNamesByPosition(ctx).values

			assertThat(semaphoreNames).hasSize(PRAHA_SEMAPHORE_COUNT)
			assertThat(semaphoreNames.all { it.isNotBlank() }, name = "every semaphore has a name").isTrue()
			val allNodeNames = nodeNames(ctx).filter { it.isNotEmpty() }
			assertThat(allNodeNames.toSet().size, name = "node names are unique").isEqualTo(allNodeNames.size)
		}
	}

	@Test
	fun explicitlyNamedPrahaSemaphoresKeepTheirXmlNames() {
		val explicit = explicitSemaphoreNames(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML)
		val loaded = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { semaphoreNamesByPosition(it) }

		assertThat(explicit.size, name = "explicitly named semaphores in the fixture").isEqualTo(PRAHA_NAMED_COUNT)
		for ((point, name) in explicit) {
			assertThat(loaded[point], name = "semaphore at $point").isEqualTo(name)
		}
		val generated = loaded.filterKeys { it !in explicit }
		assertThat(generated.size, name = "auto-named semaphores").isEqualTo(PRAHA_SEMAPHORE_COUNT - PRAHA_NAMED_COUNT)
		for ((point, name) in generated) {
			assertThat(name, name = "semaphore at $point").isEqualTo("S_${point.x}_${point.y}")
		}
	}

	@Test
	fun unnamedSwitchesStayUnnamed() {
		// LEGACY_NETWORK_NO_NAMES_XML: unnamed semaphore at (15,10), unnamed switch at (20,10).
		reader.parse(NetworkResources.LEGACY_NETWORK_NO_NAMES_XML).use { ctx ->
			val switch = ctx.getRailWayNetGrid()[Point(20, 10)]
			assertThat(switch).isNotNull().isInstanceOf<RailSwitch>()
			assertThat((switch as RailSwitch).getName()).isEqualTo("")
			assertThat(semaphoreAt(ctx, Point(15, 10)).getName()).isEqualTo("S_15_10")
		}
	}

	@Test
	fun savingDoesNotWriteAGeneratedNameBack() {
		val xml = reader.parse(NetworkResources.SEMAPHORE_BASIC_XML).use { writer.generate(it) }

		assertThat(xml.contains("S_15_10"), name = "saved XML contains the generated name").isFalse()
		val reloaded = reader.parse(xml).use { semaphoreAt(it, Point(15, 10)).getName() }
		assertThat(reloaded).isEqualTo("S_15_10")
	}

	@Test
	fun savingWritesANameTheUserGaveTheAutoNamedSemaphore() {
		val xml =
			reader.parse(NetworkResources.SEMAPHORE_BASIC_XML).use { ctx ->
				val point = Point(15, 10)
				ctx.replaceCell(point, semaphoreAt(ctx, point).withName("homeA"))
				writer.generate(ctx)
			}

		assertThat(xml).contains("name=\"homeA\"")
	}

	@Test
	fun savingKeepsAGeneratedFormNameThatAnotherNodeAlsoWrites() {
		// The semaphore carries "S_15_10", the generated form of its own position, and OUT — an
		// InOut the writer writes — carries the same name; the load passes because no name is
		// generated. Dropping the semaphore's attribute on save would make the next load
		// regenerate "S_15_10" and then clash with OUT, so the saved file keeps both attributes.
		val xml =
			NetworkResources.SEMAPHORE_BASIC_XML
				.replace("name=\"OUT\"", "name=\"S_15_10\"")
				.replace(
					"<RailSemaphore X=\"15\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\"/>",
					"<RailSemaphore X=\"15\" Y=\"10\" SpatialType=\"HORIZONTAL\" orientation=\"true\" name=\"S_15_10\"/>"
				)

		val saved = reader.parse(xml).use { writer.generate(it) }

		assertThat(
			NAME_ATTR.findAll(saved).count { it.groupValues[1] == "S_15_10" },
			name = "the saved XML keeps both \"S_15_10\" name attributes"
		).isEqualTo(2)
		reader.parse(saved).use { ctx ->
			assertThat(semaphoreAt(ctx, Point(15, 10)).getName(), name = "semaphore after reload").isEqualTo("S_15_10")
			val out = ctx.getRailWayNetGrid()[Point(20, 10)]
			assertThat(out).isNotNull().isInstanceOf<InOut>()
			assertThat((out as InOut).getName(), name = "OUT after reload").isEqualTo("S_15_10")
		}
	}

	@Test
	fun generatedNameClashingWithAnExplicitNameFailsTheLoadNamingTheCause() {
		val xml =
			NetworkResources.SEMAPHORE_BASIC_XML.replace(
				"name=\"OUT\"",
				"name=\"S_15_10\""
			)

		assertFailure { reader.parse(xml) }
			.isInstanceOf<IllegalArgumentException>()
			.messageContains("S_15_10")
		assertFailure { reader.parse(xml) }.messageContains("(15,10)")
		assertFailure { reader.parse(xml) }.messageContains("(20,10)")
	}

	@Test
	fun prahaRoundTripKeepsEveryName() {
		val before = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { semaphoreNamesByPosition(it) }
		val saved = reader.parse(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).use { writer.generate(it) }
		val after = reader.parse(saved).use { semaphoreNamesByPosition(it) }

		assertThat(after).isEqualTo(before)
		assertThat(explicitSemaphoreNames(saved).values)
			.containsExactlyInAnyOrder(*explicitSemaphoreNames(NetworkResources.PRAHA_HLAVNI_NADRAZI_XML).values.toTypedArray())
	}

	private fun semaphoreAt(
		ctx: DefaultEditingContext,
		point: Point
	): RailSemaphore {
		val cell = ctx.getRailWayNetGrid()[point]
		assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
		return cell as RailSemaphore
	}

	private fun semaphoreNamesByPosition(ctx: DefaultEditingContext): Map<Point, String> {
		// The editing grid also holds TrackBlockPart cells despite its NodeCell type argument;
		// a typed view makes Kotlin/Native insert a NodeCell downcast that fails on them.
		val grid: RailwayNetGrid<*> = ctx.getRailWayNetGrid()
		return grid
			.mapNotNull { (position, cell) -> (cell as? RailSemaphore)?.let { position to it.getName() } }
			.toMap()
	}

	private fun nodeNames(ctx: DefaultEditingContext): List<String> {
		// The editing grid also holds TrackBlockPart cells despite its NodeCell type argument.
		val grid: RailwayNetGrid<*> = ctx.getRailWayNetGrid()
		return grid.mapNotNull { (it.value as? NodeCell)?.getName() }
	}

	private companion object {
		const val PRAHA_SEMAPHORE_COUNT = 37
		const val PRAHA_NAMED_COUNT = 19

		private val SEMAPHORE_TAG = Regex("<RailSemaphore\\b[^>]*>")
		private val X_ATTR = Regex("\\bX=\"(\\d+)\"")
		private val Y_ATTR = Regex("\\bY=\"(\\d+)\"")
		private val NAME_ATTR = Regex("\\bname=\"([^\"]+)\"")

		/** The `name` attributes written in [xml] for `RailSemaphore` elements, by grid position. */
		fun explicitSemaphoreNames(xml: String): Map<Point, String> =
			SEMAPHORE_TAG
				.findAll(xml)
				.mapNotNull { tag ->
					val name = NAME_ATTR.find(tag.value)?.groupValues?.get(1) ?: return@mapNotNull null
					val x = checkNotNull(X_ATTR.find(tag.value)).groupValues[1].toInt()
					val y = checkNotNull(Y_ATTR.find(tag.value)).groupValues[1].toInt()
					Point(x, y) to name
				}.toMap()
	}
}
