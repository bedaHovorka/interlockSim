/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: per-train heading sampler behind the heading-flip regression tests
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.gui.animation.TrainHeadingResolver
import cz.vutbr.fit.interlockSim.gui.animation.TrainPositionCalculator
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.Train
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import kotlin.math.abs

/**
 * Per-train heading sampler behind the heading-flip regression tests
 * (`StraightRunHeadingFlipRegressionTest`, `RedSignalWaitHeadingFlipRegressionTest`).
 *
 * Holds all sampling state (previous headings, the recorded flips) so the test class itself
 * stays stateless and the #789 skip contract has one home.
 *
 * A train that cannot be sampled (no section, no resolvable heading) must skip **only
 * itself**: `continue`, never `return`. A `return` inside the loop would abort the whole frame
 * and silently drop every train after the skipped one, which can hide a real heading flip
 * (#789). The sim-level tests pin this through real `MultiTrainLoop` frames; the unit test pins
 * the guard with a hand-built frame, because `vyhybna.xml` serialises trains and never produces
 * a frame that exercises it.
 *
 * The test harness ([runSampled]) takes the frame as a callback so a test can control the
 * exact train list — and its order — of a single frame.
 */
class HeadingFlipSampler(
	private val calculator: TrainPositionCalculator
) {
	private val resolver = TrainHeadingResolver()
	private val prevHeading = mutableMapOf<Int, Double>()
	private val prevResolvedHeading = mutableMapOf<Int, Double>()

	/** RAW heading flips observed, formatted for diagnostics. */
	val flips = mutableListOf<String>()

	/** RESOLVED heading flips observed, formatted for diagnostics. */
	val resolvedFlips = mutableListOf<String>()

	/** Train numbers that produced a RAW sample — a skipped train is absent. */
	val rawSampledTrainNumbers: Set<Int>
		get() = prevHeading.keys

	/** Train numbers that produced a RESOLVED sample. */
	val resolvedSampledTrainNumbers: Set<Int>
		get() = prevResolvedHeading.keys

	/**
	 * Sample exactly [trains] — one frame's train list, snapshotted once by the caller so the
	 * sampler and the frame filter can never disagree about what the frame contained.
	 */
	fun sample(trains: List<Train>) {
		for (train in trains) {
			val trainNumber = train.trainNumber
			val section = train.frontSection ?: continue
			val heading = calculator.calculateTrainHeadingRadians(train, section) ?: continue
			val entry = train.trainEntrySeparator

			// The RESOLVED heading (what the renderer draws) must never flip either — the
			// resolver stays as the second layer of defence (#719, kept by #788).
			sampleResolved(train, section, heading)

			val prev = prevHeading[trainNumber]
			if (prev != null) {
				val delta = normalizeAngleDiff(heading - prev)
				if (abs(delta) > Math.PI / 2.0) {
					val ends = section.ends()
					val end0 = ends.getOrNull(0)
					val end1 = ends.getOrNull(1)
					val entryMatch =
						when {
							entry == null -> "NULL -> fallback DECLARED order"
							sameStatic(entry, end0) -> "matches ends[0] -> DECLARED"
							sameStatic(entry, end1) -> "matches ends[1] -> REVERSED"
							else -> "MATCHES_NEITHER -> fallback DECLARED order"
						}
					val line =
						"FLIP train#$trainNumber ${deg(prev)} -> ${deg(heading)} (delta ${deg(delta)}); " +
							"frontSection ends=[${endLabel(end0)}, ${endLabel(end1)}]; entrySep=${endLabel(entry)} | $entryMatch"
					flips.add(line)
				}
			}
			prevHeading[trainNumber] = heading
		}
	}

	/**
	 * Sample the resolved heading of a **single** train.
	 *
	 * The `?: return` below is deliberately *not* a `continue` (#789): this helper's whole body
	 * handles one train, so the `return` already skips exactly that train and nothing else — the
	 * caller's loop carries on with the next train. A `continue` would not even compile here.
	 */
	private fun sampleResolved(
		train: Train,
		section: TrackSection,
		rawHeading: Double
	) {
		val trainNumber = train.trainNumber
		val location =
			calculator.calculateTrainGridLocation(train, section, train.frontPosition)
				?: return
		val resolved = resolver.resolveHeading(trainNumber, rawHeading, location)
		val prev = prevResolvedHeading[trainNumber]
		if (prev != null) {
			val delta = normalizeAngleDiff(resolved - prev)
			if (abs(delta) > Math.PI / 2.0) {
				resolvedFlips.add(
					"RESOLVED_FLIP train#$trainNumber ${deg(prev)} -> ${deg(resolved)} (delta ${deg(delta)}); " +
						"raw=${deg(rawHeading)}; entrySep=${endLabel(train.trainEntrySeparator)}"
				)
			}
		}
		prevResolvedHeading[trainNumber] = resolved
	}

	private fun endLabel(end: PathSeparator?): String {
		if (end == null) return "null"
		val name = (DynamicWrapperUtils.unwrapToStatic(end) as? NodeCell)?.getName()
		val grid = calculator.getGridPosition(end)
		return "${name ?: "?"}@${grid ?: "?"}"
	}
}
