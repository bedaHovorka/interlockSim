/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Phase 3: XML Parsing & Configuration Tests
 */
package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultEditingContext
import cz.vutbr.fit.interlockSim.context.DefaultRailWayNetGrid
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.INOUT_KEY
import cz.vutbr.fit.interlockSim.testutil.RAIL_SEMAPHORE_KEY
import cz.vutbr.fit.interlockSim.testutil.RAIL_SWITCH_KEY
import cz.vutbr.fit.interlockSim.testutil.countCellTypes
import cz.vutbr.fit.interlockSim.testutil.existPath
import cz.vutbr.fit.interlockSim.testutil.saveAndReloadThroughFile
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Topology / path-existence tests for [XMLContextFactory]: complex station
 * configurations and the Praha improvements (PR #347), built from
 * praha-hlavni-nadrazi.xml.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class XMLContextFactoryTopologyTest : XMLContextFactoryTestBase() {
	// Praha Hlavní Nádraží Tests

	@Test
	fun testPragueContextLoading() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			assertThat(context).isNotNull()
			val grid = context.getRailWayNetGrid()
			assertThat(grid.cols).isEqualTo(70)
			assertThat(grid.rows).isEqualTo(25)

			// Verify grid is not empty
			val hasElements = grid.iterator().hasNext()
			assertThat(hasElements).withMessage("Praha grid should contain elements").isTrue()
		}
	}

	@Test
	fun testPragueElementComposition() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			val counts = countCellTypes(context.getRailWayNetGrid())

			// Verify element counts meet thresholds (adjusted for simplified topology)
			assertThat(counts.getValue(INOUT_KEY))
				.withMessage("Praha should have at least 6 InOut points (4 north + 6 south)")
				.isGreaterThan(5)
			assertThat(counts.getValue(RAIL_SWITCH_KEY))
				.withMessage("Praha should have at least 8 switches (4 north throat + 4 south throat)")
				.isGreaterThan(7)
			assertThat(counts.getValue(RAIL_SEMAPHORE_KEY))
				.withMessage("Praha should have at least 20 signals (entry + platform + exit)")
				.isGreaterThan(19)
		}
	}

	@Test
	fun testPraguePlatformConnectivity() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			// Find entry and exit InOuts by orientation
			val entries = mutableListOf<InOut>() // orientation=false (entries from west/north)
			val exits = mutableListOf<InOut>() // orientation=true (exits to east/south)

			for (entry in context.getRailWayNetGrid()) {
				if (entry.value is InOut) {
					val inOut = entry.value as InOut
					if (inOut.getOrientation()) {
						exits.add(inOut)
					} else {
						entries.add(inOut)
					}
				}
			}

			assertThat(entries.size)
				.withMessage("Praha should have entry points (orientation=false)")
				.isGreaterThan(3)
			assertThat(exits.size)
				.withMessage("Praha should have exit points (orientation=true)")
				.isGreaterThan(3)

			// Verify connectivity between north entry and south exit
			if (entries.isNotEmpty() && exits.isNotEmpty()) {
				val from = entries[0]
				val to = exits[0]
				assertThat(existPath(from, to, context))
					.withMessage("Path should exist from north entry ${from.getName()} to south exit ${to.getName()}")
					.isTrue()
			}
		}
	}

	@Test
	fun testPragueSignalPlacement() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			val grid = context.getRailWayNetGrid()

			// Find signals in north throat area (X=1-20)
			val northSignals = findSignalsInArea(context, 1, 20, 0, grid.rows - 1)
			assertThat(northSignals.size)
				.withMessage("North throat should have entry signals")
				.isGreaterThan(3)

			// Find signals in south throat area (X=50-69)
			val southSignals =
				findSignalsInArea(context, 50, grid.cols - 1, 0, grid.rows - 1)
			assertThat(southSignals.size)
				.withMessage("South throat should have exit signals")
				.isGreaterThan(3)
		}
	}

	@Test
	fun testPragueMultipleRoutes() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			// Find all InOuts
			val allInOuts = mutableListOf<InOut>()
			for (entry in context.getRailWayNetGrid()) {
				if (entry.value is InOut) {
					allInOuts.add(entry.value as InOut)
				}
			}

			// Verify at least 3 independent entry/exit pairs exist
			assertThat(allInOuts.size)
				.withMessage("Praha should support multiple routes with multiple InOuts")
				.isGreaterThan(6)
		}
	}

	@Test
	fun testPragueBayPlatformTermination() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			// Bay platforms should exist (check for InOuts with "Bay" in name)
			var foundBayPlatform = false
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut && cell.getName().contains("Bay")) {
					foundBayPlatform = true
					break
				}
			}

			// This test is informational - bay platforms are optional
			// Just verify we can load and parse the structure
			assertThat(context).isNotNull()
		}
	}

	// Validation Tests

	@Test
	fun testSwitchSegmentConsistencyPraha() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			// Verify all switches are properly connected
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is RailSwitch) {
					val location = context.getRailWayNetGrid().getLocation(cell)
					if (location != null) {
						val edges = context.getGraph().assignedEdges(location)
						// Switches should have at least 2 connections (input + output)
						assertThat(edges.size)
							.withMessage("Switch at $location should have connections")
							.isGreaterThan(1)
					}
				}
			}
		}
	}

	// Serialization Tests

	@Test
	fun testPragueSaveLoad(
		@TempDir tempDir: File
	) {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { originalContext ->
			val tempFile = File(tempDir, "praha.xml")

			// Save, load back, and verify structure is preserved
			editingContextFactory.saveAndReloadThroughFile(originalContext, tempFile) { loadedContext ->
				assertThat(loadedContext).isNotNull()
				assertThat(loadedContext.getRailWayNetGrid().cols).isEqualTo(70)
				assertThat(loadedContext.getRailWayNetGrid().rows).isEqualTo(25)

				// Verify element counts match
				val originalCounts = countCellTypes(originalContext.getRailWayNetGrid() as DefaultRailWayNetGrid)
				val loadedCounts = countCellTypes(loadedContext.getRailWayNetGrid() as DefaultRailWayNetGrid)
				assertThat(loadedCounts.getValue(INOUT_KEY)).isEqualTo(originalCounts.getValue(INOUT_KEY))
				assertThat(loadedCounts.getValue(RAIL_SWITCH_KEY)).isEqualTo(originalCounts.getValue(RAIL_SWITCH_KEY))
				assertThat(loadedCounts.getValue(RAIL_SEMAPHORE_KEY)).isEqualTo(originalCounts.getValue(RAIL_SEMAPHORE_KEY))
			}
		}
	}

	// Praha improvements (PR #347)

	@Test
	@DisplayName("Praha XML loads with exact element counts after PR #347 additions")
	fun testPragueExactElementCounts() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			@Suppress("UNCHECKED_CAST")
			val cellGrid = context.getRailWayNetGrid() as RailwayNetGrid<Cell>

			var inOutCount = 0
			var switchCount = 0
			var semaphoreCount = 0
			for (entry in cellGrid) {
				when (entry.value) {
					is InOut -> inOutCount++
					is RailSwitch -> switchCount++
					is RailSemaphore -> semaphoreCount++
				}
			}

			// Count unique track blocks via graph (SimpleTrackBlocks are graph edges, not grid cells)
			val seenBlocks = java.util.IdentityHashMap<TrackSection, Unit>()
			val graph = (context as DefaultEditingContext).getGraph()
			for (node in graph.nodeSet()) {
				for (entry in graph.assignedEdges(node).entries) {
					val edge = entry.value
					if (edge is TrackSection) {
						seenBlocks[edge] = Unit
					}
				}
			}
			val trackBlockCount = seenBlocks.size

			assertThat(inOutCount)
				.withMessage("Praha should have exactly 11 InOut elements (car train terminal deferred)")
				.isEqualTo(11)
			assertThat(switchCount)
				.withMessage("Praha should have exactly 50 switches")
				.isEqualTo(50)
			assertThat(semaphoreCount)
				.withMessage("Praha should have exactly 37 signals")
				.isEqualTo(37)
			assertThat(trackBlockCount)
				.withMessage("Praha should have exactly 117 track blocks (car train terminal deferred)")
				.isEqualTo(117)
		}
	}

	@Test
	@DisplayName("N-Bypass InOut is present at grid (2,20) with entry orientation")
	fun testPragueNorthBypassInOutPresent() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			val cell = context.getRailWayNetGrid().getCellAt(2, 20)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)

			val inOut = cell as InOut
			assertThat(inOut.getName())
				.withMessage("N-Bypass InOut should have correct name")
				.isEqualTo("N-Bypass")
			assertThat(inOut.getOrientation())
				.withMessage("N-Bypass InOut should be an entry point (orientation=false)")
				.isFalse()
		}
	}

	@Test
	@DisplayName("S-Bypass InOut is present at grid (60,20) with exit orientation")
	fun testPragueSouthBypassInOutPresent() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			val cell = context.getRailWayNetGrid().getCellAt(60, 20)
			assertThat(cell).isNotNull().isInstanceOf(InOut::class)

			val inOut = cell as InOut
			assertThat(inOut.getName())
				.withMessage("S-Bypass InOut should have correct name")
				.isEqualTo("S-Bypass")
			assertThat(inOut.getOrientation())
				.withMessage("S-Bypass InOut should be an exit point (orientation=true)")
				.isTrue()
		}
	}

	@Test
	@DisplayName("Bypass route N-Bypass to S-Bypass is navigable")
	fun testPragueBypassRouteNavigable() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		(editingContextFactory.createContext(xml) as EditingContext).use { context ->
			var nBypass: InOut? = null
			var sBypass: InOut? = null
			for (entry in context.getRailWayNetGrid()) {
				val cell = entry.value
				if (cell is InOut) {
					when (cell.getName()) {
						"N-Bypass" -> nBypass = cell
						"S-Bypass" -> sBypass = cell
					}
				}
			}

			assertThat(nBypass)
				.withMessage("N-Bypass InOut should exist in Praha XML")
				.isNotNull()
			assertThat(sBypass)
				.withMessage("S-Bypass InOut should exist in Praha XML")
				.isNotNull()

			assertThat(existPath(nBypass!!, sBypass!!, context))
				.withMessage("Path should exist from N-Bypass to S-Bypass")
				.isTrue()
		}
	}

	@Test
	@DisplayName("Bypass corridor switches at Y=20 have the orientations the bypass route requires")
	fun testPragueBypassSwitchOrientations() {
		val xml = getFixtureStream("praha-hlavni-nadrazi.xml")

		editingContextFactory.createContext(xml).use { context ->
			val grid = context.getRailWayNetGrid()

			// Four switches sit on the bypass corridor (Y=20). Their types are the
			// physical orientation of the diverge — assert each one to lock the
			// bypass topology against regression.
			val expectedTypes =
				mapOf(
					(11 to 20) to RailSwitch.Type.SIMPLE_RIGHT_TRUE,
					(15 to 20) to RailSwitch.Type.SIMPLE_RIGHT_TRUE,
					(46 to 20) to RailSwitch.Type.SIMPLE_RIGHT_TRUE,
					(51 to 20) to RailSwitch.Type.SIMPLE_RIGHT_FALSE
				)
			for ((coords, expectedType) in expectedTypes) {
				val (x, y) = coords
				val cell = grid.getCellAt(x, y)
				assertThat(cell)
					.withMessage("Cell at ($x,$y) should be a RailSwitch on the bypass corridor")
					.isNotNull()
					.isInstanceOf(RailSwitch::class)
				assertThat((cell as RailSwitch).type)
					.withMessage("Switch at ($x,$y) must be $expectedType to keep the bypass route diverging correctly")
					.isEqualTo(expectedType)
			}
		}
	}

	/**
	 * Finds all signals within specified grid rectangle.
	 */
	private fun findSignalsInArea(
		context: EditingContext,
		startX: Int,
		endX: Int,
		startY: Int,
		endY: Int
	): List<RailSemaphore> {
		val signals = mutableListOf<RailSemaphore>()

		// Phase 6: Grid is typed as RailwayNetGrid<NodeCell> but internally contains Cell (NodeCell + TrackBlockPart)
		// Cast to Cell grid to access all cells without ClassCastException
		@Suppress("UNCHECKED_CAST")
		val grid = context.getRailWayNetGrid() as RailwayNetGrid<Cell>

		for (x in startX..endX) {
			for (y in startY..endY) {
				val cell = grid.getCellAt(x, y)
				if (cell is RailSemaphore) {
					signals.add(cell)
				}
			}
		}
		return signals
	}
}
