/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The distance to the signal ahead that a train standing at a mid-leg switch reports, after a
 * partial rollback leaves it there (Issue #1084, follow-up of Issue #1061 / #1078).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
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
 * The reserved leg runs `zB`—`vB`—`doB1`—`doA1` (5 m + 5 m + 100 m), so the signal ahead is
 * `doA1`. The pre-fix code published `distanceToSemaphore()`, the whole leg length minus the
 * rebased position, over-reading by the `zB`—`vB` section (5 m) already behind the front. The
 * train coasts a few metres past `vB` before it stops, so the absolute value depends on the
 * braking; the invariant is that the published distance is `distanceToSemaphore()` minus that
 * 5 m section — and not zero, since the train is still short of `doA1`.
 */
@Tag("integration-test")
@DisplayName("Distance to the signal ahead while standing at a mid-leg switch")
class StandingAtMidLegSwitchPerceptionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		const val HOLD_SIGNAL = "vB"
		const val NEXT_SIGNAL = "doA1"
		const val END_TIME = 90L

		/** `zB`—`vB` block length in `vyhybna.xml`: the section behind the front that the old code counted. */
		const val SECTION_BEHIND_FRONT = 5.0

		/** Held long enough for the wait to have settled; the reading is taken after this. */
		const val STAND_HOLD_SECONDS = 2.0

		/** The unfixed code over-reads by the whole 5 m section, so this is tight enough. */
		const val TOLERANCE = 1e-2

		const val TRAIN_LENGTH = 20.0
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
		val context = loadVyhybnaContext().tracked()
		val inOuts = context.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
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
		var trainDistance = Double.NaN
		var wholeLegDistance = Double.NaN
		var perceivedDistance = Double.NaN
		var perceivedName: String? = null

		runSimpleLinearTrackScenario(
			context,
			endTime = END_TIME,
			trainSpecs =
				listOf(
					SimpleLinearTrackTestProcess.TrainSpec(
						inName = "B",
						outName = "A",
						inTime = 1.0,
						outTime = END_TIME.toDouble(),
						length = TRAIN_LENGTH
					)
				),
			env = env
		) { train ->
			assertReservationSuccess(reservationService.reservePath(train.name, b, a))
			val port = DefaultNetworkPerceptionPort(context, activeTrains = { listOf(train) })
			Process.activate(
				TrainKinematicSampler(train, END_TIME.toDouble(), 0.05) { sample ->
					if (standTime < 0.0 && sample.velocity == 0.0 && sample.totalDistance > 100.0) {
						standTime = sample.time
					}
					if (standTime >= 0.0 && perceivedName == null && sample.time >= standTime + STAND_HOLD_SECONDS) {
						trainDistance = train.distanceToSignalAhead()
						wholeLegDistance = train.distanceToSemaphore()
						val reading = port.trainPerception(train.name)
						perceivedDistance = reading?.distanceToSignalAheadMetres ?: Double.NaN
						perceivedName = reading?.signalAheadName
					}
				}
			)
		}
		return Outcome(trainDistance, wholeLegDistance, perceivedDistance, perceivedName)
	}

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
}
