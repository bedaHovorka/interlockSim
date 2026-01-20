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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.Random

/**
 * Testing Generator
 */
open class Generator(
	protected val context: SimulationContext,
	protected val shuffleInOuts: Boolean = true
) : LoopProcess() {
	companion object {
		private val logger = KotlinLogging.logger {}

		init {
			dtMin = 1e-6
			dtMax = 1e-3
			maxRelError = 1e-2
			maxAbsError = 1e-2
		}
	}

	protected var random = Random(0)
	val trains = mutableListOf<Train>()
	private var i = 0

	private fun generateRandomTimetable(): Timetable {
		val inOutsList = context.getInOuts().toMutableList()
		if (shuffleInOuts) {
			inOutsList.shuffle(random)
		}
		val timeIn = time() + random.normal(15.0, 5.0)
		val timeOut = timeIn + random.normal(15.0, 5.0)
		logger.debug {
			"Generating random timetable: from ${inOutsList[0].name} to ${inOutsList[1].name}, " +
				"arrival at $timeIn, departure at $timeOut"
		}

		return Timetable(inOutsList[0].staticRef, inOutsList[1].staticRef, Time(timeIn), Time(timeOut), 40.0)
	}

	override fun iteration() {
		val train = Train(context, generateRandomTimetable())
		logger.debug { "Generator: creating and placing train (total trains: ${trains.size + 1})" }
		placeTrain(train)
		trains.add(train)
	}

	/**
	 * @param train
	 */
	protected open fun placeTrain(train: Train) {
		activate(train)
	}

	override fun interLoopSleep() {
		hold(random.exp(43.0))
		i++
	}

	override fun byTerminateAction() {
		for (train in trains) {
			while (!train.terminated()) {
				hold(2.0)
			}
		}
	}
}
