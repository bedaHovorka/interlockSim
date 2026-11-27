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

import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading

/**
 * The guards a dispatcher's route request must pass before it reaches the kernel: the one source
 * the request-route paths share (Issue #1414, spec SP3.1 #1456 Q6). A sibling of
 * [ReservationTargetPolicy], so the policy stays a pure function over candidate lists.
 *
 * Today it holds the origin rule, [legalOriginsOf], called by `:dispatcher-agent`'s
 * `RequestRouteTool` and `ActionValidator`. A planned follow-up (#1461) adds the target guard
 * (`admits`) here.
 *
 * ## Not a copy of `SwitchLegalPairs`
 *
 * [SwitchLegalPairs] answers a different question — "does this InOut pair have any switch-legal
 * route at all" — for the generator, through `RouteFinder.isRouteAvailable`, before a train exists.
 * The rules here answer "where may *this* train's next route start (and end) now", from the
 * train's perceived position. Neither can be derived from the other, so they stay separate.
 *
 * ## The front-block origin guard (1B-SP12.9)
 *
 * Demand 1B-SP12.9 asked for a stricter kernel guard beside
 * `RouteCandidateScreen.rejectNonContiguousStart`: the origin must bound the train's *front*
 * block. [legalOriginsOf] covers this for **one case only: a train standing at its stop signal**.
 * Such a train reports the block it has traversed up to that signal as its front section
 * (Issue #788), so [LegalOrigins.AtSignal] is the front block's far boundary there (pinned by
 * `StandingOriginBoundsFrontBlockTest`). A train without a front section is held to its entry.
 * Other cases are not covered:
 * - a block can also be bounded by a switch;
 * - a train can stop short of its signal (a mid-block standstill, the ownership-conflict window),
 *   where the signal ahead does not bound its front block;
 * - for a moving train the front boundary is ambiguous mid-transition, and a forward extension
 *   legitimately starts at the held route's end, beyond the front block.
 * In all of these the kernel's contiguity guard ("any footprint block") stays the rule on purpose.
 * A kernel front-block guard would refuse legitimate extensions there and adds nothing at a stop
 * signal, so none is added.
 *
 * @since Issue #1414
 */
object RouteRequestAdmission {
	/**
	 * The facts [legalOriginsOf] reads about one train. The snapshot overload builds them from a
	 * [SimulationSnapshot]; a caller that holds the same facts in another projection (the
	 * dispatcher observation's `TrainView`) builds them directly, so both apply one rule.
	 *
	 * @property isQueued The train waits in the admission queue (queued trains are not in the snapshot).
	 * @property isActive The train is approved and has a perception.
	 * @property frontSectionName The block holding the train's front, `null` before it has entered.
	 * @property timetableOriginName The InOut the train's timetable enters at, if known.
	 * @property signalAheadName The next semaphore ahead, `null` when no path is set.
	 * @property velocity The train's speed in m/s.
	 */
	data class OriginFacts(
		val isQueued: Boolean,
		val isActive: Boolean,
		val frontSectionName: String?,
		val timetableOriginName: String?,
		val signalAheadName: String?,
		val velocity: Double
	)

	/** Where a train's next route may start; [accepts] is the membership test both callers apply. */
	sealed interface LegalOrigins {
		/** Every origin this rule accepts. */
		val names: Set<String>

		/** Whether a route starting at [fromEndpointName] satisfies this rule. */
		fun accepts(fromEndpointName: String): Boolean = fromEndpointName in names

		/** A queued train has not entered: its route starts at an InOut. */
		data class AtInOut(
			override val names: Set<String>
		) : LegalOrigins

		/**
		 * An approved train that has not entered and is not standing at a signal: its timetable
		 * entry [entryInOut], or the [signalAhead] its entry route already reaches.
		 */
		data class AtEntry(
			val entryInOut: String,
			val signalAhead: String?
		) : LegalOrigins {
			override val names: Set<String> get() = setOfNotNull(entryInOut, signalAhead)
		}

		/**
		 * A train standing at [signal]: its route starts there. [entryInOut] is set when the train
		 * has not entered yet (its entry route reaches [signal]); the request-route tool words its
		 * refusal from it.
		 */
		data class AtSignal(
			val signal: String,
			val entryInOut: String? = null
		) : LegalOrigins {
			override val names: Set<String> get() = setOf(signal)
		}
	}

	/**
	 * The legal origins of [trainId]'s next route, read from [snapshot]; `null` when no origin rule
	 * applies (a moving train on the network, or a train neither queued nor active).
	 *
	 * [isQueued] comes from the caller, because the snapshot lists approved trains only.
	 * [inOutNames] are the network's InOuts; without them the queued rule is inert.
	 */
	fun legalOriginsOf(
		snapshot: SimulationSnapshot,
		trainId: String,
		isQueued: Boolean,
		inOutNames: Set<String>
	): LegalOrigins? {
		val perception = snapshot.trainPerception(trainId)
		val facts =
			OriginFacts(
				isQueued = isQueued,
				isActive = perception != null,
				frontSectionName = perception?.frontSectionName,
				timetableOriginName = snapshot.trainTimetable(trainId)?.originInOutName,
				signalAheadName = perception?.signalAheadName,
				velocity = perception?.velocity ?: 0.0
			)
		return legalOriginsOf(facts, inOutNames)
	}

	/**
	 * The rule itself, over [facts]:
	 *
	 * - a queued, not yet active train: the [inOutNames] ([LegalOrigins.AtInOut]);
	 * - an active train without a front section and with a timetable origin: that origin plus the
	 *   signal ahead ([LegalOrigins.AtEntry]), narrowed to the signal when the train stands at it
	 *   ([LegalOrigins.AtSignal] with `entryInOut`);
	 * - any other active train standing at a signal ([standingAtSignalName]): that signal;
	 * - otherwise `null`: a moving train's origin is the kernel's contiguity guard's business.
	 */
	fun legalOriginsOf(
		facts: OriginFacts,
		inOutNames: Set<String>
	): LegalOrigins? {
		if (!facts.isActive) {
			return if (facts.isQueued && inOutNames.isNotEmpty()) LegalOrigins.AtInOut(inOutNames) else null
		}
		val standing = standingSignalName(facts.velocity, facts.signalAheadName)
		if (facts.frontSectionName == null) {
			val entry = facts.timetableOriginName?.takeIf { it.isNotBlank() }
			if (entry != null) {
				return if (standing != null) {
					LegalOrigins.AtSignal(standing, entryInOut = entry)
				} else {
					LegalOrigins.AtEntry(entry, facts.signalAheadName?.takeIf { it.isNotBlank() })
				}
			}
		}
		return standing?.let { LegalOrigins.AtSignal(it) }
	}
}

/**
 * The "standing at signal" predicate over one train's captured perception: the name of the signal
 * ahead when the train is stopped, `null` when it is moving or has no signal ahead. One definition
 * for every consumer that has to agree — [RouteRequestAdmission.legalOriginsOf], and in
 * `:dispatcher-agent` the "standing at signal" prompt clause and `NextSectionOfferCounter`'s
 * per-train standing count — so the prompt and the origin guard can never disagree about whether a
 * train is stopped in front of a named signal.
 *
 * A train with **no** perception is not standing either; deciding that is the caller's job, so
 * this sees one perception, not a train. Moved from `:dispatcher-agent` in Issue #1414.
 */
fun TrainPerceptionReading.standingAtSignalName(): String? = standingSignalName(velocity, signalAheadName)

private fun standingSignalName(
	velocity: Double,
	signalAheadName: String?
): String? = signalAheadName?.takeIf { velocity == 0.0 && it.isNotBlank() }
