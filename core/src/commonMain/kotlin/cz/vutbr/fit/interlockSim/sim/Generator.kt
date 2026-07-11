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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Testing Generator
 */
open class Generator(
	protected val env: SimulationEnvironment,
	protected val shuffleInOuts: Boolean = true
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override suspend fun startAction() {
		dtMin = 1e-6
		// Block-boundary and velocity-target events are now located by kDisco root-finding
		// (`Process.waitCrossing`, see Train.kt) rather than by step granularity, so `dtMax` no
		// longer has to be tiny to keep event overshoot negligible. Raising it to kDisco's natural
		// value lets the adaptive RKF45 error controller pick large steps during cruise, cutting
		// derivative evaluations by orders of magnitude (Issue #750).
		dtMax = 1e-3
		maxRelError = 1e-2
		maxAbsError = 1e-2
	}

	protected var random = Random(0L)
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
		logger.debug {
			"Generating random timetable: from ${inOutsList[0].name} to ${inOutsList[1].name}, " +
				"arrival at $timeIn, departure at $timeOut"
		}

		return Timetable(inOutsList[0], inOutsList[1], Time(timeIn), Time(timeOut), 40.0)
	}

	override suspend fun iteration() {
		val train = Train(env, generateRandomTimetable())
		logger.debug { "Generator: creating and placing train (total trains: ${trains.size + 1})" }
		placeTrain(train)
		trains.add(train)
	}

	/**
	 * @param train
	 */
	protected open fun placeTrain(train: Train) {
		Process.activate(train)
	}

	override suspend fun interLoopSleep() {
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
