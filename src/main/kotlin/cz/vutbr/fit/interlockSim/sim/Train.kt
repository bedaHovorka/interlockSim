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

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.Condition
import jDisco.Continuous
import jDisco.Process
import jDisco.Reporter
import jDisco.Variable

/**
 * Train Process
 *
 */
class Train :
	Process,
	TrackOccupant {
	companion object {
		private val logger = KotlinLogging.logger {}
		private var count: Int = 0

		/**
		 * Maximum train acceleration in m/s²
		 */
		private const val MAXIMAL_ACCELERATION = 4

		/**
		 * Minimum train deceleration in m/s² (negative value for braking)
		 */
		private const val MINIMAL_DECELERATION = -3
	}

	private val r: Reporter =
		object : Reporter() { // nesmi byt static!!!
			private var started: Boolean = false

			override fun actions() {
				if (!started || !env.isReporting(ReportType.TRAIN_CONTINUOUS)) return // opti-hack
				val builder = StringBuilder()
				builder.append(getAcceleration()).append(' ')
				builder.append(getVelocity()).append(' ')
				builder.append(front.getTotalDistance()).append(' ')
				// builder.append(tail.getTotalDistance()).append(' ')
				builder.append(front.getFrontSection()).append(' ')
				builder.append(tail.getTailSection()).append(' ')
				val distanceToSemaphore: Double = distanceToSemaphore()
				builder.append(if (distanceToSemaphore > 0) distanceToSemaphore else 0)
				env.report(builder, this@Train, ReportType.TRAIN_CONTINUOUS)
			}

			override fun start(): Reporter {
				started = true
				return super.start()
			}

			override fun stop() {
				started = false
				super.stop()
			}
		}.setFrequency(1.0)

	// GitHub #62: Support bidirectional train operation (reverse direction)
	// Allow train engineer to move to opposite end and drive in reverse direction.
	// This is a simulation simplification of locomotive coupling/uncoupling operations.
	// Implementation: Either swap start/end positions OR cancel/restore events with train stationary.

	private abstract inner class Site : Process() { // lepsi nazev?
		private val position: Variable = Variable(0.0)
		private val pv: SimpleIntegration = SimpleIntegration(position, velocity)
		private var totalLenghtOfPreviousBlocks: Double = 0.0
		private var next: TrackSection? = null
		private var current: TrackSection? = null
		private var onNext: Boolean = false

		val terminated: Condition = Condition { terminated() }

		final override fun actions() {
			var where: PathSeparator = timetable.getIn()
			requireSimulationNotNull(where) { "PathSeparator from timetable.getIn() must not be null" }
			// out se muze rovnat in => bude vyreseno "prepojenim lokomotivy"

			while (true) {
				next = env.getNextTrackSection(where, current)
				if (next == null) {
					if (where is DynamicInOut) break
					// Train should wait for dispatcher to reserve next path
					// Do NOT stop the entire simulation!
					passivate()
					continue // Restart loop after passivation to re-check for next track section
				}
				val nextLength: Double = next!!.length()
				separatorAction(where, current, next)

				onNext = true
				requireSimulation(position.isActive && pv.isActive) {
					"Position and velocity integration must be active"
				}
				waitUntil {
					// dtmin - horni odhad zmeny pri poslednim kroku numericke metody behem dobrzdovani k uzlu
					position.state + dtMin >= nextLength
				}

				position.state -= nextLength
				totalLenghtOfPreviousBlocks += nextLength
				val staticWhere = next!!.getSecondEnd(where)
				requireSimulationNotNull(staticWhere) { "PathSeparator from getSecondEnd() must not be null" }
				where = env.toDynamic(staticWhere)

				current = next
				onNext = false
			}

			stop()
			separatorAction(where, current, null)
		}

		/**
		 * Action at aPath separator
		 * @param where
		 * @param current
		 * @param next
		 */
		abstract fun separatorAction(
			where: PathSeparator,
			current: TrackSection?,
			next: TrackSection?
		)

		override fun start(): Site {
			position.start()
			pv.start()
			return this
		}

		override fun stop() {
			position.stop()
			pv.stop()
		}

		/**
		 * @return distance
		 */
		fun distanceToPathSeparator(): Double = if (next == null) 0.0 else next!!.length() - position.state

		/**
		 * @return getter
		 */
		fun getPosition(): Double = position.state

		/**
		 * @return "to co cast vlaku urazila uvnitr modelu"
		 */
		fun getTotalDistance(): Double = totalLenghtOfPreviousBlocks + position.state

		protected fun getSection(): TrackSection? = if (onNext) next else current

		internal fun getFrontSection(): TrackSection? = getSection()

		internal fun getTailSection(): TrackSection? = getSection()
	}

	private inner class Front : Site() {
		private fun semaphoreAction(
			semaphore: DynamicRailSemaphore,
			separator: PathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			// isSeparatorInDirection accepts nullable Track parameters
			requireSimulation(env.isSeparatorInDirection(separator as OrientedPathSeparator, next, current)) {
				"Separator must be in direction, semaphore: $semaphore"
			}
			requireSimulationNotNull(semaphore.signal) { "Semaphore signal must not be null" }
			logger.info {
				"${jDisco.Process.time()} SENSOR: Train $number detected at semaphore " +
					"${semaphore.name}, " +
					"signal=${semaphore.signal}, velocity=${getVelocity()} m/s"
			}
			val path: Path? = env.pathToNextSemaphore(separator, next!!)

			// GOAL 15: Station stops for tutorial scenarios - see LONG_TERM_GOALS.md

			// CRITICAL FIX (Issue #282): Handle null path when navigation is blocked
			// If path is null, it means getNextTrackSection() blocked navigation to unreserved blocks.
			// Treat this as STOP signal: halt the train and wait for a valid path.
			if (semaphore.signal == Signal.STOP || path == null) {
				requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative when approaching semaphore" }
				if (path == null) {
					logger.info {
						"Train $number cannot build path from ${semaphore.name} - " +
							"next block not properly reserved, treating as STOP signal"
					}
					env.report("STOP (path blocked)", this@Train, ReportType.TRAIN_EVENTS)
				} else {
					logger.debug { "Train $number approaching semaphore with STOP signal, halting" }
					env.report(semaphore.signal.toString(), this@Train, ReportType.TRAIN_EVENTS)
				}
				fireStop()

				// freePath(separator, next); //vlak si sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
				waitUntil(allowingSignal(semaphore))
				logger.debug { "Train $number received allowing signal from semaphore, resuming movement" }

				// Try to build path again - it might still be blocked
				val newPath = env.pathToNextSemaphore(separator, next!!)
				if (newPath != null) {
					env.report("OK " + semaphore.signal, this@Train, ReportType.TRAIN_EVENTS)
					fireStart(semaphore, newPath)
				} else {
					// Path still blocked even with allowing signal - stop simulation to investigate
					logger.error {
						"Train $number: semaphore signal is ALLOWING but path is still blocked - " +
							"this indicates a deadlock or interlocking error"
					}
					env.stop()
				}
			} else if (semaphore.signal.isAllowing() && velocity.state <= maxAbsError) {
				logger.debug { "Train $number starting movement with allowing signal" }
				fireStart(semaphore, path)
			} else {
				logger.debug { "Train $number accelerating toward next semaphore" }
				accelerateToSignal(semaphore, path)
			}
			hold(1.0)
			semaphore.signal = Signal.STOP
		}

		// pro ucely ladeni - moznost ze si vlak sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
// 		private Unit freePath(final PathSeparator separator, final TrackSection next) {
// 			if (separator instanceof InOut) return;
// 			try {
// 				env.pathToNextSemaphore(separator, next).setUpPath(separator);
// 			} catch (TrackOperationException e1) {
// 				env.errorStop(e1);
// 				e1.printStackTrace();
// 			}
// 			Process.activate(Process() {
//
// 				override //				protected Unit actions() {
// 					waitUntil(Condition() {
// 						Path aPath;
//
// 						Boolean test() {
// 							aPath = env.pathToNextSemaphore(separator, next);
// 							try {
// 								final Boolean b = aPath != null && aPath.isFreeFrom(separator);
// 								if (b == true) aPath.setUpPath(separator);
// 								return b;
// 							} catch (TrackOperationException e) {
// 								env.errorStop(e);
// 								return false;
// 							}
// 						}
//
// 					});
//
// 				}
//
// 			});
// 		}

		private fun allowingSignal(semaphore: DynamicRailSemaphore): Condition =
			Condition {
				val allowing: Boolean = semaphore.signal.isAllowing()
				allowing
			}

		private fun fireStop() {
			requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative when stopping" }
			front.stop()
			tail.stop()
			this@Train.stop()
			velocity.state = 0.0
			r.stop()
		}

		private fun fireStart(
			semaphore: DynamicRailSemaphore,
			path: Path?
		) {
			accelerateToSignal(semaphore, path)
			this@Train.start()
			front.start()
			tail.start()
			r.start()
		}

		@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
		private fun accelerateToSignal(
			semaphore: DynamicRailSemaphore,
			path: Path?
		) {
			requireSimulationNotNull(path) { "Path must not be null in accelerate method" }
			val thisSignal: Signal = semaphore.signal
			requireSimulation(thisSignal.isAllowing()) { "Signal must be allowing: $thisSignal" }
			@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			val lastSeparator = path!!.getLast()
			val nextSemaphore: DynamicRailSemaphore =
				when (lastSeparator) {
					is DynamicRailSemaphore -> lastSeparator
					is DynamicInOut -> lastSeparator.outSemaphore
					else -> throw IllegalStateException("Last path separator must be DynamicRailSemaphore or DynamicInOut")
				}
			val nextSignal: Signal = nextSemaphore.signal
			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			pathToSemaphore = path

			@Suppress(
				"RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS",
				"NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS"
			)
			val min: Double =
				Math.min(
					path.maxSpeed(path.getFirst()),
					thisSignal.allowedSpeed()
				)
			if (nextSignal.isAllowing()) {
				motor.accelerateTo(Math.min(nextSignal.allowedSpeed(), min))
			} else {
				motor.onWarning(min)
			}
		}

		override fun separatorAction(
			where: PathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			logger.debug {
				"${jDisco.Process.time()} POSITION: Train $number front at separator $where, " +
					"entering block $next, leaving block $current"
			}

			if (where is DynamicRailSemaphore &&
				next != null &&
				env.isSeparatorInDirection(where, next, current)
			) {
				val semaphore: DynamicRailSemaphore = where
				semaphoreAction(semaphore, semaphore, current, next)
			} else if (where == timetable.getIn() && next != null) {
				requireSimulationNotNull(getAcceleration()) { "Acceleration must not be null at timetable entry" }
				semaphoreAction((where as DynamicInOut).inSemaphore, where, current, next)
			} else {
				@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				pathToSemaphore?.removeFirst()
				@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				pathToSemaphore?.removeFirst()
			}
			requireSimulation(pathToSemaphore?.getFirst() == where) {
				"Path to semaphore first element must match current position: ${pathToSemaphore ?: "null"}"
			}
			if (next != null) next.enter(this@Train)
		}
	}

	private inner class Tail : Site() {
		private var fromHome: Boolean = false

		override fun separatorAction(
			where: PathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			logger.debug {
				"${jDisco.Process.time()} POSITION: Train $number tail at separator $where, clearing block $current"
			}
			if (where == timetable.getIn()) {
				fromHome = true
				start()
			}

			if (current != null) {
				current.leave(this@Train)
			}
			if (next == null &&
				where != timetable.getOut()
			) {
				env.report("ends in wrong out", this@Train, ReportType.TRAIN_EVENTS)
			}
		}

		override fun start(): Site = if (fromHome) super.start() else this
	}

	private inner class LengthChecker : ContinuousInvariantChecker() {
		override fun check(): Boolean =
			Math.abs(front.getTotalDistance() - tail.getTotalDistance() - getLength()) <= maxAbsError

		override fun report(reportObj: StringBuilder): StringBuilder {
			requireSimulationNotNull(reportObj) { "Report object must not be null" }
			reportObj.append(front.getTotalDistance()).append(' ').append(tail.getTotalDistance())
			return reportObj.append(' ').append(getLength())
		}
	}

	private enum class AccelerationStopTest(
		private val decelarate: Boolean
	) {
		/**
		 *
		 */
		ACCELERATION_ENDED(false),

		/**
		 *
		 */
		TO_HALF_SPEED(false) {
			override fun condition(
				targetSpeed: Double,
				velocity: Double
			): Boolean = targetSpeed <= 2 * velocity
		},

		/**
		 *
		 */
		DECELERATION_ENDED(true);

		fun isDecelarate(): Boolean = decelarate

		open fun condition(
			targetSpeed: Double,
			velocity: Double
		): Boolean = if (isDecelarate()) targetSpeed >= velocity else targetSpeed <= velocity
	}

	private inner class Motor : LoopProcess() {
		private var currentCondition: AccelerationStopCondition? = null
		private var targetSpeed: Double = 0.0
		private var accelerate: Boolean = false

		private inner class AccelerationStopCondition(
			private val stopTest: AccelerationStopTest
		) : Condition {
			override fun test(): Boolean = !accelerate || stopTest.condition(targetSpeed, getVelocity())

			fun getStopTest(): AccelerationStopTest = stopTest
		}

		override fun iteration() {
			requireSimulationNotNull(currentCondition) { "Current condition must not be null during iteration" }
			accelerate = true
			logger.trace {
				"Train $number motor iteration: target speed $targetSpeed, " +
					"current velocity ${getVelocity()}"
			}
			start()
			waitUntil(currentCondition)

			if (accelerate && currentCondition!!.getStopTest() == AccelerationStopTest.TO_HALF_SPEED) {
				targetSpeed = 0.0
				logger.trace { "Train $number motor: deceleration phase to half speed, target $targetSpeed" }
				waitUntil(AccelerationStopCondition(AccelerationStopTest.DECELERATION_ENDED))
			}

			accelerate = false
			stop()
			acceleration.state = 0.0
		}

		private fun privateAccelerateTo(
			speed: Double,
			test: AccelerationStopTest
		) {
			requireSimulation(speed >= 0) { "Speed must be non-negative: $speed" }
			targetSpeed = speed
			currentCondition = AccelerationStopCondition(test)
			cancelAccelerating()
			activate(this)
		}

		/**
		 * change speed
		 * @param speed
		 */
		fun accelerateTo(speed: Double) {
			logger.debug { "Train $number motor: accelerate to speed $speed, current velocity ${getVelocity()}" }
			env.report("in on warning", this@Train, ReportType._DEBUG)
			privateAccelerateTo(
				speed,
				if (speed >
					getVelocity()
				) {
					AccelerationStopTest.ACCELERATION_ENDED
				} else {
					AccelerationStopTest.DECELERATION_ENDED
				}
			)
		}

		/**
		 * special behaviour
		 * @param normalSpeed
		 */
		fun onWarning(normalSpeed: Double) {
			logger.debug {
				"Train $number motor: warning mode, target speed $normalSpeed, current velocity ${getVelocity()}"
			}
			env.report("in on warning $normalSpeed", this@Train, ReportType._DEBUG)

			requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative in onWarning" }
			privateAccelerateTo(normalSpeed, AccelerationStopTest.TO_HALF_SPEED)
		}

		/**
		 *
		 */
		fun cancelAccelerating() {
			if (accelerate) {
				accelerate = false
				activate(this)
			}
		}

		override fun start(): Continuous = if (accelerate) super.start() else this

		override fun derivatives() {
			// minmax zpomaleni
			val s: Double = distanceToSemaphore()
			if (s <= 0) {
				accelerate = false
				return
			}
			if (velocity.state <= 0) velocity.state = 0.0

			val a: Double = ((targetSpeed - velocity.state) * (targetSpeed + velocity.state)) / (2 * s)
			acceleration.state =
				if (currentCondition!!.getStopTest().isDecelarate()) {
					Math.max(a, MINIMAL_DECELERATION.toDouble())
				} else {
					Math.min(a, MAXIMAL_ACCELERATION.toDouble())
				}
		}
	}

	private val acceleration: Variable = Variable(0.0)
	private val velocity: Variable = Variable(0.0)
	private val va: SimpleIntegration = SimpleIntegration(velocity, acceleration)
	private val front: Front = Front()
	private val tail: Tail = Tail()
	private val motor: Motor = Motor()
	private val timetable: Timetable
	private val env: SimulationEnvironment
	private var pathToSemaphore: Path? = null
	private val trainPrefix: String

	private val number: Int

	private var length: Double
	private var ap: ContinuousInvariantChecker = LengthChecker()

	/**
	 * Create train
	 * @param env The simulation environment
	 * @param timetable Train timetable
	 */
	constructor(env: SimulationEnvironment?, timetable: Timetable?) {
		this.env = requireSimulationNotNull(env) { "env must not be null" }
		val validatedTimetable = requireSimulationNotNull(timetable) { "timetable must not be null" }
		this.timetable = validatedTimetable
		this.length = validatedTimetable.getLength()
		number = ++count
		trainPrefix = "Train #$number"
		val inName = validatedTimetable.getIn().name
		val outName = validatedTimetable.getOut().name
		logger.debug { "Train $number created: from $inName to $outName, length $length" }
	}

	override fun distanceToSemaphore(): Double =
		if (pathToSemaphore == null) 0.0 else pathToSemaphore!!.length() - front.getPosition()

	override fun actions() { // spusten odsouhlasenim
		// zarazeni do fronty vstupniho bodu (simulace systemu sousedni stanice)
		val inout = timetable.getIn()
		val worker: InOutWorker = env.getWorkerFor(inout)
		logger.debug { "Train $number approved for movement from ${inout.name} to ${timetable.getOut().name}" }
		worker.enterTrain(this)
		env.report("approved ${inout.name}->${timetable.getOut().name}", this, ReportType.TRAIN_EVENTS)

		activate(front)

		waitUntil { front.getTotalDistance() >= getLength() }
		activate(tail)

		out()
		activate(worker.getQueqe().first() as? Train)
		ap.start()

		waitUntil(front.terminated)
		ap.stop()
		// predkem v systemu sousedni stanice

		waitUntil(tail.terminated)
		r.setFrequency(Double.POSITIVE_INFINITY)
		r.stop()
		stop()
		motor.terminate()
		// ukoncovaci..
		logger.debug { "Train $number completed journey: distance traveled ${front.getTotalDistance()}" }
		env.report("ends", this, ReportType.TRAIN_EVENTS)
	}

	/**
	 * @return current acceleration of train
	 */
	fun getAcceleration(): Double = acceleration.state

	/**
	 * @return current speed of train
	 */
	fun getVelocity(): Double = velocity.state

	/**
	 * @return length of train
	 */
	fun getLength(): Double {
		return length // pozdeji soucet vagonu
	}

	@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
	override fun nextSemaphore(): OrientedPathSeparator? = pathToSemaphore?.getLast()

	override fun start(): Train {
		acceleration.start()
		velocity.start()
		va.start()
		return this
	}

	override fun stop() {
		acceleration.stop()
		velocity.stop()
		va.stop()
		velocity.state = 0.0
		velocity.rate = 0.0
		acceleration.rate = 0.0
		acceleration.state = 0.0
	}

	override fun toString(): String = trainPrefix
}
