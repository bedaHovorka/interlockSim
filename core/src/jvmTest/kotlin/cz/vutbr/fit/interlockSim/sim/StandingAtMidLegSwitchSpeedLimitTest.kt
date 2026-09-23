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
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSampler
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.decoratingTrainNavigationService
import cz.vutbr.fit.interlockSim.testutil.runSimpleLinearTrackScenario
import cz.vutbr.fit.interlockSim.testutil.separatorLabel
import cz.vutbr.fit.interlockSim.util.Util
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

		/** Held long enough for the wait to have settled; the reading is taken after this. */
		const val STAND_HOLD_SECONDS = 2.0

		const val SAMPLE_PERIOD = 0.05

		/**
		 * The front crosses into `sw1` 60 m past `C`; a stand below this distance would mean the
		 * train stopped short of the switch, outside the window this test pins.
		 */
		const val MIN_STAND_DISTANCE = 50.0

		const val TRAIN_LENGTH = 20.0
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
		val context = loadSwitchBetweenSemaphoresContext().tracked()
		val inOuts = context.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val c = inOuts.single { it.name == "C" }
		val reservationService = context.getRoutingServices().getPathReservationService()
		val realNav = context.getRoutingServices().getTrainNavigationService()
		val holdingNav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->
				if (separatorLabel(separator) == HOLD_SIGNAL) {
					PathResult.OwnershipConflict
				} else {
					realNav.findReservedPathForTrain(trainId, separator)
				}
			}
		val env = NavigationDecoratingContext(context, holdingNav)

		var standTime = -1.0
		var trainSpeedLimit = Double.NaN
		var perceivedSpeedLimit = Double.NaN
		var perceivedName: String? = null

		runSimpleLinearTrackScenario(
			context,
			endTime = END_TIME,
			trainSpecs =
				listOf(
					SimpleLinearTrackTestProcess.TrainSpec(
						inName = "C",
						outName = "A",
						inTime = 1.0,
						outTime = END_TIME.toDouble(),
						length = TRAIN_LENGTH
					)
				),
			env = env
		) { train ->
			assertReservationSuccess(reservationService.reservePath(train.name, c, a))
			val port = DefaultNetworkPerceptionPort(context, activeTrains = { listOf(train) })
			Process.activate(
				TrainKinematicSampler(train, END_TIME.toDouble(), SAMPLE_PERIOD) { sample ->
					if (standTime < 0.0 && sample.velocity == 0.0 && sample.totalDistance > MIN_STAND_DISTANCE) {
						standTime = sample.time
					}
					if (standTime >= 0.0 && perceivedName == null && sample.time >= standTime + STAND_HOLD_SECONDS) {
						trainSpeedLimit = train.currentSpeedLimitMps
						val reading = port.trainPerception(train.name)
						perceivedSpeedLimit = reading?.currentSpeedLimitMps ?: Double.NaN
						perceivedName = reading?.signalAheadName
					}
				}
			)
		}
		return Outcome(trainSpeedLimit, perceivedSpeedLimit, perceivedName)
	}

	/**
	 * Loads `switch-between-semaphores.xml` the same way [TestFixtures.loadShuntingSimulationContext]
	 * loads `vyhybna.xml` — through the Koin-injected factories, so the context is wired exactly
	 * as this test module configures them. There is no named helper for this fixture, so the
	 * chain is spelled out here once.
	 */
	private fun loadSwitchBetweenSemaphoresContext(): DefaultSimulationContext =
		TestFixtures.loadSwitchBetweenSemaphoresXml().use { xmlStream ->
			val editingContext =
				Util.assertInstanceOf<EditingContext>(editingContextFactory.createContext(xmlStream))
			editingContext.use {
				Util.assertInstanceOf<DefaultSimulationContext>(simulationContextFactory.createContext(it))
			}
		}
}
