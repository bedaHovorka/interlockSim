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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.AbstractTrack
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrack
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.sim.TrackOperationException
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.Iterator
import java.util.List

/**
 * Base implemetation of {@link Path}
 */
abstract class AbstractPath protected constructor(
	private val context: SimulationContext
) : AbstractTrack(),
	Path {
	companion object {
		private val logger = KotlinLogging.logger {}

		private const val IS_FREE_FROM = "isFreeFrom"
		private const val SET_UP_PATH = "setUpPath"
		private const val CANCEL_PATH_SETUP = "cancelPathSetup"
		private const val IS_SET_UP_PATH = "isSetUpPath"
		private val trackMethods: MutableMap<String, Method> = HashMap()
		private val parameterTypes: MutableMap<Class<*>, Array<Class<*>>> = HashMap()

		init {
			try {
				parameterTypes[Track::class.java] = arrayOf(PathSeparator::class.java)
				val nameList = ArrayList<String>()
				Collections.addAll(nameList, CANCEL_PATH_SETUP, SET_UP_PATH, IS_FREE_FROM, IS_SET_UP_PATH)
				fillMethodMap(trackMethods, Track::class.java, nameList as List<String>)
			} catch (e: Exception) {
				assert(false) { e }
				e.printStackTrace()
			}
		}

		@Throws(Exception::class)
		private fun fillMethodMap(
			map: MutableMap<String, Method>,
			clazz: Class<*>,
			methodNames: List<String>
		) {
			for (name in methodNames) {
				val method = clazz.getMethod(name, *parameterTypes[clazz]!!)
				map[name] = method
			}
		}
	}

	override fun getLastPathSemaphore(): RailSemaphore {
		val last = getLast()
		if (last is RailSemaphore) return last
		return Util.assertInstanceOf(InOut::class.java, last).getOutSemaphore()
	}

	override fun maxSpeed(sep: PathSeparator?): Double {
		var prevSep = sep
		// Track prevTrack = null;
		var min = sep!!.allowedSpeed()

		val it = getIterator(sep!!)
		while (it.hasNext()) {
			val e = it.next()

			if (e == getLast() || e == getFirst()) {
				// EMPTY
			} else {
				val es: Double
				if (e is Track) {
					val track = e
					es = track.maxSpeed(prevSep)
					logger.trace { "Track $track max speed: $es" }
					// prevTrack = track;
				} else {
					val ps = Util.assertInstanceOf(PathSeparator::class.java, e)
					prevSep = ps
					continue // DEBUG
// 					if (ps instanceof OrientedPathSeparator &&
// 							!context.isSeparatorInDirection((OrientedPathSeparator)sep, null, prevTrack)) continue;
// 					es = ps.allowedSpeed();
				}
				min = if (es < min) es else min
			}
		}
		logger.trace { "Path max speed calculation result: $min" }
		return min
	}

	override fun length(): Double {
		var sum = 0.0
		for (e in this) {
			if (e is Track) {
				val trackLength = e.length()
				sum += trackLength
				logger.trace { "Track $e length: $trackLength, cumulative path length: $sum" }
			}
		}
		logger.trace { "Total path length calculation: $sum" }
		return sum
	}

	override fun ends(): Array<PathSeparator> = arrayOf(getFirst(), getLast())

	override fun isFreeFrom(sep: PathSeparator): Boolean = pathIterating(sep)

	override fun isSetUpPath(sep: PathSeparator): Boolean = pathIterating(sep)

	override fun setUpPath(sep: PathSeparator) {
		logger.debug { "Setting up path from separator: $sep" }
		pathIterating(sep)
	}

	override fun cancelPathSetup(sep: PathSeparator) {
		pathIterating(sep)
	}

	@Throws(TrackOperationException::class)
	private fun pathIterating(sep: PathSeparator): Boolean {
		try {
			// lepsi nez 10x copypastovat kod z cyklem a vymenit jen volani metody
			val e = Thread.currentThread().stackTrace[2]
			val methodName = e.methodName
			val method = trackMethods[methodName]
			var previous: Track? = null
			logger.debug { "Path iteration starting: method=$methodName, separator=$sep, pathLength=${length()}" }

			val iterator = getIterator(sep)
			while (iterator.hasNext()) {
				val separator = Util.assertInstanceOf(PathSeparator::class.java, iterator.next())
				if (!iterator.hasNext()) break // posledni prvek je semafor a separatorSetting ho nenastavuje
				val nextTrack = Util.assertInstanceOf(Track::class.java, iterator.next())

				if (!separatorSetting(methodName, separator, previous, nextTrack)) {
					if (IS_FREE_FROM == methodName) {
						logger.info {
							"${jDisco.Process.time()} PATH_NOT_FREE: Separator $separator prevents path - config cannot be set"
						}
					}
					logger.debug { "Separator setting failed for method: $methodName" }
					return false
				}

				val invoke = method!!.invoke(nextTrack, separator)
				if (java.lang.Boolean.FALSE == invoke) {
					if (IS_FREE_FROM == methodName) {
						logger.info {
							"${jDisco.Process.time()} PATH_NOT_FREE: Track $nextTrack prevents path - " +
								"state=${if (nextTrack is SimpleTrack) nextTrack.getState() else "unknown"}"
						}
					}
					logger.debug { "Method invocation returned false for method: $methodName" }
					return false
				}
				assert(invoke == null || java.lang.Boolean.TRUE == invoke) { invoke }
				previous = nextTrack
			}
			if (method == trackMethods[SET_UP_PATH]) {
				logger.debug { "Setting up semaphores for path" }
				setUpSemaphores(sep)
			}
			return true
		} catch (e: InvocationTargetException) {
			val cause = e.cause
			throw if (cause is TrackOperationException) cause else TrackOperationException(cause ?: e, this)
		} catch (e: Exception) {
			throw TrackOperationException(e, this)
		}
	}

	@Throws(PathSeparatorChangeException::class)
	private fun separatorSetting(
		methodName: String,
		separator: PathSeparator,
		previous: Track?,
		next: Track
	): Boolean {
		assert(methodName != null && next != null)
		val from = context.getSegment(separator, previous, next)
		val to = context.getSegment(separator, next, previous)
		assert(!Segment.conflict(from, to)) { "$from $to" }

		// NOTE: from and to CAN be null - this is intentional and matches Java behavior
		// getSegment() returns null when no segment exists (e.g., InOut.getFollowingSegment)
		// The Java version did not check for nulls here

		if (methodName == IS_SET_UP_PATH) {
			// Java: return separator.getFollowingSegment(from) == to;
			// NOTE: getFollowingSegment accepts nullable Segment, so no null check needed
			return separator.getFollowingSegment(from) === to
		} else if (methodName == CANCEL_PATH_SETUP) {
			// Java: separator.cancelPathSetup(from, to);
			separator.cancelPathSetup(from, to)
		} else if (methodName == SET_UP_PATH) {
			// Java: if (!(separator instanceof RailSemaphore)) separator.setUpPath(from, to, ...);
			// CRITICAL FIX: Java has NO null check here!
			// setUpPath is called via reflection on Track objects (see pathIterating method)
			// The segments may be null in certain network configurations
			// Passing nulls to setUpPath is INTENTIONAL - it's up to the implementation to handle
			if (separator !is RailSemaphore) {
				logger.debug {
					"SET_UP_PATH: setUpPath(from=$from, to=$to, speed=${separator.allowedSpeed()}) on $separator"
				}
				separator.setUpPath(from, to, separator.allowedSpeed())
			}
			val following = separator.getFollowingSegment(from)
			logger.debug { "SET_UP_PATH: getFollowingSegment($from) returned $following, expected $to" }
			assert(following === to) { "Separator $separator: getFollowingSegment($from) returned $following but expected $to" }
		} else if (methodName == IS_FREE_FROM) {
			// Java: //EMPTY
			// Intentionally empty - segments can be null, no action needed
		} else {
			throw IllegalArgumentException("wrong method name")
		}
		return true
	}

	@Throws(PathSeparatorChangeException::class)
	private fun setUpSemaphores(sep: PathSeparator) {
		// ukolem je natavit zpetnym pruchodem rychlosti semaforu podle vyhybek
		var previousSwitch: RailSwitch? = null
		var previousTrack: Track? = null
		val iterator = getIterator(getSecondEnd(sep))
		while (iterator.hasNext()) {
			val element = iterator.next()
			if (element is RailSwitch) {
				previousSwitch = element
			} else if (element is OrientedPathSeparator) {
				if (previousTrack == null) continue
				val semaphore = element
				if (context.isSeparatorInDirection(semaphore, previousTrack, null)) {
					val speed = previousSwitch?.allowedSpeed() ?: PathElement.ABSOLUTE_MAX_SPEED
					val segment = context.getSegment(semaphore, null, previousTrack)
					val segment2 = context.getSegment(semaphore, previousTrack, null)
					semaphore.setUpPath(segment, segment2, speed)
					previousSwitch = null
				}
			} else {
				previousTrack = Util.assertInstanceOf(Track::class.java, element)
			}
		}
		context.report("", this, ReportType.PATH_SETTING)
		assert(maxSpeed(sep) >= PathElement.MINIMAL_MAX_SPEED) { maxSpeed(sep) }
	}

	@Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE", "UNCHECKED_CAST")
	protected fun getIterator(sep: PathSeparator): Iterator<PathElement> {
		if (!isEnd(sep)) throw IllegalArgumentException("Is not end of abstrPath")
		if (sep == getFirst()) return iterator() as Iterator<PathElement>
		assert(sep == getLast())
		return (descendingIterator() as Iterator<PathElement>?)!!
	}

	override fun equalsWithElements(path: Path): Boolean {
		if (path === this) return true
		if (size != path.size) return false
		if (size == 0) return true

		val thisIt = this.iterator()
		val pathIt = path.iterator()

		while (thisIt.hasNext()) {
			assert(pathIt.hasNext())
			val thisNext = thisIt.next()
			val pathNext = pathIt.next()
			assert(thisNext != null)
			if (thisNext != pathNext) return false
		}

		return !pathIt.hasNext()
	}

	/**
	 * @return context getter
	 */
	fun getContext(): SimulationContext = context

	override fun reversePath(): Path {
		val arrayPath = ArrayPath(getContext())
		val iter = descendingIterator()
		while (iter.hasNext()) {
			arrayPath.addLast(iter.next())
		}
		return arrayPath
	}
}
