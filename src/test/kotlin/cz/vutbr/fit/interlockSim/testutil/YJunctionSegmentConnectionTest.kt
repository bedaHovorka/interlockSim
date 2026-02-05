package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.segmentFor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Test to verify that yJunctionWithSwitch has correct segment connections.
 *
 * Issue: A SIMPLE switch only has 3 segments, but the current topology tries to connect 4 tracks.
 */
@DisplayName("Y-Junction Switch Segment Connection Test")
class YJunctionSegmentConnectionTest : KoinTestBase() {

	@Test
	@DisplayName("SIMPLE_RIGHT_FALSE switch should only have 3 valid segments")
	fun switchSupportsOnly3Segments() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val switchCell = context.getRailWayNetGrid().getCellAt(30, 30)
			assertThat(switchCell).isNotNull()
			assertThat(switchCell as Any).isInstanceOf<RailSwitch>()

			val switch = switchCell as RailSwitch
			assertThat(switch.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			assertThat(switch.getSpatialType()).isEqualTo(Cell.SpatialType.HORIZONTAL)

			// SIMPLE_RIGHT_FALSE + HORIZONTAL should only support 3 segments:
			// - A (West) - merging/stem
			// - F (East) - main direction
			// - G (Southeast) - branch direction
			val validSegments = switch.joins()
			println("Switch supports ${validSegments.size} segments: $validSegments")
			assertThat(validSegments.size).isEqualTo(3)
		}
	}

	@Test
	@DisplayName("Verify segments for each connection attempt")
	fun verifyConnectionSegments() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val switchPos: Pair<Int, Int> = 30 to 30

			// Check what segment each connection would use
			val connections = listOf(
				Triple("EntryA", 5 to 20, "Entry from West"),
				Triple("EntryB", 5 to 40, "Entry from Southwest"),
				Triple("ExitC", 55 to 20, "Exit to East"),
				Triple("ExitD", 55 to 40, "Exit to Southeast")
			)

			connections.forEach { (name: String, pos: Pair<Int, Int>, description: String) ->
				val dx = switchPos.first - pos.first
				val dy = switchPos.second - pos.second
				val segment = segmentFor(dx, dy)
				println("$name at $pos: dx=$dx, dy=$dy -> segment=$segment ($description)")
			}

			// Expected segments:
			// EntryA (5,20) -> (30,30): dx=25, dy=10 -> Segment.G (SE)? Or reverse?
			// Actually we need to think about direction FROM the cell TO the switch

			// Let me recalculate correctly:
			// From EntryA (5,20) TO Switch (30,30): dx=+25, dy=+10 -> Segment.G (going SE)
			// From EntryB (5,40) TO Switch (30,30): dx=+25, dy=-10 -> Segment.E (going NE)
			// From Switch (30,30) TO ExitC (55,20): dx=+25, dy=-10 -> Segment.E (going NE)
			// From Switch (30,30) TO ExitD (55,40): dx=+25, dy=+10 -> Segment.G (going SE)

			// Check actual switch segments
			val switchCell = context.getRailWayNetGrid().getCellAt(30, 30) as RailSwitch
			val validSegments = switchCell.joins()

			println("\nSwitch valid segments: $validSegments")

			// Check graph structure
			val graph = context.getGraph()
			println("\nGraph edges: ${graph.size()}")
		}
	}

	@Test
	@DisplayName("Check switch joins() - should only return 3 segments")
	fun switchJoinsCount() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val switchCell = context.getRailWayNetGrid().getCellAt(30, 30) as RailSwitch
			val segments = switchCell.joins()

			println("Switch joins() returns ${segments.size} segments: $segments")

			// A SIMPLE switch should only have 3 segments
			assertThat(segments.size).isEqualTo(3)
		}
	}

	@Test
	@DisplayName("Verify which tracks are actually connected in the graph")
	fun verifyActualConnections() {
		TestTopologies.yJunctionWithSwitch().use { context ->
			val graph = context.getGraph()
			println("Graph has ${graph.size()} edges")

			// Get all InOuts
			val inOuts = context.getInOuts()
			println("\nAll InOuts (${inOuts.size}):")
			inOuts.forEach { println("  - $it") }

			// Check graph connectivity
			println("\nGraph nodes:")
			val nodes = graph.nodeSet()
			nodes.forEach { node ->
				val edges = graph.get(node)
				println("  Node $node connects to ${edges.size()} edge(s): ${edges.joinToString()}")
			}

			println("\nAll edges in graph:")
			val allEdges = graph.values()
			allEdges.forEach { edge ->
				println("  - $edge")
			}

			// Expected: Only 3 edges because one track cannot connect
			assertThat(graph.size()).isEqualTo(3)

			// The fixture tries to create 4 tracks but only 3 should succeed
			println("\nConclusion: One of the 4 SimpleTrackBlocks failed to connect!")
		}
	}
}
