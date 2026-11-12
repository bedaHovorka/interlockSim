/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.Random
import cz.ksimulantenbande.kdisco.dtMax
import cz.ksimulantenbande.kdisco.dtMin
import cz.ksimulantenbande.kdisco.maxAbsError
import cz.ksimulantenbande.kdisco.maxRelError
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Testing Generator
 *
 * Each train runs from a random InOut to another one. When the network has InOuts on both sides
 * (both orientations), the pair is always one InOut from each side: trains travel through the
 * station, never between two exits of the same side (Cerveny Ujezd has four InOuts, two per side;
 * Goal 1B SP3.1, Issue #1163). With two InOuts, or InOuts of one orientation only, the pair is
 * the first two of the shuffled list as before, so vyhybna output is unchanged.
 *
 * A subclass can narrow the pairs through [isPairAcceptable] (Issue #1376); [selectPair] then
 * moves on to the next candidate pair without drawing another random number.
 *
 * @param seed seed of the kDisco random stream; the default 0 is the historical value
 * @param maxTrains stop generating after this many trains; `null` (default) generates until
 *   stopped; must be positive when given
 */
open class Generator(
	protected val env: SimulationEnvironment,
	protected val shuffleInOuts: Boolean = true,
	seed: Long = 0L,
	private val maxTrains: Int? = null
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * The one global integration step cap, in simulated seconds: kDisco's `dtMax` for every
		 * scenario run. [startAction] is its only production use, and every loop that starts a
		 * generator (`MultiTrainLoop`, `ShuntingLoop`, …) runs with it; there is no per-scenario
		 * override (Goal 1B SP13, Issue #1160; `DtMaxSingleSiteTripwireTest` pins this). The value
		 * and its accuracy bound are recorded in `docs/goal9b-demands/SP13-dtmax.md`.
		 */
		const val DT_MAX: Double = 1e-3
	}

	init {
		// `LoopProcess.actions()` runs `iteration()` before the cap check in `interLoopSleep()`,
		// so an accepted-but-nonpositive limit would still create and place one train
		// (PR #1267 review round, Copilot thread); reject it here instead. null = unlimited.
		require(maxTrains == null || maxTrains > 0) {
			"maxTrains must be positive, got: $maxTrains"
		}
	}

	override suspend fun startAction() {
		dtMin = 1e-6
		// Block-boundary and tail-entry events (Train.kt) and every Engine wait (Issue #1014 for
		// the approach, Issue #760 for the rest) are located by kDisco root-finding
		// (`Process.waitCrossing` / `waitUntilCrossing`), so no Engine wait resolves to a whole
		// step, and Engine runs ahead of the velocity integration (Issue #1126), so each RK stage
		// integrates the velocity with the acceleration of that stage. `dtMax` is the global
		// [DT_MAX] (1 ms; decided in Goal 1B SP13, Issue #1160).
		dtMax = DT_MAX
		maxRelError = 1e-2
		maxAbsError = 1e-2
	}

	protected var random = Random(seed)
		set(value) {
			field = value
			shuffleRandom = value.asKotlinRandom()
		}
	private var shuffleRandom = random.asKotlinRandom()
	val trains = mutableListOf<Train>()
	private var i = 0

	private fun generateRandomTimetable(): Timetable {
		val inOutsList = env.getInOuts().toMutableList()
		if (shuffleInOuts) {
			inOutsList.shuffle(shuffleRandom)
		}
		val timeIn = time() + random.normal(15.0, 5.0)
		val timeOut = timeIn + random.normal(15.0, 5.0)
		val (from, to) = selectPair(inOutsList, ::isPairAcceptable)
		logger.debug {
			"Generating random timetable: from ${from.name} to ${to.name}, " +
				"arrival at $timeIn, departure at $timeOut"
		}

		return Timetable(from, to, Time(timeIn), Time(timeOut), 40.0)
	}

	override suspend fun iteration() {
		val train = Train(env, generateRandomTimetable())
		logger.debug { "Generator: creating and placing train (total trains: ${trains.size + 1})" }
		placeTrain(train)
		trains.add(train)
	}

	/**
	 * Whether a train may be generated from [from] to [to] (Issue #1376). The default accepts every
	 * pair, so the generator picks the same pairs as before the hook existed; a subclass narrows it
	 * (`ShuntingLoop` to the switch-legal pairs of the Praha arms). See [selectPair].
	 */
	protected open fun isPairAcceptable(
		from: DynamicInOut,
		to: DynamicInOut
	): Boolean = true

	/**
	 * @param train
	 */
	protected open fun placeTrain(train: Train) {
		Process.activate(train)
	}

	override suspend fun interLoopSleep() {
		if (maxTrains != null && trains.size >= maxTrains) {
			passivate() // wakes only through terminate()
			return
		}
		hold(random.exp(43.0))
		i++
	}

	override suspend fun byTerminateAction() {
		for (train in trains) {
			while (!train.terminated()) {
				hold(2.0)
			}
		}
	}
}

/**
 * The (origin, destination) pair of a generated train, chosen from the [shuffled] InOuts by a
 * deterministic scan that draws no random number (Issue #1376).
 *
 * When [shuffled] holds InOuts of both orientations, the pair is one InOut of each side; otherwise
 * the destination is a later entry of the list. Origins are tried in list order, and for each
 * origin its partners in list order; the first pair [accept] takes wins. With an accept-all
 * [accept] this is exactly the historical rule: the first InOut and the first later one of the
 * other side, or the first two when there is no other side.
 *
 * @throws IllegalArgumentException when [shuffled] holds fewer than two InOuts
 * @throws IllegalStateException when [accept] takes no pair: a network whose hook rejects every
 *   pair cannot be run, so it fails fast instead of falling back to a rejected pair
 */
internal fun selectPair(
	shuffled: List<DynamicInOut>,
	accept: (DynamicInOut, DynamicInOut) -> Boolean
): Pair<DynamicInOut, DynamicInOut> {
	require(shuffled.size >= 2) { "A timetable needs at least two InOuts, got ${shuffled.size}" }
	val crossSide = shuffled.any { it.getOrientation() != shuffled[0].getOrientation() }
	for ((i, from) in shuffled.withIndex()) {
		val partners =
			if (crossSide) {
				shuffled.filter { it.getOrientation() != from.getOrientation() }
			} else {
				shuffled.drop(i + 1)
			}
		partners.firstOrNull { accept(from, it) }?.let { return from to it }
	}
	error(
		"No InOut pair of this network passes isPairAcceptable: " +
			"${shuffled.size} InOut(s) ${shuffled.map { it.name }}"
	)
}
