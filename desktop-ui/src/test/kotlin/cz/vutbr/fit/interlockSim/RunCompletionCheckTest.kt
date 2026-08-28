/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Unit tests for [RunCompletionCheck] (Issue #847 round 3, PR #891 defect C).
 *
 * ## Why this exists
 *
 * A headless run that deadlocks is indistinguishable from one that succeeded. kDisco's
 * `Simulation.run()` returns `true` whether it reached the end time or simply ran out of events
 * (`Simulation.kt:138` breaks out of the loop on a drained queue; `:187` returns `true`
 * unconditionally), and the value is discarded by the caller. `Main.main` has no `exitProcess` at
 * all, so the JVM exits 0 on every path — deadlock, CRITICAL collision, or swallowed exception.
 * `ThrottlingSimulationController.requestPause()` is a documented no-op, so a CRITICAL
 * `CollisionWarning` dead-ends there too.
 *
 * Round 2's third verification run ended at simTime 72.0 s of a requested 600 s and still exited 0.
 * That matters beyond this PR: #847's sweep would count such a run as a valid data point.
 *
 * Reached-vs-requested simulated time is the one signal available on every headless path without
 * touching `core/`, so that is what this checks.
 *
 * @since Issue #847 (round 3)
 */
@DisplayName("RunCompletionCheck distinguishes a completed run from one that stopped early")
class RunCompletionCheckTest {
	@ParameterizedTest(name = "reached {0}s of {1}s requested -> {2}")
	@CsvSource(
		// The round-2 deadlock: ended at 72 s of a requested 600 s.
		"72.0, 600.0, TERMINATED_EARLY",
		// Round 1's controller-wiring bug: 0 trains, 0.0 s simulated.
		"0.0, 600.0, TERMINATED_EARLY",
		"500.0, 600.0, TERMINATED_EARLY",
		// A healthy run stops once its work is done, a little under the end time.
		"599.0, 600.0, COMPLETED",
		"600.9, 600.0, COMPLETED"
	)
	@DisplayName("classification follows reached-vs-requested simulated time")
	fun classifiesByReachedSimTime(
		reached: Double,
		requested: Double,
		expected: RunOutcome
	) {
		assertThat(RunCompletionCheck.evaluate(reached, requested, toleranceSeconds = 1.0))
			.isEqualTo(expected)
	}

	@Test
	@DisplayName("exactly one tolerance short still counts as completed")
	fun boundaryIsInclusive() {
		assertThat(RunCompletionCheck.evaluate(599.0, 600.0, toleranceSeconds = 1.0))
			.isEqualTo(RunOutcome.COMPLETED)
	}

	/**
	 * Regression from round 3's own measurements: the rule-based `shuntingLoop` baseline — the
	 * healthy reference every AI run is compared against — ends at **597.2 s** of a requested 600 s,
	 * because `ShuntingLoop.interLoopSleep` calls `env.stop()` once its work is done rather than
	 * idling to the exact end time. A one-second tolerance classified that as TERMINATED_EARLY and
	 * exited 1 on a perfectly good run.
	 *
	 * A false positive here is worse than a miss: #847's sweep would discard its own control.
	 */
	@Test
	@DisplayName("the healthy rule-based baseline's normal 597.2s finish is not flagged")
	fun measuredBaselineFinishIsNotFlagged() {
		assertThat(RunCompletionCheck.evaluate(597.2, 600.0)).isEqualTo(RunOutcome.COMPLETED)
	}

	@Test
	@DisplayName("the default tolerance scales with the requested end time")
	fun defaultToleranceScalesWithRequestedEndTime() {
		// 5% of 600s = 30s: absorbs a normal early stop, still an order of magnitude tighter than
		// round 2's 72s deadlock.
		assertThat(RunCompletionCheck.evaluate(570.0, 600.0)).isEqualTo(RunOutcome.COMPLETED)
		assertThat(RunCompletionCheck.evaluate(569.0, 600.0)).isEqualTo(RunOutcome.TERMINATED_EARLY)
		// Below 20s the 5% fraction would be under a second, so the floor takes over: a 10s run
		// tolerates a full second rather than 0.5s.
		assertThat(RunCompletionCheck.evaluate(9.0, 10.0)).isEqualTo(RunOutcome.COMPLETED)
		assertThat(RunCompletionCheck.evaluate(8.9, 10.0)).isEqualTo(RunOutcome.TERMINATED_EARLY)
	}

	@Test
	@DisplayName("a non-positive requested end time is a caller error, not an early termination")
	fun rejectsNonPositiveRequestedEndTime() {
		assertThrows<IllegalArgumentException> { RunCompletionCheck.evaluate(0.0, 0.0) }
	}

	@Test
	@DisplayName("a negative tolerance is a caller error")
	fun rejectsNegativeTolerance() {
		assertThrows<IllegalArgumentException> {
			RunCompletionCheck.evaluate(10.0, 600.0, toleranceSeconds = -1.0)
		}
	}

	@Test
	@DisplayName("each outcome carries a distinct process exit code")
	fun outcomeCarriesTheProcessExitCode() {
		// The whole point of the check: #847's unattended sweep must be able to tell a deadlocked
		// run from a healthy one by exit status alone, without parsing a log. NOT_STARTED is
		// distinct again so a typo in an example name is never scored as a failed dispatch run.
		assertThat(RunOutcome.COMPLETED.exitCode).isEqualTo(0)
		assertThat(RunOutcome.TERMINATED_EARLY.exitCode).isEqualTo(1)
		assertThat(RunOutcome.NOT_STARTED.exitCode).isEqualTo(2)
	}

	/**
	 * Issue #847 round 4 (R4-5): the persisted run JSON records a [RunEndCause], and #846's
	 * aggregator passes a run only when `completedNaturally` — which it derives from that cause. A
	 * deadlocked run mapped to `NATURAL_COMPLETION` would be counted as a **passing** data point,
	 * which is precisely the measurement-validity failure round 3's exit-code work set out to
	 * prevent, reintroduced one layer up.
	 */
	@Test
	@DisplayName("only a completed run maps to NATURAL_COMPLETION")
	fun outcomeMapsToRunEndCause() {
		assertThat(RunOutcome.COMPLETED.toRunEndCause()).isEqualTo(RunEndCause.NATURAL_COMPLETION)
		assertThat(RunOutcome.TERMINATED_EARLY.toRunEndCause()).isEqualTo(RunEndCause.TERMINATED_EARLY)
		assertThat(RunOutcome.NOT_STARTED.toRunEndCause()).isEqualTo(RunEndCause.CRASH)
	}
}
