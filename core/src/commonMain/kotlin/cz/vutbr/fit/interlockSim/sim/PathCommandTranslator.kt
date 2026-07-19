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
import cz.vutbr.fit.interlockSim.context.navigation.PathCandidate
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Translates a [PathCandidate] selected by [CandidatePathRuleEngine] into an ordered
 * list of dispatcher commands — switch-position changes followed by an entry-signal
 * aspect change — that must be applied through the interlocking before the path is
 * formally reserved.
 *
 * ## SP2b.3 (Issue #558)
 *
 * SP2b.3 is the "command generation" step in the SP2b dispatcher pipeline:
 * ```
 * TopologyNavigator.findCandidatePaths()
 *   → CandidatePathRuleEngine.select()       [SP2b.2 — Issue #557]
 *   → PathCommandTranslator.translate()      [SP2b.3 — Issue #558]
 *   → SetSwitchPosition × N  ─╮
 *   → SetSignalAspect          ╰─ issued through interlocking (re-validates safety)
 * ```
 *
 * ## Algorithm
 *
 * 1. Walk [PathCandidate.sections] from [start] using
 *    [cz.vutbr.fit.interlockSim.objects.core.StaticTrack.getSecondEnd] — the same
 *    traversal used by
 *    [cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator]'s
 *    `countSwitchMovements`.
 * 2. For each exit separator that is a [DynamicRailSwitch] **and** is not the terminal
 *    separator (a next section must exist), compute the required
 *    [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf] via
 *    [SimulationContext.getSegment] and emit [DispatchDecision.SetSwitchPosition].
 * 3. If [start] resolves to a [RailSemaphore] (or is already a [DynamicRailSemaphore]),
 *    emit [DispatchDecision.SetSignalAspect] with [Signal.FREE] as the maximum permissive
 *    aspect — the interlocking independently limits it according to the route conditions.
 *
 * ## Ordering
 *
 * Switch commands are emitted first (in traversal order), followed by the entry-signal
 * command. This ordering satisfies the ESA-11 C2 requirement: switches must be in the
 * correct position before a route can be requested.
 *
 * ## Safety
 *
 * Commands are only **generated** here — they are not applied. Applying them through
 * [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort] is the caller's responsibility.
 * The actuator port re-validates each command against live occupancy and locking state
 * before acting — this is the "interlocking re-validates safety" guarantee stated in the
 * issue.
 *
 * @see CandidatePathRuleEngine
 * @see DispatchDecision.SetSwitchPosition
 * @see DispatchDecision.SetSignalAspect
 * @since Issue #558 (SP2b.3 — Goal 10)
 */
object PathCommandTranslator {
	/**
	 * Translate a selected [PathCandidate] into switch-position and entry-signal commands.
	 *
	 * Switch commands are generated for every [DynamicRailSwitch] encountered during
	 * path traversal (excluding the terminal separator, which has no outgoing section to
	 * route to). Each command carries the [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf]
	 * required to align the switch with the chosen path.
	 *
	 * An entry-signal command is added at the end if [start] is (or wraps) a
	 * [RailSemaphore]. The requested aspect is always [Signal.FREE]; the interlocking
	 * downgrades it according to the route and block conditions.
	 *
	 * @param trainId   Identifier of the train that will use this path. Included in log
	 *   messages and decision [DispatchDecision.rationale] strings.
	 * @param start     The separator at which traversal begins — typically a
	 *   [DynamicRailSemaphore] or a [cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut].
	 * @param candidate The selected path; [PathCandidate.sections] drives traversal.
	 * @param context   Live simulation context required for switch segment lookup via
	 *   [SimulationContext.getSegment].
	 * @return Ordered [DispatchDecision] list: one [DispatchDecision.SetSwitchPosition] per
	 *   switch traversed (in path order), followed by one [DispatchDecision.SetSignalAspect]
	 *   if [start] is a semaphore. Returns an empty list when no switches are traversed and
	 *   [start] is not a semaphore.
	 */
	fun translate(
		trainId: String,
		start: PathSeparator,
		candidate: PathCandidate,
		context: SimulationContext,
	): List<DispatchDecision> {
		val switchCommands = mutableListOf<DispatchDecision.SetSwitchPosition>()
		val sections = candidate.sections

		// Walk sections from `start` using getSecondEnd — mirrors countSwitchMovements in
		// DefaultTopologyNavigator.  prevSection / currentSeparator track position.
		var currentSeparator: PathSeparator = start

		for (index in sections.indices) {
			val section = sections[index]
			val nextSeparator = section.getSecondEnd(currentSeparator)
			val nextSection = sections.getOrNull(index + 1)

			// Only configure switches that the train passes THROUGH.
			// A switch at the terminal position has no outgoing section → skip.
			if (nextSection != null && nextSeparator is DynamicRailSwitch) {
				val from = context.getSegment(nextSeparator, section, nextSection)
				val to = context.getSegment(nextSeparator, nextSection, section)

				if (from != null && to != null) {
					val conf = nextSeparator.staticRef.confs.get(from, to)
					if (conf != null) {
						logger.debug {
							"PathCommandTranslator: switch ${nextSeparator.staticRef.getName()} → $conf " +
								"for train $trainId (from=${from.name}, to=${to.name})"
						}
						switchCommands.add(
							DispatchDecision.SetSwitchPosition(
								switchName = nextSeparator.staticRef.getName(),
								position = conf,
								rationale = "SP2b.3: switch on selected path for $trainId",
							)
						)
					} else {
						logger.warn {
							"PathCommandTranslator: no conf for switch ${nextSeparator.staticRef.getName()} " +
								"(from=${from.name}, to=${to.name}) — SetSwitchPosition skipped for $trainId"
						}
					}
				} else {
					logger.debug {
						"PathCommandTranslator: segments undeterminable for switch " +
							"${nextSeparator.staticRef.getName()} — SetSwitchPosition skipped for $trainId " +
							"(from=${from?.name ?: "null"}, to=${to?.name ?: "null"})"
					}
				}
			}

			currentSeparator = nextSeparator
		}

		// Emit SetSignalAspect for the entry semaphore if start is (or wraps) a RailSemaphore.
		// Signal.FREE = maximum permissive aspect; the interlocking caps it per route conditions.
		// Dynamic wrapper case uses the wrapper's name directly; static fallback uses NodeCell.getName()
		// to avoid toDynamic() which requires initializeDynamicMapping() (normally called inside run()).
		val signalCommand: DispatchDecision.SetSignalAspect? =
			when {
				start is DynamicRailSemaphore ->
					DispatchDecision.SetSignalAspect(
						semaphoreName = start.name,
						signal = Signal.FREE,
						rationale = "SP2b.3: entry signal on selected path for $trainId",
					)
				else -> {
					val staticCell = CellUtilities.assertNodeCell(start)
					if (staticCell is RailSemaphore) {
						DispatchDecision.SetSignalAspect(
							semaphoreName = staticCell.getName(),
							signal = Signal.FREE,
							rationale = "SP2b.3: entry signal on selected path for $trainId",
						)
					} else {
						null
					}
				}
			}

		return switchCommands + listOfNotNull(signalCommand)
	}
}
