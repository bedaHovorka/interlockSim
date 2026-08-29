/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.paths

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.domain.ABSOLUTE_MAX_SPEED
import cz.vutbr.fit.interlockSim.domain.MINIMAL_MAX_SPEED
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.Track
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.core.conflict
import cz.vutbr.fit.interlockSim.objects.tracks.AbstractTrack
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val IS_FREE_FROM = "isFreeFrom"
private const val SET_UP_PATH = "setUpPath"
private const val CANCEL_PATH_SETUP = "cancelPathSetup"
private const val IS_SET_UP_PATH = "isSetUpPath"

/**
 * Base implemetation of {@link Path}
 */
abstract class AbstractPath protected constructor(
	private val context: SimulationContext
) : AbstractTrack(),
	Path {
	override fun getLastPathSemaphore(): RailSemaphore {
		val last = getLast()
		// Use polymorphic method - no instanceof checks needed!
		// Works for RailSemaphore, InOut, DynamicRailSemaphore, and DynamicInOut
		return last.asRailSemaphore()
	}

	override fun maxSpeed(from: PathSeparator?): Double {
		var prevSep = from
		// Track prevTrack = null;
		var min = (from as DynamicPathSeparator).allowedSpeed()

		val it = getIterator(from)
		while (it.hasNext()) {
			val e = it.next()

			if (e == getLast() || e == getFirst()) {
				// Skip path endpoints
				continue
			}

			// Polymorphic contribution - no instanceof checks needed
			val oldMin = min
			val contribution = e.contributeToPathMaxSpeed(prevSep, min)
			min = contribution.minSpeed
			prevSep = contribution.updatedPreviousSeparator

			// Log only when track actually reduces the max speed
			if (contribution.minSpeed < oldMin) {
				logger.trace { "Element $e contributes max speed: ${contribution.minSpeed}" }
			}
		}
		logger.trace { "Path max speed calculation result: $min" }
		return min
	}

	override fun length(): Double {
		var sum = 0.0
		for (e in this) {
			val elementLength = e.contributeToPathLength()
			if (elementLength > 0.0) {
				sum += elementLength
				logger.trace { "Element $e length: $elementLength, cumulative path length: $sum" }
			}
		}
		logger.trace { "Total path length calculation: $sum" }
		return sum
	}

	override fun ends(): Array<PathSeparator> = arrayOf(getFirst(), getLast())

	/**
	 * Ensure track is a TrackFacility (DynamicTrackBlock or DynamicTrack).
	 * **CRITICAL FIX (Issue #282):** Do NOT convert DynamicTrackBlock to DynamicTrack!
	 * Paths contain DynamicTrackBlock instances from the grid. Converting them to
	 * DynamicTrack wrappers creates duplicate state - one instance gets reserved,
	 * another instance gets entered, causing "Wrong state: FREE, expected: RESERVED" errors.
	 *
	 * Converts a Track to DynamicTrack wrapper for state operations.
	 * Helper method to reduce code duplication in path operations.
	 *
	 * **Precondition:** track must be a TrackFacility (all tracks in paths are TrackFacility instances)
	 *
	 * @param track The track to wrap (must be TrackFacility)
	 * @return DynamicTrack wrapper for state operations
	 * @throws ClassCastException if track is not a TrackFacility
	 */
	private fun toTrackFacility(track: Track): TrackFacility {
		require(track is TrackFacility) {
			"Track in path must be a TrackFacility, got: ${track::class.simpleName}"
		}
		return track
	}

	override fun isFreeFrom(sep: DynamicPathSeparator): Boolean =
		pathIterating(sep, IS_FREE_FROM) { track, separator ->
			toTrackFacility(track).isFreeFrom(separator)
		}

	override fun isSetUpPath(from: DynamicPathSeparator): Boolean =
		pathIterating(from, IS_SET_UP_PATH) { track, separator ->
			toTrackFacility(track).isSetUpPath(separator)
		}

	override fun setUpPath(
		from: DynamicPathSeparator,
		reservingTrainId: String
	) {
		logger.debug { "PATH_RESERVATION_START: from=$from, pathSize=$size" }
		var blockCount = 0
		pathIterating(from, SET_UP_PATH) { track, separator ->
			val facility = toTrackFacility(track)
			facility.setUpPath(separator, reservingTrainId)
			blockCount++
			true
		}
		logger.debug { "PATH_RESERVATION_COMPLETE: from=$from, reserved $blockCount blocks" }
	}

	override fun cancelPathSetup(from: DynamicPathSeparator) {
		pathIterating(from, CANCEL_PATH_SETUP) { track, separator ->
			toTrackFacility(track).cancelPathSetup(separator)
			true
		}
	}

	/**
	 * Iterates over path elements and applies the given operation to each track.
	 * This replaces the legacy Java 6 reflection-based approach with idiomatic Kotlin lambdas.
	 * Operations are performed via DynamicTrack wrappers to ensure proper state management.
	 *
	 * The conversion to DynamicTrack is handled internally by the calling methods
	 * (isFreeFrom, setUpPath, etc.) which use the toDynamicTrack() helper.
	 *
	 * @param sep The path separator to start iteration from
	 * @param operationName Name of the operation for logging and separator setting
	 * @param trackOperation Lambda that performs the operation on a track and returns true if successful.
	 *                       The lambda receives a Track parameter (guaranteed to be a TrackFacility).
	 * @return true if all operations succeeded, false otherwise
	 */
	private fun pathIterating(
		sep: DynamicPathSeparator,
		operationName: String,
		trackOperation: (Track, DynamicPathSeparator) -> Boolean
	): Boolean {
		try {
			var previous: Track? = null
			logger.debug { "Path iteration starting: operation=$operationName, separator=$sep, pathLength=${length()}" }

			val iterator = getIterator(sep)
			while (iterator.hasNext()) {
				val separator = Util.assertInstanceOf<DynamicPathSeparator>(iterator.next())
				if (!iterator.hasNext()) break // Last element is semaphore, separatorSetting doesn't set it
				val nextTrack = Util.assertInstanceOf<Track>(iterator.next())

				if (!separatorSetting(operationName, separator, previous, nextTrack)) {
					if (operationName == IS_FREE_FROM) {
						logger.info {
							"${Process.time()} PATH_NOT_FREE: Separator $separator prevents path - config cannot be set"
						}
					}
					logger.debug { "Separator setting failed for operation: $operationName" }
					return false
				}

				// Execute the track operation via lambda (replaces reflection invoke)
				if (!trackOperation(nextTrack, separator)) {
					if (operationName == IS_FREE_FROM) {
						logger.info {
							"${Process.time()} PATH_NOT_FREE: Track $nextTrack prevents path - " +
								"state=${toTrackFacility(nextTrack).getState()}"
						}
					}
					logger.debug { "Track operation returned false for operation: $operationName" }
					return false
				}

				previous = nextTrack
			}

			// Set up semaphores if this is a setUpPath operation
			if (operationName == SET_UP_PATH) {
				logger.debug { "Setting up semaphores for path" }
				setUpSemaphores(sep)
			}

			return true
		} catch (e: TrackOperationException) {
			throw e
		} catch (e: Exception) {
			throw TrackOperationException(e, this)
		}
	}

	private fun separatorSetting(
		methodName: String,
		dynamicSeparator: DynamicPathSeparator,
		previous: Track?,
		next: Track
	): Boolean {
		val from = context.getSegment(dynamicSeparator, previous, next)
		val to = context.getSegment(dynamicSeparator, next, previous)
		requireSimulation(!conflict(from, to)) { "Segment conflict: from=$from, to=$to" }

		// NOTE: from and to CAN be null - this is intentional and matches Java behavior
		// getSegment() returns null when no segment exists (e.g., InOut.getFollowingSegment)
		// The Java version did not check for nulls here

		if (methodName == IS_SET_UP_PATH) {
			// Java: return separator.getFollowingSegment(from) == to;
			// NOTE: getFollowingSegment accepts nullable Segment, so no null check needed
			return dynamicSeparator.getFollowingSegment(from) === to
		} else if (methodName == CANCEL_PATH_SETUP) {
			// Java: separator.cancelPathSetup(from, to);
			dynamicSeparator.cancelPathSetup(from, to)
			// Tier 1: Unlock switch after cancelling path setup
			if (dynamicSeparator.isSwitch() && dynamicSeparator is DynamicRailSwitch) {
				dynamicSeparator.unlock()
				logger.debug { "Switch ${dynamicSeparator.hashCode()} unlocked after CANCEL_PATH_SETUP" }
			}
		} else if (methodName == SET_UP_PATH) {
			val following = dynamicSeparator.getFollowingSegment(from)
			logger.debug { "SET_UP_PATH: getFollowingSegment($from) returned $following, expected $to" }
			requireSimulation(following === to) {
				"Separator $dynamicSeparator: getFollowingSegment($from) returned $following but expected $to"
			}
			// Note: Switch configuration is handled by PathReservationService.configureSwitchesInPath()
			// See Issue #300 - AbstractPath iteration was causing incorrect switch configuration
		} else if (methodName == IS_FREE_FROM) {
			// Java: //EMPTY
			// Intentionally empty - segments can be null, no action needed
		} else {
			throw IllegalArgumentException("wrong method name")
		}
		return true
	}

	private fun setUpSemaphores(sep: DynamicPathSeparator) {
		// ukolem je natavit zpetnym pruchodem rychlosti semaforu podle vyhybek
		var previousSwitch: DynamicPathSeparator? = null
		var previousTrack: Track? = null
		val iterator = getIterator(getSecondEnd(sep))
		while (iterator.hasNext()) {
			val element = iterator.next()
			// Type-safe pattern matching without unsafe casts
			when {
				element is DynamicPathSeparator && element.isSwitch() -> {
					// Switch element: store for speed calculation
					previousSwitch = element
				}
				element is DynamicRailSemaphore -> {
					// Semaphore element: configure with previous track and switch speed
					if (previousTrack == null) continue
					if (context.isSeparatorInDirection(element, previousTrack, null)) {
						val speed = previousSwitch?.allowedSpeed() ?: ABSOLUTE_MAX_SPEED
						val segment = context.getSegment(element, null, previousTrack)
						val segment2 = context.getSegment(element, previousTrack, null)
						element.setUpSpeed(segment, segment2, speed)
						previousSwitch = null
					}
				}
				element is Track -> {
					// Track element: store for semaphore configuration
					previousTrack = element
				}
				else -> {
					// Should not happen in valid paths
					logger.warn { "Unexpected path element type: ${element::class.simpleName}" }
				}
			}
		}
		context.report("", this, ReportType.PATH_SETTING)
		requireSimulation(maxSpeed(sep) >= MINIMAL_MAX_SPEED) {
			"Max speed must be at least MINIMAL_MAX_SPEED, got: ${maxSpeed(sep)}"
		}
	}

	protected fun getIterator(sep: PathSeparator): Iterator<PathElement> {
		if (!isEnd(sep)) throw IllegalArgumentException("Is not end of abstrPath")
		if (sep == getFirst()) return iterator()
		requireSimulation(sep == getLast()) { "Separator must be either first or last" }
		return descendingIterator()
	}

	override fun equalsWithElements(path: Path): Boolean {
		if (path === this) return true
		if (size != path.size) return false
		if (size == 0) return true

		val thisIt = this.iterator()
		val pathIt = path.iterator()

		while (thisIt.hasNext()) {
			requireSimulation(pathIt.hasNext()) { "Path iterator ended prematurely" }
			val thisNext = thisIt.next()
			val pathNext = pathIt.next()
			requireSimulationNotNull(thisNext) { "Path element must not be null" }
			if (thisNext != pathNext) return false
		}

		return !pathIt.hasNext()
	}

	/**
	 * @return context getter
	 */
	fun getContext(): SimulationContext = context

	// Dynamic behavior methods for Path (aggregate operations)
	// These are not typically called on Path directly, but required by interface

	override fun getState(): TrackFacility.State {
		// Path doesn't have its own state - it's an aggregate
		// Return FREE as default (paths are not facilities themselves)
		return TrackFacility.State.FREE
	}

	override fun enter(occupant: TrackOccupant): Unit =
		throw UnsupportedOperationException(
			"Enter operation not supported on Path aggregate. " +
				"Call enter() on individual TrackSection elements."
		)

	override fun leave(occupant: TrackOccupant): Unit =
		throw UnsupportedOperationException(
			"Leave operation not supported on Path aggregate. " +
				"Call leave() on individual TrackSection elements."
		)

	override fun getTrackOccupant(): TrackOccupant =
		throw UnsupportedOperationException(
			"getTrackOccupant not supported on Path aggregate. " +
				"Query individual TrackSection elements."
		)

	override fun reversePath(): Path {
		val arrayPath = ArrayPath(getContext())
		val iter = descendingIterator()
		while (iter.hasNext()) {
			arrayPath.addLast(iter.next())
		}
		return arrayPath
	}
}
