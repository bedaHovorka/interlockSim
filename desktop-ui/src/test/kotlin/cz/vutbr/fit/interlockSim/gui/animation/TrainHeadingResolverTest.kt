/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 */
package cz.vutbr.fit.interlockSim.gui.animation

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.util.PointF
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.PI

/**
 * Unit tests for [TrainHeadingResolver] (Issue #719, revised for Issue #788).
 *
 * The resolver was introduced as the canvas-side guard against the spurious 180° nose flips
 * the simulation produced when its `(frontSection, trainEntrySeparator)` pair went stale at a
 * boundary with no section to enter — at the destination InOut after arrival, and while the
 * route beyond the separator was not reserved yet. Issue #788 fixed that in `:core`, so the
 * authoritative heading no longer reverses there.
 *
 * The resolver is kept as defence in depth (owner decision, #788), and these tests pin its
 * behaviour rather than a simulation defect: a 180° change while the front does not move is
 * suppressed, and a genuine reversal
 * ([cz.vutbr.fit.interlockSim.sim.Train.reverseDirection]) is accepted once the train moves
 * with the flipped heading persisting.
 */
class TrainHeadingResolverTest {
	private lateinit var resolver: TrainHeadingResolver

	private val east = 0.0
	private val west = PI
	private val northEast = -PI / 4.0 // grid Y grows downward, so NE is negative
	private val north = -PI / 2.0

	@BeforeEach
	fun setUp() {
		resolver = TrainHeadingResolver()
	}

	private fun at(
		x: Float,
		y: Float
	): PointF = PointF(x, y)

	// ========== Authoritative heading pass-through ==========

	@Test
	fun `first authoritative heading is accepted as-is`() {
		val heading = resolver.resolveHeading(1, east, at(5f, 8f))
		assertThat(heading).isEqualTo(east)
	}

	@Test
	fun `authoritative heading is snapped to nearest 45 degree compass direction`() {
		// 50° is closest to 45° (PI/4)
		val heading = resolver.resolveHeading(1, Math.toRadians(50.0), at(5f, 8f))
		assertThat(heading).isEqualTo(PI / 4.0)
	}

	@Test
	fun `45 degree turn is accepted immediately`() {
		resolver.resolveHeading(1, east, at(5f, 8f))
		val heading = resolver.resolveHeading(1, northEast, at(6f, 8f))
		assertThat(heading).isEqualTo(northEast)
	}

	@Test
	fun `90 degree turn is accepted immediately`() {
		resolver.resolveHeading(1, east, at(5f, 8f))
		val heading = resolver.resolveHeading(1, north, at(6f, 8f))
		assertThat(heading).isEqualTo(north)
	}

	// ========== Stale-boundary 180° flip suppression (#719) ==========

	@Test
	fun `180 flip while stationary is suppressed - arrival at destination InOut`() {
		// Train travels East along the last section...
		resolver.resolveHeading(1, east, at(28f, 8f))
		resolver.resolveHeading(1, east, at(29f, 8f))
		// ...front crosses the destination InOut: stale (frontSection, entrySeparator)
		// pair reverses the authoritative heading while the front is frozen at the InOut.
		val atArrival = at(30f, 8f)
		val flipped = resolver.resolveHeading(1, west, atArrival)
		assertThat(flipped).isEqualTo(east)
	}

	@Test
	fun `180 flip stays suppressed across many stationary frames - RED signal wait`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		// Front crossed the semaphore separator; next path not reserved (RED) → stale state
		// persists for the whole wait, front frozen at the semaphore.
		val atSemaphore = at(20f, 8f)
		repeat(50) {
			val heading = resolver.resolveHeading(1, west, atSemaphore)
			assertThat(heading).isEqualTo(east)
		}
	}

	@Test
	fun `sub-epsilon jitter during a suppressed flip does not accept the flip`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.resolveHeading(1, west, at(20f, 8f))
		// Movement below MOVEMENT_EPSILON must not be treated as a genuine reversal.
		val heading = resolver.resolveHeading(1, west, at(20.005f, 8f))
		assertThat(heading).isEqualTo(east)
	}

	@Test
	fun `forward heading after a suppressed flip is accepted - train proceeds after RED clears`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.resolveHeading(1, west, at(20f, 8f)) // suppressed stale flip
		// Path reserved, train proceeds: authoritative heading is forward again.
		val heading = resolver.resolveHeading(1, east, at(21f, 8f))
		assertThat(heading).isEqualTo(east)
	}

	@Test
	fun `new flip after a cleared flip is suppressed again`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.resolveHeading(1, west, at(20f, 8f)) // first stale flip (suppressed)
		resolver.resolveHeading(1, east, at(21f, 8f)) // proceeds, pending flip cleared
		// Second stale boundary (e.g. arrival) — must be suppressed independently.
		val heading = resolver.resolveHeading(1, west, at(30f, 8f))
		assertThat(heading).isEqualTo(east)
	}

	@Test
	fun `diagonal heading flip while stationary is suppressed`() {
		resolver.resolveHeading(1, northEast, at(14f, 10f))
		val flipped = resolver.resolveHeading(1, northEast + PI, at(15f, 9f))
		assertThat(flipped).isEqualTo(northEast)
	}

	// ========== Genuine reversal acceptance (GitHub #62 bidirectional operation) ==========

	@Test
	fun `180 flip is accepted once the train moves - genuine reversal`() {
		resolver.resolveHeading(1, east, at(29f, 8f))
		// Reversal: train stationary at the InOut, flipped heading appears → held back.
		val whileStationary = resolver.resolveHeading(1, west, at(30f, 8f))
		assertThat(whileStationary).isEqualTo(east)
		// Train departs in the opposite direction: flip becomes a genuine reversal.
		val whileMoving = resolver.resolveHeading(1, west, at(29.5f, 8f))
		assertThat(whileMoving).isEqualTo(west)
	}

	@Test
	fun `accepted reversal heading persists on later frames`() {
		resolver.resolveHeading(1, east, at(29f, 8f))
		resolver.resolveHeading(1, west, at(30f, 8f))
		resolver.resolveHeading(1, west, at(29.5f, 8f)) // accepted
		val heading = resolver.resolveHeading(1, west, at(29f, 8f))
		assertThat(heading).isEqualTo(west)
	}

	// ========== Fallback chain (no authoritative heading) ==========

	@Test
	fun `null authoritative heading falls back to movement inference`() {
		resolver.resolveHeading(1, null, at(5f, 8f))
		val heading = resolver.resolveHeading(1, null, at(6f, 8f))
		assertThat(heading).isEqualTo(east)
	}

	@Test
	fun `null authoritative heading with no movement falls back to previous heading`() {
		resolver.resolveHeading(1, east, at(5f, 8f))
		val heading = resolver.resolveHeading(1, null, at(5f, 8f))
		assertThat(heading).isEqualTo(east)
	}

	@Test
	fun `nothing known falls back to default heading`() {
		val heading = resolver.resolveHeading(1, null, at(5f, 8f))
		assertThat(heading).isEqualTo(TrainHeadingResolver.DEFAULT_TRAIN_HEADING)
	}

	@Test
	fun `custom default heading is used when nothing is known`() {
		val custom = TrainHeadingResolver(defaultHeading = west)
		val heading = custom.resolveHeading(1, null, at(5f, 8f))
		assertThat(heading).isEqualTo(west)
	}

	@Test
	fun `movement inference snaps to nearest compass direction`() {
		resolver.resolveHeading(1, null, at(5f, 8f))
		// Mostly-east movement with slight vertical drift snaps to East.
		val heading = resolver.resolveHeading(1, null, at(6f, 8.1f))
		assertThat(heading).isEqualTo(east)
	}

	// ========== Per-train independence and pruning ==========

	@Test
	fun `flip suppression state is tracked per train`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.resolveHeading(2, west, at(25f, 8f))
		// Train 1 hits a stale flip; train 2 keeps its own heading untouched.
		assertThat(resolver.resolveHeading(1, west, at(20f, 8f))).isEqualTo(east)
		assertThat(resolver.resolveHeading(2, west, at(24f, 8f))).isEqualTo(west)
	}

	@Test
	fun `retainTrains drops state of removed trains`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.retainTrains(emptySet())
		// State was pruned: nothing known for train 1 anymore → default heading.
		val heading = resolver.resolveHeading(1, null, at(19f, 8f))
		assertThat(heading).isEqualTo(TrainHeadingResolver.DEFAULT_TRAIN_HEADING)
	}

	@Test
	fun `retainTrains keeps state of active trains`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.resolveHeading(2, west, at(25f, 8f))
		resolver.retainTrains(setOf(2))
		assertThat(resolver.resolveHeading(2, null, at(25f, 8f))).isEqualTo(west)
		assertThat(resolver.resolveHeading(1, null, at(19f, 8f)))
			.isEqualTo(TrainHeadingResolver.DEFAULT_TRAIN_HEADING)
	}

	@Test
	fun `retainTrains clears a pending flip so a reused train number starts fresh`() {
		resolver.resolveHeading(1, east, at(19f, 8f))
		resolver.resolveHeading(1, west, at(20f, 8f)) // pending stale flip
		resolver.retainTrains(emptySet())
		// A brand-new train reusing number 1 travelling West must not inherit suppression.
		val heading = resolver.resolveHeading(1, west, at(30f, 8f))
		assertThat(heading).isEqualTo(west)
	}
}
