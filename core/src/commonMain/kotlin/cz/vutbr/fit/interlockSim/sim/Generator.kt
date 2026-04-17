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

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import io.github.oshai.kotlinlogging.KotlinLogging
import cz.hovorka.kdisco.Process
import cz.hovorka.kdisco.dtMax
import cz.hovorka.kdisco.dtMin
import cz.hovorka.kdisco.maxAbsError
import cz.hovorka.kdisco.maxRelError
import cz.hovorka.kdisco.Random

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
		dtMax = 1e-3
		maxRelError = 1e-2
		maxAbsError = 1e-2
	}

	protected var random = Random(0L)
	private val shuffleRandom = kotlin.random.Random(0)
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
