/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.sim.Station
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The structural journey of a station, computed before the run (Issue #1301).
 *
 * [KoogAgentFactory.createAgent] runs lazily, on the dispatcher thread, in the first `plan()` —
 * while the simulation already runs. The reservation target query the journey is picked from
 * mutates navigation state confined to the simulation thread, so it must not run there. The
 * wiring layer computes this once, on the thread that builds the run and before it starts
 * (`DispatcherAgentModule`'s `KoogAgentFactory` binding, resolved by the example setup), and the
 * factory renders it as is.
 *
 * @property journey The picked journey, or `null` when no InOut qualifies — a computed absence,
 *   distinct from "not prepared".
 */
class PreparedEntryJourney(
	val journey: EntryJourney?
) {
	companion object {
		/** Computes the journey of [env] now, on the calling thread; call it before the run starts. */
		fun of(env: SimulationEnvironment): PreparedEntryJourney = PreparedEntryJourney(EntryJourneyPicker.journeyOf(env))
	}
}

/**
 * Picks the structural worked example of [WorkedExampleSource.STRUCTURAL] (Issue #1301): one real
 * journey from a station's home signal through the next signal to an exit, every hop a target the
 * reservation target query offers.
 *
 * Calling the reservation target query here is allow-listed in `:core`'s
 * `TargetSelectionSingleEntryTest` (`ALLOWED_TARGET_API_CALLERS`, #1152) as a read-only, pre-run
 * reader of the structure for the prompt's worked example: it chooses no train's target. Never call
 * it per tick or with an owner — the live pick stays `ReservationTargetPolicy.pick`.
 */
internal object EntryJourneyPicker {
	/**
	 * One reachable next target of a hop origin, reduced to what the pick rule reads (Issue #1301).
	 *
	 * @property name The target's name, as the STATION TOPOLOGY lists it.
	 * @property isSignal `true` for a semaphore, `false` for an InOut.
	 */
	internal data class JourneyTarget(
		val name: String,
		val isSignal: Boolean
	)

	/**
	 * The structural journey of [env] (Issue #1301), with the [Station] derived here: see
	 * [journeyOf] with a station. Runs the reservation target query, so call it before the
	 * simulation starts ([PreparedEntryJourney]).
	 */
	fun journeyOf(env: SimulationEnvironment): EntryJourney? =
		StationTopologySerializer.stationOrNull(env)?.let { journeyOf(env, it) }

	/**
	 * The station's structural journey (Issue #1301): home signals from
	 * [Station.entryExitBlocks] (the single facing-away signal only; a misdirected signal is never an
	 * origin), targets from the reservation target query with no owner — the NEXT SECTION source.
	 * Candidates are taken in the query's order whatever their current availability: this is static
	 * structure, read once before the run. Fail-soft: any failure yields `null` and one WARN.
	 *
	 * Not thread-safe against a running simulation: the query walks and caches routes in navigation
	 * state confined to the simulation thread. Call it before the run starts.
	 */
	fun journeyOf(
		env: SimulationEnvironment,
		station: Station
	): EntryJourney? =
		runCatching {
			val query = env.getRoutingServices().getReservationTargetQuery()
			val semaphoresByName: Map<String, DynamicRailSemaphore> =
				station.signals.filter { it.name.isNotBlank() }.associateBy { it.name }
			val homeSignalByInOut: Map<String, String?> =
				station.entryExitBlocks
					.flatMap { block ->
						val home =
							block.signals
								.singleOrNull()
								?.name
								?.takeIf { it.isNotBlank() }
						block.inOuts.map { it.name to home }
					}.toMap()
			pickEntryJourney(homeSignalByInOut) { origin ->
				val semaphore = semaphoresByName[origin] ?: return@pickEntryJourney emptyList()
				query.findReservationTargetCandidates(semaphore, null).mapNotNull { candidate ->
					when (val separator = candidate.separator) {
						is DynamicRailSemaphore -> separator.name.takeIf { it.isNotBlank() }?.let { JourneyTarget(it, true) }
						is DynamicInOut -> separator.name.takeIf { it.isNotBlank() }?.let { JourneyTarget(it, false) }
						else -> null
					}
				}
			}
		}.onFailure { logger.warn(it) { "EntryJourneyPicker: structural worked example not derived" } }
			.getOrNull()

	/**
	 * The pick rule of the structural worked example (Issue #1301), over plain names so the skip
	 * paths are testable without a network.
	 *
	 * Named InOuts in name order; the first whose home signal offers a signal candidate wins: S = the
	 * first signal candidate (query order), D = the first InOut candidate offered from S (`null` when
	 * S offers none: hop 1 alone). Without any such InOut, the first whose home signal offers an InOut
	 * gives the single hop home signal -> exit (a one-signal station). Otherwise `null` — never an
	 * alphabetical guess. One DEBUG line names the InOuts skipped.
	 *
	 * @param homeSignalByInOut InOut name to its home signal name, `null` without a home signal.
	 * @param targetsOf The next targets offered from a signal name, in query order.
	 */
	internal fun pickEntryJourney(
		homeSignalByInOut: Map<String, String?>,
		targetsOf: (String) -> List<JourneyTarget>
	): EntryJourney? {
		val entries = homeSignalByInOut.filterKeys { it.isNotBlank() }.toSortedMap()
		val skipped = mutableListOf<String>()
		val homeTargets = mutableMapOf<String, List<JourneyTarget>>()
		for ((inOut, home) in entries) {
			if (home == null) {
				skipped += "$inOut (no home signal)"
				continue
			}
			val targets = homeTargets.getOrPut(home) { targetsOf(home) }
			val next = targets.firstOrNull { it.isSignal }
			if (next == null) {
				skipped += "$inOut (home signal $home offers no signal)"
				continue
			}
			val destination = targetsOf(next.name).firstOrNull { !it.isSignal }?.name
			logSkipped(skipped)
			return EntryJourney(inOut, SignalId(home), SignalId(next.name), destination)
		}
		logSkipped(skipped)
		return entries.entries.firstNotNullOfOrNull { (inOut, home) ->
			home?.let { signal ->
				homeTargets.getValue(signal).firstOrNull { !it.isSignal }?.let { exit ->
					EntryJourney(inOut, SignalId(signal), nextSignal = null, destination = exit.name)
				}
			}
		}
	}

	private fun logSkipped(skipped: List<String>) {
		if (skipped.isNotEmpty()) {
			logger.debug { "EntryJourneyPicker: structural worked example skipped ${skipped.joinToString(", ")}" }
		}
	}
}
