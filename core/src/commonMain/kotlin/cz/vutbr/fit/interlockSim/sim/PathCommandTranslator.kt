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
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
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
 *    [cz.vutbr.fit.interlockSim.objects.core.StaticTrack.getSecondEnd] — the same section
 *    walk used by
 *    [cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator]'s
 *    `countSwitchMovements`, with one deliberate difference in start handling (see step 2).
 * 2. For each **mid-path** exit separator that is a [DynamicRailSwitch] **and** is not the
 *    terminal separator (a next section must exist), compute the required
 *    [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf] via
 *    [SimulationContext.getSegment] and emit [DispatchDecision.SetSwitchPosition].
 *    The [start] separator is **never** configured, even when it is a switch: this is the
 *    dispatcher's *permissive request*, which assumes the train already occupies [start],
 *    so aligning a switch the train sits on is the interlocking's/train's responsibility —
 *    not part of this request. (`countSwitchMovements` counts the start separator and
 *    `configureSwitchesInPath` configures it; this translator intentionally does not.)
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
	 * Switch commands are generated for every **mid-path** [DynamicRailSwitch] encountered
	 * during path traversal — i.e. switches the train passes *through*. The start separator
	 * and the terminal separator are both excluded (the start is the train's current position
	 * and aligning it is the interlocking's responsibility; the terminal has no outgoing
	 * section to route to). Each command carries the
	 * [cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf] required to align the switch
	 * with the chosen path.
	 *
	 * An entry-signal command is added at the end if [start] is (or wraps) a
	 * [RailSemaphore]. The requested aspect is always [Signal.FREE]; the interlocking
	 * downgrades it according to the route and block conditions.
	 *
	 * @param trainId   Identifier of the train that will use this path. Included in log
	 *   messages and decision [DispatchDecision.rationale] strings.
	 * @param start     The separator at which traversal begins — typically a
	 *   [DynamicRailSemaphore] or a [cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut].
	 *   May also be a [DynamicRailSwitch]; in that case the entry switch is intentionally not
	 *   reconfigured (see the Algorithm section above).
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
		context: SimulationContext
	): List<DispatchDecision> {
		val switchCommands = mutableListOf<DispatchDecision.SetSwitchPosition>()
		val sections = candidate.sections

		// The start separator is never reconfigured (see KDoc): if the train is sitting on a
		// switch, aligning it is the interlocking's/train's responsibility, not this request.
		val staticStart = CellUtilities.assertNodeCell(start)
		if (staticStart is RailSwitch) {
			logger.debug {
				"PathCommandTranslator: start ${staticStart.getName()} is a switch — entry " +
					"switch intentionally not reconfigured (train's current position); only " +
					"mid-path switches are commanded for $trainId"
			}
		}

		// Walk sections from `start` using getSecondEnd — same section walk as
		// countSwitchMovements in DefaultTopologyNavigator (start handling differs: the start
		// separator is not reconfigured here). currentSeparator tracks position.
		var currentSeparator: PathSeparator = start

		for (index in sections.indices) {
			val section = sections[index]
			// getSecondEnd() always returns the STATIC separator (see AbstractTrack.getSecondEnd);
			// convert to its Dynamic wrapper — required by context.getSegment() below and by the
			// `is DynamicRailSwitch` check, same pattern as PathInfoBuilder.buildFullPath.
			val nextSeparator = context.toDynamic(section.getSecondEnd(currentSeparator))
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
								rationale = listOf("SP2b.3: switch on selected path for $trainId")
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
						rationale = listOf("SP2b.3: entry signal on selected path for $trainId")
					)
				else -> {
					val staticCell = CellUtilities.assertNodeCell(start)
					if (staticCell is RailSemaphore) {
						DispatchDecision.SetSignalAspect(
							semaphoreName = staticCell.getName(),
							signal = Signal.FREE,
							rationale = listOf("SP2b.3: entry signal on selected path for $trainId")
						)
					} else {
						null
					}
				}
			}

		return switchCommands + listOfNotNull(signalCommand)
	}
}
