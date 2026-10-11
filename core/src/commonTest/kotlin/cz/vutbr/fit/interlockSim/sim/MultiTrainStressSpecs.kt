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

/**
 * Train specifications of the 20-train Praha stress scenario, shared by the heavy
 * `MultiTrainScaleStressTest` (JVM) and the fast [MultiTrainStressSpecsTest] guard
 * (`commonTest`, every target).
 *
 * ## Why the pairs are listed instead of rotated over all InOuts (Issue #1179)
 *
 * The scenario used to rotate over the product of the five north entries and the six south
 * exits (`NORTH_ENTRIES[i % 5]`, `SOUTH_EXITS[i % 6]`). 13 of the 20 pairs that produced have
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
}
