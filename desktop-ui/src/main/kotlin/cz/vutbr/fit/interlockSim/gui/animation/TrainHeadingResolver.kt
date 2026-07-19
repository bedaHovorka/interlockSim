/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import cz.vutbr.fit.interlockSim.util.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round

/**
 * Stateful per-train heading resolver for the animated canvas.
 *
 * Resolves the nose direction that the renderer should draw for each train, preferring the
 * authoritative entry → exit heading captured from the simulation ([TrainState.headingRadians])
 * and falling back to frame-to-frame delta inference, the previously rendered heading, and
 * finally a default heading.
 *
 * ## Spurious 180° flip suppression (Issue #719)
 *
 * `Train.Site.actions()` advances `entrySeparator` to the separator the front just crossed.
 * At two boundaries there is no upcoming [cz.vutbr.fit.interlockSim.objects.tracks.TrackSection]
 * to advance `frontSection` to:
 *
 * 1. **Arrival at the destination InOut** — the InOut is not a `TrackSection`, so `frontSection`
 *    keeps reporting the just-traversed last section while `entrySeparator` already points at
 *    its *exit* end (the InOut).
 * 2. **Waiting before a RED signal** — the next path is not reserved yet (ownership conflict),
 *    so there is no upcoming section either; `frontSection` stays on the just-traversed section
 *    with `entrySeparator` at its exit end (the semaphore).
 *
 * In both stale states the authoritative heading calculation treats the exit end as the entry
 * end and reverses the heading by exactly 180°, which made the rendered nose flip backward at
 * arrival and made a train waiting before a RED signal appear *beyond* the semaphore (the body
 * was drawn on the wrong side of the nose).
 *
 * The mid-journey variant of this lag was fixed in the simulation (PR #718) by advancing
 * `frontSection` atomically with `entrySeparator`; at the boundaries above there is nothing to
 * advance to, so the correction lives here on the animated-canvas side (owner decision, #719):
 *
 * - A candidate flip is an authoritative heading that is the exact opposite (±180°) of the
 *   previously rendered heading. Track directions on the grid change in 45° steps, so an
 *   instantaneous 180° change can only be the stale boundary state or a genuine reversal.
 * - While the train front does not move (both stale states freeze the front at the crossed
 *   separator), the flip is suppressed and the previous heading is kept.
 * - A genuine reversal ([cz.vutbr.fit.interlockSim.sim.Train.reverseDirection], GitHub #62)
 *   also starts at velocity 0, but the train then *moves* with the flipped heading persisting.
 *   Once the front moves more than [MOVEMENT_EPSILON] grid cells away from where the flip was
 *   first observed, the flip is accepted as a real direction change.
 *
 * ## Thread safety
 *
 * All calls must happen on the Swing EDT (single-threaded rendering), matching the renderer
 * that owns this resolver. No synchronization is performed.
 *
 * @property defaultHeading Heading used when nothing better is known (radians; 0 = East)
 *
 * @see cz.vutbr.fit.interlockSim.gui.gridcanvas.AnimatedSimulationCellRenderer
 * @see TrainPositionCalculator.calculateTrainHeadingRadians
 * @since Issue #719 (arrival / RED-signal heading flip)
 */
class TrainHeadingResolver(
	private val defaultHeading: Double = DEFAULT_TRAIN_HEADING
) {
	private val previousLocations = mutableMapOf<Int, PointF>()
	private val previousHeadings = mutableMapOf<Int, Double>()

	/** Location at which a candidate 180° flip was first observed, per train. */
	private val pendingFlipLocations = mutableMapOf<Int, PointF>()

	/**
	 * Resolve the heading to render for a train this frame.
	 *
	 * Resolution order:
	 * 1. Authoritative heading (snapped to the nearest 45° compass direction), with spurious
	 *    180° flips suppressed while the train front is stationary (see class KDoc)
	 * 2. Heading inferred from the frame-to-frame position delta
	 * 3. Previously rendered heading
	 * 4. [defaultHeading]
	 *
	 * @param trainNumber Unique train identifier ([TrainState.trainNumber])
	 * @param authoritativeHeadingRadians Heading captured from the simulation, or null
	 * @param currentLocation Current front grid location of the train
	 * @return Heading in radians to render (never null)
	 */
	fun resolveHeading(
		trainNumber: Int,
		authoritativeHeadingRadians: Double?,
		currentLocation: PointF
	): Double {
		val authoritative = authoritativeHeadingRadians?.let { snapHeadingToNearestCompassDirection(it) }
		val previousLocation = previousLocations[trainNumber]
		val previousHeading = previousHeadings[trainNumber]

		val resolved =
			if (authoritative != null) {
				resolveAuthoritativeHeading(trainNumber, authoritative, previousHeading, currentLocation)
			} else {
				previousLocation?.let { inferHeading(it, currentLocation) }
					?: previousHeading
					?: defaultHeading
			}

		previousLocations[trainNumber] = currentLocation
		previousHeadings[trainNumber] = resolved
		return resolved
	}

	/**
	 * Drop per-train state for trains that are no longer active.
	 *
	 * Should be called once per frame with the set of currently rendered train numbers so
	 * that removed trains (arrived / despawned) do not leak memory or stale headings into
	 * a later train that happens to reuse the number.
	 *
	 * @param activeTrainNumbers Train numbers present in the current animation state
	 */
	fun retainTrains(activeTrainNumbers: Set<Int>) {
		previousLocations.keys.retainAll(activeTrainNumbers)
		previousHeadings.keys.retainAll(activeTrainNumbers)
		pendingFlipLocations.keys.retainAll(activeTrainNumbers)
	}

	/**
	 * Apply flip suppression to an authoritative heading.
	 *
	 * A heading exactly opposite (±180°) to the previously rendered one is held back until
	 * the train front actually moves away from where the flip was first observed. Stale
	 * boundary states (arrival at the destination InOut, waiting before a RED signal) freeze
	 * the front, so their flip never gets accepted; a genuine reversal starts moving and its
	 * flip is accepted as soon as the front leaves the flip origin.
	 */
	private fun resolveAuthoritativeHeading(
		trainNumber: Int,
		authoritative: Double,
		previousHeading: Double?,
		currentLocation: PointF
	): Double {
		if (previousHeading == null || !isOppositeHeading(authoritative, previousHeading)) {
			// Normal case: accept the authoritative heading and clear any pending flip.
			pendingFlipLocations.remove(trainNumber)
			return authoritative
		}

		val flipOrigin = pendingFlipLocations[trainNumber]
		return when {
			flipOrigin == null -> {
				// First frame of a candidate flip: hold the previous heading and remember
				// where the flip appeared.
				pendingFlipLocations[trainNumber] = currentLocation
				previousHeading
			}
			currentLocation.distanceTo(flipOrigin) > MOVEMENT_EPSILON -> {
				// The front moved while the flipped heading persisted: genuine reversal.
				pendingFlipLocations.remove(trainNumber)
				authoritative
			}
			else -> previousHeading
		}
	}

	private fun inferHeading(
		previousLocation: PointF,
		currentLocation: PointF
	): Double? {
		val dx = (currentLocation.x - previousLocation.x).toDouble()
		val dy = (currentLocation.y - previousLocation.y).toDouble()
		if (abs(dx) < HEADING_EPSILON && abs(dy) < HEADING_EPSILON) {
			return null
		}

		return snapHeadingToNearestCompassDirection(atan2(dy, dx))
	}

	private fun snapHeadingToNearestCompassDirection(angle: Double): Double = round(angle / SEGMENT_ANGLE_STEP) * SEGMENT_ANGLE_STEP

	private fun isOppositeHeading(
		a: Double,
		b: Double
	): Boolean = abs(abs(normalizeAngleDiff(a - b)) - PI) < OPPOSITE_HEADING_TOLERANCE

	private fun normalizeAngleDiff(diff: Double): Double {
		var normalized = diff
		while (normalized > PI) {
			normalized -= 2.0 * PI
		}
		while (normalized < -PI) {
			normalized += 2.0 * PI
		}
		return normalized
	}

	companion object {
		/** Minimum position delta (grid cells) treated as movement when inferring heading. */
		const val HEADING_EPSILON = 0.001

		/** Heading used when nothing better is known (radians; 0 = East). */
		const val DEFAULT_TRAIN_HEADING = 0.0

		/** Track directions on the grid are multiples of 45°. */
		const val SEGMENT_ANGLE_STEP = PI / 4.0

		/**
		 * Distance (grid cells) the front must move away from the flip origin before a 180°
		 * heading flip is accepted as a genuine reversal. Stale boundary states keep the front
		 * frozen at the crossed separator, so they never exceed this threshold.
		 */
		const val MOVEMENT_EPSILON = 0.01f

		/** Tolerance (radians) when testing whether two snapped headings are exact opposites. */
		const val OPPOSITE_HEADING_TOLERANCE = 1e-6
	}
}
