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

import cz.vutbr.fit.interlockSim.context.navigation.PathCandidate
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Deterministic rule engine (SP2b.2, Issue #557) that ranks candidate routes for the
 * [RuleBasedDispatcher].
 *
 * ## Pipeline position
 *
 * The dispatcher pipeline described by Issue #557 is:
 *
 * ```
 * PathFinder (Goal 2)          →  ConflictResolver (Goal 9)      →  this engine
 * enumerate candidate paths       conflict-aware selection          score & rank
 * (TopologyNavigator                (attach conflictRiskWeight        (priority rules)
 *  .findCandidatePaths)              via assignConflictRisk)
 * ```
 *
 * 1. **Enumerate** — Goal 2 pathfinding
 *    ([cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator.findCandidatePaths])
 *    produces the [PathCandidate]s, each carrying a pre-computed
 *    [PathCandidate.switchMovementCount] and a [PathCandidate.conflictRiskWeight] hook
 *    (initially `0.0` at the static topology layer).
 * 2. **Consult conflict awareness** — a dynamic layer attaches a conflict-risk score to
 *    each candidate.  [assignConflictRisk] derives that score from current block
 *    occupancy (the fraction of the path's sections that are busy), matching the design
 *    documented on [PathCandidate.conflictRiskWeight].  This is the route-selection
 *    counterpart to Goal 9's reactive
 *    [cz.vutbr.fit.interlockSim.sim.conflict.ConflictResolver] machinery, which resolves
 *    conflicts *after* they are detected.
 * 3. **Score & rank** — [rank] / [select] order the candidates by the configured
 *    [priority] rules.
 *
 * ## Priority rules
 *
 * The three priority rules from Issue #557 are modelled by [Rule]:
 *
 * - [Rule.LOWEST_CONFLICT_RISK] — prefer the route least likely to conflict with other
 *   trains (lowest [PathCandidate.conflictRiskWeight]).
 * - [Rule.SHORTEST_PATH] — prefer the route traversing the fewest track sections
 *   ([PathCandidate.sections] size).
 * - [Rule.FEWEST_SWITCH_MOVEMENTS] — prefer the route requiring the fewest switch
 *   position changes ([PathCandidate.switchMovementCount]).
 *
 * The rules are applied lexicographically in [priority] order: the first rule is the
 * primary sort key, the next breaks ties, and so on.  The default order
 * ([DEFAULT_PRIORITY]) leads with conflict avoidance, which is the paramount concern of
 * a railway dispatcher (a safe route that is slightly longer is always preferred over a
 * short route that risks a conflict); shortest path and then fewest switch movements
 * break remaining ties.  Callers that want a different emphasis (for example the exact
 * listing order in Issue #557) pass their own [priority].
 *
 * ## Determinism (Goal 10 Stage A3)
 *
 * [rank] is a pure function and uses a **stable** sort, so candidates that compare equal
 * under every configured rule retain their input order.  Given a deterministic candidate
 * enumeration (as Goal 2 pathfinding provides for a fixed topology), the engine produces
 * identical rankings across consecutive runs — a prerequisite for the A3 deterministic
 * gate on `vyhybna.xml`.
 *
 * @property priority Ordered, non-empty, duplicate-free list of [Rule]s applied
 *   lexicographically.  Defaults to [DEFAULT_PRIORITY].
 *
 * @see RuleBasedDispatcher
 * @see PathCandidate
 * @since Issue #557 (SP2b.2 — Goal 10)
 */
class CandidatePathRuleEngine(
	val priority: List<Rule> = DEFAULT_PRIORITY
) {
	init {
		require(priority.isNotEmpty()) { "priority must not be empty" }
		require(priority.toSet().size == priority.size) {
			"priority must not contain duplicate rules, got: $priority"
		}
	}

	/**
	 * Lexicographic comparator derived once from [priority] (the engine is stateless and
	 * [priority] is immutable), so [rank] does not rebuild it on every call.
	 */
	private val comparator: Comparator<PathCandidate> =
		priority.map(::comparatorFor).reduce { acc, next -> acc.then(next) }

	/**
	 * A single deterministic scoring dimension.  Each rule sorts ascending — smaller is
	 * better — so lower conflict risk, fewer sections, and fewer switch movements all rank
	 * a candidate higher.
	 */
	enum class Rule {
		/** Lowest [PathCandidate.conflictRiskWeight] first. */
		LOWEST_CONFLICT_RISK,

		/** Fewest [PathCandidate.sections] first (shortest route by section count). */
		SHORTEST_PATH,

		/** Fewest [PathCandidate.switchMovementCount] first. */
		FEWEST_SWITCH_MOVEMENTS
	}

	/**
	 * Rank [candidates] from best (index 0) to worst according to [priority].
	 *
	 * The input is never mutated; the returned list is a new, stably-sorted copy.  An
	 * empty or single-element input is returned as-is (as a copy).
	 *
	 * @param candidates The candidate routes to rank (typically the output of Goal 2
	 *   pathfinding, optionally enriched via [assignConflictRisk]).
	 * @return A new list ordered best-first.
	 */
	fun rank(candidates: List<PathCandidate>): List<PathCandidate> {
		if (candidates.size <= 1) {
			return candidates.toList()
		}
		return candidates.sortedWith(comparator)
	}

	/**
	 * Return the single best candidate according to [priority], or `null` when
	 * [candidates] is empty.
	 *
	 * @param candidates The candidate routes to choose from.
	 * @return The best candidate, or `null` when there is nothing to choose.
	 */
	fun select(candidates: List<PathCandidate>): PathCandidate? {
		val (best, _) = selectWithRationale(candidates)
		return best
	}

	/**
	 * Return the best candidate together with a human-readable rationale list
	 * describing the rule evaluations that produced the selection (SP2b.5,
	 * Issue #560).
	 *
	 * The rationale list always contains at least one entry:
	 * - If [candidates] is empty: a single entry explaining no path was available.
	 * - Otherwise: one entry describing the active [priority] rules and one entry
	 *   summarising the selected candidate's key cost metrics, plus — when more than
	 *   one candidate was ranked — a third entry
	 *   `"Ranked N candidate path(s); top-ranked selected"`.
	 *
	 * The list is therefore 1 entry (empty input), 2 entries (single candidate), or
	 * 3 entries (multiple candidates).  The returned [PathCandidate] is `null` when
	 * [candidates] is empty.
	 *
	 * @param candidates The candidate routes to choose from.
	 * @return A pair of `(best candidate or null, non-empty rationale list)`.
	 *
	 * @since Issue #560 (SP2b.5 — Goal 10)
	 */
	fun selectWithRationale(candidates: List<PathCandidate>): Pair<PathCandidate?, List<String>> {
		if (candidates.isEmpty()) {
			return null to listOf("No candidate paths available")
		}
		val ranked = rank(candidates)
		val best = ranked.first()
		logger.debug {
			"CandidatePathRuleEngine selected route with ${best.sections.size} section(s), " +
				"switchMovementCount=${best.switchMovementCount}, conflictRiskWeight=${best.conflictRiskWeight}"
		}
		val rationale =
			buildList {
				add(
					"Rules (priority): ${priority.joinToString(" → ") { it.name }}"
				)
				add(
					"Selected: ${best.sections.size} section(s), " +
						"switches=${best.switchMovementCount}, " +
						"conflictRisk=${best.conflictRiskWeight}"
				)
				if (ranked.size > 1) {
					add("Ranked ${ranked.size} candidate path(s); top-ranked selected")
				}
			}
		return best to rationale
	}

	/**
	 * Enrich [candidates] with a dynamic conflict-risk score derived from current block
	 * occupancy — the conflict-aware selection step of the SP2b.2 pipeline.
	 *
	 * For each candidate the new [PathCandidate.conflictRiskWeight] is the fraction of its
	 * [PathCandidate.sections] for which [isSectionBusy] returns `true` (a value in
	 * `0.0..1.0`); a candidate with no sections is assigned `0.0`.  This matches the
	 * design documented on [PathCandidate.conflictRiskWeight] ("a value derived from
	 * current block-occupation state (e.g. fraction of occupied/reserved blocks in the
	 * path)").
	 *
	 * The caller supplies [isSectionBusy] so the engine stays decoupled from the dynamic
	 * simulation state: pass a predicate that reports whether a section's block is
	 * currently occupied or reserved by another train.
	 *
	 * @param candidates The candidate routes to enrich (never mutated).
	 * @param isSectionBusy Predicate returning `true` when a section is unavailable
	 *   (occupied or reserved) for the train being routed.
	 * @return New candidate copies with an updated [PathCandidate.conflictRiskWeight].
	 */
	fun assignConflictRisk(
		candidates: List<PathCandidate>,
		isSectionBusy: (TrackSection) -> Boolean
	): List<PathCandidate> =
		candidates.map { candidate ->
			val total = candidate.sections.size
			val risk =
				if (total == 0) {
					0.0
				} else {
					candidate.sections.count(isSectionBusy).toDouble() / total
				}
			candidate.copy(conflictRiskWeight = risk)
		}

	private fun comparatorFor(rule: Rule): Comparator<PathCandidate> =
		when (rule) {
			Rule.LOWEST_CONFLICT_RISK -> compareBy { it.conflictRiskWeight }
			Rule.SHORTEST_PATH -> compareBy { it.sections.size }
			Rule.FEWEST_SWITCH_MOVEMENTS -> compareBy { it.switchMovementCount }
		}

	companion object {
		/**
		 * Default priority order: conflict avoidance first, then shortest path, then
		 * fewest switch movements.  See the class KDoc for the rationale.
		 */
		val DEFAULT_PRIORITY: List<Rule> =
			listOf(Rule.LOWEST_CONFLICT_RISK, Rule.SHORTEST_PATH, Rule.FEWEST_SWITCH_MOVEMENTS)

		private val logger = KotlinLogging.logger {}
	}
}
