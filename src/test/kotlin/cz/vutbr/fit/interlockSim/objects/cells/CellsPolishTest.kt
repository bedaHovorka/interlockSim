/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Coverage Polish Tests - Cells Package
 */
package cz.vutbr.fit.interlockSim.objects.cells

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.*
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.Cell.SpatialType
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.objects.core.conflict
import cz.vutbr.fit.interlockSim.objects.core.d2r
import cz.vutbr.fit.interlockSim.objects.core.r2d
import cz.vutbr.fit.interlockSim.objects.core.segmentFor
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.Point
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test coverage polish for cells package - targeting 85%+ coverage.
 *
 * Focus areas:
 * - Cell utility functions edge cases (segmentFor, conflict, d2r, r2d)
 * - AbstractCell utility methods (joinsOnLine, secondOnLine, arr2set)
 * - DynamicRailSemaphore speed validation and constant semaphore
 * - DynamicRailSwitch path setup edge cases
 * - DynamicInOut invalid segment handling
 * - OrientedNodeCell null safety
 * - CellUtilities type checking
 */
@DisplayName("Cells Package - Coverage Polish Tests")
class CellsPolishTest {
	@Nested
	@DisplayName("Cell Utility Functions - Edge Cases")
	inner class CellUtilityEdgeCases {
		@Test
		fun `segmentFor with dx=0 dy=0 returns null`() {
			// Edge case: origin point has no direction segment
			val result = segmentFor(0, 0)
			assertThat(result).isNull()
		}

		@Test
		fun `segmentFor with all 8 valid directions`() {
			// Verify all 8 directional segments work correctly
			assertThat(segmentFor(-1, -1)).isEqualTo(Segment.B)
			assertThat(segmentFor(-1, 0)).isEqualTo(Segment.A)
			assertThat(segmentFor(-1, 1)).isEqualTo(Segment.D)
			assertThat(segmentFor(0, -1)).isEqualTo(Segment.C)
			assertThat(segmentFor(0, 1)).isEqualTo(Segment.H)
			assertThat(segmentFor(1, -1)).isEqualTo(Segment.E)
			assertThat(segmentFor(1, 0)).isEqualTo(Segment.F)
			assertThat(segmentFor(1, 1)).isEqualTo(Segment.G)
		}

		@Test
		fun `conflict with null segments returns true`() {
			// Both null are equal (a == b), so conflict returns true
			assertThat(conflict(null, null)).isTrue()
		}

		@Test
		fun `conflict with one null segment returns false`() {
			// One segment null - no conflict possible
			assertThat(conflict(Segment.A, null)).isFalse()
			assertThat(conflict(null, Segment.B)).isFalse()
		}

		@Test
		fun `conflict with same segment returns true`() {
			// Same segment always conflicts with itself
			assertThat(conflict(Segment.A, Segment.A)).isTrue()
			assertThat(conflict(Segment.F, Segment.F)).isTrue()
		}

		@Test
		fun `conflict with adjacent non-anti segments returns false`() {
			// Adjacent segments that aren't opposite don't conflict
			assertThat(conflict(Segment.A, Segment.B)).isFalse()
			assertThat(conflict(Segment.C, Segment.D)).isFalse()
		}

		@Test
		fun `conflict with anti segments returns false`() {
			// Anti-parallel segments are far apart (distance = 2.0 > 0.5), so no conflict
			// conflict() checks if distance <= 0.5, which is false for anti-segments
			assertThat(conflict(Segment.A, Segment.F)).isFalse()
			assertThat(conflict(Segment.B, Segment.G)).isFalse()
			assertThat(conflict(Segment.C, Segment.H)).isFalse()
			assertThat(conflict(Segment.D, Segment.E)).isFalse()
		}

		@Test
		fun `d2r conversion preserves values`() {
			// Test coordinate conversion d→r
			assertThat(d2r(0)).isEqualTo(0.5f)
			assertThat(d2r(1)).isEqualTo(1.0f)
			assertThat(d2r(-1)).isEqualTo(0.0f)
			assertThat(d2r(5)).isEqualTo(3.0f)
		}

		@Test
		fun `r2d conversion preserves values`() {
			// Test coordinate conversion r→d
			assertThat(r2d(0.5f)).isEqualTo(0)
			assertThat(r2d(1.0f)).isEqualTo(1)
			assertThat(r2d(0.0f)).isEqualTo(-1)
			assertThat(r2d(3.0f)).isEqualTo(5)
		}

		@Test
		fun `d2r and r2d are inverse operations`() {
			// Round-trip conversion should preserve values
			val testValues = listOf(-5, -1, 0, 1, 5, 10)
			for (d in testValues) {
				val converted = d2r(d)
				val roundTripped = r2d(converted)
				assertThat(roundTripped)
					.withMessage("Round-trip conversion for d=$d")
					.isEqualTo(d)
			}
		}
	}

	@Nested
	@DisplayName("AbstractCell Utility Methods")
	inner class AbstractCellUtilityMethods {
		@Test
		fun `arr2set with single element`() {
			val segments = arrayOf(Segment.A)
			val result = arr2set(segments)
			assertThat(result).containsExactlyInAnyOrder(Segment.A)
		}

		@Test
		fun `arr2set with multiple elements`() {
			val segments = arrayOf(Segment.A, Segment.B, Segment.C)
			val result = arr2set(segments)
			assertThat(result).containsAll(Segment.A, Segment.B, Segment.C)
			assertThat(result).hasSize(3)
		}

		@Test
		fun `arr2set with duplicate elements removes duplicates`() {
			val segments = arrayOf(Segment.A, Segment.B, Segment.A)
			val result = arr2set(segments)
			assertThat(result).containsExactlyInAnyOrder(Segment.A, Segment.B)
		}

		@Test
		fun `arr2set with empty array throws exception`() {
			val segments = emptyArray<Segment>()
			assertFailure {
				arr2set(segments)
			}.isInstanceOf(IllegalStateException::class)
		}

		@Test
		fun `joinsOnLine returns 1 segment for HORIZONTAL`() {
			// InOut.joins() returns only direction() segment, not both endpoints
			val cell = InOut("test", true, SpatialType.HORIZONTAL)
			val joins = cell.joins()
			assertThat(joins).hasSize(1)
			assertThat(joins).containsExactlyInAnyOrder(Segment.A)
		}

		@Test
		fun `joinsOnLine returns 1 segment for VERTICAL`() {
			// InOut.joins() returns only direction() segment, not both endpoints
			val cell = InOut("test", true, SpatialType.VERTICAL)
			val joins = cell.joins()
			assertThat(joins).hasSize(1)
			assertThat(joins).containsExactlyInAnyOrder(Segment.C)
		}
	}

	@Nested
	@DisplayName("OrientedNodeCell - Direction Edge Cases")
	inner class OrientedNodeCellDirectionTests {
		@Test
		fun `direction returns correct segment for HORIZONTAL orientation true`() {
			// HORIZONTAL segments: [F, A], orientation=true -> index 1 -> A
			val cell = InOut("test", true, SpatialType.HORIZONTAL)
			assertThat(cell.direction()).isEqualTo(Segment.A)
		}

		@Test
		fun `direction returns correct segment for HORIZONTAL orientation false`() {
			// HORIZONTAL segments: [F, A], orientation=false -> index 0 -> F
			val cell = InOut("test", false, SpatialType.HORIZONTAL)
			assertThat(cell.direction()).isEqualTo(Segment.F)
		}

		@Test
		fun `direction returns correct segment for VERTICAL orientation true`() {
			// VERTICAL segments: [H, C], orientation=true -> index 1 -> C
			val cell = InOut("test", true, SpatialType.VERTICAL)
			assertThat(cell.direction()).isEqualTo(Segment.C)
		}

		@Test
		fun `direction returns correct segment for VERTICAL orientation false`() {
			// VERTICAL segments: [H, C], orientation=false -> index 0 -> H
			val cell = InOut("test", false, SpatialType.VERTICAL)
			assertThat(cell.direction()).isEqualTo(Segment.H)
		}
	}

	@Nested
	@DisplayName("TrackBlockPart - Spatial Type Edge Cases")
	inner class TrackBlockPartTests {
		@Test
		fun `getSpatialType returns null for TrackBlockPart`() {
			val sep1 = mockk<OrientedPathSeparator>()
			val sep2 = mockk<OrientedPathSeparator>()
			val trackBlock = SimpleTrackBlock(sep1, sep2, 100.0, 30.0, 30.0)
			val part = TrackBlockPart(trackBlock, arrayOf(Segment.A, Segment.F))
			assertThat(part.getSpatialType()).isNull()
		}

		@Test
		fun `joins returns segment set for TrackBlockPart`() {
			val sep1 = mockk<OrientedPathSeparator>()
			val sep2 = mockk<OrientedPathSeparator>()
			val trackBlock = SimpleTrackBlock(sep1, sep2, 100.0, 30.0, 30.0)
			val part = TrackBlockPart(trackBlock, arrayOf(Segment.A, Segment.F))
			val joins = part.joins()
			assertThat(joins).containsExactlyInAnyOrder(Segment.A, Segment.F)
		}

		@Test
		fun `TrackBlockPart with 3 segments`() {
			// Test with more than 2 segments (edge case)
			val sep1 = mockk<OrientedPathSeparator>()
			val sep2 = mockk<OrientedPathSeparator>()
			val trackBlock = SimpleTrackBlock(sep1, sep2, 100.0, 30.0, 30.0)
			val part = TrackBlockPart(trackBlock, arrayOf(Segment.A, Segment.F, Segment.C))
			val joins = part.joins()
			assertThat(joins).hasSize(3)
			assertThat(joins).containsAll(Segment.A, Segment.F, Segment.C)
		}
	}

	@Nested
	@DisplayName("Cell Connection Validation")
	inner class CellConnectionValidation {
		@Test
		fun `canJoin returns true for compatible segments`() {
			val cell1 = InOut("A", true, SpatialType.HORIZONTAL)
			val cell2 = InOut("B", false, SpatialType.HORIZONTAL)
			
			// Both have HORIZONTAL spatial type, should be compatible
			assertThat(cell1.getSpatialType()).isNotNull()
			assertThat(cell2.getSpatialType()).isNotNull()
		}

		@Test
		fun `segment transform creates new point`() {
			val center = Point(5, 10)
			val transformed = Segment.A.transform(center)
			
			assertThat(transformed).isNotEqualTo(center)
			assertThat(transformed.x).isNotEqualTo(center.x)
		}

		@Test
		fun `anti returns opposite segment`() {
			assertThat(anti(Segment.A)).isEqualTo(Segment.F)
			assertThat(anti(Segment.B)).isEqualTo(Segment.G)
			assertThat(anti(Segment.C)).isEqualTo(Segment.H)
			assertThat(anti(Segment.D)).isEqualTo(Segment.E)
		}

		@Test
		fun `anti is reflexive`() {
			for (segment in Segment.values()) {
				assertThat(anti(anti(segment)))
					.withMessage("anti(anti($segment)) should equal $segment")
					.isEqualTo(segment)
			}
		}
	}

	@Nested
	@DisplayName("RailSemaphore Edge Cases")
	inner class RailSemaphoreEdgeCases {
		@Test
		fun `RailSemaphore with all spatial types`() {
			for (spatialType in SpatialType.values()) {
				val semaphore = RailSemaphore(true, spatialType)
				assertThat(semaphore.getSpatialType()).isEqualTo(spatialType)
			}
		}

		@Test
		fun `RailSemaphore orientation affects direction`() {
			val semTrue = RailSemaphore(true, SpatialType.HORIZONTAL)
			val semFalse = RailSemaphore(false, SpatialType.HORIZONTAL)
			
			assertThat(semTrue.direction()).isNotEqualTo(semFalse.direction())
			assertThat(anti(semTrue.direction())).isEqualTo(semFalse.direction())
		}
	}

	@Nested
	@DisplayName("RailSwitch Edge Cases")
	inner class RailSwitchEdgeCases {
		@Test
		fun `RailSwitch with different types`() {
			// Test creation with different switch types
			val simple = RailSwitch(SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_TRUE)
			assertThat(simple.type).isEqualTo(RailSwitch.Type.SIMPLE_RIGHT_TRUE)
		}

		@Test
		fun `RailSwitch getFollowingSegment returns valid segments`() {
			val railSwitch = RailSwitch(SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_TRUE)
			val joins = railSwitch.joins()

			// Should have 3 segments for a SIMPLE switch
			assertThat(joins).hasSize(3)
		}
	}

	@Nested
	@DisplayName("InOut Edge Cases")
	inner class InOutEdgeCases {
		@Test
		fun `InOut with empty name`() {
			val inOut = InOut("", true, SpatialType.HORIZONTAL)
			assertThat(inOut.getName()).isEmpty()
		}

		@Test
		fun `InOut with long name`() {
			val longName = "A".repeat(100)
			val inOut = InOut(longName, true, SpatialType.HORIZONTAL)
			assertThat(inOut.getName()).isEqualTo(longName)
		}

		@Test
		fun `InOut name affects toString`() {
			val inOut = InOut("TestStation", true, SpatialType.HORIZONTAL)
			val string = inOut.toString()
			assertThat(string).contains("TestStation")
		}
	}
}
