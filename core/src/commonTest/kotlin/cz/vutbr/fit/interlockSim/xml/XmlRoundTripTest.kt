package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.Resources
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class XmlRoundTripTest {

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
	fun roundTripPreservesGridDimensions() {
		val ctx1 = reader.parse(minimalNetworkXml())
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		reader.parse(generatedXml).use { ctx2 ->
			assertThat(ctx2.getRailWayNetGrid().cols).isEqualTo(30)
			assertThat(ctx2.getRailWayNetGrid().rows).isEqualTo(30)
		}
	}

	@Test
	fun roundTripPreservesInOutCount() {
		val ctx1 = reader.parse(minimalNetworkXml())
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		reader.parse(generatedXml).use { ctx2 ->
			assertThat(ctx2.getInOuts()).hasSize(2)
		}
	}

	@Test
	fun roundTripPreservesInOutPositionsAndNames() {
		val ctx1 = reader.parse(minimalNetworkXml())
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		reader.parse(generatedXml).use { ctx2 ->
			val grid = ctx2.getRailWayNetGrid()
			val cellA = grid[Point(1, 1)]
			assertThat(cellA).isNotNull().isInstanceOf<InOut>()
			assertThat((cellA as InOut).getName()).isEqualTo("A")

			val cellB = grid[Point(5, 5)]
			assertThat(cellB).isNotNull().isInstanceOf<InOut>()
			assertThat((cellB as InOut).getName()).isEqualTo("B")
		}
	}

	@Test
	fun roundTripPreservesRailSemaphore() {
		val xml = Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/roundtrip-with-semaphore.xml")

		val ctx1 = reader.parse(xml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		reader.parse(generatedXml).use { ctx2 ->
			val cell = ctx2.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSemaphore>()
			assertThat(cell!!.getSpatialType()).isEqualTo(Cell.SpatialType.VERTICAL)
			assertThat(cell).isInstanceOf<OrientedPathSeparator>()
			assertThat((cell as OrientedPathSeparator).getOrientation()).isEqualTo(true)
			assertThat((cell as RailSemaphore).getName()).isEqualTo("S1")
		}
	}

	@Test
	fun roundTripPreservesRailSwitch() {
		val xml = Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/roundtrip-with-switch.xml")

		val ctx1 = reader.parse(xml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		reader.parse(generatedXml).use { ctx2 ->
			val cell = ctx2.getRailWayNetGrid()[Point(3, 3)]
			assertThat(cell).isNotNull().isInstanceOf<RailSwitch>()
			assertThat((cell as RailSwitch).type).isEqualTo(RailSwitch.Type.SIMPLE_LEFT_FALSE)
			assertThat(cell.getName()).isEqualTo("W1")
		}
	}

	@Test
	fun roundTripPreservesTrackBlock() {
		val xml = Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/roundtrip-with-trackblock.xml")

		val ctx1 = reader.parse(xml)
		val generatedXml = writer.generate(ctx1)
		ctx1.close()

		reader.parse(generatedXml).use { ctx2 ->
			val graph = ctx2.getGraph()
			val edges = graph.entrySet()
			assertThat(edges).hasSize(1)

			val entry = edges.first()
			val trackBlock = entry.value
			assertThat(trackBlock.length()).isEqualTo(100.0)
		}
	}

	@Test
	fun doubleRoundTripProducesStableXml() {
		// First round trip
		val ctx1 = reader.parse(minimalNetworkXml())
		val xml1 = writer.generate(ctx1)
		ctx1.close()

		// Second round trip
		val ctx2 = reader.parse(xml1)
		val xml2 = writer.generate(ctx2)
		ctx2.close()

		// The XML from second round trip should be identical to first
		assertThat(xml2).isEqualTo(xml1)
	}

	// --- Helper methods ---

	private fun minimalNetworkXml(): String =
		Resources.read("cz/vutbr/fit/interlockSim/xml/fixtures/roundtrip-minimal.xml")
}
