/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: PathCandidate builders for the route-scoring tests
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.vutbr.fit.interlockSim.context.navigation.PathCandidate
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.mockk.mockk

/**
 * [count] relaxed [TrackSection] mocks.
 *
 * The route-scoring rules count sections; they never look inside one. So a candidate of the right
 * length is all a scoring test needs.
 */
fun testSections(count: Int): List<TrackSection> = List(count) { mockk<TrackSection>(relaxed = true) }

/**
 * A [PathCandidate] built from the three numbers the scoring rules actually read.
 *
 * `CandidatePathRuleEngineTest` and `DispatchDecisionSp2b5Test` each declared this builder and
 * [testSections] privately, character for character (Issue #955, cluster C6).
 */
fun testCandidate(
	sectionCount: Int = 1,
	switchMovementCount: Int = 0,
	conflictRiskWeight: Double = 0.0
): PathCandidate =
	PathCandidate(
		sections = testSections(sectionCount),
		switchMovementCount = switchMovementCount,
		conflictRiskWeight = conflictRiskWeight
	)
