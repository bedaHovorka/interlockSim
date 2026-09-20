/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * The distance to the signal ahead that a train standing at a separator reports (Issue #1061).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
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
 * A train that stops at `zB` because navigation answers its path query with an ownership
 * conflict must be published as standing **at** `zB` — distance about zero, signal `zB` —
 * not as 100 m short of it. The front rebases its position past the section end before the
 * query, and the leg just completed stays as the train's path, so an unguarded
 * `pathLength - position` reads a whole leg too much for as long as the train waits.
 *
 * The same scenario run also reads the train while it is still running between `B` and `zB`:
 * the fix must not change the plain running distance, so an always-zero regression is caught.
 *
 * The scenario is the one of [OwnershipConflictStandRestartTest]: `vyhybna.xml`, B to A, the
 * whole route reserved, navigation holding the train at `zB`.
 */
@Tag("integration-test")
@DisplayName("Distance to the signal ahead while standing at, or running to, a separator")
class StandingAtSeparatorPerceptionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		const val HOLD_SIGNAL = "zB"
		const val END_TIME = 90L
		const val SAMPLE_PERIOD = 0.05
		const val TRAIN_LENGTH = 20.0

		/** Held long enough for the wait to have settled; the reading is taken after this. */
		const val STAND_HOLD_SECONDS = 2.0

		/** The unfixed code publishes a whole leg (`B —100 m— zB`) too much. */
		const val TOLERANCE = 1e-2

		/**
		 * Running-sample window, comfortably inside the first (`B —100 m— zB`) leg and away
		 * from both separators, so the positive branch of the fixed reading is what runs.
		 */
		const val MOVING_MIN_DISTANCE = 10.0
		const val MOVING_MAX_DISTANCE = 50.0
	}

	private class Outcome(
		val trainDistance: Double,
		val perceivedDistance: Double,
		val perceivedName: String?,
		val movingTrainAhead: Double,
		val movingSemaphore: Double,
		val movingPerceivedDistance: Double
	)

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("Perception reports zero metres to zB while the train stands at zB")
	fun standingTrainReportsZeroDistanceToTheSeparator() {
		val outcome = runScenario()

		assertThat(outcome.trainDistance, name = "Train.distanceToSignalAhead()").isBetween(-TOLERANCE, TOLERANCE)
		assertThat(outcome.perceivedDistance, name = "distanceToSignalAheadMetres").isBetween(-TOLERANCE, TOLERANCE)
		assertThat(outcome.perceivedName, name = "signalAheadName").isNotNull().isEqualTo(HOLD_SIGNAL)
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("A train still running between separators keeps its positive distanceToSemaphore")
	fun runningTrainKeepsItsPositiveDistanceToTheSemaphore() {
		val outcome = runScenario()

		// The plain running distance is positive and the fixed reading is exactly it; an
		// always-zero `distanceToSignalAhead()` fails the closeness assertions below.
		assertThat(outcome.movingSemaphore, name = "distanceToSemaphore() while running").isGreaterThan(0.0)
		assertThat(outcome.movingTrainAhead, name = "Train.distanceToSignalAhead() while running")
			.isCloseTo(outcome.movingSemaphore, delta = TOLERANCE)
		assertThat(outcome.movingPerceivedDistance, name = "distanceToSignalAheadMetres while running")
			.isCloseTo(outcome.movingSemaphore, delta = TOLERANCE)
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
		var perceivedDistance = Double.NaN
		var perceivedName: String? = null
		var movingTrainAhead = Double.NaN
		var movingSemaphore = Double.NaN
		var movingPerceivedDistance = Double.NaN

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
				TrainKinematicSampler(train, END_TIME.toDouble(), SAMPLE_PERIOD) { sample ->
					if (
						movingSemaphore.isNaN() &&
						sample.velocity > 0.0 &&
						sample.totalDistance > MOVING_MIN_DISTANCE &&
						sample.totalDistance < MOVING_MAX_DISTANCE
					) {
						movingTrainAhead = train.distanceToSignalAhead()
						movingSemaphore = train.distanceToSemaphore()
						movingPerceivedDistance = port.trainPerception(train.name)?.distanceToSignalAheadMetres ?: Double.NaN
					}
					if (standTime < 0.0 && sample.velocity == 0.0 && sample.totalDistance > 50.0) {
						standTime = sample.time
					}
					if (standTime >= 0.0 && perceivedName == null && sample.time >= standTime + STAND_HOLD_SECONDS) {
						trainDistance = train.distanceToSignalAhead()
						val reading = port.trainPerception(train.name)
						perceivedDistance = reading?.distanceToSignalAheadMetres ?: Double.NaN
						perceivedName = reading?.signalAheadName
					}
				}
			)
		}
		return Outcome(
			trainDistance,
			perceivedDistance,
			perceivedName,
			movingTrainAhead,
			movingSemaphore,
			movingPerceivedDistance
		)
	}

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
}
