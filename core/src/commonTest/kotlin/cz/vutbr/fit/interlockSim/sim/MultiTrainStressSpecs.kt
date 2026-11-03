/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP1 (#1148) / demand 3 (#1179): legal route pairs for the Praha stress scenario.
 */
package cz.vutbr.fit.interlockSim.sim

import kotlin.random.Random

/**
 * Train specifications of the 20-train Praha stress scenario, shared by the heavy
 * `MultiTrainScaleStressTest` (JVM) and the fast [MultiTrainStressSpecsTest] guard
 * (`commonTest`, every target). The Goal 1B exit calibration (Issue #1346,
 * [LowTrafficExitScenario] and the JVM `ExitCalibrationRowsTest`) draws its pairs and its stress
 * configuration from here too.
 *
 * ## Why the pairs are listed instead of rotated over all InOuts (Issue #1179)
 *
 * The scenario used to rotate over the product of the five north entries and the six south
 * exits (`NORTH_ENTRIES[i % 5]`, `SOUTH_EXITS[i % 6]`). 13 of the 20 pairs produced this way have
 * topological paths on `praha-hlavni-nadrazi.xml` but **no switch-legal route**: every path
 * reverses through a switch, so `RouteFinder` — and with it
 * `PathReservationService.reservePath` — refuses them with `NO_ROUTE` on the first attempt.
 *
 * The per-pair decision (railway-civil-engineer, #1179) was **the specs were wrong, not the
 * fixture**: the modelled Praha throats fan each north group into the platforms and out to the
 * matching exit group, and the bypass (Y=20) is a through line, not a platform approach. A real
 * dispatcher would route none of the 13 pairs as a through movement; each would need a shunting
 * reversal. No crossover was added to the fixture.
 *
 * [LEGAL_PAIRS] therefore lists only pairs with a switch-legal route, and
 * [MultiTrainStressSpecsTest] keeps that property pinned: a spec change cannot bring an
 * impossible pair back unnoticed.
 */
internal object MultiTrainStressSpecs {
	/** Seconds between two consecutive train entries. */
	const val HEADWAY_SECONDS: Double = 5.0

	/** Length of every stress train in meters. */
	const val TRAIN_LENGTH: Double = 40.0

	/** Number of trains in the stress scenario. */
	const val TRAINS: Int = 20

	/**
	 * Simulated horizon of the stress scenario in seconds. Reusing a legal pair serializes the trains
	 * that share it, so the scenario needs a long horizon.
	 */
	const val END_TIME: Long = 3600L

	/** Concurrency cap of the stress scenario: every train may be admitted at once. */
	const val MAX_CONCURRENT_TRAINS: Int = TRAINS

	/**
	 * Entry attempts per train before the run records a structured failure. One attempt per two
	 * simulated seconds, so the cap covers the whole [END_TIME] horizon: a train that waits for a
	 * route reused by an earlier train is never given up early.
	 */
	const val MAX_ENTRY_ATTEMPTS: Int = 1800

	/** Shortest gap between two consecutive entries of the shoulder-traffic row (Issue #1346). */
	const val SHOULDER_MIN_GAP_SECONDS: Double = 15.0

	/** Longest gap between two consecutive entries of the shoulder-traffic row (Issue #1346). */
	const val SHOULDER_MAX_GAP_SECONDS: Double = 30.0

	/**
	 * `(entry, exit)` pairs of `praha-hlavni-nadrazi.xml` that have a switch-legal route.
	 *
	 * The first five are the pairwise block-disjoint routes of
	 * `MultiTrainScaleValidationTest.fiveTrainCompleteness`; the last three are the remaining
	 * legal pairs measured in #1179. They are ordered so that consecutive trains take
	 * disjoint routes wherever possible.
	 */
	val LEGAL_PAIRS: List<Pair<String, String>> =
		listOf(
			"N-Lib-1" to "S-Vin-1",
			"N-Lib-2" to "S-Vin-2",
			"N-Vys-1" to "S-Vrs-1",
			"N-Vys-2" to "S-Vrs-2",
			"N-Bypass" to "S-Bypass",
			"N-Lib-2" to "S-Vin-1",
			"N-Vys-2" to "S-Vrs-1",
			"N-Lib-2" to "S-Vrs-3"
		)

	/** The [TRAINS] stress specs, rotating over [LEGAL_PAIRS] with a [HEADWAY_SECONDS] headway. */
	fun twentyTrainSpecs(): List<MultiTrainLoop.TrainSpec> =
		List(TRAINS) { i ->
			val (inName, outName) = LEGAL_PAIRS[i % LEGAL_PAIRS.size]
			MultiTrainLoop.TrainSpec(
				inName = inName,
				outName = outName,
				inTime = i * HEADWAY_SECONDS,
				length = TRAIN_LENGTH
			)
		}

	/**
	 * The [TRAINS] shoulder-traffic specs of the Goal 1B exit calibration (Issue #1346): the pairs of
	 * [twentyTrainSpecs], but each gap drawn uniformly from [SHOULDER_MIN_GAP_SECONDS] to
	 * [SHOULDER_MAX_GAP_SECONDS] with `kotlin.random.Random(seed)` instead of the fixed headway.
	 */
	fun shoulderTrafficSpecs(seed: Int): List<MultiTrainLoop.TrainSpec> {
		val random = Random(seed)
		var inTime = 0.0
		return List(TRAINS) { i ->
			if (i > 0) inTime += random.nextDouble(SHOULDER_MIN_GAP_SECONDS, SHOULDER_MAX_GAP_SECONDS)
			val (inName, outName) = LEGAL_PAIRS[i % LEGAL_PAIRS.size]
			MultiTrainLoop.TrainSpec(inName = inName, outName = outName, inTime = inTime, length = TRAIN_LENGTH)
		}
	}
}
