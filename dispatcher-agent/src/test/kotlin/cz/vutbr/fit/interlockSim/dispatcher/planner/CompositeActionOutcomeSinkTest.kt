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
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [CompositeActionOutcomeSink] (Issue #847 round 4, finding R4-5).
 *
 * ## Why a fan-out is needed at all
 *
 * `DispatchDecisionApplier` accepts exactly **one** [ActionOutcomeSink], and production already
 * spends it on `ActionOutcomeAggregator`. `DispatcherRunRecorder.onActionOutcome` — the method that
 * fills `emittedByActionType`, `rejectionsByCode`, `applyFailuresByCode` and `actionsByAuthor` in
 * the per-run JSON that #847's sweep and #846's aggregator consume — therefore had **no production
 * caller at all**. Every run snapshot was all zeroes, and the aggregator had no producer.
 *
 * ## The sim-thread contract
 *
 * [ActionOutcomeSink.onActionOutcome] runs inside the kDisco event loop and is contractually
 * forbidden from logging, because every log call injects latency into the physics loop. A fan-out
 * multiplies that risk across delegates, so this class adds nothing of its own: no logging, no
 * allocation per call beyond the iteration itself, and — deliberately — **no exception swallowing**,
 * since a silently-dropped outcome is exactly the class of invisible failure round 4 is removing.
 */
@DisplayName("CompositeActionOutcomeSink — one applier outcome must reach every consumer")
class CompositeActionOutcomeSinkTest {
	private fun outcome(kind: String): ActionOutcome =
		ActionOutcome(
			phase = ActionPhase.APPLIED,
			rejection = null,
			applyFailure = null,
			authored =
				AuthoredAction(
					author = ActionAuthor.LLM,
					reason = "test",
					decisionKind = kind,
					tickIndex = 1L
				)
		)

	@Test
	@DisplayName("every delegate receives every outcome, in registration order")
	fun fansOutToEveryDelegate() {
		val first = mutableListOf<String>()
		val second = mutableListOf<String>()
		val order = mutableListOf<String>()
		val sink =
			CompositeActionOutcomeSink(
				ActionOutcomeSink {
					first += it.authored.decisionKind
					order += "first"
				},
				ActionOutcomeSink {
					second += it.authored.decisionKind
					order += "second"
				}
			)

		sink.onActionOutcome(outcome("ApproveTrain"))
		sink.onActionOutcome(outcome("RequestRoute"))

		assertThat(first, "first delegate").containsExactly("ApproveTrain", "RequestRoute")
		assertThat(second, "second delegate").containsExactly("ApproveTrain", "RequestRoute")
		assertThat(order, "delegate order").containsExactly("first", "second", "first", "second")
	}

	@Test
	@DisplayName("a composite over no delegates is a harmless no-op")
	fun emptyCompositeIsANoOp() {
		val sink = CompositeActionOutcomeSink()

		sink.onActionOutcome(outcome("ApproveTrain"))
	}

	@Test
	@DisplayName("null delegates are dropped, so an absent optional consumer needs no branch")
	fun nullDelegatesAreDropped() {
		val received = mutableListOf<String>()
		val sink = CompositeActionOutcomeSink.of(null, ActionOutcomeSink { received += it.authored.decisionKind }, null)

		sink.onActionOutcome(outcome("CancelRoute"))

		assertThat(received, "surviving delegate").containsExactly("CancelRoute")
	}

	/**
	 * A throwing delegate must not be swallowed. The sim thread's caller decides what a broken
	 * consumer means; hiding it here would reintroduce the "the metric was silently never recorded"
	 * failure this whole work stream exists to remove.
	 */
	@Test
	@DisplayName("a throwing delegate propagates rather than being silently swallowed")
	fun throwingDelegatePropagates() {
		val laterDelegateCalls = mutableListOf<String>()
		val sink =
			CompositeActionOutcomeSink(
				ActionOutcomeSink { error("consumer is broken") },
				ActionOutcomeSink { laterDelegateCalls += it.authored.decisionKind }
			)

		assertThrows<IllegalStateException> { sink.onActionOutcome(outcome("ApproveTrain")) }

		assertThat(laterDelegateCalls, "delegates after the throwing one").isEmpty()
	}
}
