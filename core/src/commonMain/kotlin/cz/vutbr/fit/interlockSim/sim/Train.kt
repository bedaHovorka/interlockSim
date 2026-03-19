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
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging
import cz.hovorka.kdisco.engine.Condition
import cz.hovorka.kdisco.engine.Continuous
import cz.hovorka.kdisco.engine.Process
import cz.hovorka.kdisco.engine.Variable
import cz.hovorka.kdisco.engine.dtMin
import cz.hovorka.kdisco.engine.maxAbsError

/**
 * Train Process
 *
 */
class Train :
	Process,
	TrackOccupant {
	companion object {
		private val logger = KotlinLogging.logger {}
		private var countValue = 0
		private fun nextCount(): Int = ++countValue

		/**
		 * Maximum train acceleration in m/s²
		 */
		private const val MAXIMAL_ACCELERATION = 4

		/**
		 * Minimum train deceleration in m/s² (negative value for braking)
		 */
		private const val MINIMAL_DECELERATION = -3
	}

	// GitHub #62: Support bidirectional train operation (reverse direction)
	// Allow train engineer to move to opposite end and drive in reverse direction.
	// This is a simulation simplification of locomotive coupling/uncoupling operations.
	// Implementation: Either swap start/end positions OR cancel/restore events with train stationary.

	private abstract inner class Site : Process() { // lepsi nazev?
		private val position: Variable = Variable(0.0)
		private val pv: SimpleIntegration = SimpleIntegration(position, velocity)
		private var totalLengthOfPreviousBlocks: Double = 0.0
		private var next: TrackSection? = null
		private var current: TrackSection? = null
		private var onNext: Boolean = false

		val terminated: Condition = Condition { terminated() }

		final override suspend fun actions() {
			var where: DynamicPathSeparator = timetable.getIn()
			requireSimulationNotNull(where) { "PathSeparator from timetable.getIn() must not be null" }
			// out se muze rovnat in => bude vyreseno "prepojenim lokomotivy"

			// Initialize entry separator for animation (train enters network here)
			this@Train.entrySeparator = where

			while (true) {
				// Check if we've reached the destination InOut BEFORE querying for path
				if (where is DynamicInOut && current != null) {
					// We're at an InOut and we've already traveled through at least one block
					// This is our destination - exit the loop
					break
				}

				/**
				 * PathResult Pattern Matching (Issue #291, PR #358)
				 *
				 * This pattern matching logic distinguishes between permanent and temporary path failures:
				 * - NoTopologicalPath: Train has reached a dead-end (permanent condition)
				 * - OwnershipConflict: Path exists but blocks are reserved (temporary condition)
				 *
				 * Rationale for sim/ package modification:
				 * - Type safety: Sealed class prevents null-pointer errors
				 * - Semantic clarity: Explicit distinction aids debugging and logging
				 * - Performance: No overhead beyond nullable check (sealed class, no boxing)
				 * - Physics: No impact on simulation correctness (validated in TRAIN_PASSIVATION_FIX.md)
				 *
				 * Conservative approach compliance:
				 * - ✅ Comprehensive tests (TrainPathReservationIntegrationTest, TrainNavigationServiceTest)
				 * - ✅ Documentation (TRAIN_PASSIVATION_FIX.md, PathResult.kt KDoc)
				 * - ✅ Physics validation (no regression in motor behavior)
				 * - ✅ Backward compatible (sealed class replaces nullable Path)
				 *
				 * @see cz.vutbr.fit.interlockSim.context.navigation.PathResult
				 * @see docs/TRAIN_PASSIVATION_FIX.md
				 */
				val pathResult = trainNavService.findReservedPathForTrain(name, where)
				val path = when (pathResult) {
					is PathResult.Available -> pathResult.path
					is PathResult.NoTopologicalPath -> {
						// Permanent condition - no path exists in network topology
						if (where is DynamicInOut) {
							// At destination InOut, this is expected (train has arrived)
							null
						} else {
							// Not at destination, this is an error
							logger.error {
								"Train $number: No topological path exists from $where. " +
									"Network may be misconfigured or train reached dead-end."
							}
							null
						}
					}
					is PathResult.OwnershipConflict -> {
						// Temporary condition - blocks reserved for different train
						logger.debug {
							"Train $number: Path blocked by ownership conflict at $where, " +
								"halting and waiting for dispatcher (will retry after 5s)"
						}
						null
					}
				}
				next = path?.getNext(current)

				if (path == null || next == null) {
					if (where is DynamicInOut) break
					// Train should wait for dispatcher to reserve next path
					// Stop motor completely to prevent creeping motion during passivation
					motor.cancelAccelerating()
					this@Train.stop()

					/**
					 * Polling Mechanism Trade-off (Issue #291, PR #358)
					 *
					 * Uses hold(5.0) instead of passivate() to prevent motor creeping bug.
					 *
					 * Performance consideration:
					 * - Polling overhead: 0.2 wakeups/second per blocked train
					 * - Acceptable impact: Discrete event simulation operates at simulation-time (seconds),
					 *   not real-time (microseconds). The 5-second poll interval is insignificant
					 *   compared to typical train travel times (60+ seconds between signals).
					 * - Worst case: 10 blocked trains = 2 wakeups/second (negligible CPU overhead)
					 *
					 * Alternative considered (passivate):
					 * - ❌ Rejected: Motor continued running during passivation, causing train to
					 *   drift ~100m from semaphore after 146+ simulation seconds (see TRAIN_PASSIVATION_FIX.md)
					 * - ❌ Rejected: Requires dispatcher to explicitly reactivate train (unreliable)
					 *
					 * Physics validation:
					 * - Before: velocity=8.48E-4 m/s, acceleration=-3.6E-9 m/s² (creeping)
					 * - After: velocity=0.0 m/s, acceleration=0.0 m/s² (fully stopped)
					 *
					 * @see docs/TRAIN_PASSIVATION_FIX.md for detailed analysis
					 */
					hold(5.0) // Wait 5 seconds before retrying path request
					continue // Restart loop to retry path request
				}
				val nextLength: Double = next!!.length()
				separatorAction(where, current, next)

				onNext = true
				requireSimulation(position.isActive() && pv.isActive()) {
					"Position and velocity integration must be active"
				}
				waitUntil {
					// dtmin - horni odhad zmeny pri poslednim kroku numericke metody behem dobrzdovani k uzlu
					position.state + dtMin >= nextLength
				}

				position.state -= nextLength
				totalLengthOfPreviousBlocks += nextLength
				val staticWhere = next!!.getSecondEnd(where)
				requireSimulationNotNull(staticWhere) { "PathSeparator from getSecondEnd() must not be null" }
				where = env.toDynamic(staticWhere)

				// Store entry separator for animation position calculation
				// where = separator we just crossed (entry to next section)
				this@Train.entrySeparator = where

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
		abstract suspend fun separatorAction(
			where: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		)

		// Not an override: kdisco-engine Process has no start()/stop() — intentional design.
		open fun start(): Site {
			position.start()
			pv.start()
			return this
		}

		fun stop() {
			position.stop()
			pv.stop()
		}

		/**
		 * Called by [Tail] when the train first enters the network from its home [InOut].
		 * Corrects the initial position to account for the [Front]'s integration overshoot past
		 * the train-length threshold, ensuring [LengthChecker] invariant (front − tail = length)
		 * holds from the moment the tail enters.
		 *
		 * Without this correction, RKF45 may overshoot `front.getTotalDistance() >= length` by
		 * several metres (one integration step ≈ dtMax × velocity), leaving tail at 0 while
		 * front is already ahead by the overshoot amount, which violates `abs(front−tail−length) ≤ maxAbsError`.
		 */
		protected fun initPositionFromFrontOffset(frontTotalDistance: Double, trainLength: Double) {
			val offset = frontTotalDistance - trainLength
			if (offset > 0.0) position.state = offset
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
		fun getTotalDistance(): Double = totalLengthOfPreviousBlocks + position.state

		protected fun getSection(): TrackSection? = if (onNext) next else current

		internal fun getFrontSection(): TrackSection? = getSection()

		internal fun getTailSection(): TrackSection? = getSection()
	}

	private inner class Front : Site() {
		private suspend fun semaphoreAction(
			semaphore: DynamicRailSemaphore,
			separator: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			// isSeparatorInDirection accepts nullable Track parameters
			requireSimulation(env.isSeparatorInDirection(separator as OrientedPathSeparator, next, current)) {
				"Separator must be in direction, semaphore: $semaphore"
			}
			requireSimulationNotNull(semaphore.signal) { "Semaphore signal must not be null" }
			logger.info {
				"${Process.time()} SENSOR: Train $number detected at semaphore " +
					"${semaphore.name}, " +
					"signal=${semaphore.signal}, velocity=${getVelocity()} m/s"
			}

			/**
			 * PathResult Pattern Matching (Issue #291, PR #358)
			 *
			 * This pattern matching logic distinguishes between permanent and temporary path failures:
			 * - NoTopologicalPath: Train has reached a dead-end (permanent condition)
			 * - OwnershipConflict: Path exists but blocks are reserved (temporary condition)
			 *
			 * Rationale for sim/ package modification:
			 * - Type safety: Sealed class prevents null-pointer errors
			 * - Semantic clarity: Explicit distinction aids debugging and logging
			 * - Performance: No overhead beyond nullable check (sealed class, no boxing)
			 * - Physics: No impact on simulation correctness (validated in TRAIN_PASSIVATION_FIX.md)
			 *
			 * Conservative approach compliance:
			 * - ✅ Comprehensive tests (TrainPathReservationIntegrationTest, TrainNavigationServiceTest)
			 * - ✅ Documentation (TRAIN_PASSIVATION_FIX.md, PathResult.kt KDoc)
			 * - ✅ Physics validation (no regression in motor behavior)
			 * - ✅ Backward compatible (sealed class replaces nullable Path)
			 *
			 * @see cz.vutbr.fit.interlockSim.context.navigation.PathResult
			 * @see docs/TRAIN_PASSIVATION_FIX.md
			 */
			val pathResult = trainNavService.findReservedPathForTrain(name, separator)
			val path: Path? = when (pathResult) {
				is PathResult.Available -> pathResult.path
				is PathResult.NoTopologicalPath -> {
					logger.error {
						"Train $number at semaphore ${semaphore.name}: No topological path exists. " +
							"Network may be misconfigured."
					}
					null
				}
				is PathResult.OwnershipConflict -> {
					logger.debug {
						"Train $number at semaphore ${semaphore.name}: Path blocked by ownership conflict"
					}
					null
				}
			}

			// GOAL 15: Station stops for tutorial scenarios - see LONG_TERM_GOALS.md

			if (semaphore.signal == Signal.STOP) {
				requireSimulation(getVelocity() >= 0) { "Velocity must be non-negative when approaching semaphore" }
				logger.debug { "Train $number approaching semaphore with STOP signal, halting" }
				fireStop()
				env.report(semaphore.signal.toString(), this@Train, ReportType.TRAIN_EVENTS)

				// freePath(separator, next); //vlak si sam pri zastaveni u semaforu postavi cestu k dalsimu sem.
				waitUntil(allowingSignal(semaphore))
				logger.debug { "Train $number received allowing signal from semaphore, resuming movement" }
				env.report("OK " + semaphore.signal, this@Train, ReportType.TRAIN_EVENTS)

				/**
				 * PathResult Pattern Matching (Issue #291, PR #358)
				 *
				 * This pattern matching logic distinguishes between permanent and temporary path failures:
				 * - NoTopologicalPath: Train has reached a dead-end (permanent condition)
				 * - OwnershipConflict: Path exists but blocks are reserved (temporary condition)
				 *
				 * Rationale for sim/ package modification:
				 * - Type safety: Sealed class prevents null-pointer errors
				 * - Semantic clarity: Explicit distinction aids debugging and logging
				 * - Performance: No overhead beyond nullable check (sealed class, no boxing)
				 * - Physics: No impact on simulation correctness (validated in TRAIN_PASSIVATION_FIX.md)
				 *
				 * Conservative approach compliance:
				 * - ✅ Comprehensive tests (TrainPathReservationIntegrationTest, TrainNavigationServiceTest)
				 * - ✅ Documentation (TRAIN_PASSIVATION_FIX.md, PathResult.kt KDoc)
				 * - ✅ Physics validation (no regression in motor behavior)
				 * - ✅ Backward compatible (sealed class replaces nullable Path)
				 *
				 * Note: Re-fetch path after signal becomes allowing.
				 * The signal should only become allowing when a path is reserved.
				 *
				 * @see cz.vutbr.fit.interlockSim.context.navigation.PathResult
				 * @see docs/TRAIN_PASSIVATION_FIX.md
				 */
				val resumeResult = trainNavService.findReservedPathForTrain(name, separator)
				val resumePath: Path? = when (resumeResult) {
					is PathResult.Available -> resumeResult.path
					is PathResult.NoTopologicalPath -> {
						logger.error {
							"Train $number at semaphore ${semaphore.name}: Signal is allowing but no topological path exists. " +
								"This indicates a logic error - signal should only allow when path exists."
						}
						null
					}
					is PathResult.OwnershipConflict -> {
						logger.error {
							"Train $number at semaphore ${semaphore.name}: Signal is allowing but path not reserved for this train. " +
								"This indicates a logic error - signal should only allow when path is reserved."
						}
						null
					}
				}
				requireSimulationNotNull(resumePath) {
					"Train $number at semaphore ${semaphore.name}: Signal is allowing but no reserved path found. " +
						"This indicates a logic error - signal should only allow when path is reserved."
				}
				fireStart(semaphore, resumePath)
			} else if (semaphore.signal.isAllowing() && velocity.state <= maxAbsError) {
				logger.debug { "Train $number starting movement with allowing signal" }
				// Validate path exists before starting
				requireSimulationNotNull(path) {
					"Train $number at semaphore ${semaphore.name}: Signal is allowing but no reserved path found. " +
						"This indicates a logic error - signal should only allow when path is reserved."
				}
				fireStart(semaphore, path)
			} else {
				// Already moving, accelerate toward next semaphore
				logger.debug { "Train $number accelerating toward next semaphore" }
				// Validate path exists before accelerating
				requireSimulationNotNull(path) {
					"Train $number at semaphore ${semaphore.name}: Cannot accelerate without reserved path."
				}
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
		}

		private fun fireStart(
			semaphore: DynamicRailSemaphore,
			path: Path?
		) {
			accelerateToSignal(semaphore, path)
			this@Train.start()
			front.start()
			tail.start()
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
				minOf(
					path.maxSpeed(path.getFirst()),
					thisSignal.allowedSpeed()
				)
			if (nextSignal.isAllowing()) {
				motor.accelerateTo(minOf(nextSignal.allowedSpeed(), min))
			} else {
				motor.onWarning(min)
			}
		}

		override suspend fun separatorAction(
			where: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			logger.debug {
				"${Process.time()} POSITION: Train $number front at separator $where, " +
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

		override suspend fun separatorAction(
			where: DynamicPathSeparator,
			current: TrackSection?,
			next: TrackSection?
		) {
			logger.debug {
				"${time()} POSITION: Train $number tail at separator $where, clearing block $current"
			}
			if (where == timetable.getIn()) {
				fromHome = true
				initPositionFromFrontOffset(front.getTotalDistance(), getLength())
				start()
			}

			if (current != null) {
				current.leave(this@Train)
				val block = current.getTrackBlock()
				if (block is DynamicTrackBlock) {
					env.unregisterBlock(name, block)
				}
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
			kotlin.math.abs(front.getTotalDistance() - tail.getTotalDistance() - getLength()) <= maxAbsError

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

	// Motor extends Continuous (not LoopProcess) because it requires ODE-based physics integration
	// via derivatives(). LoopProcess extends Process (discrete-only) and cannot provide continuous
	// integration. Motor uses Continuous.start()/stop() to activate/deactivate the ODE integrator
	// per acceleration phase, while the terminate flag provides graceful shutdown (safe to reimplement
	// here because Motor cannot inherit from LoopProcess). The "continuous simulation not required"
	// determination in the decision docs refers to the framework choice (DSOL vs kDisco), not to
	// Motor's kinematics, which have always been ODE-based. See: #373
	private inner class Motor : Continuous() {
		private var currentCondition: AccelerationStopCondition? = null
		private var targetSpeed: Double = 0.0
		private var accelerate: Boolean = false
		private var terminate = false

		private inner class AccelerationStopCondition(
			private val stopTest: AccelerationStopTest
		) : Condition {
			override fun test(): Boolean = !accelerate || stopTest.condition(targetSpeed, getVelocity())

			fun getStopTest(): AccelerationStopTest = stopTest
		}

		override suspend fun actions() {
			while (true) {
				if (terminate) break
				iteration()
				if (terminate) break
				passivate()
			}
		}

		private suspend fun iteration() {
			val cond = requireSimulationNotNull(currentCondition) { "Current condition must not be null during iteration" }
			accelerate = true
			logger.trace {
				"Train $number motor iteration: target speed $targetSpeed, " +
					"current velocity ${getVelocity()}"
			}
			start()
			waitUntil(cond)

			if (accelerate && cond.getStopTest() == AccelerationStopTest.TO_HALF_SPEED) {
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
			Process.activate(this)
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
				Process.activate(this)
			}
		}

		override fun terminate() {
			terminate = true
			if (!terminated()) Process.activate(this)
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
				if (requireNotNull(currentCondition) { "currentCondition must be set" }.getStopTest().isDecelarate()) {
					maxOf(a, MINIMAL_DECELERATION.toDouble())
				} else {
					minOf(a, MAXIMAL_ACCELERATION.toDouble())
				}
		}
	}

	private inner class TrainReporter : Continuous() {
		private var started: Boolean = false
		private var lastReportTime: Double = -1.0

		override fun derivatives() {
			if (!started || !env.isReporting(ReportType.TRAIN_CONTINUOUS)) return
			// Throttle to report at most once per 1.0 simulation-time unit,
			// matching the old Reporter.setFrequency(1.0) behaviour from jDisco.
			val currentTime = Process.time()
			val currentSecond = kotlin.math.floor(currentTime)
			if (currentSecond <= lastReportTime) return
			lastReportTime = currentSecond
			val builder = StringBuilder()
			builder.append(getAcceleration()).append(' ')
			builder.append(getVelocity()).append(' ')
			builder.append(front.getTotalDistance()).append(' ')
			builder.append(front.getFrontSection()).append(' ')
			builder.append(tail.getTailSection()).append(' ')
			val distanceToSemaphore: Double = distanceToSemaphore()
			builder.append(if (distanceToSemaphore > 0) distanceToSemaphore else 0)
			env.report(builder, this@Train, ReportType.TRAIN_CONTINUOUS)
		}

		override fun start(): TrainReporter {
			started = true
			super.start()
			return this
		}

		override fun stop() {
			started = false
			super.stop()
		}
	}
	private val reporter: TrainReporter = TrainReporter()

	private val acceleration: Variable = Variable(0.0)
	private val velocity: Variable = Variable(0.0)
	private val va: SimpleIntegration = SimpleIntegration(velocity, acceleration)
	private val front: Front = Front()
	private val tail: Tail = Tail()
	private val motor: Motor = Motor()
	private val timetable: Timetable
	private val env: SimulationEnvironment
	private var pathToSemaphore: Path? = null
	override val name: String
	private val trainNavService: TrainNavigationService

	/**
	 * The separator through which the train entered the current section.
	 * Used by animation calculator to determine interpolation direction.
	 * Null if train hasn't entered any section yet (initialization).
	 */
	private var entrySeparator: DynamicPathSeparator? = null

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
		number = nextCount()
		name = "Train #$number"
		val inName = validatedTimetable.getIn().name
		val outName = validatedTimetable.getOut().name
		trainNavService = env.getTrainNavigationService()
		logger.debug { "Train $number created: from $inName to $outName, length $length" }
	}

	override fun distanceToSemaphore(): Double =
		if (pathToSemaphore == null) 0.0 else pathToSemaphore!!.length() - front.getPosition()

	override suspend fun actions() { // spusten odsouhlasenim
		// zarazeni do fronty vstupniho bodu (simulace systemu sousedni stanice)
		val inout = timetable.getIn()
		val worker: InOutWorker = env.getWorkerFor(inout)
		logger.debug { "Train $number approved for movement from ${inout.name} to ${timetable.getOut().name}" }
		worker.enterTrain(this)
		env.report("approved ${inout.name}->${timetable.getOut().name}", this, ReportType.TRAIN_EVENTS)

		// Wait for InOutWorker to reserve initial path before starting Front
		// This prevents race condition where Front checks for reserved path before InOutWorker completes
		waitUntil {
			// Check if we have any reserved blocks (path has been reserved)
			trainNavService.isPathReservedForTrain(name, inout)
		}
		logger.info { "Train $number path is reserved, starting Front process" }

		Process.activate(front)

		waitUntil { front.getTotalDistance() >= getLength() }
		Process.activate(tail)

		out()
		(worker.getQueqe().first() as? Train)?.let { Process.activate(it) }
		ap.start()
		reporter.start()

		waitUntil(front.terminated)
		ap.stop()
		// predkem v systemu sousedni stanice

		waitUntil(tail.terminated)
		reporter.stop()
		stop()
		motor.terminate()
		env.releaseTrainReservations(name)
		// ukoncovaci..
		logger.debug { "Train $number completed journey: distance traveled ${front.getTotalDistance()}" }
		env.report("ends", this, ReportType.TRAIN_EVENTS)
	}

	/**
	 * @return current acceleration of train
	 */
	fun getAcceleration(): Double = acceleration.state

	/**
	 * Current acceleration of train (Kotlin property accessor).
	 * Delegates to [getAcceleration].
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainAcceleration: Double
		get() = getAcceleration()

	/**
	 * @return current speed of train
	 */
	fun getVelocity(): Double = velocity.state

	/**
	 * Current velocity of train in m/s (Kotlin property accessor).
	 * Delegates to [getVelocity].
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainVelocity: Double
		get() = getVelocity()

	/**
	 * @return length of train
	 */
	fun getLength(): Double {
		return length // pozdeji soucet vagonu
	}

	/**
	 * Length of train in meters (Kotlin property accessor).
	 * Delegates to [getLength].
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainLength: Double
		get() = getLength()


	/**
	 * Reverse the train's direction of travel.
	 *
	 * This simulates the train engineer moving to the opposite end of the train
	 * and driving in the reverse direction. This is a simulation simplification
	 * of real-world locomotive coupling/uncoupling operations.
	 *
	 * **Preconditions:**
	 * - Train must be completely stopped (velocity = 0)
	 * - Motor must not be accelerating
	 *
	 * **Operation:**
	 * - Validates train is stopped
	 * - Simulates engineer movement delay (30 seconds)
	 * - Swaps In/Out destinations in timetable
	 * - Reports the reversal event
	 *
	 * **Usage Example:**
	 * ```kotlin
	 * // In a custom interlocking/dispatcher process
	 * class CustomInterlocking(context: SimulationContext) : Interlocking(context) {
	 *     override suspend fun actions() {
	 *         val train = Train(env, timetable)
	 *         Process.activate(train)
	 *
	 *         // Wait for train to reach station
	 *         waitUntil { train.getVelocity() == 0.0 }
	 *
	 *         // Reverse direction (this will hold for 30 seconds)
	 *         train.reverseDirection()
	 *
	 *         // Train can now continue in opposite direction
	 *     }
	 * }
	 * ```
	 *
	 * **Note:** This method uses `hold(30.0)` and must be called from within
	 * a kdisco-engine coroutine process context (i.e., from a `suspend` function
	 * inside a [Process.actions] override).
	 *
	 * @throws IllegalStateException if train is not stopped
	 * @since GitHub #62: Bidirectional train operation support
	 */
	suspend fun reverseDirection() {
		// Validate preconditions
		requireSimulation(getVelocity() == 0.0) {
			"Train $number must be stopped (velocity = 0) to reverse direction. Current velocity: ${getVelocity()}"
		}

		logger.info { "Train $number: Engineer moving to opposite end (reversing direction)" }
		env.report("reversing direction", this, ReportType.TRAIN_EVENTS)

		// Simulate time for engineer to walk to opposite end of train
		// Typical walking speed: 1.5 m/s, train length varies (e.g., 200m)
		// Use fixed 30 second delay for simulation consistency
		hold(30.0)

		// Swap In and Out destinations
		timetable.reverseDirection()

		val newDestination = timetable.getOut().name
		logger.info { "Train $number: Direction reversed, new destination: $newDestination" }
		env.report("reversed, destination now $newDestination", this, ReportType.TRAIN_EVENTS)
	}

	/**
	 * Get train number for identification and rendering.
	 *
	 * Each train is assigned a unique sequential number starting from 1.
	 * Used for train overlay rendering and identification in animation.
	 *
	 * @return Unique train number
	 * @since 2026-01-22 (Issue #203)
	 */
	fun getNumber(): Int = number

	/**
	 * Unique train number for identification (Kotlin property accessor).
	 * Each train is assigned a sequential number starting from 1.
	 * Delegates to [getNumber].
	 * @since 2026-02-06 (Public Train API for animation)
	 */
	val trainNumber: Int
		get() = getNumber()

	/**
	 * Origin InOut where this train entered the network.
	 *
	 * Used by animation system to determine train color coding based on entry point.
	 * Color mapping: InOut "B" → blue, InOut "A" → orange (configurable in vyhybna.xml).
	 *
	 * This is a minimal accessor to support animation visualization without exposing
	 * full Timetable structure. No sim/ package refactoring required.
	 *
	 * @return The DynamicInOut where the train originated
	 * @since 2026-02-04 (Fix train color coding bug)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val originInOut: DynamicInOut
		get() = timetable.getIn()

	/**
	 * Track section where the train's front is currently located.
	 *
	 * Used for train position interpolation in animation rendering.
	 * Returns null if train has not yet started moving.
	 *
	 * @return Current track section for train front, or null
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val frontSection: TrackSection?
		get() = front.getFrontSection()

	/**
	 * Distance traveled by the train's front along current track section.
	 *
	 * Used for train position interpolation in animation rendering.
	 * Returns position within the current section (0.0 to section length).
	 *
	 * @return Distance along current section in meters
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val frontPosition: Double
		get() = front.getPosition()

	/**
	 * Total distance traveled by the train's front since departure.
	 *
	 * Includes all previously completed sections plus position in current section.
	 * Used for train progress tracking and animation.
	 *
	 * @return Total distance traveled in meters
	 * @since 2026-01-22 (Issue #203)
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val totalDistance: Double
		get() = front.getTotalDistance()

	/**
	 * Separator where train entered current section.
	 * Used for correct position interpolation in animation.
	 * @return entry separator, or null if train hasn't entered any section yet
	 * @since 2026-02-06 (Converted to Kotlin property for idiomatic API)
	 */
	val trainEntrySeparator: DynamicPathSeparator?
		get() = entrySeparator

	@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
	override fun nextSemaphore(): OrientedPathSeparator? = pathToSemaphore?.getLast()

	fun start(): Train {
		acceleration.start()
		velocity.start()
		va.start()
		return this
	}

	fun stop() {
		acceleration.stop()
		velocity.stop()
		va.stop()
		velocity.state = 0.0
		velocity.rate = 0.0
		acceleration.rate = 0.0
		acceleration.state = 0.0
	}

	override fun toString(): String = name
}
