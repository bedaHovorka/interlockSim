/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RailwayOutcome.progress].
 *
 * The classifier is pure, so every case here is a literal [RailwayOutcome] — no simulation and no
 * Ollama. What matters is the three-way split: a measured zero is a finding, an absent figure is
 * not, and the two must never collapse into each other.
 *
 * @since Issue #930 (Wave 3 — GUI starvation flag)
 */
class RailwayProgressTest {
	@Nested
	@DisplayName("Starved")
	inner class Starved {
		@Test
		@DisplayName("no journeys and no exits is starved")
		fun noJourneysNoExitsIsStarved() {
			val outcome = RailwayOutcome(journeysCompleted = 0L, trainsExited = 0L)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.STARVED)
		}

		/**
		 * The measured #930 demonstration: `tickPeriodMs=20000`, seven trains admitted, none of
		 * them ever finished, and three block transitions in the whole run. A handful of movement
		 * events is not progress — nothing left the network.
		 */
		@Test
		@DisplayName("the measured tickPeriodMs=20000 run is starved despite 3 block transitions")
		fun measuredStarvedRun() {
			val outcome =
				RailwayOutcome(
					journeysCompleted = 0L,
					trainsEntered = 7L,
					trainsExited = 0L,
					maxConcurrentTrains = 7L,
					blockTransitions = 3L,
					conflicts = 1L,
					failedReservations = 12L
				)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.STARVED)
		}

		/**
		 * `trainsExited` comes from `ShuntingLoop` and is absent for any other main process. An
		 * absent exit count cannot contradict a **measured** zero journey count, so it must not
		 * block the verdict — otherwise a starved `multiTrainLoop` run would score as a pass.
		 */
		@Test
		@DisplayName("absent trainsExited does not block the verdict when journeys are a measured zero")
		fun absentExitsStillStarved() {
			val outcome = RailwayOutcome(journeysCompleted = 0L, trainsExited = null)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.STARVED)
		}

		@Test
		@DisplayName("a run that admitted nothing at all is starved")
		fun admittedNothingIsStarved() {
			val outcome = RailwayOutcome(journeysCompleted = 0L, trainsEntered = 0L, trainsExited = 0L)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.STARVED)
		}

		/**
		 * The measured #895 leak. Arm 1 `t=0.5 r03` of the re-baseline campaign admitted five
		 * trains, ran for 228 simulated seconds and let **none** of them leave, yet recorded
		 * `journeysCompleted = 1`. Under the previous rule that single journey vetoed the verdict
		 * and the run was classified `MADE_PROGRESS`.
		 *
		 * `journeysCompleted` increments when a train's reservation count reaches zero, with no
		 * termination and no movement predicate (Issue #906), so it can fire for a train that
		 * never finished. `trainsExited` is termination-gated. When the termination-gated counter
		 * is present it therefore decides alone.
		 */
		@Test
		@DisplayName("a measured journey with no train out is starved (#895)")
		fun measuredJourneyWithoutExitIsStarved() {
			val outcome =
				RailwayOutcome(
					journeysCompleted = 1L,
					trainsEntered = 5L,
					trainsExited = 0L,
					blockTransitions = 3L
				)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.STARVED)
		}
	}

	@Nested
	@DisplayName("Made progress")
	inner class MadeProgress {
		/**
		 * A completed journey is progress **only** when the termination-gated counter does not
		 * contradict it. See [RailwayProgressTest.Starved.measuredJourneyWithoutExitIsStarved]
		 * for the case where it does.
		 */
		@Test
		@DisplayName("one completed journey with a train out is progress")
		fun oneJourneyIsProgress() {
			val outcome = RailwayOutcome(journeysCompleted = 1L, trainsExited = 1L)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.MADE_PROGRESS)
		}

		@Test
		@DisplayName("a train that exited is progress even with no completed journey")
		fun oneExitIsProgress() {
			val outcome = RailwayOutcome(journeysCompleted = 0L, trainsExited = 1L)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.MADE_PROGRESS)
		}

		/**
		 * The classifier is threshold-free on purpose: the reporter's partially stuck run
		 * (journeys 2 of 7 admitted) is progress here. Deciding how many of the admitted trains
		 * must finish is a gate threshold and belongs to the #895 re-baseline, next to
		 * [RunReportAggregator.MIN_ACTIONABLE_RATE].
		 */
		@Test
		@DisplayName("a partially stuck run (2 of 7) is progress, not starvation")
		fun partialProgressIsNotStarvation() {
			val outcome = RailwayOutcome(journeysCompleted = 2L, trainsEntered = 7L, trainsExited = 2L)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.MADE_PROGRESS)
		}

		/** The healthy rule-based baseline: 173 block transitions per 600 s run. */
		@Test
		@DisplayName("the healthy rule-based baseline is progress")
		fun healthyBaselineIsProgress() {
			val outcome =
				RailwayOutcome(
					journeysCompleted = 7L,
					trainsEntered = 7L,
					trainsExited = 7L,
					blockTransitions = 173L
				)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.MADE_PROGRESS)
		}
	}

	@Nested
	@DisplayName("Unmeasured")
	inner class Unmeasured {
		/**
		 * Absent is not zero. Inventing `STARVED` for a run nobody measured would reproduce the
		 * "structurally empty columns" misreading #847's sweep recorded.
		 */
		@Test
		@DisplayName("an absent journey count yields no verdict")
		fun absentJourneysIsUnmeasured() {
			val outcome = RailwayOutcome(journeysCompleted = null, trainsExited = 0L)

			assertThat(outcome.progress()).isEqualTo(RailwayProgress.UNMEASURED)
		}

		@Test
		@DisplayName("the all-absent outcome yields no verdict")
		fun unmeasuredOutcomeIsUnmeasured() {
			assertThat(RailwayOutcome.UNMEASURED.progress()).isEqualTo(RailwayProgress.UNMEASURED)
		}
	}
}
