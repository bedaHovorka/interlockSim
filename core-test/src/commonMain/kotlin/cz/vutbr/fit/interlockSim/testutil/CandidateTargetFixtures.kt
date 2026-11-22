/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: CandidateTarget factories for dispatcher tests
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.sim.CandidateTarget
import cz.vutbr.fit.interlockSim.sim.SeparatorKind

/**
 * A station-exit [CandidateTarget] from the Issue #970 observation vocabulary. [available] is
 * spelled at every call — the interesting case in a policy test is rarely the available one.
 */
fun inOutCandidate(name: String, available: Boolean): CandidateTarget =
	CandidateTarget(name, SeparatorKind.IN_OUT, available)

/**
 * A signal [CandidateTarget] from the Issue #970 observation vocabulary. [available] is spelled
 * at every call — the interesting case in a policy test is rarely the available one.
 */
fun semaphoreCandidate(name: String, available: Boolean): CandidateTarget =
	CandidateTarget(name, SeparatorKind.SEMAPHORE, available)

/**
 * The candidate list a test input carries when it only cares that a signal target exists:
 * a single available [semaphoreCandidate] for [name], or an empty list when [name] is `null`
 * ("no candidate at all"). Extracted 2026-10-11 from eight per-test spellings of the same
 * one-liner (Issue #1152 dedup pass).
 */
fun semaphoreCandidates(name: String?, available: Boolean = true): List<CandidateTarget> =
	listOfNotNull(name?.let { semaphoreCandidate(it, available) })
