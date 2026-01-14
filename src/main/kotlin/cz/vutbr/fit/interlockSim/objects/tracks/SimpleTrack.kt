/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.tracks

import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.Process
import java.util.IdentityHashMap

/**
 * Base implementation of {@link Track} and {@link TrackSection} and {@link TrackFacility}
 *
 * Represents common track unit
 */
abstract class SimpleTrack(
	end1: PathSeparator,
	end2: PathSeparator,
	private val length: Double,
	maxSpeed1: Double,
	maxSpeed2: Double
) : AbstractTrack(),
	TrackSection,
	TrackFacility {
	companion object {
		private val logger = KotlinLogging.logger {}

		// Track minimum length constant
		private const val MIN_LENGTH = Track.MIN_LENGTH
	}

	private val speeds: IdentityHashMap<PathSeparator, Double> = IdentityHashMap()
	private val ends: Array<PathSeparator>
	private var `in`: TrackOccupant? = null
	private var from: PathSeparator? = null
	private var state = TrackFacility.State.FREE

	init {
		if (length < MIN_LENGTH || maxSpeed1 < PathElement.MINIMAL_MAX_SPEED || maxSpeed2 < PathElement.MINIMAL_MAX_SPEED) {
			throw IllegalArgumentException("length or maxspeed is very small")
		}
		ends = arrayOf(end1, end2)
		speeds[end2] = maxSpeed2
		speeds[end1] = maxSpeed1
	}

	override fun enter(occupant: TrackOccupant) {
		logger.info {
			"${Process.time()} Block $this ENTRY: occupant=$occupant, state=$state->OCCUPIED"
		}
		if (`in` != null) {
			logger.error {
				"${Process.time()} CONFLICT: Block $this collision! Existing occupant=${`in`}, occupant=$occupant"
			}
		}
		requireSimulation(`in` == null) { "Track occupant collision - must be null on entry (shunting not implemented)" }
		assertGoodStateChange(TrackFacility.State.RESERVED, TrackFacility.State.OCCUPIED)
		`in` = occupant
		from = null
	}

	override fun leave(occupant: TrackOccupant) {
		logger.info {
			"${Process.time()} Block $this EXIT: occupant=$occupant, state=OCCUPIED->FREE"
		}
		requireSimulation(`in` === occupant) { "Track occupant mismatch on leave" }
		assertGoodStateChange(TrackFacility.State.OCCUPIED, TrackFacility.State.FREE)
		`in` = null
	}

	override fun isFreeFrom(seg: PathSeparator): Boolean {
		val isFree = state == TrackFacility.State.FREE
		logger.debug { "Track $this isFreeFrom check: from=$seg, state=$state, result=$isFree" }
		return isFree
	}

	override fun setUpPath(sep: PathSeparator) {
		logger.info {
			"${Process.time()} Block $this RESERVE: from=$sep, state=FREE->RESERVED"
		}
		if (state != TrackFacility.State.FREE) {
			logger.warn {
				"${Process.time()} CONFLICT: Block $this reservation rejected - state=$state, occupant=${`in`}, requested by=$sep"
			}
		}
		exeptionStateChange(TrackFacility.State.FREE, TrackFacility.State.RESERVED)
		from = sep
	}

	override fun isSetUpPath(sep: PathSeparator): Boolean {
		requireSimulationNotNull(sep) { "Path separator must not be null" }
		val isSetUp: Boolean
		if (state == TrackFacility.State.RESERVED) {
			isSetUp = sep === from
		} else {
			requireSimulation(from == null) { "From separator must be null when state is not RESERVED" }
			isSetUp = false
		}
		logger.debug { "Track $this isSetUpPath check: sep=$sep, state=$state, from=$from, result=$isSetUp" }
		return isSetUp
	}

	override fun cancelPathSetup(sep: PathSeparator) {
		logger.info {
			"${Process.time()} Block $this RELEASE: from=$sep, state=RESERVED->FREE"
		}
		exeptionStateChange(TrackFacility.State.RESERVED, TrackFacility.State.FREE)
		if (sep !== from) throw TrackOperationException("wrong end on cancel", this)
		from = null
	}

	private fun stateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	): Boolean {
		val ok = state == from
		if (ok) state = to
		return ok
	}

	@Throws(TrackOperationException::class)
	private fun exeptionStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	) {
		if (!stateChange(from, to)) {
			logger.error {
				"${Process.time()} CONFLICT: Block $this state violation - expected=$from, actual=$state, attempted=$to"
			}
			throw TrackOperationException(errorStateMessage(from), this)
		}
	}

	private fun errorStateMessage(from: TrackFacility.State): String = "Wrong state: $state , expected : $from"

	private fun assertGoodStateChange(
		from: TrackFacility.State,
		to: TrackFacility.State
	) {
		// mozna nekdy jina vyjimka...
		val stateChange = stateChange(from, to)
		requireSimulation(stateChange) { errorStateMessage(from) }
	}

	override fun length(): Double = length

	override fun getState(): TrackFacility.State = state

	override fun ends(): Array<PathSeparator> = ends

	override fun getTrackOccupant(): TrackOccupant {
		// Note: Track must be occupied when this method is called, but field is nullable for initialization
		// This violates the interface contract if called when track is free - requireSimulation checks this
		requireSimulationNotNull(`in`) { "Track occupant should not be null - must call when track is OCCUPIED" }
		return `in`!!
	}

	override fun maxSpeed(from: PathSeparator?): Double {
		requireSimulation(isEnd(from!!)) { "Path separator must be an end of this track" }
		return speeds[from]!!
	}
}
