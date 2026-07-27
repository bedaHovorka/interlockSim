/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.ksimulantenbande.kdisco.Process
import cz.ksimulantenbande.kdisco.dtMax
import cz.ksimulantenbande.kdisco.dtMin
import cz.ksimulantenbande.kdisco.maxAbsError
import cz.ksimulantenbande.kdisco.maxRelError
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Integration tests for [Train.holdAtStation] and its `StationDwellProcess` (SP2a.3,
 * Issue #554).
 *
 * `StationDwellProcess` is a private inner class of [Train], so it is observable only
 * through a real kDisco run: its two `TRAIN_EVENTS` reports (`dwell start` / `dwell end`)
 * and the [Train.isStationDwelling] flag it maintains.  `DefaultTrainActuatorPortTest`
 * covers the port's delegation and its ≤ 0 precondition against a mocked [Train]; this
 * class complements it by executing the real kernel code path.
 *
 * Each test drives the engine with a small [LoopProcess] that calls `holdAtStation` from
 * the kDisco simulation thread (the method's documented threading requirement) and records
 * what happened for assertion after `run()` returns.
 */
@DisplayName("Train.holdAtStation + StationDwellProcess (SP2a.3)")
@Tag("integration-test")
class TrainHoldAtStationIntegrationTest : KoinTestBase() {
	private companion object {
		const val DWELL_SECONDS = 5.0
		const val END_TIME = 30.0

		/** Tolerance for the dwell-duration assertion: the driver polls at 1 s intervals. */
		const val DWELL_TOLERANCE_SECONDS = 1.5
	}

	/**
	 * Driver that polls once per simulation second.
	 *
	 * [LoopProcess.interLoopSleep] defaults to `passivate()`, which would stall the driver
	 * after its first iteration and freeze the simulation clock; holding for 1 s instead
	 * lets simulation time advance so a dwell can expire and a train can accelerate.
	 * Mirrors [SimpleTestProcess.interLoopSleep].
	 */
	private abstract class PollingDriver : LoopProcess() {
		/**
		 * Applies the project's physics tolerances, mirroring [Generator.startAction] and
		 * [SimpleTestProcess.startAction]. Without this, the simulation runs on kDisco's raw
		 * defaults (`dtMin = 1e-5`, `maxAbsError = 1e-5`), where the `dtMin` slack that
		 * [Train]'s tail-entry gate deliberately leaves below the train-length threshold
		 * (`Train.kt`, Issue #797) is the same magnitude as the tolerance and spuriously
		 * trips `LengthChecker`'s `abs(front − tail − length) ≤ maxAbsError` invariant.
		 *
		 * Overriding subclasses must call `super.startAction()`.
		 */
		override suspend fun startAction() {
			dtMin = 1e-6
			dtMax = 1e-3
			maxRelError = 1e-2
			maxAbsError = 1e-2
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	/**
	 * Builds a linear-topology context with a reserved path and a stationary [Train].
	 *
	 * The train is constructed but deliberately **not** activated, so it stays at
	 * `velocity == 0` — the precondition [Train.holdAtStation] requires.
	 */
	private fun DefaultSimulationContext.newStationaryTrain(): Train {
		val inOuts = getInOuts().toList()
		require(inOuts.size >= 2) { "Test requires at least 2 InOuts" }
		val train = Train(this, Timetable(inOuts[0], inOuts[1], Time(0.0), Time(60.0), 100.0))
		// Reserve under the train's own auto-assigned name (the counter is static and keeps
		// incrementing across tests in this JVM, so the key cannot be hardcoded).  Train.actions()
		// blocks in waitUntil { isPathReservedForTrain(name, ...) } until this reservation exists.
		getRoutingServices().getPathReservationService().reservePath(train.name, inOuts[0], inOuts[1])
		return train
	}

	/** Collects every `TRAIN_EVENTS` report message fired by this context. */
	private fun DefaultSimulationContext.collectTrainEvents(): MutableList<String> {
		val events = mutableListOf<String>()
		addReportTypes(ReportType.TRAIN_EVENTS)
		addPropertyChangeListener(
			ContextPropertyChangeListener { event ->
				if (event.propertyName == ReportType.TRAIN_EVENTS.name) {
					events.add(event.newValue as String)
				}
			}
		)
		return events
	}

	/**
	 * Parses the simulation timestamp that `DefaultSimulationContext.report` prepends to
	 * every report message (format: `"<time> <obj> <message>"`).
	 */
	private fun simTimeOf(report: String): Double = report.trim().substringBefore(' ').toDouble()

	private fun List<String>.matching(fragment: String): List<String> = filter { fragment in it }

	@Test
	@DisplayName("stationary train — dwell runs for the requested duration and clears the flag")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun dwellRunsForRequestedDurationAndClearsFlag() {
		(TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext).use { ctx ->
			val events = ctx.collectTrainEvents()
			val train = ctx.newStationaryTrain()
			var dwellingObservedDuringHold = false

			ctx.setMainProcess(
				object : PollingDriver() {
					private var commanded = false

					override suspend fun iteration() {
						if (!commanded) {
							train.holdAtStation(DWELL_SECONDS)
							commanded = true
							// Flag must be observable immediately after the act step returns
							dwellingObservedDuringHold = train.isStationDwelling
							return
						}
						if (time() >= END_TIME) terminate()
					}
				}
			)
			ctx.run()

			val started = events.matching("dwell start")
			val ended = events.matching("dwell end")
			assertThat(started.size, name = "dwell start reports").isEqualTo(1)
			assertThat(ended.size, name = "dwell end reports").isEqualTo(1)

			// The dwell must actually consume simulation time, not complete instantly
			val elapsed = simTimeOf(ended.first()) - simTimeOf(started.first())
			assertThat(
				abs(elapsed - DWELL_SECONDS) <= DWELL_TOLERANCE_SECONDS,
				name = "dwell elapsed ${elapsed}s should be ≈ ${DWELL_SECONDS}s"
			).isTrue()

			assertThat(dwellingObservedDuringHold, name = "isStationDwelling during dwell").isTrue()
			assertThat(train.isStationDwelling, name = "isStationDwelling after dwell expired").isFalse()
			// The train never moved, so it is still at rest — isDwelling stays true even
			// though the commanded dwell has ended.  The two flags are independent.
			assertThat(train.isDwelling, name = "isDwelling after dwell expired").isTrue()
		}
	}

	@Test
	@DisplayName("moving train — holdAtStation is rejected (it is a dwell timer, not a brake)")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun movingTrainIsRejected() {
		(TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext).use { ctx ->
			val events = ctx.collectTrainEvents()
			val train = ctx.newStationaryTrain()
			var rejection: SimulationException? = null
			var observedVelocity = 0.0

			ctx.setMainProcess(
				object : PollingDriver() {
					override suspend fun startAction() {
						super.startAction()
						// Activating the train starts its journey, so it accelerates
						Process.activate(train)
					}

					override suspend fun iteration() {
						if (rejection == null && train.getVelocity() > 0.0) {
							observedVelocity = train.getVelocity()
							rejection =
								runCatching { train.holdAtStation(DWELL_SECONDS) }
									.exceptionOrNull() as? SimulationException
							terminate()
							return
						}
						if (time() >= END_TIME) terminate()
					}
				}
			)
			ctx.run()

			assertThat(
				observedVelocity > 0.0,
				name = "train must reach velocity > 0 for this test to be meaningful"
			).isTrue()
			assertThat(rejection, name = "SimulationException for moving train").isNotNull()
			// No dwell may have been started — a timer alongside a rolling train would
			// silently misrepresent a station stop
			assertThat(events.matching("dwell start").size, name = "dwell start reports").isEqualTo(0)
			assertThat(train.isStationDwelling, name = "isStationDwelling after rejection").isFalse()
		}
	}

	@Test
	@DisplayName("second holdAtStation while already dwelling is rejected")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun doubleDwellIsRejected() {
		(TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext).use { ctx ->
			val events = ctx.collectTrainEvents()
			val train = ctx.newStationaryTrain()
			var rejection: SimulationException? = null

			ctx.setMainProcess(
				object : PollingDriver() {
					private var commanded = false

					override suspend fun iteration() {
						if (!commanded) {
							train.holdAtStation(DWELL_SECONDS)
							rejection =
								runCatching { train.holdAtStation(DWELL_SECONDS) }
									.exceptionOrNull() as? SimulationException
							commanded = true
							return
						}
						if (time() >= END_TIME) terminate()
					}
				}
			)
			ctx.run()

			assertThat(rejection, name = "SimulationException for overlapping dwell").isNotNull()
			// Exactly one dwell ran — the rejected call must not have spawned a second process
			assertThat(events.matching("dwell start").size, name = "dwell start reports").isEqualTo(1)
			assertThat(events.matching("dwell end").size, name = "dwell end reports").isEqualTo(1)
		}
	}

	@Test
	@DisplayName("non-positive dwell duration is rejected before any dwell is scheduled")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun nonPositiveDurationIsRejected() {
		(TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext).use { ctx ->
			val events = ctx.collectTrainEvents()
			val train = ctx.newStationaryTrain()
			var zeroRejection: Throwable? = null
			var negativeRejection: Throwable? = null

			ctx.setMainProcess(
				object : PollingDriver() {
					private var commanded = false

					override suspend fun iteration() {
						if (!commanded) {
							zeroRejection = runCatching { train.holdAtStation(0.0) }.exceptionOrNull()
							negativeRejection = runCatching { train.holdAtStation(-5.0) }.exceptionOrNull()
							commanded = true
							terminate()
							return
						}
						if (time() >= END_TIME) terminate()
					}
				}
			)
			ctx.run()

			assertThat(zeroRejection is IllegalArgumentException, name = "zero duration rejected").isTrue()
			assertThat(negativeRejection is IllegalArgumentException, name = "negative duration rejected").isTrue()
			assertThat(events.matching("dwell start").size, name = "dwell start reports").isEqualTo(0)
			assertThat(train.isStationDwelling, name = "isStationDwelling after rejection").isFalse()
		}
	}
}
