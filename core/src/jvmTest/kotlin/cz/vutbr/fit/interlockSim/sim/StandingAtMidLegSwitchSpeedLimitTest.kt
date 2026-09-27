/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The speed limit a train standing at a mid-leg switch reports while an ownership
 * conflict holds it there (Issue #1088, follow-up of Issue #1084).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
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
 * On the C→A route of `switch-between-semaphores.xml`, `semA` faces the opposite direction
 * (it only ends a leg for an A→… train), so a C→A train's reserved leg runs in one piece all
 * the way to the destination InOut `A`, through the mid-leg switch `sw1`, 60 m past the entry
 * `C` — the same suspension window as [StandingAtMidLegSwitchPerceptionTest], but on a network
 * whose speeds are not uniform: the C—`sw1` branch track is limited to 20 m/s while the
 * `sw1`—`semA`—`A` main track allows 24 m/s. That is exactly what `vyhybna.xml` cannot provide
 * (every section there is 24 m/s), which is why the first round of the #1088 fix carried the
 * discrimination at the unit level only.
 *
 * A train whose front crosses into `sw1` and is then answered with an
 * [PathResult.OwnershipConflict] for the query from `sw1` — injected through the same
 * navigation seam as the #1084 tests — stands there with `pathToSemaphore` untrimmed for that
 * crossing: `C—(20 m/s)—sw1—(24 m/s)—semA—(24 m/s)—A`. The pre-fix fold started at the path
 * head, so it still folded the 20 m/s section already behind the front and published the stale
 * 20 m/s limit. The fixed fold starts strictly after `sw1`, the separator the front actually
 * stands at, and publishes 24 m/s — the discrimination this test pins.
 *
 * This is also the first end-to-end exercise of `currentSpeedLimitMps` through
 * [DefaultNetworkPerceptionPort]: the port's own unit test mocks this property, so without
 * this scenario nothing anywhere reads the real getter against a live suspension window.
 */
@Tag("integration-test")
@DisplayName("Speed limit while standing at a mid-leg switch")
class StandingAtMidLegSwitchSpeedLimitTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		const val HOLD_SIGNAL = "sw1"

		/** The reserved leg's own endpoint for a C→A train — see the class KDoc on `semA`'s orientation. */
		const val NEXT_SIGNAL = "A"
		const val END_TIME = 60L

		/** Speed limit of the `sw1`—`semA`—`A` main track: the section ahead the fixed fold reports. */
		const val AHEAD_LIMIT = 24.0

		/**
		 * The front crosses into `sw1` 60 m past `C`; a stand below this distance would mean the
		 * train stopped short of the switch, outside the window this test pins.
		 */
		const val MIN_STAND_DISTANCE = 50.0
	}

	private class Outcome(
		val trainSpeedLimit: Double,
		val perceivedSpeedLimit: Double,
		val perceivedName: String?
	)

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("Perception excludes the section behind the front while standing at sw1")
	fun standingTrainAtMidLegSwitchReportsAheadSpeedLimit() {
		val outcome = runScenario()

		assertThat(outcome.trainSpeedLimit, name = "Train.currentSpeedLimitMps")
			.isEqualTo(AHEAD_LIMIT)
		assertThat(outcome.perceivedSpeedLimit, name = "currentSpeedLimitMps (perception port)")
			.isEqualTo(AHEAD_LIMIT)
		assertThat(outcome.perceivedName, name = "signalAheadName").isNotNull().isEqualTo(NEXT_SIGNAL)
	}

	private fun runScenario(): Outcome {
		val context =
			TestFixtures
				.loadSwitchBetweenSemaphoresSimulationContext(simulationContextFactory, editingContextFactory)
				.tracked()
		var trainSpeedLimit = Double.NaN
		var perceivedSpeedLimit = Double.NaN
		var perceivedName: String? = null

		runHoldAtSeparatorScenario(
			context,
			holdSignal = HOLD_SIGNAL,
			standThreshold = MIN_STAND_DISTANCE,
			endTime = END_TIME,
			inName = "C",
			outName = "A",
			onHoldElapsed = { observation ->
				trainSpeedLimit = observation.train.currentSpeedLimitMps
				val reading = observation.port.trainPerception(observation.train.name)
				perceivedSpeedLimit = reading?.currentSpeedLimitMps ?: Double.NaN
				perceivedName = reading?.signalAheadName
			}
		)
		return Outcome(trainSpeedLimit, perceivedSpeedLimit, perceivedName)
	}
}
