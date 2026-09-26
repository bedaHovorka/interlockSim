/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The distance to the signal ahead that a train standing at a mid-leg switch reports while an
 * ownership conflict holds it there (Issue #1084, follow-up of Issue #1061 / #1078).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.runHoldAtSeparatorScenario
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * On the B→A route of `vyhybna.xml`, the `zB`→`doA1` leg has a mid-leg switch, `vB`, five metres
 * past `zB` (Issue #1084). A train whose front crosses into `vB` and is then answered with an
 * [PathResult.OwnershipConflict] for the query from `vB` stands there with `pathToSemaphore`
 * unconsumed for that crossing — the same suspension window as
 * [StandingAtSeparatorPerceptionTest], but opened mid-leg rather than at the leg's own end.
 *
 * The conflict answer is injected through the navigation seam: the decorator returns exactly the
 * [PathResult.OwnershipConflict] that `findReservedPathForTrain` yields when the section ahead
 * is owned by another train. It does not drive the partial-release mechanism, which today
 * cannot produce this state — `RegistryPartialRouteReleaser` refuses a release whose trimmed
 * path would end at a switch (Issues #1031, #1063, #1067). Whether any production path can
 * reach the mid-leg trigger stays the open question recorded in #1084; this test pins the
 * defensive handling of the #1061 window class, not its reachability.
 *
 * The reserved leg runs `zB`—`vB`—`doB1`—`doA1` (5 m + 5 m + 100 m), so the signal ahead is
 * `doA1`. The pre-fix code published `distanceToSemaphore()`, the whole leg length minus the
 * rebased position, over-reading by the `zB`—`vB` section (5 m) already behind the front. The
 * train coasts a few metres past `vB` before it stops, so the absolute value depends on the
 * braking; the invariant is that the published distance is `distanceToSemaphore()` minus that
 * 5 m section — and not zero, since the train is still short of `doA1`.
 *
 * Branch note: this scenario exercises the mid-path measurement of `remainingLegLengthFrom`;
 * the leg-end fast path stays covered by [StandingAtSeparatorPerceptionTest]. The `null`
 * fallback to [Train.distanceToSemaphore] and the `maxOf` clamp are not reachable here — no
 * state in this scenario lacks the entry separator on `pathToSemaphore` or leaves a negative
 * remainder.
 */
@Tag("integration-test")
@DisplayName("Distance to the signal ahead while standing at a mid-leg switch")
class StandingAtMidLegSwitchPerceptionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		const val HOLD_SIGNAL = "vB"
		const val NEXT_SIGNAL = "doA1"

		/** `zB`—`vB` block length in `vyhybna.xml`: the section behind the front that the old code counted. */
		const val SECTION_BEHIND_FRONT = 5.0

		/** Past this distance a stand is the one at `vB`, not the one at `B` before admission. */
		const val STAND_THRESHOLD = 100.0

		/** The unfixed code over-reads by the whole 5 m section, so this is tight enough. */
		const val TOLERANCE = 1e-2
	}

	private class Outcome(
		val trainDistance: Double,
		val wholeLegDistance: Double,
		val perceivedDistance: Double,
		val perceivedName: String?
	)

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("Perception excludes the section behind the front while standing at vB")
	fun standingTrainAtMidLegSwitchReportsRemainingSectionDistance() {
		val outcome = runScenario()

		val expected = outcome.wholeLegDistance - SECTION_BEHIND_FRONT
		assertThat(outcome.trainDistance, name = "Train.distanceToSignalAhead()")
			.isBetween(expected - TOLERANCE, expected + TOLERANCE)
		assertThat(outcome.perceivedDistance, name = "distanceToSignalAheadMetres")
			.isBetween(expected - TOLERANCE, expected + TOLERANCE)
		assertThat(outcome.perceivedName, name = "signalAheadName").isNotNull().isEqualTo(NEXT_SIGNAL)
	}

	private fun runScenario(): Outcome {
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()

		var trainDistance = Double.NaN
		var wholeLegDistance = Double.NaN
		var perceivedDistance = Double.NaN
		var perceivedName: String? = null

		runHoldAtSeparatorScenario(
			context,
			holdSignal = HOLD_SIGNAL,
			standThreshold = STAND_THRESHOLD
		) { observation ->
			trainDistance = observation.train.distanceToSignalAhead()
			wholeLegDistance = observation.train.distanceToSemaphore()
			val reading = observation.port.trainPerception(observation.train.name)
			perceivedDistance = reading?.distanceToSignalAheadMetres ?: Double.NaN
			perceivedName = reading?.signalAheadName
		}
		return Outcome(trainDistance, wholeLegDistance, perceivedDistance, perceivedName)
	}
}
