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

		return Timetable(inOutsList[0].static, inOutsList[1].static, Time(timeIn), Time(timeOut), 40.0)
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
		// Use fixed 5-second interval instead of random.exp(43.0) to allow timely
		// response to termination signals. The exponential distribution can produce
		// very long sleep periods (up to minutes), preventing the generator from
		// checking its terminate flag when ShuntingLoop ends the simulation.
		hold(5.0)
		i++
	}

	override fun byTerminateAction() {
		// Note: Due to jDisco framework limitations, this method is not reliably called
		// when the generator is terminated while sleeping in hold(). The Process.activate()
		// call in LoopProcess.terminate() doesn't interrupt ongoing hold() operations.
		// Therefore, we cannot wait for trains to complete here. This matches the behavior
		// of the develop branch where simulations with fixed endTime may terminate before
		// all trains complete their journeys.
		logger.debug { "Generator byTerminateAction called with ${trains.size} trains in system" }
	}
}
