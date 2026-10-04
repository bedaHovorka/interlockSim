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
		// Block-boundary and tail-entry events (Train.kt) and every Engine wait (Issue #1014 for
		// the approach, Issue #760 for the rest) are located by kDisco root-finding
		// (`Process.waitCrossing` / `waitUntilCrossing`), so no Engine wait resolves to a whole step.
		// `dtMax` still stays at 1 ms (the Issue #760 ladder tried 1e-3, 1e-2, 1e-1 and 1.0; its
		// table is in PR #1133). When Engine
		// switches its law at an event, the first accepted step after it still integrates the
		// velocity with the old acceleration: the `acceleration` Variable is reset to its
		// step-start value in every RK stage, and the velocity integration reads it before Engine
		// rewrites it. At a braking onset that step overruns the braking point, and the clearance
		// gate then ends the stand from a residual speed that grows with the step: 0.46 m/s at
		// 1 ms, 0.92 m/s at 10 ms (`Issue1057LateFlipBrakingTest`), against the 0.1 m/s a raise
		// must keep. Raising `dtMax` needs that lag removed first (#1126).
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
