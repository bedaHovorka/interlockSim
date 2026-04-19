/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import cz.vutbr.fit.interlockSim.testutil.createMockNodeCell
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Regression tests for [SimpleTrack] — specifically verifying that the replacement of
 * [java.util.IdentityHashMap] with [HashMap] in the `speeds` field preserves correct semantics.
 *
 * ## Background
 *
 * PR #375 replaced `IdentityHashMap<PathSeparator, Double>` with `HashMap<PathSeparator, Double>`
 * in [SimpleTrack.speeds]. A reviewer raised the concern that HashMap uses `equals`/`hashCode`
 * for key lookup, which could conflate two distinct endpoint instances if they happened to be
 * equal by value.
 *
 * ## Why HashMap is safe here
 *
 * [cz.vutbr.fit.interlockSim.objects.cells.NodeCell] (and all static `PathSeparator`
 * implementations used as endpoints) do **not** override `equals` or `hashCode`. They inherit
 * the default `Object` implementations, which are based on object identity
 * (`System.identityHashCode`). Therefore, `HashMap` and `IdentityHashMap` produce identical
 * key-lookup behaviour for these types.
 *
 * These tests demonstrate that property:
 * - Two distinct [cz.vutbr.fit.interlockSim.testutil.MockNodeCell] instances created with
 *   identical configuration are NOT considered equal by `HashMap`.
 * - `maxSpeed()` resolves to the speed registered for the exact endpoint reference passed
 *   at construction time.
 */
@DisplayName("SimpleTrack — HashMap semantics regression (PR #375)")
class SimpleTrackTest {
	@Nested
	@DisplayName("speeds map — IdentityHashMap → HashMap replacement")
	inner class SpeedsMapRegressionTests {
		@Test
		@DisplayName("maxSpeed returns end1 speed when queried with end1 reference")
		fun `maxSpeed returns correct speed for end1`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val track = SimpleTrackBlock(end1, end2, 100.0, 40.0, 80.0)

			// Act & Assert
			assertThat(track.maxSpeed(end1)).isEqualTo(40.0)
		}

		@Test
		@DisplayName("maxSpeed returns end2 speed when queried with end2 reference")
		fun `maxSpeed returns correct speed for end2`() {
			// Arrange
			val end1 = createMockNodeCell(name = "End1")
			val end2 = createMockNodeCell(name = "End2")
			val track = SimpleTrackBlock(end1, end2, 100.0, 40.0, 80.0)

			// Act & Assert
			assertThat(track.maxSpeed(end2)).isEqualTo(80.0)
		}

		@Test
		@DisplayName("two endpoints with identical configuration have independent speed entries")
		fun `two endpoints with same config are independent keys in HashMap`() {
			// Arrange: create two distinct NodeCell instances with the same configuration.
			// If HashMap used value equality these would map to the same key and overwrite
			// each other, producing only one entry. With identity-based hashCode (the default
			// for NodeCell which does not override equals/hashCode) they remain separate keys.
			val end1 = createMockNodeCell(name = "Node", speed = 50.0)
			val end2 = createMockNodeCell(name = "Node", speed = 50.0) // same config, distinct object

			val speed1 = 30.0
			val speed2 = 70.0
			val track = SimpleTrackBlock(end1, end2, 100.0, speed1, speed2)

			// Act & Assert: both endpoints are independently addressable
			assertThat(track.maxSpeed(end1)).isEqualTo(speed1)
			assertThat(track.maxSpeed(end2)).isEqualTo(speed2)
			// And crucially, they differ from each other
			assertThat(track.maxSpeed(end1)).isNotEqualTo(track.maxSpeed(end2))
		}

		@Test
		@DisplayName("speeds are asymmetric — end1 speed != end2 speed is preserved")
		fun `asymmetric speeds per endpoint are preserved after HashMap replacement`() {
			// Arrange
			val end1 = createMockNodeCell(name = "Left")
			val end2 = createMockNodeCell(name = "Right")
			// Deliberately different speeds to confirm no accidental overwrite
			val track = SimpleTrackBlock(end1, end2, 200.0, 60.0, 120.0)

			// Act & Assert
			assertThat(track.maxSpeed(end1)).isEqualTo(60.0)
			assertThat(track.maxSpeed(end2)).isEqualTo(120.0)
		}
	}
}
