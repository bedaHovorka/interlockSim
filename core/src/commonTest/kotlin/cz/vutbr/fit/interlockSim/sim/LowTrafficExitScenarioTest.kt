/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B exit calibration (#591 part 1, Issue #1346): fast guard of the exit-scenario helper.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.each
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isIn
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import kotlin.test.Test

/**
 * Pins the exit parameters of [LowTrafficExitScenario] without running a simulation, so the
 * spacing rules hold on every target and in every gate. The simulation runs themselves are the
 * heavy `LowTrafficExitCalibrationTest` (JVM) and the native seed-1 test.
 */
class LowTrafficExitScenarioTest {
	@Test
	fun seedListIsTheFixedOneToTen() {
		assertThat(LowTrafficExitScenario.LOW_TRAFFIC_SEEDS).isEqualTo((1..10).toList())
	}

	@Test
	fun arrivalsAreDeterministicPerSeed() {
		LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.forEach { seed ->
			assertThat(LowTrafficExitScenario.arrivals(seed), name = "arrivals of seed $seed")
				.isEqualTo(LowTrafficExitScenario.arrivals(seed))
		}
	}

	@Test
	fun arrivalsUseOnlySwitchLegalPrahaPairs() {
		LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.forEach { seed ->
			val specs = LowTrafficExitScenario.arrivals(seed)
			assertThat(specs, name = "arrivals of seed $seed").isNotEmpty()
			assertThat(specs.map { it.inName to it.outName }, name = "pairs of seed $seed")
				.each { it.isIn(*MultiTrainStressSpecs.LEGAL_PAIRS.toTypedArray()) }
		}
	}

	@Test
	fun lastInjectionLeavesTheRunEndMargin() {
		LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.forEach { seed ->
			val last = LowTrafficExitScenario.arrivals(seed).maxOf { it.inTime }
			assertThat(last, name = "last injection of seed $seed")
				.isLessThanOrEqualTo(LowTrafficExitScenario.END_TIME - LowTrafficExitScenario.RUN_END_MARGIN_SECONDS)
		}
	}

	@Test
	fun consecutiveInjectionsKeepTheGlobalMinimumGap() {
		LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.forEach { seed ->
			LowTrafficExitScenario.arrivals(seed).zipWithNext().forEach { (earlier, later) ->
				assertThat(later.inTime - earlier.inTime, name = "global gap of seed $seed before ${later.inTime}")
					.isGreaterThanOrEqualTo(LowTrafficExitScenario.MIN_GAP_GLOBAL_SECONDS)
			}
		}
	}

	@Test
	fun injectionsNamingTheSameInOutKeepThePerInOutMinimumGap() {
		LowTrafficExitScenario.LOW_TRAFFIC_SEEDS.forEach { seed ->
			val specs = LowTrafficExitScenario.arrivals(seed)
			val inOuts = specs.flatMap { listOf(it.inName, it.outName) }.toSet()
			inOuts.forEach { inOut ->
				specs.filter { it.inName == inOut || it.outName == inOut }.zipWithNext().forEach { (earlier, later) ->
					assertThat(later.inTime - earlier.inTime, name = "gap at $inOut of seed $seed")
						.isGreaterThanOrEqualTo(LowTrafficExitScenario.MIN_GAP_PER_IN_OUT_SECONDS)
				}
			}
		}
	}

	@Test
	fun cleanRunHasNoFailureCause() {
		assertThat(run().failureCause).isEqualTo(ExitFailureCause.NONE)
	}

	@Test
	fun watchdogReportIsClassifiedFirst() {
		val failing =
			run().copy(
				exited = 1,
				stallReport = "deadlock",
				entryFailures = listOf(exhausted())
			)

		assertThat(failing.failureCause).isEqualTo(ExitFailureCause.WATCHDOG_FIRED)
	}

	@Test
	fun exhaustedEntryAttemptsAreClassified() {
		val failing = run().copy(exited = 1, entryFailures = listOf(exhausted()))

		assertThat(failing.failureCause).isEqualTo(ExitFailureCause.ENTRY_ATTEMPTS_USED_UP)
	}

	@Test
	fun trainsHeldBehindTheCapAtRunEndAreClassified() {
		val failing = run().copy(exited = 1, heldByCapAtEnd = 1)

		assertThat(failing.failureCause).isEqualTo(ExitFailureCause.MAX_CONCURRENCY_REACHED)
	}

	@Test
	fun anyOtherFunctionalMissIsClassifiedAsOther() {
		assertThat(run().copy(occupied = 1).failureCause).isEqualTo(ExitFailureCause.OTHER)
	}

	@Test
	fun meanEntryGapIsTheMeanGapBetweenConsecutiveInjections() {
		val specs =
			listOf(100.0, 200.0, 400.0).map { inTime ->
				MultiTrainLoop.TrainSpec("N-Lib-1", "S-Vin-1", inTime, MultiTrainStressSpecs.TRAIN_LENGTH)
			}

		assertThat(run().copy(specs = specs).meanEntryGapSeconds).isEqualTo(150.0)
	}

	@Test
	fun firstEntryFailureTimeIsTheTimeOfTheFirstRecordedFailure() {
		assertThat(run().firstEntryFailureSimTime).isNull()
		assertThat(run().copy(entryFailures = listOf(exhausted())).firstEntryFailureSimTime).isEqualTo(100.0)
	}

	@Test
	fun summaryPrintsNoneWhenNoEntryFailedAndNoStallWasReported() {
		assertThat(run().summary()).all {
			contains("firstEntryFailureAt=none")
			contains("stallAt=none")
		}
	}

	@Test
	fun summaryPrintsTheFirstEntryFailureAndStallTimes() {
		val failing = run().copy(entryFailures = listOf(exhausted()), stallReport = "deadlock", stallSimTime = 250.0)

		assertThat(failing.summary()).all {
			contains("firstEntryFailureAt=100.0")
			contains("stallAt=250.0")
		}
	}

	private fun run(): MultiTrainRun {
		val specs = MultiTrainStressSpecs.twentyTrainSpecs().take(2)
		return MultiTrainRun(
			label = "classifier fixture",
			specs = specs,
			maxConcurrentTrainsCap = 4,
			entered = 2,
			exited = 2,
			maxConcurrent = 2,
			occupied = 0,
			entryFailures = emptyList(),
			stallReport = null,
			stallSimTime = null,
			waitingAtEnd = emptyList(),
			heldByCapAtEnd = 0,
			firstBlockedSimTime = null,
			firstCapHoldSimTime = null,
			reservationExpiries = 0,
			dtMax = 1e-3,
			wallMs = 0L
		)
	}

	private fun exhausted(): MultiTrainLoop.EntryFailure =
		MultiTrainLoop.EntryFailure(
			trainName = "Train #2",
			inName = "N-Lib-2",
			outName = "S-Vin-2",
			kind = MultiTrainLoop.EntryFailureKind.ATTEMPTS_EXHAUSTED,
			attempts = 3600,
			lastResult = null,
			simTime = 100.0
		)
}
