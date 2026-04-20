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

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * XML round-trip tests using canonical NetworkResources definitions.
 *
 * These tests exercise parse -> write -> re-parse for each standard network
 * topology, verifying that all element types and attributes survive the cycle.
 * Runs on both JVM and linuxX64 via commonTest.
 *
 * @since 2026-03-24 (Issue #410 — increase native test coverage)
 */
class XmlRoundTripNetworkResourcesTest {
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

	// --- VYHYBNA_XML (shunting loop — the canonical complex network) ---

	@Test
	fun roundTripVyhybnaPreservesInOutCount() {
		val (inOutCount, xml) = CommonTestFixtures.parseEditingContext(NetworkResources.VYHYBNA_XML).use { ctx1 ->
			ctx1.getInOuts().size to writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getInOuts()).hasSize(inOutCount)
		}
	}

	@Test
	fun roundTripVyhybnaPreservesTrackBlocks() {
		val (edges1, xml) = CommonTestFixtures.parseEditingContext(NetworkResources.VYHYBNA_XML).use { ctx1 ->
			ctx1.getGraph().entrySet().size to writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getGraph().entrySet().size).isEqualTo(edges1)
		}
	}

	@Test
	fun doubleRoundTripVyhybnaPreservesStructure() {
		val (inOutCount1, edgeCount1, xml1) =
			CommonTestFixtures.parseEditingContext(NetworkResources.VYHYBNA_XML).use { ctx1 ->
				Triple(ctx1.getInOuts().size, ctx1.getGraph().entrySet().size, writer.generate(ctx1))
			}

		reader.parse(xml1).use { ctx2 ->
			val inOutCount2 = ctx2.getInOuts().size
			val edgeCount2 = ctx2.getGraph().entrySet().size
			val xml2 = writer.generate(ctx2)

			reader.parse(xml2).use { ctx3 ->
				val inOutCount3 = ctx3.getInOuts().size
				val edgeCount3 = ctx3.getGraph().entrySet().size

				// Structure is preserved across both round trips
				assertThat(inOutCount2).isEqualTo(inOutCount1)
				assertThat(edgeCount2).isEqualTo(edgeCount1)
				assertThat(inOutCount3).isEqualTo(inOutCount1)
				assertThat(edgeCount3).isEqualTo(edgeCount1)
			}
		}
	}

	// --- LINEAR_TRACK_XML ---

	@Test
	fun roundTripLinearTrackPreservesStructure() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getInOuts()).hasSize(2)
			assertThat(ctx2.getGraph().entrySet()).hasSize(1)
		}
	}

	@Test
	fun roundTripLinearTrackPreservesTrackLength() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.LINEAR_TRACK_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			val trackBlock =
				ctx2
					.getGraph()
					.entrySet()
					.first()
					.value
			assertThat(trackBlock.length()).isEqualTo(100.0)
		}
	}

	// --- SWITCH_BASIC_XML ---

	@Test
	fun roundTripSwitchPreservesThreeInOuts() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.SWITCH_BASIC_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getInOuts()).hasSize(3)
		}
	}

	@Test
	fun roundTripSwitchPreservesSwitchType() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.SWITCH_BASIC_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			// Coordinate coupled to SWITCH_BASIC_XML fixture: the switch element is at grid (15,10)
			val cell = ctx2.getRailWayNetGrid()[Point(15, 10)]
			assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
			assertThat((cell as RailSwitch).type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
		}
	}

	@Test
	fun roundTripSwitchPreservesThreeTrackBlocks() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.SWITCH_BASIC_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getGraph().entrySet()).hasSize(3)
		}
	}

	// --- SEMAPHORE_BASIC_XML ---

	@Test
	fun roundTripSemaphorePreservesSemaphoreElement() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.SEMAPHORE_BASIC_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			// Coordinate coupled to SEMAPHORE_BASIC_XML fixture: the semaphore element is at grid (15,10)
			val cell = ctx2.getRailWayNetGrid()[Point(15, 10)]
			assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
			assertThat((cell as RailSemaphore).getOrientation()).isTrue()
		}
	}

	@Test
	fun roundTripSemaphorePreservesTwoTrackBlocks() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.SEMAPHORE_BASIC_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getGraph().entrySet()).hasSize(2)
		}
	}

	// --- TWO_TRACKS_PARALLEL_XML ---

	@Test
	fun roundTripParallelTracksPreservesFourInOuts() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.TWO_TRACKS_PARALLEL_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getInOuts()).hasSize(4)
		}
	}

	@Test
	fun roundTripParallelTracksPreservesTwoTrackBlocks() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.TWO_TRACKS_PARALLEL_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getGraph().entrySet()).hasSize(2)
		}
	}

	@Test
	fun doubleRoundTripParallelTracksIsStable() {
		data class NetworkStructureData(
			val inOutCount: Int,
			val edgeCount: Int,
			val cols: Int,
			val rows: Int,
			val xml: String,
		)
		val data1 = CommonTestFixtures.parseEditingContext(NetworkResources.TWO_TRACKS_PARALLEL_XML).use { ctx1 ->
			NetworkStructureData(
				ctx1.getInOuts().size,
				ctx1.getGraph().entrySet().size,
				ctx1.getRailWayNetGrid().cols,
				ctx1.getRailWayNetGrid().rows,
				writer.generate(ctx1)
			)
		}

		reader.parse(data1.xml).use { ctx2 ->
			val xml2 = writer.generate(ctx2)

			reader.parse(xml2).use { ctx3 ->
				// Structure is preserved across both round trips
				assertThat(ctx3.getInOuts()).hasSize(data1.inOutCount)
				assertThat(ctx3.getGraph().entrySet()).hasSize(data1.edgeCount)
				assertThat(ctx3.getRailWayNetGrid().cols).isEqualTo(data1.cols)
				assertThat(ctx3.getRailWayNetGrid().rows).isEqualTo(data1.rows)
			}
		}
	}

	// --- SINGLE_INOUT_XML ---

	@Test
	fun roundTripSingleInOutPreservesOneInOut() {
		val xml = CommonTestFixtures.parseEditingContext(NetworkResources.SINGLE_INOUT_XML).use { ctx1 ->
			writer.generate(ctx1)
		}

		reader.parse(xml).use { ctx2 ->
			assertThat(ctx2.getInOuts()).hasSize(1)
			val inOut = ctx2.getInOuts().first()
			assertThat(inOut).isInstanceOf<InOut>()
			assertThat((inOut as InOut).getName()).isEqualTo("OnlyOne")
		}
	}
}
