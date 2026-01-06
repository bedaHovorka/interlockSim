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
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore.Signal
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import jDisco.Condition
import jDisco.Continuous
import jDisco.Process
import jDisco.Reporter
import jDisco.Variable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Train Process
 *
 */
class Train :
	Process,
	TrackOccupant {
	companion object {
		private val logger: Logger = LoggerFactory.getLogger(Train::class.java)
		private var count: Int = 0
	}

	// Motor acceleration/deceleration constants (accessible to inner classes)
	private val MAXIMAL_ACCELERATION: Int = 4
	private val MINIMAL_DECELERATION: Int = -3

	private val r: Reporter =
		object : Reporter() { // nesmi byt static!!!
			private var started: Boolean = false

			override fun actions() {
				if (!started || !context.isReporting(ReportType.TRAIN_CONTINUOUS)) return // opti-hack
				val builder = StringBuilder()
				builder.append(getAccelaration()).append(' ')
				builder.append(getVelocity()).append(' ')
				builder.append(front.getTotalDistance()).append(' ')
				// builder.append(tail.getTotalDistance()).append(' ')
				builder.append(front.getFrontSection()).append(' ')
				builder.append(tail.getTailSection()).append(' ')
				val distanceToSemaphore: Double = distanceToSemaphore()
				builder.append(if (distanceToSemaphore > 0) distanceToSemaphore else 0)
				context.report(builder, this@Train, ReportType.TRAIN_CONTINUOUS)
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

	// EXTENSION musi bud aspon umet prohodit zacatek a konec
	// nebo je udalosti oba dva zrusit a dalsi udalosti obnovit, s tim ze vlak stoji na miste
	// tj. "odpojeni" a "pripojeni" lokomotivy

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
			assert(where != null)
			// out se muze rovnat in => bude vyreseno "prepojenim lokomotivy"

			while (true) {
				next = context.getNextTrackSection(where, current)
				if (next == null) {
					if (where is InOut) break
					context.stop()
					passivate()
					continue // Restart loop after passivation to re-check for next track section
				}
				val nextLength: Double = next!!.length()
				separatorAction(where, current, next)

				onNext = true
				assert(position.isActive && pv.isActive)
				waitUntil {
					// dtmin - horni odhad zmeny pri poslednim kroku numericke metody behem dobrzdovani k uzlu
					position.state + dtMin >= nextLength
				}

				position.state -= nextLength
				totalLenghtOfPreviousBlocks += nextLength
				where = next!!.getSecondEnd(where)
				assert(where != null)
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
			semaphore: RailSemaphore,
			separator: PathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			// isSeparatorInDirection accepts nullable Track parameters
			assert(context.isSeparatorInDirection(separator as OrientedPathSeparator, next, current)) { semaphore }
			assert(semaphore.getSignal() != null)
			if (logger.isInfoEnabled) {
				logger.info(
					"{} SENSOR: Train {} detected at semaphore {}, signal={}, velocity={} m/s",
					jDisco.Process.time(),
					number,
					if (semaphore.getName() != null) semaphore.getName() else semaphore.hashCode(),
					semaphore.getSignal(),
					getVelocity()
				)
			}
			val path: Path? = context.pathToNextSemaphore(separator, next!!)

			// EXTENSION stanice

			if (semaphore.getSignal() == RailSemaphore.Signal.STOP) {
				assert(getVelocity() >= 0)
				logger.debug("Train {} approaching semaphore with STOP signal, halting", number)
				fireStop()
				context.report(semaphore.getSignal().toString(), this@Train, ReportType.TRAIN_EVENTS)

				// freePath(separator, next); //vlak si sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
				waitUntil(allowingSignal(semaphore))
				logger.debug("Train {} received allowing signal from semaphore, resuming movement", number)
				context.report("OK " + semaphore.getSignal(), this@Train, ReportType.TRAIN_EVENTS)
				fireStart(semaphore, context.pathToNextSemaphore(separator, next!!)) // znovu najit
			} else if (semaphore.getSignal().isAllowing() && velocity.state <= maxAbsError) {
				logger.debug("Train {} starting movement with allowing signal", number)
				fireStart(semaphore, path)
			} else {
				logger.debug("Train {} accelerating toward next semaphore", number)
				accelerateToSignal(semaphore, path)
			}
			hold(1.0)
			semaphore.setSignal(RailSemaphore.Signal.STOP)
		}

		// pro ucely ladeni - moznost ze si vlak sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
// 		private Unit freePath(final PathSeparator separator, final TrackSection next) {
// 			if (separator instanceof InOut) return;
// 			try {
// 				context.pathToNextSemaphore(separator, next).setUpPath(separator);
// 			} catch (TrackOperationException e1) {
// 				context.errorStop(e1);
// 				e1.printStackTrace();
// 			}
// 			Process.activate(Process() {
//
// 				override //				protected Unit actions() {
// 					waitUntil(Condition() {
// 						Path aPath;
//
// 						Boolean test() {
// 							aPath = context.pathToNextSemaphore(separator, next);
// 							try {
// 								final Boolean b = aPath != null && aPath.isFreeFrom(separator);
// 								if (b == true) aPath.setUpPath(separator);
// 								return b;
// 							} catch (TrackOperationException e) {
// 								context.errorStop(e);
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

		private fun allowingSignal(semaphore: RailSemaphore): Condition =
			Condition {
				val allowing: Boolean = semaphore.getSignal().isAllowing()
				allowing
			}

		private fun fireStop() {
			assert(getVelocity() >= 0)
			front.stop()
			tail.stop()
			this@Train.stop()
			velocity.state = 0.0
			r.stop()
		}

		private fun fireStart(
			semaphore: RailSemaphore,
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
			semaphore: RailSemaphore,
			path: Path?
		) {
			assert(path != null)
			val thisSignal: Signal = semaphore.getSignal()
			assert(thisSignal.isAllowing()) { thisSignal }
			@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			val nextSemaphore: RailSemaphore = path!!.getLastPathSemaphore()
			val nextSignal: Signal = nextSemaphore.getSignal()
			@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
			pathToSemaphore = path

			@Suppress(
				"RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS",
				"NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS"
			)
			val min: Double = Math.min(
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
			if (logger.isDebugEnabled) {
				logger.debug(
					"{} POSITION: Train {} front at separator {}, entering block {}, leaving block {}",
					jDisco.Process.time(),
					number,
					where,
					next,
					current
				)
			}

			if (where is RailSemaphore &&
				next != null &&
				context.isSeparatorInDirection(where, next, current)
			) {
				val semaphore: RailSemaphore = where
				semaphoreAction(semaphore, semaphore, current, next)
			} else if (where == timetable.getIn() && next != null) {
				assert(getAccelaration() != null)
				semaphoreAction((where as InOut).getInSemaphore(), where, current, next)
			} else {
				@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				pathToSemaphore?.removeFirst()
				@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
				pathToSemaphore?.removeFirst()
			}
			assert(pathToSemaphore?.getFirst() == where) { pathToSemaphore ?: "null" }
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
			if (logger.isDebugEnabled) {
				logger.debug(
					"{} POSITION: Train {} tail at separator {}, clearing block {}",
					jDisco.Process.time(),
					number,
					where,
					current
				)
			}
			if (where == timetable.getIn()) {
				fromHome = true
				start()
			}

			if (current != null) current.leave(this@Train)
			if (next == null &&
				where != timetable.getOut()
			) {
				context.report("ends in wrong out", this@Train, ReportType.TRAIN_EVENTS)
			}
		}

		override fun start(): Site = if (fromHome) super.start() else this
	}

	private inner class LenghtChecker : AssertionContinuous() {
		override fun check(): Boolean =
			Math.abs(front.getTotalDistance() - tail.getTotalDistance() - getLength()) <= maxAbsError

		override fun report(reportObj: StringBuilder): StringBuilder {
			assert(reportObj != null)
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
			assert(currentCondition != null)
			accelerate = true
			if (logger.isTraceEnabled) {
				logger.trace("Train {} motor iteration: target speed {}, current velocity {}", number, targetSpeed, getVelocity())
			}
			start()
			waitUntil(currentCondition)

			if (accelerate && currentCondition!!.getStopTest() == AccelerationStopTest.TO_HALF_SPEED) {
				targetSpeed = 0.0
				if (logger.isTraceEnabled) {
					logger.trace("Train {} motor: deceleration phase to half speed, target {}", number, targetSpeed)
				}
				waitUntil(AccelerationStopCondition(AccelerationStopTest.DECELERATION_ENDED))
			}

			accelerate = false
			stop()
			accelaration.state = 0.0
		}

		private fun privateAccelerateTo(
			speed: Double,
			test: AccelerationStopTest
		) {
			assert(speed >= 0)
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
			if (logger.isDebugEnabled) {
				logger.debug("Train {} motor: accelerate to speed {}, current velocity {}", number, speed, getVelocity())
			}
			context.report("in on warning", this@Train, ReportType._DEBUG)
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
			if (logger.isDebugEnabled) {
				logger.debug(
					"Train {} motor: warning mode, target speed {}, current velocity {}",
					number,
					normalSpeed,
					getVelocity()
				)
			}
			context.report("in on warning $normalSpeed", this@Train, ReportType._DEBUG)

			assert(getVelocity() >= 0)
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
			accelaration.state =
				if (currentCondition!!.getStopTest().isDecelarate()) {
					Math.max(a, this@Train.MINIMAL_DECELERATION.toDouble())
				} else {
					Math.min(a, this@Train.MAXIMAL_ACCELERATION.toDouble())
				}
		}
	}

	private val accelaration: Variable = Variable(0.0)
	private val velocity: Variable = Variable(0.0)
	private val va: SimpleIntegration = SimpleIntegration(velocity, accelaration)
	private val front: Front = Front()
	private val tail: Tail = Tail()
	private val motor: Motor = Motor()
	private val timetable: Timetable
	private val context: SimulationContext
	private var pathToSemaphore: Path? = null
	private val trainPrefix: String

	private val number: Int

	private var length: Double
	private var ap: AssertionContinuous = LenghtChecker()

	/**
	 * Create train
	 * @param context
	 * @param timetable
	 */
	constructor(context: SimulationContext?, timetable: Timetable?) {
		if (context == null) {
			throw NullPointerException("context must not be null")
		}
		if (timetable == null) {
			throw NullPointerException("timetable must not be null")
		}
		this.context = context
		this.timetable = timetable
		this.length = timetable.getLength()
		number = ++count
		trainPrefix = "Train #$number"
		logger.debug(
			"Train {} created: from {} to {}, length {}",
			number,
			timetable.getIn().getName(),
			timetable.getOut().getName(),
			length
		)
	}

	override fun distanceToSemaphore(): Double =
		if (pathToSemaphore == null) 0.0 else pathToSemaphore!!.length() - front.getPosition()

	override fun actions() { // spusten odsouhlasenim
		// zarazeni do fronty vstupniho bodu (simulace systemu sousedni stanice)
		val inout: InOut = timetable.getIn()
		val worker: InOutWorker = context.getWorkerFor(inout)
		logger.debug("Train {} approved for movement from {} to {}", number, inout.getName(), timetable.getOut().getName())
		worker.enterTrain(this)
		context.report("approved ${inout.getName()}->${timetable.getOut().getName()}", this, ReportType.TRAIN_EVENTS)

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
		logger.debug("Train {} completed journey: distance traveled {}", number, front.getTotalDistance())
		context.report("ends", this, ReportType.TRAIN_EVENTS)
	}

	/**
	 * @return current acceleration of train
	 */
	fun getAccelaration(): Double = accelaration.state

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
		accelaration.start()
		velocity.start()
		va.start()
		return this
	}

	override fun stop() {
		accelaration.stop()
		velocity.stop()
		va.stop()
		velocity.state = 0.0
		velocity.rate = 0.0
		accelaration.rate = 0.0
		accelaration.state = 0.0
	}

	override fun toString(): String = trainPrefix
}
